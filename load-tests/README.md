# Locust + Langfuse 压测

这个目录现在包含两类压测：

- `locustfile.py`：主压测套件，覆盖聊天 SSE、文档上传、ETL 状态轮询和自定义业务指标。
- `Invoke-UploadStressProbe.ps1` / `k6-upload.js`：保留为上传链路对照基准。

## Locust 快速开始

安装依赖：

```powershell
python -m pip install -r .\load-tests\requirements.txt
```

Web UI 调试：

```powershell
locust -f .\load-tests\locustfile.py --host http://localhost:8080
```

无 UI 基线：

```powershell
locust -f .\load-tests\locustfile.py `
  --headless -u 5 -r 1 -t 10m `
  --csv .\load-tests\results\baseline `
  --html .\load-tests\results\baseline.html
```

常用环境变量：

```powershell
$env:BASE_URL="http://localhost:8080"
$env:USERNAME="admin"
$env:PASSWORD="admin"
$env:ADMIN_USERNAME="admin"
$env:ADMIN_PASSWORD="admin"
$env:MODEL_ID="ollama"
$env:MODEL_RATIO="ollama=70,deepseek=30"
$env:CHAT_MODE_RATIO="rag=80,agent=20"
$env:QUESTION_SEED="20260416"
$env:QUESTION_FILE="D:\soft\java\javaFile\spring-ai-rag-knowledgebase\load-tests\data\questions.csv"
$env:UPLOAD_FILES="D:\path\a.pdf;D:\path\b.pdf"
$env:LOCUST_RUN_ID="baseline-local-001"
```

## Locust 指标口径

聊天 SSE 不把 HTTP 200 当作完整成功，拆成这些指标：

- `CHAT_METRIC request_accept_latency`：发起请求到 HTTP 200 / stream 建立。
- `CHAT_METRIC first_event_latency`：发起请求到第一个有效 SSE event。
- `CHAT_METRIC stream_complete_latency`：发起请求到收到 `done` event。
- `CHAT_RATE sse_error_event_rate`：每个聊天流是否出现 SSE `error` event。
- `CHAT_RATE stream_aborted_rate`：每个聊天流是否断开、超时或没有 `done` 收尾。

上传和 ETL 拆成这些指标：

- `UPLOAD_METRIC upload_accept_latency`：上传接口返回耗时。
- `UPLOAD_METRIC etl_complete_latency`：上传接受到最终 `COMPLETED` 的总耗时。
- `UPLOAD_METRIC external_stage_observed_duration:<STATUS>`：通过 `/api/v1/docs` 轮询观察到的阶段停留时间。
- `UPLOAD_RATE etl_final_failure_rate`：最终不是 `COMPLETED` 的比例。

注意：`external_stage_observed_duration` 是轮询估算值，受 `DOC_POLL_INTERVAL_SECONDS` 影响。报告中应优先使用 Langfuse/OTEL 的 `internal_stage_span_duration`，也就是 `etl.read`、`etl.split`、`etl.embed`、`etl.vector_upsert`、`etl.finish` span 的真实耗时。

## 问题集治理

`data/questions.csv` 固定字段：

```csv
question_id,mode,bucket,tags,expected_answer_length,user_input
```

要求：

- 使用固定 `QUESTION_SEED`，保证每轮阶梯压测的问题分布一致。
- `rag` 和 `agent` 使用独立样本池。
- `bucket` 至少区分 `short` / `long`，报告分别观察 p95/p99。
- `tags` 使用 `|` 分隔多个标签。
- 每个请求会带上 `X-Question-Id`、`X-Question-Bucket`、`X-Locust-Run-Id`，便于和 Langfuse trace 对齐。

## Langfuse / OTEL

本地 Langfuse 使用仓库根目录的 `docker-compose.langfuse.yml` 单独启动。后端压测时建议用环境变量打开 tracing：

```powershell
$env:TRACING_ENABLED="true"
$env:TRACING_SAMPLING_PROBABILITY="1.0"
$env:OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://localhost:3000/api/public/otel/v1/traces"
$env:OTEL_EXPORTER_OTLP_AUTHORIZATION="Basic <base64(public_key:secret_key)>"
```

采样建议：

- 基线测试：`1.0`
- 阶梯压测前半段：`0.2 ~ 0.5`
- 稳定性测试：`0.05 ~ 0.1`
- 击穿测试：低比例抽样或只保留关键 span

后端会写入这些关键 span：

- 聊天：`rag.hybrid_search`、`rag.rerank`、`rag.context_build`、`agent.execute`、`llm.chat_stream`
- ETL：`etl.upload_accept`、`etl.ingestion`、`etl.read`、`etl.split`、`etl.embed`、`etl.vector_upsert`、`etl.finish`

## 测试矩阵

- 模型：`Local LLM only`、`Cloud LLM only`、`Cloud preferred + local fallback`，如果项目启用则补测 `Local preferred + cloud fallback`。
- 冷热：`Cold run` 在服务启动后少量预热即开始；`Warm run` 完成一轮基线后再开始。
- 负载：聊天用户按 `1 -> 5 -> 10 -> 20 -> 50`；上传并发按 `1 -> 5 -> 10 -> 20`。

业务验收阈值：

- `chat first_event_latency p95 <= 3s`
- `chat stream_complete_latency p95 <= 30s`
- `upload_accept_latency p95 <= 1500ms`
- `etl_complete_latency p95 <= 5min`
- 聊天 HTTP 接受失败率、`sse_error_event_rate`、`stream_aborted_rate`、上传 5xx、ETL 最终失败率都 `< 1%`

---

# 上传压测验证

这个目录把“验证当前项目是否真的非阻塞、并发上传是否会炸”的方案落成了可直接执行的脚本和说明。

## 包含内容

- `Invoke-UploadStressProbe.ps1`
  - 使用管理员账号登录。
  - 通过 `/api/v1/docs/upload` 并发上传 PDF。
  - 上传进行时，默认每 `200ms` 探测一次 `/actuator/health`。
  - 轮询 `/api/v1/docs`，收集文档最终状态。
  - 在每个压测阶段前后抓取 `/actuator/prometheus` 快照。
  - 可选删除当前阶段创建的文档，便于重复复用同一批 PDF。

- `k6-upload.js`
  - 只做 HTTP 层的突发上传测试，观察上传延迟和健康检查接口响应。
  - 适合在不依赖 PowerShell 编排的情况下，拿到更干净的吞吐和时延信号。

## 重要注意事项

- 不要使用 `/api/v1/upload/batch` 来验证并发上传。当前服务实现用的是 `concatMap`，本质上是串行处理。
- 上传去重基于文件哈希。如果重复上传同一个 PDF 且不删除已有文档，ETL 会被短路，这样的结果不能代表真实压测。
- 想要得到真实并发效果，准备的不同 PDF 数量至少要达到你的最大并发数。否则实际并发上传数会被文件数量卡住。

## PowerShell 探针脚本

在仓库根目录下，用 PowerShell 7 运行：

```powershell
pwsh .\load-tests\Invoke-UploadStressProbe.ps1 `
  -BaseUrl "http://localhost:8080" `
  -Username "admin" `
  -Password "admin" `
  -FilePaths @(
    ".\backend\data\input\07c7d6497f3b455c8ed4636a85b9dbb9\学生守则.pdf",
    ".\backend\data\input\c30d2c57b4084e16bc217995a3fcfa3a\西北大学学生手册.pdf"
  ) `
  -ConcurrencyLevels @(1, 5, 10)
```

常用参数：

- `-SkipHealthProbe`：只测上传，不探测健康检查接口。
- `-SkipCleanup`：保留创建出的文档，便于手工检查。
- `-OutputDir .\load-tests\results\run-001`：自定义结果输出目录。
- `-DocPollTimeoutSeconds 900`：对大文档放宽 ETL 等待超时时间。

脚本会输出：

- `summary.json`：每个阶段的汇总结果。
- `<stage>/upload-results.json`：每个上传请求一条记录。
- `<stage>/health-results.json`：`/actuator/health` 探针原始采样数据。
- `<stage>/documents.json`：`/api/v1/docs` 返回的文档最终记录。

成功信号：

- 上传延迟保持在可接受范围内，HTTP 5xx 很少或没有明显增长。
- 上传过程中，`/actuator/health` 延迟没有明显恶化。
- 上传请求返回时间明显早于文档进入最终 `COMPLETED` 状态的时间。
- 文档最终状态大多数是 `COMPLETED`，而不是长时间卡在 `READING/SPLITTING/VECTORIZING`。

失败信号：

- `/actuator/health` 延迟明显升高，甚至开始超时。
- 随着并发升高，上传 5xx 或传输错误明显增加。
- 文档长时间停留在中间状态。
- 同一时间窗口内，Python `/embed` 失败或向量清理日志明显增多。

## k6 突发测试

示例：

```powershell
k6 run `
  -e BASE_URL=http://localhost:8080 `
  -e USERNAME=admin `
  -e PASSWORD=admin `
  -e UPLOAD_FILES="D:\soft\java\javaFile\spring-ai-rag-knowledgebase\backend\data\input\07c7d6497f3b455c8ed4636a85b9dbb9\学生守则.pdf;D:\soft\java\javaFile\spring-ai-rag-knowledgebase\backend\data\input\c30d2c57b4084e16bc217995a3fcfa3a\西北大学学生手册.pdf" `
  -e VUS=5 `
  -e ITERATIONS=10 `
  -e HEALTH_DURATION=3m `
  .\load-tests\k6-upload.js
```

建议用法：

- 每个并发级别单独跑一次 k6。
- PowerShell 脚本保留用来汇总文档状态和做清理。
- 对比 k6 的 HTTP 延迟和 PowerShell 健康探针延迟。如果两边一起恶化，通常说明是应用整体接近饱和，而不是只有某一个下游服务出问题。

## 推荐执行流程

1. 启动后端、MySQL、Redis、Milvus 和本地 embedding 服务。
2. 先用 `-ConcurrencyLevels @(1)` 跑一轮基线。
3. 再按 `@(5, 10, 20, 30)` 逐步提升并发，前提是你准备了足够多的不同 PDF。
4. 每轮结束后重点查看：
   - `summary.json`
   - 后端日志
   - Python `/embed` 日志
   - 各阶段汇总中的 `/actuator/prometheus` 快照
5. 在没有系统性 5xx、没有健康检查崩塌、没有文档状态大面积积压的前提下，能稳定完成的最高并发级别，就是当前系统的大致稳定并发上限。
