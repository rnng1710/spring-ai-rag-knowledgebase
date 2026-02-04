import uvicorn
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from optimum.intel import OVModelForFeatureExtraction
from transformers import AutoTokenizer
from typing import List, Dict, Union

# ================= 配置区域 =================
OV_MODEL_PATH = r"D:\soft\python\model\bge-m3-ov"
PORT = 8098
DEVICE = "GPU" 
# ===========================================

print(f"🚀 正在启动 OpenVINO 优化版服务，设备: {DEVICE}")

ov_config = {"CACHE_DIR": ""} 

tokenizer = AutoTokenizer.from_pretrained(OV_MODEL_PATH)
model = OVModelForFeatureExtraction.from_pretrained(
    OV_MODEL_PATH, 
    device=DEVICE,
    ov_config=ov_config
)

app = FastAPI(title="BGE-M3 OpenVINO Pro Server")

class EmbedRequest(BaseModel):
    inputs: Union[str, List[str]]

class EmbedResponse(BaseModel):
    dense_vecs: List[List[float]]
    sparse_vecs: List[Dict[int, float]] # 注意：Key 改为 int 类型以匹配 Milvus

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
    sentences = [request.inputs] if isinstance(request.inputs, str) else request.inputs
    
    try:
        # 使用 return_tensors="np" 配合 OpenVINO
        encoded = tokenizer(sentences, padding=True, truncation=True, return_tensors="np")
        output = model(**encoded)
        
        # 1. 稠密向量：取 [CLS] 位
        dense_vecs = output.last_hidden_state[:, 0, :].tolist()
        
        # 2. 稀疏向量：传入 input_ids 进行索引映射
        sparse_vecs = get_sparse_weights(output.last_hidden_state, encoded['input_ids'])

        return EmbedResponse(
            dense_vecs=dense_vecs,
            sparse_vecs=sparse_vecs
        )

    except Exception as e:
        print(f"❌ 推理出错: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT, workers=1)