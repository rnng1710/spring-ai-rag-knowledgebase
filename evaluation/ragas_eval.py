#!/usr/bin/env python3
"""
Ragas RAG Assessment Script (Ragas v0.4+ using Legacy API)
Reads JSONL exported by RagasDataExporter (Java) and runs Ragas evaluation.

Judge LLM: DeepSeek API (deepseek-chat)
Embeddings: Local BGE-M3 server (localhost:8098)
"""

import json
import os
import sys
import asyncio
import warnings
from pathlib import Path
from typing import List

import pandas as pd
import requests
from datasets import Dataset

# 💡 屏蔽 Ragas 烦人的版本弃用黄色警告，让输出保持整洁
warnings.filterwarnings("ignore", category=DeprecationWarning)

from ragas import evaluate
# 💡 新增导入：引入运行配置，用于限制并发数
from ragas.run_config import RunConfig

# 核心回退 1：退回到旧版指标导入，因为 evaluate() 只认识它们
from ragas.metrics import Faithfulness, AnswerRelevancy, AnswerCorrectness
try:
    from ragas.metrics import ResponseGroundedness
except ImportError:
    pass

# 核心回退 2：退回到带 s 的旧版 Embeddings 基类
from ragas.embeddings import BaseRagasEmbeddings

# ======================== Configuration ========================

# ⚠️ 请确保这里填入了你的实际 Key，或者在终端设置了环境变量！
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
EMBEDDING_URL = os.environ.get("EMBEDDING_URL", "http://localhost:8098")

JSONL_INPUT_PREFIX = Path(__file__).parent / "ragas_eval_data_"
REPORT_OUTPUT_PREFIX = Path(__file__).parent / "ragas_report_"


# ======================== Custom Embedding Wrapper ========================

class LocalBgeM3Embeddings(BaseRagasEmbeddings):
    """Legacy Ragas embedding wrapper for local BGE-M3 server at localhost:8098."""

    def __init__(self, base_url: str = "http://localhost:8098", timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # 旧版接口的 4 个必须方法
    def embed_query(self, text: str) -> List[float]:
        resp = requests.post(
            f"{self.base_url}/embed",
            json={"inputs": text},
            timeout=self.timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        return data["dense_vecs"][0]

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        results = []
        for text in texts:
            results.append(self.embed_query(text))
        return results

    async def aembed_query(self, text: str) -> List[float]:
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self.embed_query, text)

    async def aembed_documents(self, texts: List[str]) -> List[List[float]]:
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self.embed_documents, texts)


# ======================== Helper Functions ========================

def load_jsonl(file_path: Path) -> list[dict]:
    if not file_path.exists():
        print(f"Error: JSONL file not found: {file_path}")
        sys.exit(1)

    records = []
    with open(file_path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                print(f"Warning: Skipping invalid JSON at line {line_num}: {e}")

    if not records:
        print("Error: No valid records found in JSONL file.")
        sys.exit(1)

    return records


def build_dataset(records: list[dict]) -> Dataset:
    return Dataset.from_dict({
        "user_input": [r["question"] for r in records],
        "response": [r["answer"] for r in records],
        "retrieved_contexts": [r["contexts"] for r in records],
        "reference": [r.get("ground_truth", "") for r in records],
    })


def create_judge_llm():
    from openai import OpenAI
    from ragas.llms import llm_factory

    client = OpenAI(
        api_key=DEEPSEEK_API_KEY,
        base_url="https://api.deepseek.com/v1",
    )
    return llm_factory(
        "deepseek-chat",
        client=client,
    )


def main():
    print("=" * 50)
    print("  Ragas RAG Assessment (Dual Model Evaluation: Ollama & DeepSeek)")
    print("  Judge: DeepSeek API | Embeddings: local BGE-M3")
    print("=" * 50)

    models_to_evaluate = ["ollama", "deepseek"]

    for model_name in models_to_evaluate:
        jsonl_input = Path(f"{JSONL_INPUT_PREFIX}{model_name}.jsonl")
        report_output = Path(f"{REPORT_OUTPUT_PREFIX}{model_name}.csv")

        print(f"\n\n{'=' * 40}")
        print(f"  Evaluating Model: {model_name.upper()}")
        print(f"{'=' * 40}")

        print(f"\n[1/4] Loading JSONL from: {jsonl_input}")
        records = load_jsonl(jsonl_input)
        print(f"       Loaded {len(records)} evaluation records.")

        print("[2/4] Building Ragas dataset...")
        dataset = build_dataset(records)

        print("[3/4] Configuring DeepSeek as judge LLM + local BGE-M3 embeddings...")
        judge_llm = create_judge_llm()
        local_embeddings = LocalBgeM3Embeddings(base_url=EMBEDDING_URL)

        metrics = [
            Faithfulness(llm=judge_llm),
            AnswerRelevancy(llm=judge_llm, embeddings=local_embeddings),
            AnswerCorrectness(llm=judge_llm, embeddings=local_embeddings, weights=[1.0, 0.0]),
        ]
        print(f"       Using {len(metrics)} metrics initialized.")

        print("[4/4] Running Ragas evaluation (this may take a few minutes)...")

        # 💡 核心防错：限制最大并发数为 2，防止触发 DeepSeek 的限流保护
        result = evaluate(
            dataset,
            metrics=metrics,
            llm=judge_llm,
            embeddings=local_embeddings,
            run_config=RunConfig(max_workers=2) # 限制同时只跑 2 个任务
        )

        print("\n" + "-" * 40)
        print(f"  Evaluation Results for: {model_name.upper()}")
        print("-" * 40)
        print(result)

        df = result.to_pandas()
        df.to_csv(report_output, index=False, encoding="utf-8-sig")
        print(f"\nDetailed report saved to: {report_output}")

        print("\n--- Per-Question Scores ---")
        print(df.to_string(index=False))


if __name__ == "__main__":
    main()