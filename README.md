<p align="center">
  <a href="#-中文">中文</a> | 
  <a href="#-english">English</a>
</p>

---

## 🇺🇸 English

# 🚀 Enterprise Async RAG KnowledgeBase
### Full-Async Hybrid Retrieval & Generation System based on Spring AI + Milvus V2

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green) ![Milvus](https://img.shields.io/badge/Milvus-2.6.9-blue) ![License](https://img.shields.io/badge/License-Apache%202.0-red)

> **Developer's Note**: This is an industrial-grade RAG solution designed for **private deployment**. Unlike simple demos, this project addresses real-world engineering pain points such as **hybrid retrieval latency**, **high-concurrency document ingestion**, and **context window overflows**.

---

## 🌟 Key Innovations

Built on Spring Boot 3 and Project Reactor, this system features several architectural breakthroughs:

### 1. 🚀 Industrial-Grade Hybrid Search
* **Dual-Path Parallel Retrieval**: Utilizes the **TEI (Text Embeddings Inference)** engine to generate both Dense and Sparse vectors in parallel, balancing semantic understanding with exact keyword matching.
* **Server-Side RRF Fusion**: Abandons traditional in-memory ranking. We directly leverage the **Milvus V2 Client's** `HybridSearchReq` to perform **Reciprocal Rank Fusion (RRF)** within the vector database, significantly reducing network overhead.
* **High-Availability Semantic Reranking**: Integrates a local **BGE-Reranker** service fortified with a custom **Circuit Breaker**. If the reranker experiences high latency or failure, the system automatically degrades gracefully to ensure 100% availability.

### 2. ⚡ Reactive Async ETL Pipeline
* **Non-Blocking I/O**: Refactored entirely with **Project Reactor (Flux/Mono)**. The entire pipeline—file reading, Tika parsing, chunking, and vectorization—is asynchronous.
* **Backpressure Control**: The `HybridVectorWriter` implements flow control logic. Architected to handle high-concurrency ingestion scenarios, ensuring that the backend Embedding Model service remains stable under heavy load.
* **Smart De-duplication**: Implements **SHA-256** file fingerprinting for idempotent uploads, preventing resource waste from duplicate processing.

### 3. 🔍 Granular Context Governance
* **Structured Injection**: Automatically extracts document metadata (filenames, page numbers, chunk indices) and explicitly injects them into the Prompt, ensuring LLM responses are fully traceable.
* **Token Budget Management**: Dynamically calculates context length and supports a `maxContextChars` soft limit, precisely pruning content before it reaches the LLM to prevent **Context Window Overflow**.

---

## 🏗 System Architecture

### Tech Stack
| Module | Selection | Description |
| :--- | :--- | :--- |
| **Backend** | Spring Boot 3.5, Spring AI | Core framework integrated with Reactor for reactive programming |
| **Vector DB** | **Milvus V2** | Supports Sparse/Dense hybrid search & server-side RRF |
| **AI Engine** | Ollama + TEI | Local LLM inference & high-performance embedding/reranking |
| **Storage** | MySQL + Redis | Metadata management & session/stream caching |
| **Protocol** | SSE (Server-Sent Events) | Typewriter-style streaming responses |

### 📂 Core Code Guide
For developers looking to dive straight into the code:

```text
net.topikachu.rag.service/
├── chat/
│   ├── HybridSearchService.java  # [Core] Milvus V2 Dual-Path Recall & Server-Side RRF
│   ├── RerankService.java        # [Highlight] Local Rerank + State-Aware Circuit Breaker
│   └── ChatService.java          # [Orchestrator] Structured Context Assembly & Streaming
└── etl/
    ├── EtlPipeline.java          # [Pipeline] Flux-based Reactive Document Processing
    ├── HybridVectorWriter.java   # [Storage] Parallel Vector Write & Backpressure
    └── SparseVectorGenerator.java # [Algo] Text to Sparse Vector (SPLADE/BM25 Logic)
```
🛠 Quick Start
1. Prerequisites
Ensure Docker and JDK 17+ are installed.

### 🐳 Infrastructure Stack
This project orchestrates **8 core microservices** via Docker Compose, establishing a robust environment for Vector Storage, AI Inference, and System Observability.

This project utilizes a **Hybrid Architecture** combining Docker containers and Local Computing to leverage Windows native GPU/NPU resources for vector inference.

| Layer | Component | Environment | Port | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Storage** | Milvus V2 / MySQL / Redis | **Docker** | 19530 / 3306 / 6379 | Data persistence & Session caching |
| **Observability** | Jaeger / OTEL Collector | **Docker** | 16686 / 4318 | Distributed tracing & Metrics |
| **Rerank** | TEI Reranker Service | **Docker** | 8099 | Cross-Encoder Reranking service |
| **Vector Service** | **Embedding Service** | **Local (Python)** | **8098** | **(Core)** Sparse/Dense vector generation via `sentence-transformers` |
| **Backend** | Spring Boot App | **Local (Java)** | 8080 | RAG Orchestration & Business Logic |
| **Inference** | Ollama | Local App | 11434 | LLM Chat Generation |

> [!WARNING]
> **⚠️ Pre-flight Check: Model Weights**
> The `rerank-service` mounts a local volume for model weights. Before running `docker-compose up`, you **MUST**:
> 1. Create a directory named `models` in the project root.
> 2. Download the `bge-reranker-base` model (or your preferred reranker) and place it at:
>    `./models/bge-reranker-base`

Bash
# 1. Start Infrastructure (Milvus, Redis, MySQL)
docker-compose up -d

# 2. Start Embedding Service (Python)
This service generates vectors locally using your Windows GPU.

cd embedding-service
Install dependencies (e.g., torch, fastapi, uvicorn, sentence-transformers)
pip install -r requirements.txt
Start the API server (Ensure it listens on port 8098)
python server.py

# 3. Pull LLM Model (Ensure Ollama is running)
ollama pull qwen2.5:3b
2. Key Configuration (application.properties)
This project is deeply tuned for Hybrid Retrieval. Please refer to the following configuration:

Properties
# --- Milvus V2 Connection ---
spring.ai.vectorstore.milvus.client.host=localhost
spring.ai.vectorstore.milvus.collection-name=enterprise_knowledge

# --- RAG Tuning Strategies ---
rag.retrieval.dense-topk=50    # Initial vector recall count
rag.retrieval.hybrid-topk=20   # Count retained after RRF fusion
rag.retrieval.rerank-topk=5    # Final count fed to LLM (Key Performance Metric)
rag.retrieval.rrf-k=60         # RRF fusion constant

# --- Hardware Adaptation (Prevent iGPU Crash) ---
# If using Intel Arc or other iGPUs, strictly limit context length
rag.retrieval.max-context-chars=4096 

⚠️ Engineering Notes
Milvus Version: Must use v2.4+. The HybridSearchReq API is not available in older versions.

TEI Compatibility: Sparse vector generation depends on specific Text Embeddings Inference models (e.g., bge-m3). Ensure your TEI container is running and ports are open.

Circuit Breaker Tuning: If your server CPU is weak, consider increasing rag.rerank.timeout in application.properties to avoid frequent circuit breaker trips.

📅 Roadmap
[x] Full-Async ETL Pipeline

[x] Milvus Server-Side RRF Fusion

[ ] GraphRAG Knowledge Graph Augmentation

[ ] Multi-modal Document Parsing (OCR)


## 🇨🇳 中文

---

# 🚀 Enterprise Async RAG KnowledgeBase
### 基于 Spring AI + Milvus V2 的全异步混合检索与生成系统

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green) ![Milvus](https://img.shields.io/badge/Milvus-2.4-blue) ![License](https://img.shields.io/badge/License-Apache%202.0-red)

> **Developer's Note**: 这是一个专为**私有化部署**设计的工业级 RAG 方案。不同于简单的 Demo，本项目解决了**混合检索延迟**、**大规模文档并发写入**以及**上下文窗口溢出**等真实的工程痛点。

---

## 🌟 核心技术演进 (Key Innovations)

本项目基于 Spring Boot 3 与 Project Reactor 构建，核心架构突破包括：

### 1. 🚀 工业级混合检索 (Industrial-Grade Hybrid Search)
* **双路并行化召回**：利用 **TEI (Text Embeddings Inference)** 引擎并行生成 Dense（稠密）与 Sparse（稀疏）向量，兼顾语义理解与精确关键词匹配。
* **服务端 RRF 融合**：摒弃传统的内存排序，直接调用 **Milvus V2 Client** 的 `HybridSearchReq`，在向量数据库侧完成 **RRF (Reciprocal Rank Fusion)** 排名，显著降低网络开销与计算延迟。
* **高可用语义精排**：集成本地 **BGE-Reranker** 服务，并辅以**熔断器 (Circuit Breaker)**，确保在重排服务高延时或异常时自动降级，保障系统 100% 可用性。

### 2. ⚡ 响应式异步 ETL 流水线 (Reactive Async ETL)
* **全链路非阻塞**：基于 **Project Reactor (Flux/Mono)** 重构。从文件读取、Tika 解析、分块到向量化，全流程异步处理。
* **背压控制 (Backpressure)**：`HybridVectorWriter` 实现了流控逻辑，基于 Reactive Streams 规范构建，HybridVectorWriter 内置流控机制。设计上能够应对大规模文档的并发写入，通过动态调节请求速率，防止压垮后端的 Embedding 模型服务。。
* **智能去重**：基于 **SHA-256** 的文件指纹去重机制，实现幂等上传，避免重复计算造成的资源浪费。

### 3. 🔍 精细化上下文治理 (Granular Context Governance)
* **结构化注入**：自动提取文档元数据（文件名、页码、Chunk索引），并在 Prompt 中显式注入，彻底解决 LLM 回答无法溯源的问题。
* **Token 预算管理**：动态计算上下文长度，支持 `maxContextChars` 软拦截，在进入 LLM 前精准裁剪，防止 **Context Window Overflow**（上下文窗口溢出）。

---

## 🏗 系统架构 (System Architecture)

### 技术栈 (Tech Stack)
| 模块 | 选型 | 说明 |
| :--- | :--- | :--- |
| **Backend** | Spring Boot 3.5, Spring AI | 核心框架，集成 Reactor 响应式编程 |
| **Vector DB** | **Milvus V2** | 支持 Sparse/Dense 混合检索与服务端 RRF |
| **AI Engine** | Ollama + TEI | 本地 LLM 推理与高性能向量嵌入/重排 |
| **Storage** | MySQL + Redis | 元数据管理与会话/流式缓存 |
| **Protocol** | SSE (Server-Sent Events) | 打字机效果的流式响应 |

### 📂 核心代码导读
方便开发者快速定位核心逻辑：

```text
net.topikachu.rag.service/
├── chat/
│   ├── HybridSearchService.java  # [核心] Milvus V2 双路召回与服务端 RRF 实现
│   ├── RerankService.java        # [亮点] 本地重排序 + 状态感知熔断器
│   └── ChatService.java          # [编排] 结构化上下文组装与流式问答
└── etl/
    ├── EtlPipeline.java          # [流水线] 基于 Flux 的响应式文档处理流
    ├── HybridVectorWriter.java   # [存储] 并行化双向量写入与背压控制
    └── SparseVectorGenerator.java # [算法] 文本转稀疏向量 (SPLADE/BM25逻辑)
```
🛠 快速开始 (Quick Start)
1. 环境准备
确保本地安装了 Docker 与 JDK 17+。

### 🐳 基础设施清单 (Infrastructure Matrix)
本项目采用 **Docker 容器化** 与 **本地原生计算** 相结合的混合架构，旨在充分利用 Windows 宿主机的 GPU/NPU 资源进行高性能向量推理。

| 服务层级 | 组件名称 | 运行环境 | 端口 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **存储层** | Milvus V2 / MySQL / Redis | **Docker** | 19530 / 3306 / 6379 | 向量/业务数据持久化与缓存 |
| **可观测性** | Jaeger / OTEL Collector | **Docker** | 16686 / 4318 | 全链路追踪与性能监控 |
| **重排序** | TEI Reranker Service | **Docker** | 8099 | 本地重排序服务 (Cross-Encoder) |
| **向量服务** | **Embedding Service** | **Local (Python)** | **8098** | **(核心)** 基于 Python 的稀疏/稠密向量生成服务 |
| **业务核心** | Spring Boot Backend | **Local (Java)** | 8080 | 业务逻辑与 RAG 流程编排 |
| **推理层** | Ollama | Local App | 11434 | LLM 对话生成 |

> [!WARNING]
> **⚠️ 启动前必读：模型挂载**
> `rerank-service` 服务依赖本地模型文件运行。在执行 `docker-compose up` 之前，请务必完成以下操作：
> 1. 在项目根目录创建 `models` 文件夹。
> 2. 下载 `bge-reranker-base` 模型文件（推荐使用 HuggingFace 或 ModelScope），并解压至：
>    `./models/bge-reranker-base`
> *如果未挂载模型文件，Reranker 容器将无法启动。*

Bash
# 1. 启动基础设施 (Milvus, Redis, MySQL)
docker-compose up -d

# 2. 启动向量化服务 (Python)
该服务运行在本地环境，直接调用显卡进行推理。

cd embedding-service
安装依赖 (如 torch, fastapi, uvicorn, sentence-transformers 等)
pip install -r requirements.txt
启动 API 服务 (默认端口需配置为 8098)
python server.py

# 3. 拉取 LLM 模型 (确保 Ollama 已安装)
ollama pull qwen2.5:3b
2. 关键配置 (application.properties)
本项目针对混合检索进行了深度调优，建议参考以下配置：

Properties
# --- Milvus V2 连接 ---
spring.ai.vectorstore.milvus.client.host=localhost
spring.ai.vectorstore.milvus.collection-name=enterprise_knowledge

# --- RAG 调优策略 ---
rag.retrieval.dense-topk=50    # 初筛向量召回数量
rag.retrieval.hybrid-topk=20   # RRF融合后保留数量
rag.retrieval.rerank-topk=5    # 精排后喂给 LLM 的最终数量 (关键性能指标)
rag.retrieval.rrf-k=60         # RRF 融合常数

# --- 硬件适配 (防止集显崩溃) ---
# 如果使用 Intel Arc 等集显，务必严格限制上下文长度
rag.retrieval.max-context-chars=4096 

⚠️ 开发注意事项 (Engineering Notes)
Milvus 版本：必须使用 v2.4+，否则 HybridSearchReq API 不可用。

TEI 兼容性：稀疏向量生成依赖 Text Embeddings Inference 的特定模型（如 bge-m3），请确保 TEI 容器正常运行且端口开放。

熔断器调整：如果你的服务器 CPU 较弱，建议在 application.properties 中调大 rag.rerank.timeout，避免频繁触发熔断降级。

📅 路线图 (Roadmap)
[x] 全异步 ETL 流水线

[x] Milvus 服务端 RRF 融合

[ ] 基于 GraphRAG 的知识图谱增强

[ ] 多模态文档解析 (OCR)

License: Apache 2.0

Author: rnng
