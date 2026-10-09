# API 文档

本文档依据当前工作区源码整理，覆盖 Spring Boot WebFlux 后端、项目内 Python 嵌入服务和已暴露的 Actuator 端点。

## 1. 基本约定

- 后端默认地址：`http://localhost:8080`
- 后端 API 前缀：`/api/v1`
- 嵌入服务默认地址：`http://localhost:8098`
- JSON 编码：UTF-8
- 除登录和刷新令牌外，`/api/v1/**` 都要求 JWT Bearer Token。
- 角色分为 `USER` 和 `ADMIN`；JWT 的 `roles` claim 会映射为 Spring Security 的 `ROLE_` 权限。
- SSE 请求应发送 `Accept: text/event-stream`。

认证请求头：

```http
Authorization: Bearer <access_token>
```

普通 JSON 接口通常返回 `AjaxResult`：

```json
{
  "code": 0,
  "success": true,
  "msg": "",
  "tag": "success",
  "data": {}
}
```

业务失败不一定使用非 2xx HTTP 状态，调用方还应检查 `code` 和 `success`。安全过滤器会直接以 HTTP `401` 或 `403` 拒绝未认证或无权限的请求。

## 2. 接口总览

### 2.1 认证与个人设置

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/login` | 公开 | 登录并签发访问令牌、刷新令牌 |
| `POST` | `/api/v1/auth/refresh` | 公开 | 用刷新令牌换取一对新令牌 |
| `POST` | `/api/v1/auth/register` | ADMIN | 注册用户 |
| `POST` | `/api/v1/auth/change-password` | USER / ADMIN | 修改当前用户密码 |
| `GET` | `/api/v1/auth/me/preferences` | USER / ADMIN | 查询当前用户偏好 |
| `PUT` | `/api/v1/auth/me/preferences` | USER / ADMIN | 更新当前用户偏好 |

### 2.2 聊天与会话

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/chat?conversationId={id}` | USER / ADMIN | RAG 或 Agent 模式 SSE 对话 |
| `GET` | `/api/v1/chat/sessions` | USER / ADMIN | 分页查询当前用户的会话 |
| `GET` | `/api/v1/chat/sessions/{conversationId}/messages` | USER / ADMIN | 查询会话消息 |
| `PATCH` | `/api/v1/chat/sessions/{conversationId}` | USER / ADMIN | 重命名会话 |
| `DELETE` | `/api/v1/chat/sessions/{conversationId}` | USER / ADMIN | 软删除会话 |

### 2.3 文档、知识空间与 ETL

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/docs/upload` | ADMIN | 上传单个文档 |
| `POST` | `/api/v1/upload/batch` | ADMIN | 批量上传文档 |
| `GET` | `/api/v1/docs` | ADMIN | 分页查询文档 |
| `DELETE` | `/api/v1/docs/{id}` | ADMIN | 删除单个文档 |
| `DELETE` | `/api/v1/docs` | ADMIN | 批量删除文档 |
| `GET` | `/api/v1/docs/{id}/download` | ADMIN | 下载原始文档 |
| `GET` | `/api/v1/docs/by-uuid/{docUuid}/preview` | USER / ADMIN | 在 ACL 校验后预览原文 |
| `POST` | `/api/v1/docs/{id}/retry` | ADMIN | 重试失败的 ETL |
| `PATCH` | `/api/v1/docs/{id}/permissions` | ADMIN | 更新文档 ACL |
| `POST` | `/api/v1/docs/backfill-acl-metadata` | ADMIN | 提交 Milvus ACL 元数据回填 |
| `GET` | `/api/v1/tags` | USER / ADMIN | 查询可访问标签 |
| `GET` | `/api/v1/spaces` | USER / ADMIN | 查询可访问知识空间 |
| `GET` | `/api/v1/sse/subscribe` | 已认证 | 订阅当前用户的 ETL 状态 |
| `POST` | `/api/v1/index` | ADMIN | 对配置的输入目录执行索引 |

### 2.4 用户、仪表盘与评测

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/admin/users` | ADMIN | 查询用户 |
| `POST` | `/api/v1/admin/users` | ADMIN | 创建用户 |
| `DELETE` | `/api/v1/admin/users/{id}` | ADMIN | 删除用户 |
| `PUT` | `/api/v1/admin/users/{id}/password` | ADMIN | 重置用户密码 |
| `PUT` | `/api/v1/admin/users/{id}/role` | ADMIN | 修改用户角色 |
| `PUT` | `/api/v1/admin/users/{id}/department` | ADMIN | 修改用户部门 |
| `PUT` | `/api/v1/admin/users/{id}/default-space` | ADMIN | 修改用户默认知识空间 |
| `GET` | `/api/v1/dashboard/stats` | ADMIN | 查询仪表盘统计 |
| `PATCH` | `/api/v1/evaluation/{msgId}/feedback` | USER / ADMIN | 提交回答反馈 |
| `GET` | `/api/v1/admin/evaluation/conversations` | ADMIN | 查询评测会话和统计 |
| `PATCH` | `/api/v1/admin/evaluation/{evaluationId}/reference` | ADMIN | 更新参考答案 |
| `POST` | `/api/v1/admin/evaluation/auto/trigger` | ADMIN | 异步触发 RAGAS 自动评测 |
| `GET` | `/api/v1/admin/evaluation/auto/stats` | ADMIN | 查询自动评测汇总 |
| `GET` | `/api/v1/admin/evaluation/auto/scores` | ADMIN | 查询一条会话的自动评分 |
| `GET` | `/api/v1/admin/evaluation/auto/runs` | ADMIN | 查询自动评测运行历史 |

## 3. 认证 API

### 3.1 登录

`POST /api/v1/auth/login`

```json
{
  "username": "admin",
  "password": "password"
}
```

成功响应的 `data`：

```json
{
  "access_token": "<jwt>",
  "refresh_token": "<jwt>"
}
```

访问令牌默认有效期 1 小时，刷新令牌默认有效期 24 小时。用户名或密码为空时返回业务码 `400`；认证失败时返回业务码 `401`。

### 3.2 刷新令牌

`POST /api/v1/auth/refresh`

刷新令牌可放在以下任一位置；请求头优先：

```http
Authorization: Bearer <refresh_token>
```

```json
{
  "refresh_token": "<refresh_token>"
}
```

成功响应与登录相同。该接口只接受包含 `type=refresh` claim 的未过期 JWT。

### 3.3 注册、修改密码和偏好

注册：

```http
POST /api/v1/auth/register
Content-Type: application/json
```

```json
{
  "username": "new-user",
  "password": "password"
}
```

修改当前用户密码：

```json
{
  "old_password": "old-password",
  "new_password": "new-password"
}
```

可选字段 `username` 若存在，必须与 JWT 当前用户一致。

个人偏好的查询结果：

```json
{
  "defaultSpaceCode": "public"
}
```

更新请求：

```json
{
  "defaultSpaceCode": "public"
}
```

## 4. 聊天 API

### 4.1 发起对话

`POST /api/v1/chat?conversationId={conversationId}`

请求体：

```json
{
  "userInput": "请总结报销制度",
  "tags": ["财务"],
  "spaceCodes": ["company-policy"],
  "modelId": "deepseek",
  "mode": "rag",
  "msgId": "msg-001"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `userInput` | 是 | 用户问题 |
| `tags` | 否 | 检索标签；空数组表示不按标签缩小范围 |
| `spaceCodes` | 否 | 知识空间；为空时使用当前用户的默认知识空间 |
| `modelId` | 否 | 模型策略标识，由服务端策略工厂解析 |
| `mode` | 否 | `rag` 或 `agent`；缺省值来自 `rag.agent.default-mode` |
| `msgId` | 否 | 客户端消息 ID；缺省时服务端生成时间戳 ID |

查询参数 `conversationId` 必填。服务端会为未带用户名命名空间的 ID 自动添加 `<username>:` 前缀。请求的知识空间必须位于当前用户可访问范围内。

可选观测请求头：

- `X-Locust-Run-Id`
- `X-Question-Id`
- `X-Question-Bucket`

#### RAG 模式 SSE

```text
event: sources
data: [{"evidenceId":"E1","doc_uuid":"...","file_name":"policy.pdf","page_number":3,"file_type":"pdf"}]

event: message
data: 回答文本分片

event: done
data: {"msgId":"msg-001"}
```

错误时可能依次收到：

```text
event: error
data: {"msgId":"msg-001","message":"错误说明"}

event: message
data: 面向用户的错误文本

event: done
data: {"msgId":"msg-001"}
```

#### Agent 模式 SSE

Agent 模式会额外发送过程事件，最终回答当前以一个 `message` 事件返回：

```text
event: agent_stage
data: {"msgId":"msg-001","stage":"retrieving","sequence":2}

event: agent_note
data: {"msgId":"msg-001","stage":"retrieving","kind":"...","text":"...","timestamp":0,"sequence":2}

event: sources
data: {"msgId":"msg-001","sources":[{"evidenceId":"E1","docUuid":"...","fileName":"policy.pdf","pageNumber":3,"fileType":"pdf"}]}

event: message
data: {"msgId":"msg-001","chunk":"最终回答"}

event: done
data: {"msgId":"msg-001"}
```

`stage` 的可能值：`idle`、`planning`、`query_rewriting`、`retrieving`、`drafting`、`reviewing`、`revising`、`generating_final`、`done`、`error`。

注意 RAG 与 Agent 的 `sources`、`message` 数据形状不同，客户端需要按事件数据是数组、字符串还是对象分别解析。

调用示例：

```bash
curl -N -X POST "http://localhost:8080/api/v1/chat?conversationId=demo" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"userInput":"报销需要哪些材料？","spaceCodes":["company-policy"],"mode":"rag","msgId":"msg-001"}'
```

### 4.2 会话历史

查询会话：

```http
GET /api/v1/chat/sessions?keyword=报销&page=1&size=20
```

- `page` 默认 `1`。
- `size` 默认 `20`，服务端限制为 `1..50`。
- `keyword` 可选，去除首尾空白后用于标题检索。

`data` 示例：

```json
{
  "items": [
    {
      "conversationId": "admin:demo",
      "title": "报销需要哪些材料？",
      "titleStatus": "PENDING",
      "lastMessageAt": "2026-07-14T10:00:00",
      "createDate": "2026-07-14T10:00:00"
    }
  ],
  "total": 1,
  "page": 1,
  "size": 20
}
```

消息列表 `data` 是数组，元素字段为 `id`、`role`、`content`、`modelId`、`mode`、`messageIndex` 和 `createDate`。

重命名请求：

```json
{
  "title": "报销制度"
}
```

标题会折叠连续空白并截断到 80 个字符。删除接口为软删除，且只能操作当前用户自己的会话。

## 5. 文档与 ETL API

### 5.1 单文件上传

`POST /api/v1/docs/upload`

内容类型为 `multipart/form-data`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | file | 是 | 上传文件 |
| `fileName` | string | 否 | 覆盖显示文件名 |
| `overwrite` | boolean | 否 | 默认 `false`；重复文件是否覆盖 |
| `tags` | string[] | 否 | 可重复的表单或查询参数 |

可选观测请求头：`X-Locust-Run-Id`。

成功响应的 `data`：

```json
{
  "created": true,
  "docUuid": "...",
  "fileName": "policy.pdf",
  "status": "UPLOADED",
  "fileHash": "..."
}
```

上传成功表示文档和 ETL 任务已创建，不表示向量化已经完成。

```bash
curl -X POST "http://localhost:8080/api/v1/docs/upload?overwrite=false" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@policy.pdf" \
  -F "tags=财务" \
  -F "tags=制度"
```

### 5.2 批量上传

`POST /api/v1/upload/batch`

使用重复的 `files` 表单字段，可选 `overwrite` 和重复的 `tags`。响应 `data`：

```json
{
  "total": 2,
  "successCount": 1,
  "createdCount": 1,
  "existedCount": 0,
  "failedCount": 1,
  "results": [
    {
      "success": true,
      "created": true,
      "docUuid": "...",
      "fileName": "a.pdf",
      "status": "UPLOADED",
      "fileHash": "...",
      "error": null
    }
  ]
}
```

### 5.3 查询、删除、下载与预览

查询文档：

```http
GET /api/v1/docs?page=1&size=10&keyword=policy
```

`data` 是 MyBatis-Plus `Page<Document>`，主要字段为 `records`、`total`、`size`、`current` 和 `pages`。`records` 中包含文档基础字段、ETL 状态、标签、空间和 ACL 刷新状态；按创建时间倒序排列。

单个删除使用数据库 `id`：

```http
DELETE /api/v1/docs/{id}
```

批量删除请求体是 ID 数组：

```json
["id-1", "id-2"]
```

下载接口返回 `application/octet-stream` 和 `Content-Disposition: attachment`。预览接口使用 `docUuid`，会校验当前用户的文档 ACL；PDF 返回 `application/pdf`，TXT/Markdown 返回 `text/plain`，其他类型返回 `application/octet-stream`。

### 5.4 标签与知识空间

```http
GET /api/v1/tags?spaceCodes=space-a&spaceCodes=space-b
GET /api/v1/spaces
```

两者的 `data` 都是字符串数组，并按当前用户角色、部门和文档 ACL 过滤。`spaceCodes` 不传时，标签接口查询全部可访问空间中的标签。

### 5.5 重试、权限与 ACL 回填

`POST /api/v1/docs/{id}/retry` 只允许重试状态为 `FAILED` 的文档，最多重试 3 次；源文件丢失时无法重试。

权限更新请求：

```json
{
  "spaceCode": "company-policy",
  "ownerDeptId": "dept-finance",
  "allowedRoles": ["USER"],
  "allowedDeptIds": ["dept-finance"],
  "isPublic": false
}
```

- 空 `spaceCode` 会被保存为 `public`。
- `ownerDeptId` 空白时保存为 `null`。
- 权限列表会去空、去重和规范化。
- `isPublic` 只有显式为 `true` 时才公开。
- 更新后会增加 ACL 版本并异步刷新向量元数据。

`POST /api/v1/docs/backfill-acl-metadata` 返回提交的回填数量，不等待全部任务完成。

### 5.6 ETL 状态订阅

`GET /api/v1/sse/subscribe`

建立连接后首先收到：

```text
event: init
data: connected
```

随后按文档状态接收事件：

```text
id: <docUuid>
event: VECTORIZING
data: {"docUuid":"...","userId":"admin","status":"VECTORIZING","message":"..."}
```

事件名来自文档状态：`UPLOADED`、`READING`、`SPLITTING`、`VECTORIZING`、`COMPLETED`、`FAILED`。同一用户重新订阅时，服务端会关闭旧连接。

### 5.7 手动索引

`POST /api/v1/index` 触发 `EtlPipeline.ingestionFlux()`，以 `text/event-stream` 返回被处理文档的 source 路径字符串。它针对配置的输入目录，不接收请求体。

## 6. 用户与仪表盘 API

### 6.1 用户管理

创建用户请求使用 `SysUser` 字段：

```json
{
  "username": "alice",
  "password": "password",
  "role": "USER",
  "deptId": "dept-finance",
  "deptName": "财务部",
  "defaultSpaceCode": "company-policy",
  "enabled": true
}
```

用户列表不会序列化 `password`。单字段更新请求：

```json
{ "password": "new-password" }
```

```json
{ "role": "ADMIN" }
```

```json
{ "deptId": "dept-finance", "deptName": "财务部" }
```

```json
{ "defaultSpaceCode": "company-policy" }
```

### 6.2 仪表盘

`GET /api/v1/dashboard/stats` 的 `data`：

```json
{
  "totalDocuments": 100,
  "totalChunks": 1200,
  "totalUsers": 20,
  "satisfaction": 86.5,
  "systemStatus": "ONLINE",
  "etlStats": {
    "totalJobs": 105,
    "successJobs": 100,
    "failedJobs": 5,
    "successRate": 95.2
  }
}
```

没有自动评测数据时，`satisfaction` 为 `-1.0`。

## 7. 评测 API

### 7.1 用户反馈

`PATCH /api/v1/evaluation/{msgId}/feedback`

```json
{
  "rating": "negative",
  "failureMode": "irrelevant_retrieval"
}
```

`rating` 只能是 `positive`、`negative` 或 `null`。非空 `failureMode` 只能是：

- `short_text_rerank_bias`
- `synonym_mismatch`
- `chunk_boundary`
- `multi_document_gap`
- `irrelevant_retrieval`
- `generation_hallucination`

普通用户只能更新自己的记录；管理员可以更新任意记录。校验失败、记录不存在或无权操作时返回 HTTP `400`。

### 7.2 会话评测查询

```http
GET /api/v1/admin/evaluation/conversations?page=1&size=10&modelId=deepseek&rating=positive&failureMode=synonym_mismatch&startDate=2026-07-01&endDate=2026-07-14
```

所有筛选条件可选，日期格式为 `yyyy-MM-dd`；无效日期会被当作未传。`data`：

```json
{
  "stats": {
    "total": 10,
    "approvalRate": 80.0,
    "approvalTrend": 0,
    "pending": 2,
    "topFailureMode": "synonym_mismatch",
    "topFailureCount": 1,
    "trend": [{"day":"2026-07-14","rate":80.0}]
  },
  "items": [
    {
      "id": "msg-001",
      "question": "...",
      "answer": "...",
      "model": "deepseek",
      "time": "2026-07-14 10:00",
      "rating": "positive",
      "failureMode": "",
      "traceId": "...",
      "contextSnippets": [{"text":"...","score":0.9}],
      "reference": null
    }
  ],
  "total": 10
}
```

### 7.3 参考答案和自动评测

更新参考答案：

```json
{
  "reference": "标准答案文本"
}
```

空白值会清除参考答案。记录不存在时返回 HTTP `400`。

触发自动评测后，`data` 是 `runId` 字符串；任务异步运行。已有任务占用全局锁等无法启动的情况以 `AjaxResult.warn` 返回。

自动评测分数字段包括：

- `faithfulness`
- `answerRelevancy`
- `contextPrecision`
- `contextRecall`
- `answerCorrectness`
- `answerSimilarity`

`GET /api/v1/admin/evaluation/auto/scores?evaluationId={id}` 返回该评测记录的评分数组。`auto/stats` 返回最后一次运行、总体均值和趋势；`auto/runs` 返回运行历史，运行状态为 `RUNNING`、`COMPLETED` 或 `FAILED`。

> 当前工作区的 `backend/src/main/java/net/topikachu/rag/evaluation/` 被根目录 `.gitignore` 中的 `evaluation/` 规则匹配，评测控制器和相关源码未被 Git 跟踪。干净克隆或提交前应先修正忽略规则，否则本节接口不会随仓库交付。

## 8. 项目内嵌入服务

`POST http://localhost:8098/embed`

该 FastAPI 服务供后端内部调用，当前没有认证。`inputs` 支持单个字符串或字符串数组：

```json
{
  "inputs": ["第一段文本", "第二段文本"]
}
```

响应：

```json
{
  "dense_vecs": [[0.1, 0.2]],
  "sparse_vecs": [{"123": 0.42, "456": 0.18}]
}
```

响应中的稀疏向量键在 Python 模型中定义为整数，但 JSON 对象键会编码为字符串。输入会先清理控制字符和多余空白；清理后为空或没有有效字符时返回 HTTP `422`，推理异常返回 HTTP `500`。

FastAPI 还会自动提供 OpenAPI 页面，通常为 `/docs`、`/redoc` 和 `/openapi.json`。

## 9. Actuator

配置显式暴露：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/actuator/health` | 健康检查 |
| `GET` | `/actuator/prometheus` | Prometheus 指标 |

这两个路径不属于 `/api/v1/**` JWT 安全链；生产环境应由反向代理、网络策略或额外 Security Filter Chain 限制指标端点。

## 10. 已知契约注意事项

- RAG 与 Agent 模式的 SSE `sources` 和 `message` 数据结构不一致。
- `/api/v1/upload/batch` 没有使用 `/docs` 前缀。
- `AjaxResult.success(String, Object)` 未设置 `tag`，因此部分成功响应的 `tag` 可能是空字符串。
- 多数控制器异常没有统一错误响应模型；调用方应同时处理 HTTP 状态、`AjaxResult` 和 SSE `error` 事件。
- CORS 当前只允许本机 `5173`、`5174` 开发源，且允许的自定义请求头不包含聊天和压测使用的 `X-Locust-Run-Id`、`X-Question-Id`、`X-Question-Bucket`；跨域浏览器请求若携带这些头会在预检阶段失败。
