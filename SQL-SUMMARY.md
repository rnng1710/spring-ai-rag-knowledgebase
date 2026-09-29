# SQL 清单（2026-09-29 实库核对）

WSL 部署目录：`/home/rnng/services/campus-rag`。11 个项目容器已启动，沿用原数据目录。
MySQL 8.0.46，端口 `3309`，数据库 `campus_knowledge`；已成功查询表结构。Milvus、MinIO、reranker 健康接口返回 200，Redis 返回 PONG。

## 首次建库

需要两部分：

1. **完整建表 SQL**：`backend/sql/20260929_schema_snapshot.sql`，包含下列 12 张当前表及索引，不含数据、密码、DROP TABLE 或旧表。已在独立临时数据库验证空库导入；尚未完成后端联调。
2. **首个管理员初始化**：`backend/sql/init_admin.sql`。用户自行设置密码，角色 ADMIN，enabled=true。已有管理员或同名用户时跳过，不修改原账号。现库有 1 个启用的 ADMIN，无需执行。

从仓库根目录生成密码哈希（输入密码时不回显）：

```bash
python -m pip install bcrypt
python backend/scripts/generate_admin_password_hash.py
```

连接 MySQL 并选择 `campus_knowledge`，在同一会话内执行：

```sql
SET @admin_username = 'admin';
-- 粘贴上一步生成的 SET @admin_password_hash = '...';
SOURCE backend/sql/init_admin.sql;
```

SOURCE 路径相对于 MySQL 客户端工作目录；也可在数据库工具中依次执行 SET 和脚本全文。脚本不提供默认密码，未设置有效 BCrypt 哈希时不会创建账号。

| 用途 | 表 |
| --- | --- |
| 用户和权限 | `sys_user` |
| 文档及异步导入 | `knowledge_document`、`etl_job` |
| ACL 刷新任务 | `knowledge_acl_refresh_task` |
| 父子块检索 | `knowledge_parent_block` |
| 会话和记忆 | `chat_session`、`chat_message`、`chat_memory_snapshot` |
| 人工及自动评估 | `chat_evaluation`、`chat_evaluation_auto`、`chat_evaluation_auto_run`、`chat_evaluation_auto_lock` |

`knowledge_document_old` 是旧表，当前代码未引用，不需要纳入首次建库。
结构快照不创建数据库；需先创建空的 `campus_knowledge` 并选择它作为导入目标。导入完整快照后，不再执行下面的历史增量脚本。

## 本地已有的历史 SQL（未提交到仓库）

| 文件（均在 backend/sql/） | 作用 |
| --- | --- |
| `20260429_acl_metadata_refresh.sql` | 文档 ACL 列、ACL 刷新任务表 |
| `20260507_object_storage.sql` | 文档/ETL 对象存储键，允许 FILE_PATH 为空 |
| `20260528_chat_evaluation.sql` | 人工评估表 |
| `20260529_chat_evaluation_auto.sql` | reference 列、RAGAS 自动评估/批次/锁表 |
| `20260529_ragas_auto_evaluation_migration.sql` | 从旧评分结构迁移到 RAGAS，仅适用于相应旧库 |
| `20260530_answer_sources_and_default_space.sql` | 用户默认空间、回答来源 |
| `20260531_parent_child_retrieval.sql` | 父块表 |
| `20260619_chat_history.sql` | 会话、消息、记忆快照表 |

**不能把这些文件按日期全部执行。** `20260529_chat_evaluation_auto.sql` 已包含新版 RAGAS 列；随后再执行同日 migration 会重复添加 reference 等列，还会删除并不存在的旧评分列/索引。迁移应按旧库实际结构选择。

现库已包含上述最终字段，因此本次没有执行任何建表、迁移或数据修改。

## 发布前需要补齐

- 发布前完成后端联调。结构快照的空库导入、密码哈希生成、首次管理员创建、重复执行及已有用户跳过均已通过临时数据库验证，测试库已删除，现有业务库未修改。
- `.gitignore` 已放行结构快照和管理员初始化脚本，历史迁移仍保持忽略。
- 实库 `knowledge_document`、`etl_job` 的审计日期和 VERSION 部分使用 varchar，而 Java 映射为 LocalDateTime/Integer；正式初始化脚本应另行核对兼容性，本次快照保留实际结构。

## 当前环境启动命令

在 WSL 中执行：

```bash
cd /home/rnng/services/campus-rag
docker compose up -d --no-build --pull never
docker compose ps
```

此目录已有 YAML、模型及持久化数据。仓库根目录的 Compose 与它不同，不能误在仓库目录启动另一套同名容器和空数据目录。
