---
# spring-ai-rag-knowledgebase
A “Campus Knowledge Base” RAG (Retrieval-Augmented Generation) project built with **Spring Boot + Spring AI**, **Milvus** as the vector database, and **Ollama (OpenAI-compatible API)** for local chat/embedding models. The frontend is **Vite + Vue** and supports **SSE streaming** responses.
This repository is a monorepo with `backend/` (Spring Boot) and `frontend/` (Vue).
---
## Features

- Document ingestion pipeline: parse → chunk → embed → store in Milvus
- RAG chat: vector search → context assembly → LLM generation
- SSE streaming responses for a “typing” experience
- Docker Compose stack for Milvus + dependencies
- (Optional) OpenTelemetry → OTEL Collector → Jaeger tracing

---

## Tech Stack

### Backend
- Java 17
- Spring Boot 3.x
- Spring AI 1.x
- Milvus Vector Store (Spring AI Milvus starter)
- (Optional) OpenTelemetry Java Agent

### Frontend
- Vite + Vue + TypeScript

### Infrastructure (Linux VM via Docker Compose)
- Milvus Standalone (with etcd / minio)
- Redis / MySQL (if used by the project)
- OTEL Collector / Jaeger
- Attu (Milvus UI)

---

## Repository Structure

.
├─ backend/                  # Spring Boot backend
├─ frontend/                 # Vue frontend
├─ .gitignore
├─ README.zh-CN.md
└─ README.md                 # English README

Runtime Topology (Important)
Recommended / default setup:
Linux VM: infrastructure (Milvus + dependencies, MySQL/Redis, OTEL, Jaeger, Attu)
Windows host: backend started from IntelliJ IDEA
Windows host: frontend started via Vite
You can run everything on a single machine as well, but this README focuses on the VM + Windows workflow.

---
Quick Start
Prerequisites

Windows: Java 17, Maven, Node.js 18+

Linux VM: Docker + Docker Compose

Ollama running (local or reachable over network)

---
1) Start Milvus / infra on the Linux VM

On the Linux VM:

cd ~/campus-rag/
docker compose up -d
docker compose ps


You should see containers like:

milvus-standalone, milvus-etcd, milvus-minio

(optional) otel-collector, jaeger, milvus-attu

(optional) campus-mysql, milvus-redis

Common ports (check your compose mapping)

Milvus gRPC: 19530

OTEL Collector (HTTP): 4318

Jaeger UI: 16686 (common default)

Attu UI: 3000 (common default)

Tip: use bridged networking for the VM so your Windows host can reach <VM_IP>:19530.

2) Configure the backend

Use an example config and keep secrets local.

From repo root:

cd backend
copy src\main\resources\application-ollama-openai.example.properties src\main\resources\application-ollama-openai.properties


Edit backend/src/main/resources/application-ollama-openai.properties:

# --- Ollama (OpenAI compatible) ---
spring.ai.openai.base-url=http://localhost:11434
spring.ai.openai.api-key=ollama
spring.ai.openai.chat.options.model=qwen2.5:7b
spring.ai.openai.embedding.options.model=nomic-embed-text

# --- Milvus (Linux VM) ---
spring.ai.vectorstore.milvus.host=<VM_IP>     
spring.ai.vectorstore.milvus.port=19530
spring.ai.vectorstore.milvus.initialize-schema=true

# (Optional) Redis / MySQL if required by your project

3) Run the backend (IntelliJ IDEA)

Open backend/ in IntelliJ IDEA

Run the Spring Boot main application class

(Optional) select the proper Spring profile if your project uses profiles

Backend default:

http://localhost:8080

4) Run the frontend
cd frontend
npm install
npm run dev


Frontend default:

http://localhost:5173

Optional: IntelliJ JVM Options (Observability & Ingestion)

If you want OpenTelemetry tracing (Jaeger) and/or local directory ingestion, add these VM options:

# OpenTelemetry (optional)
-javaagent:backend/otel/opentelemetry-javaagent.jar
-Dotel.exporter.otlp.endpoint=http://<VM_IP>:4318
-Dotel.exporter.otlp.protocol=http/protobuf
-Dotel.metrics.exporter=none
-Dotel.logs.exporter=none

# Spring AI (optional)
-Dspring.ai.openai.chat.options.model=qwen2.5:3b
-Dspring.ai.openai.client.read-timeout=600s

# Local ingestion directory (optional)
-Dinput.directory=<YOUR_INPUT_DIR>

-Dfile.encoding=UTF-8


Notes:

Replace <VM_IP> with your Linux VM IP (e.g. 192.168.193.128)

Replace <YOUR_INPUT_DIR> with a folder containing documents to ingest (Windows example: D:\...\data\input)

The OpenTelemetry javaagent is not committed to the repo. Download it and place it under backend/otel/.

Secrets & .gitignore

Do NOT commit:

backend/src/main/resources/application-ollama-openai.properties

frontend/.env / .env.*

any passwords, API keys, internal IPs

Recommended:

commit *.example.properties / .env.example instead

Having two .gitignore files (repo root + frontend/.gitignore) is normal and does not conflict. Git ignore rules are applied hierarchically.

Troubleshooting
Milvus connection timeout: DEADLINE_EXCEEDED

This typically means the backend cannot reach Milvus.

Checklist:

Containers running on VM: docker compose ps

Port mapping is correct (19530)

Windows can reach VM port (PowerShell):

Test-NetConnection <VM_IP> -Port 19530


VM network mode: bridged networking is simplest




