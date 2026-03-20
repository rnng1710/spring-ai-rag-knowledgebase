<p align="center">
  <a href="#-中文">中文</a> |
  <a href="#-english">English</a>
</p>

---

## 🇨🇳 中文

# 高可靠混合检索 Agent RAG 知识库系统
### 面向私有化部署的工程化 Spring AI + Milvus 知识库项目

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-blue)
![Milvus](https://img.shields.io/badge/Milvus-2.6.9-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-red)

> 一个面向校园制度、通知公告、PDF/Office 文档场景的私有化知识库项目。  
> 它不仅是“检索 + 生成”的 Demo，还把 **混合检索、异步 ETL、Agent 闭环问答、失败补偿与状态治理** 做成了可运行、可追踪、可恢复的工程链路。

## 项目简介

很多知识库项目能“回答问题”，但在真实使用中仍然会遇到这些问题：

- 关键词检索命中率低，语义表达和别名问题处理不好
- 长文档拼接上下文后容易污染答案或撑爆上下文窗口
- 文档导入链路吞吐差，失败后难以恢复，容易产生脏数据
- 回答虽然生成出来了，但来源、页码、证据路径不清晰
- 复杂问题直接“一次检索 + 一次生成”，容易出现幻觉或错误引用

这个项目的目标，是把上述问题放到一个统一系统里解决：  
通过 **Dense + Sparse 混合检索、异步 ETL 流水线、反思式 Agent RAG、状态机 + 补偿恢复机制**，构建一个更接近真实落地场景的知识库系统。

## 核心能力

### 1. 混合检索

- 基于 Milvus V2 实现 Dense + Sparse 双路召回
- 使用向量库侧 `HybridSearchReq` 完成 RRF 融合，减少应用层排序开销
- 接入本地 BGE Reranker 做语义精排，并支持超时与熔断降级
- 通过结构化上下文组装，将文件名、页码、片段等元数据注入回答链路

### 2. 异步 ETL 流水线

- 文档上传后进入异步 ETL 链路，覆盖读取、解析、切分、向量化、写库
- 使用 SHA-256 指纹避免重复入库
- 对 PDF 进行页级处理，并保留页码相关元数据
- 对文本做清洗与低质量提取过滤，减少脏内容进入向量库和提示词

### 3. Agent RAG 闭环问答

- 支持 `rag` 与 `agent` 两种对话模式切换
- Agent 模式包含：问题规划、查询改写、混合检索、草稿生成、证据审查、修订定稿 / 追问补充
- 前端通过 SSE 展示阶段状态、检索说明和回答过程
- 对无页码证据场景做约束，避免在答案中伪造页码引用

### 4. 可靠性与一致性治理

- ETL 使用状态机管理：`UPLOADED -> READING -> SPLITTING -> VECTORIZING -> COMPLETED / FAILED`
- 向量写入失败时执行补偿删除，减少孤儿向量
- 完成态使用 CAS 保护，避免看门狗和正常任务并发覆盖状态
- 提供失败重试、重试次数限制、看门狗清理卡死任务等恢复机制
- 前端请求链路支持 access token 刷新、SSE 401 重试与会话清理

## 系统架构

```text
User / Admin UI (Vue3 + TypeScript)
        |
        |  HTTP + SSE
        v
Spring Boot API / Chat / Agent Orchestrator
        |
        +--> Hybrid Retrieval (Milvus + Dense/Sparse + RRF)
        +--> Rerank Service (TEI Cross-Encoder)
        +--> Chat Models (Ollama / DeepSeek / Gemini)
        +--> Async ETL Pipeline
        |       |
        |       +--> Python Embedding Service (BGE-M3, port 8098)
        |       +--> Milvus Vector Store
        |
        +--> MySQL (document metadata / ETL status)
        +--> Redis (SSE / memory / status fan-out)
```

### 当前架构特征

- 存储与基础设施主要通过 Docker Compose 启动
- Spring Boot 后端、Vue 前端、Python 向量服务可以本地开发运行
- 向量化、重排、聊天模型分离，便于后续替换和调优
- 设计重点不是“堆模型”，而是把问答、导入、治理和恢复链路串起来

## 项目结构

```text
.
├── backend/         # Spring Boot 后端、API、检索、Agent、ETL、状态治理
├── frontend/        # Vue3 + TypeScript 前端
├── python/          # 本地 embedding 服务（BGE-M3 / OpenVINO）
├── rerank-service/  # 重排服务相关目录
├── rerank/          # 重排或实验相关内容
├── evaluation/      # 评测数据或结果导出相关内容
├── docker-compose.yml
└── README.md
```

### 关键代码入口

- `backend/src/main/java/net/topikachu/rag/service/chat/`  
  混合检索、重排、聊天编排
- `backend/src/main/java/net/topikachu/rag/agent/`  
  Agent 规划、改写、审查、追问与事件流
- `backend/src/main/java/net/topikachu/rag/service/etl/`  
  ETL 流水线、向量写入、看门狗、补偿删除
- `frontend/src/views/UserChatView.vue`  
  用户聊天页、模式切换、Agent 阶段展示

## 快速开始

### 1. 环境准备

建议至少准备以下环境：

- JDK 17+
- Node.js 18+
- Python 3.10+
- Docker / Docker Compose
- 本地可用的聊天模型与 embedding / rerank 服务

### 2. 启动基础设施

项目根目录下执行：

```bash
docker-compose up -d
```

默认会启动这些核心服务：

- MySQL
- Redis
- Milvus / etcd / MinIO / Attu
- Jaeger / OTEL Collector
- TEI Reranker（容器内）

> 注意：`reranker` 服务默认挂载 `./models/bge-reranker-base`。  
> 启动前请先准备本地模型目录，否则该容器无法正常工作。

### 3. 配置后端

建议参考并复制：

```text
backend/src/main/resources/application.example.properties
backend/src/main/resources/application-ollama-openai.example.properties
```

需要重点确认的配置包括：

- MySQL 连接
- Redis 连接
- Milvus 主机与集合名
- `input.directory`
- `rag.embedding.url`
- `rag.rerank.url`
- `security.jwt.secret`
- `rag.agent.enable`

### 4. 启动本地 embedding 服务

当前仓库中的本地 embedding 服务入口为：

```text
python/server.py
```

启动方式示例：

```bash
cd python
python server.py
```

默认监听：

```text
http://localhost:8098
```

### 5. 启动后端

```bash
cd backend
mvn spring-boot:run
```

### 6. 启动前端

```bash
cd frontend
npm install
npm run dev
```

如需前端对接自定义后端地址，请使用：

```text
frontend/.env
frontend/.env.example
```

中的 `VITE_API_BASE` 相关配置。

## 关键配置

下面这些配置最值得先看：

### 检索与上下文

- `rag.retrieval.dense-topk`
- `rag.retrieval.hybrid-topk`
- `rag.retrieval.rerank-topk`
- `rag.retrieval.rrf-k`
- `rag.retrieval.max-context-chars`

### Agent

- `rag.agent.enable`
- `rag.agent.max-steps`
- `rag.agent.timeout-ms`
- `rag.agent.default-mode`

### ETL 与向量化

- `input.directory`
- `rag.embedding.url`
- `rag.embedding.timeout-ms`
- `rag.rerank.url`
- `rag.rerank.timeout-ms`
- `rag.upload.max-size-bytes`
- `rag.upload.allowed-ext`

### 认证与安全

- `security.jwt.secret`
- `security.jwt.expiration`
- `security.jwt.refresh-expiration`

## 典型链路

### 文档导入链路

1. 用户上传文档，后端记录 `UPLOADED`
2. ETL 异步启动，进入 `READING / SPLITTING / VECTORIZING`
3. 文本清洗、页级处理、切分、向量生成后写入 Milvus
4. 成功则标记为 `COMPLETED`
5. 失败则标记 `FAILED`，并触发补偿删除或后续重试

### 问答链路

1. 用户在前端选择 `rag` 或 `agent` 模式
2. 后端执行混合检索与重排
3. `rag` 模式直接构造上下文并生成回答
4. `agent` 模式会额外执行规划、改写、草稿、审查、修订 / 追问
5. 前端通过 SSE 接收消息流、来源信息和 Agent 阶段更新

## 当前限制

- 当前更偏向单项目、私有化部署导向，不是多租户 SaaS 平台
- Python embedding 服务的模型路径和设备配置仍偏本地开发机风格，需要按部署环境调整
- 对图像、OCR、多模态文档的支持仍然有限

## Roadmap

- [x] 混合检索（Dense + Sparse + RRF）
- [x] 本地重排与熔断降级
- [x] 异步 ETL 流水线
- [x] Agent 模式问答与阶段追踪
- [x] ETL 看门狗、补偿删除、CAS 完成态治理
- [x] 前端 token 刷新与 SSE 重试
- [ ] 更细粒度的任务 heartbeat 机制
- [ ] GraphRAG / 知识图谱增强
- [ ] OCR / 多模态文档解析
- [ ] 更完整的监控与基准测试说明

## License

Apache 2.0

## Author

rnng

---

## 🇺🇸 English

# Reliable Hybrid Retrieval Agent RAG Knowledge Base
### An engineering-focused Spring AI + Milvus knowledge base for private deployment

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-blue)
![Milvus](https://img.shields.io/badge/Milvus-2.6.9-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-red)

> A private-deployment-oriented knowledge base project for campus policies, notices, PDFs, and Office documents.  
> It is not just a “retrieve then generate” demo. It turns **hybrid retrieval, async ETL, agent-style answer refinement, and recovery / compensation logic** into a runnable engineering workflow.

## What this project is

Many knowledge base demos can answer questions, but they usually break down in real usage:

- keyword search misses semantic phrasing and aliases
- long documents pollute context or overflow the model window
- ingestion pipelines are slow, fragile, and hard to recover
- answers are generated without clear source, page, or evidence trace
- complex questions rely on one-shot retrieval plus one-shot generation, which increases hallucination risk

This project is built to address those issues in one system.  
It combines **dense + sparse hybrid retrieval, async ETL, reflection-style Agent RAG, and state-machine-based recovery** to get closer to a deployable real-world knowledge base.

## Key Features

### 1. Hybrid Retrieval

- Dense + Sparse dual-path recall on Milvus V2
- Server-side RRF fusion through `HybridSearchReq`
- Local BGE reranker with timeout protection and circuit-breaker-style fallback
- Structured context assembly with file name, page number, and chunk metadata

### 2. Async ETL Pipeline

- Asynchronous ingestion flow covering read, parse, split, vectorize, and write
- SHA-256 fingerprinting to prevent duplicate ingestion
- Page-aware PDF processing with page metadata preservation
- Text sanitization and low-quality extraction filtering before vector storage

### 3. Agent RAG Workflow

- Supports both `rag` and `agent` chat modes
- Agent mode includes planning, query rewriting, retrieval, draft generation, review, and revise / follow-up
- Frontend shows agent stages and notes over SSE
- Prevents fabricated page citations when evidence has no real page metadata

### 4. Reliability & Consistency

- ETL state machine: `UPLOADED -> READING -> SPLITTING -> VECTORIZING -> COMPLETED / FAILED`
- Compensation cleanup for failed vector writes
- CAS-protected completion update to avoid watchdog / normal-task races
- Retry limits, failed-task retry, and watchdog-based stuck-job cleanup
- Frontend access-token refresh and SSE retry handling

## Architecture Overview

```text
User / Admin UI (Vue3 + TypeScript)
        |
        |  HTTP + SSE
        v
Spring Boot API / Chat / Agent Orchestrator
        |
        +--> Hybrid Retrieval (Milvus + Dense/Sparse + RRF)
        +--> Rerank Service (TEI Cross-Encoder)
        +--> Chat Models (Ollama / DeepSeek / Gemini)
        +--> Async ETL Pipeline
        |       |
        |       +--> Python Embedding Service (BGE-M3, port 8098)
        |       +--> Milvus Vector Store
        |
        +--> MySQL (document metadata / ETL status)
        +--> Redis (SSE / memory / status fan-out)
```

### Architectural characteristics

- Core infrastructure runs via Docker Compose
- Spring Boot backend, Vue frontend, and Python embedding service can run locally for development
- Retrieval, rerank, chat models, and ingestion are split for easier tuning and replacement
- The focus is not just “using an LLM”, but making the entire answer + ingestion + recovery workflow operational

## Repo Structure

```text
.
├── backend/         # Spring Boot backend, APIs, retrieval, agent, ETL, state management
├── frontend/        # Vue3 + TypeScript frontend
├── python/          # Local embedding service (BGE-M3 / OpenVINO)
├── rerank-service/  # Rerank-related service assets
├── rerank/          # Rerank or experiment-related content
├── evaluation/      # Evaluation datasets or exported results
├── docker-compose.yml
└── README.md
```

### Key code entry points

- `backend/src/main/java/net/topikachu/rag/service/chat/`  
  hybrid retrieval, reranking, and chat orchestration
- `backend/src/main/java/net/topikachu/rag/agent/`  
  agent planning, rewriting, review, follow-up, and event streaming
- `backend/src/main/java/net/topikachu/rag/service/etl/`  
  ETL pipeline, vector writing, watchdog, and compensation cleanup
- `frontend/src/views/UserChatView.vue`  
  user chat UI, mode switching, and agent stage visualization

## Quick Start

### 1. Prerequisites

Recommended minimum setup:

- JDK 17+
- Node.js 18+
- Python 3.10+
- Docker / Docker Compose
- Locally available chat model and embedding / rerank services

### 2. Start infrastructure

From the project root:

```bash
docker-compose up -d
```

This starts the main infrastructure services:

- MySQL
- Redis
- Milvus / etcd / MinIO / Attu
- Jaeger / OTEL Collector
- TEI reranker

> Note: the `reranker` service mounts `./models/bge-reranker-base`.  
> Prepare that local model directory before startup, otherwise the container will not work properly.

### 3. Configure the backend

Use these files as references:

```text
backend/src/main/resources/application.example.properties
backend/src/main/resources/application-ollama-openai.example.properties
```

At minimum, verify:

- MySQL connection
- Redis connection
- Milvus host and collection name
- `input.directory`
- `rag.embedding.url`
- `rag.rerank.url`
- `security.jwt.secret`
- `rag.agent.enable`

### 4. Start the local embedding service

The current local embedding service entry point is:

```text
python/server.py
```

Example startup:

```bash
cd python
python server.py
```

Default endpoint:

```text
http://localhost:8098
```

### 5. Start the backend

```bash
cd backend
mvn spring-boot:run
```

### 6. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

If you need a custom backend URL, check:

```text
frontend/.env
frontend/.env.example
```

for `VITE_API_BASE`.

## Key Config

These are the most important configuration groups to review first.

### Retrieval and context

- `rag.retrieval.dense-topk`
- `rag.retrieval.hybrid-topk`
- `rag.retrieval.rerank-topk`
- `rag.retrieval.rrf-k`
- `rag.retrieval.max-context-chars`

### Agent

- `rag.agent.enable`
- `rag.agent.max-steps`
- `rag.agent.timeout-ms`
- `rag.agent.default-mode`

### ETL and vectorization

- `input.directory`
- `rag.embedding.url`
- `rag.embedding.timeout-ms`
- `rag.rerank.url`
- `rag.rerank.timeout-ms`
- `rag.upload.max-size-bytes`
- `rag.upload.allowed-ext`

### Auth and security

- `security.jwt.secret`
- `security.jwt.expiration`
- `security.jwt.refresh-expiration`

## End-to-End Flow

### Document ingestion flow

1. A user uploads a document and the backend records `UPLOADED`
2. Async ETL starts and moves through `READING / SPLITTING / VECTORIZING`
3. The system sanitizes text, processes PDFs page by page, splits content, generates vectors, and writes to Milvus
4. On success, the document is marked `COMPLETED`
5. On failure, it becomes `FAILED`, and compensation cleanup or retry can be triggered

### Question-answering flow

1. The user selects either `rag` or `agent` mode in the frontend
2. The backend performs hybrid retrieval and reranking
3. In `rag` mode, the system builds context and generates an answer directly
4. In `agent` mode, it additionally performs planning, rewriting, drafting, reviewing, and revise / follow-up logic
5. The frontend receives streamed messages, sources, and agent stage updates over SSE

## Current Limitations

- The project is oriented toward private deployment, not a multi-tenant SaaS platform
- The Python embedding service still reflects local-machine assumptions for model path and device selection
- OCR and multimodal document support are still limited

## Roadmap

- [x] Hybrid retrieval (Dense + Sparse + RRF)
- [x] Local rerank with timeout fallback
- [x] Async ETL pipeline
- [x] Agent mode with stage tracing
- [x] ETL watchdog, compensation cleanup, and CAS-protected completion
- [x] Frontend token refresh and SSE retry handling
- [ ] Finer-grained ETL heartbeat mechanism
- [ ] GraphRAG / knowledge graph enhancement
- [ ] OCR / multimodal document parsing
- [ ] More complete monitoring and benchmark documentation

## License

Apache 2.0

## Author

rnng
