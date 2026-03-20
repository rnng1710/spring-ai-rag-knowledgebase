import uvicorn
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from optimum.intel import OVModelForFeatureExtraction
from transformers import AutoTokenizer
from typing import List, Dict, Union
import unicodedata

# ================= 配置区域 =================
OV_MODEL_PATH = r"D:\soft\python\model\bge-m3-ov"
PORT = 8098
DEVICE = "GPU" 
# ===========================================

print(f"OpenVINO 服务已配置，设备: {DEVICE}")

ov_config = {"CACHE_DIR": ""} 

tokenizer = None
model = None

app = FastAPI(title="BGE-M3 OpenVINO Pro Server")

class EmbedRequest(BaseModel):
    inputs: Union[str, List[str]]

class EmbedResponse(BaseModel):
    dense_vecs: List[List[float]]
    sparse_vecs: List[Dict[int, float]] # 注意：Key 改为 int 类型以匹配 Milvus

def ensure_runtime():
    global tokenizer, model
    if tokenizer is None or model is None:
        print(f"正在加载 OpenVINO 模型，设备: {DEVICE}")
        tokenizer = AutoTokenizer.from_pretrained(OV_MODEL_PATH)
        model = OVModelForFeatureExtraction.from_pretrained(
            OV_MODEL_PATH,
            device=DEVICE,
            ov_config=ov_config
        )
    return tokenizer, model

def _is_disallowed_codepoint(codepoint: int) -> bool:
    if 0xD800 <= codepoint <= 0xDFFF:
        return True
    if 0xFDD0 <= codepoint <= 0xFDEF:
        return True
    if (codepoint & 0xFFFF) in (0xFFFE, 0xFFFF):
        return True
    category = unicodedata.category(chr(codepoint))
    return category == "Cc"

def sanitize_text(text: str) -> tuple[str, dict]:
    if not text:
        return "", {
            "original_length": 0,
            "removed_chars": 0,
            "normalized_whitespace": 0,
            "meaningful_chars": 0,
        }

    sanitized_chars: List[str] = []
    removed_chars = 0
    normalized_whitespace = 0
    meaningful_chars = 0

    for char in text:
        codepoint = ord(char)

        if char in ("\n", "\r", "\t"):
            if char == "\t":
                if not sanitized_chars or sanitized_chars[-1] in ("\n", "\t"):
                    normalized_whitespace += 1
                    continue
                sanitized_chars.append("\t")
                continue

            if sanitized_chars and sanitized_chars[-1] == " ":
                sanitized_chars[-1] = "\n"
                normalized_whitespace += 1
                continue
            if not sanitized_chars or sanitized_chars[-1] == "\n":
                normalized_whitespace += 1
                continue
            sanitized_chars.append("\n")
            continue

        if _is_disallowed_codepoint(codepoint):
            removed_chars += 1
            continue

        if char.isspace() or codepoint in (0x00A0, 0x3000):
            if not sanitized_chars or sanitized_chars[-1] in (" ", "\n", "\t"):
                normalized_whitespace += 1
                continue
            sanitized_chars.append(" ")
            continue

        sanitized_chars.append(char)
        if char.isalnum() or unicodedata.name(char, "").startswith("CJK UNIFIED IDEOGRAPH"):
            meaningful_chars += 1

    while sanitized_chars and sanitized_chars[-1] in (" ", "\n", "\t"):
        sanitized_chars.pop()
        normalized_whitespace += 1

    sanitized = "".join(sanitized_chars)
    return sanitized, {
        "original_length": len(text),
        "removed_chars": removed_chars,
        "normalized_whitespace": normalized_whitespace,
        "meaningful_chars": meaningful_chars,
    }

def sanitize_inputs(inputs: Union[str, List[str]]) -> tuple[List[str], List[dict]]:
    sentences = [inputs] if isinstance(inputs, str) else inputs
    sanitized_sentences: List[str] = []
    stats_list: List[dict] = []

    for sentence in sentences:
        sanitized, stats = sanitize_text(sentence)
        if not sanitized or stats["meaningful_chars"] < 1:
            raise HTTPException(status_code=422, detail="Embedding input is empty after sanitization")
        sanitized_sentences.append(sanitized)
        stats_list.append(stats)

    return sanitized_sentences, stats_list

def get_sparse_weights(last_hidden_state, input_ids):
    """
    修复版稀疏向量提取：
    1. 使用 input_ids (Token ID) 作为索引
    2. 过滤特殊字符
    3. 确保权重为正数
    """
    # 模拟 BGE-M3 的线性层：取绝对值并做简单激活
    weights = np.max(np.maximum(last_hidden_state, 0), axis=-1)
    
    results = []
    for i, row in enumerate(weights):
        token_map = {}
        # 获取当前句子的 input_ids
        current_ids = input_ids[i]
        
        for j, tid in enumerate(current_ids):
            token_id = int(tid)
            weight = float(row[j])
            
            # 过滤：PAD(0), CLS(101), SEP(102) 以及低权重 token
            if token_id not in [0, 101, 102] and weight > 0.01:
                # 如果同一个 token 出现多次，取最大权重
                if token_id not in token_map or weight > token_map[token_id]:
                    token_map[token_id] = weight
                    
        results.append(token_map)
    return results

@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest):
    sentences, stats_list = sanitize_inputs(request.inputs)

    try:
        tokenizer_instance, model_instance = ensure_runtime()
        for stats, sentence in zip(stats_list, sentences):
            if stats["removed_chars"] > 0 or stats["normalized_whitespace"] > 0:
                preview = sentence[:120].replace("\n", "\\n").replace("\t", "\\t")
                print(
                    "sanitized embed input:",
                    f"removed={stats['removed_chars']}",
                    f"normalized_ws={stats['normalized_whitespace']}",
                    f"original_length={stats['original_length']}",
                    f"sanitized_length={len(sentence)}",
                    f"preview='{preview}'",
                )
        # 使用 return_tensors="np" 配合 OpenVINO
        encoded = tokenizer_instance(sentences, padding=True, truncation=True, return_tensors="np")
        output = model_instance(**encoded)
        
        # 1. 稠密向量：取 [CLS] 位
        dense_vecs = output.last_hidden_state[:, 0, :].tolist()
        
        # 2. 稀疏向量：传入 input_ids 进行索引映射
        sparse_vecs = get_sparse_weights(output.last_hidden_state, encoded['input_ids'])

        return EmbedResponse(
            dense_vecs=dense_vecs,
            sparse_vecs=sparse_vecs
        )

    except Exception as e:
        print(f"推理出错: {str(e)}")
        print(f"原始 inputs 类型: {type(request.inputs).__name__}")
        print(f"原始 inputs 值: {repr(request.inputs)}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT, workers=1)
