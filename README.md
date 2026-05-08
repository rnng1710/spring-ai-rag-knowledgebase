<p align="center">
  <a href="#-中文">中文</a> |
  <a href="#-english">English</a>
</p>

---

## 🇨🇳 中文

# Spring AI RAG Knowledge Base
### 高可靠混合检索 + Agent RAG 私有知识库

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.0-blue)
![Vue](https://img.shields.io/badge/Vue-3.4-42b883)
![Milvus](https://img.shields.io/badge/Milvus-2.6.9-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-red)

这是一个面向私有化部署的 RAG 知识库系统，核心目标不是做一个简单的“上传文档然后问答”的 Demo，而是把文档导入、混合检索、权限过滤、Agent 闭环问答、SSE 流式体验、失败补偿和可观测性串成一条可运行的工程链路。

项目适合校园制度、企业内规、通知公告、PDF/Office/Markdown/TXT 文档等知识库场景，也适合作为 Spring Boot WebFlux + Milvus + Vue3 的 RAG 工程参考。

## 核心能力

### 混合检索与重排

- Milvus `vector_store` 集合同时保存 dense embedding 与 sparse lexical vector。
- 查询阶段走 dense + sparse 双路召回，并使用 RRF 融合结果。
- 本地 TEI Cross-Encoder 重排服务负责精排，`RerankService` 带 Resilience4j 熔断与隔离。
- 检索上下文注入文件名、页码、标签、空间编码、ACL 等元数据，便于生成带来源的回答。

### RAG 与 Agent 双模式

`POST /api/v1/chat` 通过 SSE 返回结果，支持两种模式：

- `rag`：默认快速模式，执行检索、重排、上下文组装和直接生成。
- `agent`：多步骤 Agent 模式，包含规划、查询改写、知识检索、草稿、审查、修订、追问候选和最终答案阶段。

Agent 工具包括知识片段搜索、可用标签列表和追问选项生成，前端会展示阶段状态与流式回答。

### 异步 ETL 与任务治理

- 上传后创建文档记录和 ETL job，后台 worker 异步处理。
- 文档状态：`UPLOADED -> READING -> SPLITTING -> VECTORIZING -> COMPLETED / FAILED`。
- ETL job 状态：`PENDING / RUNNING / SUCCESS / FAILED / CANCELED`。
- PDF 按页读取并保留 page metadata，其他文档通过 Tika 读取。
- `TextSanitizer` 清理控制字符、异常空白和低质量文本。
- 向量写入失败时执行补偿清理，减少孤儿向量。
- watchdog / lease / retry 机制处理卡住任务和失败重试。

### 认证、RBAC 与文档 ACL

- `/api/v1/**` 使用 Spring Security WebFlux + JWT HS256 保护。
- `/api/v1/auth/login` 和 `/api/v1/auth/refresh` 放行，其余接口需要认证。
- JWT `roles` claim 会映射为 `ROLE_` 权限，接口使用 `@PreAuthorize` 做 RBAC。
- 文档 ACL 元数据包括 `is_public`、`allowed_roles`、`allowed_dept_ids`、`owner_dept_id`、`space_code`、`tags`。
- 检索时根据当前用户角色、部门和请求的空间/标签生成 Milvus filter，避免越权召回。

### 前端管理与用户体验

- Vue3 + TypeScript + Element Plus。
- 用户聊天页支持 RAG / Agent 模式、模型选择、空间与标签过滤、SSE 流式消息。
- 管理端支持文档上传、批量上传、删除、下载、重试、权限更新和状态查看。
- 前端封装 access token / refresh token，支持 401 后刷新和 SSE 重连。
- 中英文 i18n 已接入。

### 存储与可观测性

- MySQL：用户、文档元数据、ETL job 状态。
- Redis：聊天记忆、SSE/ETL 状态扇出。
- Milvus：dense + sparse hybrid vector store。
- MinIO：文档对象存储。
- Jaeger / OpenTelemetry / Prometheus actuator：链路追踪与指标出口。
- 可选 Langfuse OTLP 配置见 `.env.langfuse.example` 和 `docker-compose.langfuse.yml`。

## 架构

```text
Vue3 Frontend
  |  HTTP + SSE
  v
Spring Boot WebFlux Backend
  |
  +-- /api/v1/chat              RAG / Agent SSE chat
  +-- /api/v1/auth              JWT login / refresh / password
  +-- /api/v1/docs              document CRUD, upload, retry, permissions
  +-- AgentOrchestrator         planning, tool calls, review, final answer
  +-- ChatService               retrieve -> rerank -> generate
  +-- HybridSearchService       Milvus dense + sparse + RRF
  +-- RerankService             TEI Cross-Encoder with circuit breaker
  +-- EtlPipeline / Worker      read -> sanitize -> split -> vectorize -> write
  +-- CurrentUserContext        RBAC and search scope
  |
  +-- MySQL                     metadata, users, ETL jobs
  +-- Redis                     chat memory, status pub/sub
  +-- Milvus                    vector_store collection
  +-- MinIO                     uploaded document objects
  +-- TEI Reranker              http://localhost:8099/rerank
  +-- Python Embedding Server   http://localhost:8098/embed
```

## 目录结构

```text
.
├── backend/                 # Spring Boot WebFlux backend
│   ├── sql/                 # schema migration / backfill scripts
│   └── src/
│       ├── main/java/net/topikachu/rag/
│       │   ├── agent/       # Agent orchestration, tools, stages
│       │   ├── ai/memory/   # Redis-backed chat memory
│       │   ├── api/         # chat, auth, user, SSE controllers
│       │   ├── auth/        # current user context and search scope
│       │   ├── business/    # document domain, ETL jobs, ACL refresh
│       │   ├── config/      # security, Milvus, Redis, MinIO, LLM props
│       │   ├── service/     # chat, ETL, storage, user services
│       │   └── evaluation/  # benchmark and RAG evaluation utilities
│       └── test/java/       # JUnit 5 tests
├── frontend/                # Vue3 + TypeScript + Element Plus
├── python/                  # BGE-M3 OpenVINO embedding service
├── evaluation/              # evaluation assets/results
├── load-tests/              # load test assets
├── docker-compose.yml       # MySQL, Redis, Milvus, MinIO, Jaeger, TEI
├── docker-compose.langfuse.yml
└── README.md
```

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.5.0, WebFlux, Spring Security, Spring AI 1.1.0 |
| 数据访问 | MyBatis Plus, MySQL 8.0 |
| 向量检索 | Milvus 2.6.9, Java SDK 2.5.8, dense + sparse vector |
| 重排 | Hugging Face Text Embeddings Inference, BGE reranker |
| Embedding | Python FastAPI, OpenVINO, BGE-M3, dense + sparse 输出 |
| 前端 | Vue 3.4, TypeScript, Vite 5, Element Plus, vue-i18n |
| 缓存/消息 | Redis |
| 对象存储 | MinIO |
| 可观测性 | Spring Actuator, Micrometer, OpenTelemetry, Jaeger, Prometheus endpoint |

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 18+
- Python 3.10+，并安装 FastAPI、Uvicorn、Transformers、Optimum Intel、OpenVINO 等依赖
- Docker / Docker Compose
- 本地或远程 LLM 服务：项目提供 Ollama OpenAI-compatible、DeepSeek、Gemini 策略
- 本地 BGE-M3 OpenVINO 模型目录
- 本地 BGE reranker 模型目录 `./models/bge-reranker-base`

### 2. 启动基础设施

```bash
docker-compose up -d
```

默认端口：

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| MySQL | `3309 -> 3306` | database `campus_knowledge` |
| Redis | `6379` | cache and pub/sub |
| Milvus | `19530`, `9091` | vector database |
| MinIO | `9002`, `9003` | object API and console |
| Attu | `8000` | Milvus UI |
| Jaeger | `16686` | tracing UI |
| OTEL Collector | `4318` | OTLP HTTP ingest |
| TEI Reranker | `8099` | `/rerank` endpoint |

`reranker` 容器会挂载 `./models/bge-reranker-base:/data`。启动前需要先准备模型目录。

### 3. 配置后端

建议从示例文件复制本地配置：

```bash
cd backend
cp src/main/resources/application.example.properties src/main/resources/application-local.properties
```

重点检查：

- MySQL、Redis、Milvus、MinIO 地址
- `security.jwt.secret`，必须是至少 32 bytes 的本地密钥
- `rag.embedding.url`，默认 `http://localhost:8098`
- `rag.rerank.url`，默认 `http://localhost:8099/rerank`
- `rag.agent.enable` 与 `rag.agent.default-mode`
- `spring.ai.openai.deepseek.*`、`spring.ai.google.genai.*` 或 Ollama OpenAI-compatible 相关配置
- `rag.object-storage.*`

仓库中的 `application.properties` 可能包含开发机地址。正式使用时应改成本机环境变量或本地 profile 覆盖，不要提交真实密钥。

### 4. 启动 embedding 服务

编辑 `python/server.py` 中的模型路径和设备：

```python
OV_MODEL_PATH = r"D:\soft\python\model\bge-m3-ov"
PORT = 8098
DEVICE = "GPU"
```

然后启动：

```bash
cd python
python server.py
```

服务接口：

```text
POST http://localhost:8098/embed
```

请求体支持字符串或字符串数组，响应包含 `dense_vecs` 和 `sparse_vecs`。

### 5. 启动后端

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

后端默认监听 Spring Boot 默认端口 `8080`。如果你直接编辑 `application.properties`，也可以省略 `-Dspring-boot.run.profiles=local`。如果 Milvus collection 不存在，`MilvusSchemaInitializer` 会创建带 `embedding` 和 `sparse_vector` 字段的 hybrid schema。

### 6. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认读取：

```text
frontend/.env.example
VITE_API_BASE=http://localhost:8080
```

开发地址通常是 `http://localhost:5173`。后端 CORS 当前允许该地址。

## 主要接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/login` | public | 登录并返回 access / refresh token |
| `POST` | `/api/v1/auth/refresh` | public | 刷新 token |
| `POST` | `/api/v1/auth/change-password` | USER / ADMIN | 修改当前用户密码 |
| `POST` | `/api/v1/chat?conversationId=...` | USER / ADMIN | SSE RAG / Agent 对话 |
| `GET` | `/api/v1/tags` | USER / ADMIN | 当前用户可访问标签 |
| `GET` | `/api/v1/spaces` | USER / ADMIN | 当前用户可访问空间 |
| `POST` | `/api/v1/docs/upload` | ADMIN | 单文件上传 |
| `POST` | `/api/v1/upload/batch` | ADMIN | 批量上传 |
| `GET` | `/api/v1/docs` | ADMIN | 文档列表 |
| `GET` | `/api/v1/docs/{id}/download` | ADMIN | 下载原始文档 |
| `DELETE` | `/api/v1/docs/{id}` | ADMIN | 删除文档 |
| `POST` | `/api/v1/docs/{id}/retry` | ADMIN | 重试导入 |
| `PATCH` | `/api/v1/docs/{id}/permissions` | ADMIN | 更新文档 ACL |
| `POST` | `/api/v1/docs/backfill-acl-metadata` | ADMIN | 回填 Milvus ACL metadata |

## 配置速查

### 检索

```properties
rag.retrieval.dense-topk=50
rag.retrieval.hybrid-topk=30
rag.retrieval.rerank-topk=5
rag.retrieval.rrf-k=60
rag.retrieval.max-context-chars=8000
```

### Agent

```properties
rag.agent.enable=true
rag.agent.max-steps=3
rag.agent.timeout-ms=12000
rag.agent.default-mode=rag
```

### ETL

```properties
rag.etl.worker.batch-size=5
rag.etl.worker.lock-minutes=10
rag.etl.worker.fixed-delay-ms=5000
rag.upload.max-size-bytes=52428800
rag.upload.allowed-ext=pdf,doc,docx,txt,md
```

### Object Storage

```properties
rag.object-storage.enabled=true
rag.object-storage.endpoint=http://localhost:9002
rag.object-storage.bucket=rag-documents
rag.object-storage.access-key=${MINIO_ACCESS_KEY:minioadmin}
rag.object-storage.secret-key=${MINIO_SECRET_KEY:minioadmin}
```

### JWT

```properties
security.jwt.secret=${JWT_SECRET:change_me_to_a_secure_random_string_32_bytes+}
security.jwt.expiration=3600000
security.jwt.refresh-expiration=86400000
```

## 开发与测试

后端：

```bash
cd backend
mvn test
mvn -q -DskipTests test-compile
```

前端：

```bash
cd frontend
npm run build
```

Python embedding sanitizer 测试：

```bash
cd python
python -m pytest
```

当前测试覆盖包括 Agent executor/tools、Reactive chat gateway、模型策略工厂、ETL worker、Hybrid vector writer、TextSanitizer、ETL job service 和架构守护测试。

## Roadmap

- [x] Dense + sparse hybrid retrieval
- [x] Milvus RRF fusion and rerank
- [x] RAG / Agent dual chat mode
- [x] SSE streaming frontend
- [x] JWT auth and RBAC
- [x] Document ACL filtering in retrieval
- [x] Async ETL worker, lease, retry and watchdog
- [x] MinIO-backed document storage
- [x] OpenTelemetry / Jaeger / actuator metrics
- [ ] 完整数据库初始化脚本整理
- [ ] OCR / scanned PDF parsing
- [ ] More benchmark reports and reproducible evaluation docs
- [ ] Production deployment guide

## License

Apache 2.0

## Author

rnng

---

## 🇺🇸 English

# Spring AI RAG Knowledge Base
### Reliable Hybrid Retrieval + Agent RAG for Private Knowledge Bases

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.0-blue)
![Vue](https://img.shields.io/badge/Vue-3.4-42b883)
![Milvus](https://img.shields.io/badge/Milvus-2.6.9-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-red)

This is a private-deployment-oriented RAG knowledge base. It is not only a document upload and Q&A demo; it connects ingestion, hybrid retrieval, permission-aware filtering, Agent answer refinement, SSE streaming, failure compensation, and observability into a runnable engineering workflow.

The project fits internal policy, campus knowledge, notices, PDF/Office/Markdown/TXT document search, and also works as a reference implementation for Spring Boot WebFlux + Milvus + Vue3 RAG systems.

## Key Features

### Hybrid Retrieval and Rerank

- Milvus `vector_store` stores both dense embeddings and sparse lexical vectors.
- Query-time retrieval uses dense + sparse recall with RRF fusion.
- A local TEI Cross-Encoder reranker improves final ordering.
- `RerankService` uses Resilience4j circuit breaker and bulkhead protection.
- Context includes file name, page number, tags, space code, ACL fields, and chunk metadata.

### RAG and Agent Modes

`POST /api/v1/chat` streams responses over SSE and supports two modes:

- `rag`: default fast path, retrieve -> rerank -> build context -> generate.
- `agent`: multi-step flow with planning, query rewriting, retrieval, drafting, reviewing, revising, follow-up options, and final answer generation.

Agent tools include knowledge snippet search, available tag listing, and follow-up option generation. The frontend displays stage updates and streamed content.

### Async ETL and Job Governance

- Upload creates document metadata and an ETL job; background workers process ingestion.
- Document states: `UPLOADED -> READING -> SPLITTING -> VECTORIZING -> COMPLETED / FAILED`.
- ETL job states: `PENDING / RUNNING / SUCCESS / FAILED / CANCELED`.
- PDFs are read page by page with page metadata; other documents are read via Tika.
- `TextSanitizer` removes control characters, invalid whitespace, and low-quality text.
- Failed vector writes trigger compensation cleanup.
- Watchdog, lease, and retry logic handle stuck and failed jobs.

### Auth, RBAC, and Document ACL

- `/api/v1/**` is protected by Spring Security WebFlux + HS256 JWT.
- `/api/v1/auth/login` and `/api/v1/auth/refresh` are public; other API routes require authentication.
- JWT `roles` are mapped to `ROLE_` authorities and enforced through `@PreAuthorize`.
- Document ACL metadata includes `is_public`, `allowed_roles`, `allowed_dept_ids`, `owner_dept_id`, `space_code`, and `tags`.
- Retrieval builds Milvus filters from current user role, department, requested spaces, and requested tags.

### Frontend

- Vue3 + TypeScript + Element Plus.
- Chat UI supports RAG / Agent mode, model selection, space/tag filters, and SSE streaming.
- Admin UI supports upload, batch upload, delete, download, retry, permission updates, and status views.
- Access token / refresh token handling is wrapped in the frontend API client.
- Chinese and English i18n are included.

### Storage and Observability

- MySQL: users, document metadata, ETL jobs.
- Redis: chat memory and ETL/SSE status fan-out.
- Milvus: hybrid vector collection.
- MinIO: original document object storage.
- Jaeger / OpenTelemetry / Spring Actuator Prometheus endpoint: tracing and metrics.
- Optional Langfuse OTLP setup is available through `.env.langfuse.example` and `docker-compose.langfuse.yml`.

## Architecture

```text
Vue3 Frontend
  |  HTTP + SSE
  v
Spring Boot WebFlux Backend
  |
  +-- /api/v1/chat              RAG / Agent SSE chat
  +-- /api/v1/auth              JWT login / refresh / password
  +-- /api/v1/docs              document CRUD, upload, retry, permissions
  +-- AgentOrchestrator         planning, tool calls, review, final answer
  +-- ChatService               retrieve -> rerank -> generate
  +-- HybridSearchService       Milvus dense + sparse + RRF
  +-- RerankService             TEI Cross-Encoder with circuit breaker
  +-- EtlPipeline / Worker      read -> sanitize -> split -> vectorize -> write
  +-- CurrentUserContext        RBAC and search scope
  |
  +-- MySQL                     metadata, users, ETL jobs
  +-- Redis                     chat memory, status pub/sub
  +-- Milvus                    vector_store collection
  +-- MinIO                     uploaded document objects
  +-- TEI Reranker              http://localhost:8099/rerank
  +-- Python Embedding Server   http://localhost:8098/embed
```

## Repository Layout

```text
.
├── backend/                 # Spring Boot WebFlux backend
│   ├── sql/                 # schema migration / backfill scripts
│   └── src/
│       ├── main/java/net/topikachu/rag/
│       │   ├── agent/       # Agent orchestration, tools, stages
│       │   ├── ai/memory/   # Redis-backed chat memory
│       │   ├── api/         # chat, auth, user, SSE controllers
│       │   ├── auth/        # current user context and search scope
│       │   ├── business/    # document domain, ETL jobs, ACL refresh
│       │   ├── config/      # security, Milvus, Redis, MinIO, LLM props
│       │   ├── service/     # chat, ETL, storage, user services
│       │   └── evaluation/  # benchmark and RAG evaluation utilities
│       └── test/java/       # JUnit 5 tests
├── frontend/                # Vue3 + TypeScript + Element Plus
├── python/                  # BGE-M3 OpenVINO embedding service
├── evaluation/              # evaluation assets/results
├── load-tests/              # load test assets
├── docker-compose.yml       # MySQL, Redis, Milvus, MinIO, Jaeger, TEI
├── docker-compose.langfuse.yml
└── README.md
```

## Stack

| Layer | Technologies |
| --- | --- |
| Backend | Java 17, Spring Boot 3.5.0, WebFlux, Spring Security, Spring AI 1.1.0 |
| Persistence | MyBatis Plus, MySQL 8.0 |
| Vector Search | Milvus 2.6.9, Java SDK 2.5.8, dense + sparse vectors |
| Rerank | Hugging Face Text Embeddings Inference, BGE reranker |
| Embedding | Python FastAPI, OpenVINO, BGE-M3, dense + sparse output |
| Frontend | Vue 3.4, TypeScript, Vite 5, Element Plus, vue-i18n |
| Cache / Messaging | Redis |
| Object Storage | MinIO |
| Observability | Spring Actuator, Micrometer, OpenTelemetry, Jaeger, Prometheus endpoint |

## Quick Start

### 1. Prerequisites

- JDK 17+
- Maven 3.9+
- Node.js 18+
- Python 3.10+ with FastAPI, Uvicorn, Transformers, Optimum Intel, and OpenVINO dependencies
- Docker / Docker Compose
- Local or remote LLM service: Ollama OpenAI-compatible, DeepSeek, and Gemini strategies are present
- Local BGE-M3 OpenVINO model directory
- Local BGE reranker model directory at `./models/bge-reranker-base`

### 2. Start Infrastructure

```bash
docker-compose up -d
```

Default ports:

| Service | Port | Notes |
| --- | --- | --- |
| MySQL | `3309 -> 3306` | database `campus_knowledge` |
| Redis | `6379` | cache and pub/sub |
| Milvus | `19530`, `9091` | vector database |
| MinIO | `9002`, `9003` | object API and console |
| Attu | `8000` | Milvus UI |
| Jaeger | `16686` | tracing UI |
| OTEL Collector | `4318` | OTLP HTTP ingest |
| TEI Reranker | `8099` | `/rerank` endpoint |

The `reranker` container mounts `./models/bge-reranker-base:/data`, so prepare that model directory before startup.

### 3. Configure Backend

Use the example config as a starting point:

```bash
cd backend
cp src/main/resources/application.example.properties src/main/resources/application-local.properties
```

Check these settings first:

- MySQL, Redis, Milvus, and MinIO endpoints
- `security.jwt.secret`, at least 32 bytes for HS256
- `rag.embedding.url`, default `http://localhost:8098`
- `rag.rerank.url`, default `http://localhost:8099/rerank`
- `rag.agent.enable` and `rag.agent.default-mode`
- `spring.ai.openai.deepseek.*`, `spring.ai.google.genai.*`, or Ollama OpenAI-compatible settings
- `rag.object-storage.*`

The committed `application.properties` may contain local development IPs. Use environment variables or local profile overrides for your machine, and do not commit real secrets.

### 4. Start Embedding Service

Edit `python/server.py` for your local model path and device:

```python
OV_MODEL_PATH = r"D:\soft\python\model\bge-m3-ov"
PORT = 8098
DEVICE = "GPU"
```

Start it:

```bash
cd python
python server.py
```

Endpoint:

```text
POST http://localhost:8098/embed
```

The request accepts a string or string array. The response contains `dense_vecs` and `sparse_vecs`.

### 5. Start Backend

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The backend uses Spring Boot's default port `8080`. If you edit `application.properties` directly, you can omit `-Dspring-boot.run.profiles=local`. If the Milvus collection does not exist, `MilvusSchemaInitializer` creates a hybrid schema with `embedding` and `sparse_vector` fields.

### 6. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend API base:

```text
frontend/.env.example
VITE_API_BASE=http://localhost:8080
```

The Vite dev server is usually available at `http://localhost:5173`, which is allowed by backend CORS.

## Main APIs

| Method | Path | Role | Description |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/login` | public | Login and return access / refresh tokens |
| `POST` | `/api/v1/auth/refresh` | public | Refresh tokens |
| `POST` | `/api/v1/auth/change-password` | USER / ADMIN | Change current user's password |
| `POST` | `/api/v1/chat?conversationId=...` | USER / ADMIN | SSE RAG / Agent chat |
| `GET` | `/api/v1/tags` | USER / ADMIN | Accessible tags |
| `GET` | `/api/v1/spaces` | USER / ADMIN | Accessible spaces |
| `POST` | `/api/v1/docs/upload` | ADMIN | Upload one document |
| `POST` | `/api/v1/upload/batch` | ADMIN | Batch upload |
| `GET` | `/api/v1/docs` | ADMIN | List documents |
| `GET` | `/api/v1/docs/{id}/download` | ADMIN | Download original document |
| `DELETE` | `/api/v1/docs/{id}` | ADMIN | Delete document |
| `POST` | `/api/v1/docs/{id}/retry` | ADMIN | Retry ingestion |
| `PATCH` | `/api/v1/docs/{id}/permissions` | ADMIN | Update document ACL |
| `POST` | `/api/v1/docs/backfill-acl-metadata` | ADMIN | Backfill Milvus ACL metadata |

## Config Reference

### Retrieval

```properties
rag.retrieval.dense-topk=50
rag.retrieval.hybrid-topk=30
rag.retrieval.rerank-topk=5
rag.retrieval.rrf-k=60
rag.retrieval.max-context-chars=8000
```

### Agent

```properties
rag.agent.enable=true
rag.agent.max-steps=3
rag.agent.timeout-ms=12000
rag.agent.default-mode=rag
```

### ETL

```properties
rag.etl.worker.batch-size=5
rag.etl.worker.lock-minutes=10
rag.etl.worker.fixed-delay-ms=5000
rag.upload.max-size-bytes=52428800
rag.upload.allowed-ext=pdf,doc,docx,txt,md
```

### Object Storage

```properties
rag.object-storage.enabled=true
rag.object-storage.endpoint=http://localhost:9002
rag.object-storage.bucket=rag-documents
rag.object-storage.access-key=${MINIO_ACCESS_KEY:minioadmin}
rag.object-storage.secret-key=${MINIO_SECRET_KEY:minioadmin}
```

### JWT

```properties
security.jwt.secret=${JWT_SECRET:change_me_to_a_secure_random_string_32_bytes+}
security.jwt.expiration=3600000
security.jwt.refresh-expiration=86400000
```

## Development and Tests

Backend:

```bash
cd backend
mvn test
mvn -q -DskipTests test-compile
```

Frontend:

```bash
cd frontend
npm run build
```

Python embedding sanitizer:

```bash
cd python
python -m pytest
```

Current tests cover Agent executor/tools, Reactive chat gateway, model strategy factory, ETL worker, Hybrid vector writer, TextSanitizer, ETL job service, and architecture guards.

## Roadmap

- [x] Dense + sparse hybrid retrieval
- [x] Milvus RRF fusion and rerank
- [x] RAG / Agent dual chat mode
- [x] SSE streaming frontend
- [x] JWT auth and RBAC
- [x] Document ACL filtering in retrieval
- [x] Async ETL worker, lease, retry and watchdog
- [x] MinIO-backed document storage
- [x] OpenTelemetry / Jaeger / actuator metrics
- [ ] Consolidated database initialization scripts
- [ ] OCR / scanned PDF parsing
- [ ] More benchmark reports and reproducible evaluation docs
- [ ] Production deployment guide

## License

Apache 2.0

## Author

rnng
