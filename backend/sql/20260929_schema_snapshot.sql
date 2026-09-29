
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` varchar(50) NOT NULL,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `role` varchar(20) DEFAULT 'USER',
  `dept_id` varchar(50) DEFAULT NULL COMMENT '部门ID',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '部门名称',
  `enabled` tinyint(1) DEFAULT '1',
  `create_user_id` varchar(50) DEFAULT NULL,
  `create_user_name` varchar(50) DEFAULT NULL,
  `create_date` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_user_id` varchar(50) DEFAULT NULL,
  `update_user_name` varchar(50) DEFAULT NULL,
  `update_date` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `remark` varchar(255) DEFAULT NULL,
  `version` int DEFAULT '0',
  `default_space_code` varchar(64) DEFAULT NULL COMMENT 'Default knowledge space for chat retrieval',
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`),
  KEY `idx_sys_user_dept_id` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_document` (
  `id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `CREATE_USER_ID` varchar(50) DEFAULT NULL,
  `CREATE_USER_NAME` varchar(50) DEFAULT NULL,
  `CREATE_DATE` varchar(50) DEFAULT NULL,
  `UPDATE_USER_ID` varchar(50) DEFAULT NULL,
  `UPDATE_USER_NAME` varchar(50) DEFAULT NULL,
  `UPDATE_DATE` varchar(50) DEFAULT NULL,
  `VERSION` varchar(50) DEFAULT NULL,
  `REMARK` varchar(50) DEFAULT NULL,
  `DOC_UUID` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件uuid',
  `FILE_NAME` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '文件名',
  `STATUS` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '文件状态',
  `TAGS` json DEFAULT NULL COMMENT '标签列表',
  `SPACE_CODE` varchar(64) NOT NULL DEFAULT 'public' COMMENT '空间编码',
  `OWNER_DEPT_ID` varchar(50) DEFAULT NULL COMMENT '归属部门ID',
  `ALLOWED_ROLES` json DEFAULT NULL COMMENT '允许访问角色列表',
  `ALLOWED_DEPT_IDS` json DEFAULT NULL COMMENT '允许访问部门ID列表',
  `IS_PUBLIC` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否公开',
  `FILE_HASH` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件hash值',
  `ERROR_MESSAGE` varchar(2000) DEFAULT NULL COMMENT '失败原因(用户友好提示)',
  `ERROR_STACK` text COMMENT '技术异常堆栈(运维排查用)',
  `RETRY_COUNT` int DEFAULT '0' COMMENT '重试次数',
  `ACL_VERSION` int NOT NULL DEFAULT '1',
  `ACL_REFRESH_STATUS` varchar(32) NOT NULL DEFAULT 'PENDING',
  `ACL_REFRESH_ERROR` varchar(1000) DEFAULT NULL,
  `ACL_REFRESH_TIME` datetime DEFAULT NULL,
  `OBJECT_KEY` varchar(1024) DEFAULT NULL COMMENT '文件密钥',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_file_hash` (`FILE_HASH`),
  KEY `idx_doc_space_code` (`SPACE_CODE`),
  KEY `idx_doc_owner_dept_id` (`OWNER_DEPT_ID`),
  KEY `idx_doc_is_public` (`IS_PUBLIC`),
  KEY `idx_tags` ((cast(`TAGS` as char(255) array))),
  KEY `idx_doc_allowed_roles` ((cast(`ALLOWED_ROLES` as char(64) array))),
  KEY `idx_doc_allowed_dept_ids` ((cast(`ALLOWED_DEPT_IDS` as char(64) array)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `etl_job` (
  `id` varchar(64) NOT NULL,
  `CREATE_USER_ID` varchar(50) DEFAULT NULL,
  `CREATE_USER_NAME` varchar(50) DEFAULT NULL,
  `CREATE_DATE` varchar(50) DEFAULT NULL,
  `UPDATE_USER_ID` varchar(50) DEFAULT NULL,
  `UPDATE_USER_NAME` varchar(50) DEFAULT NULL,
  `UPDATE_DATE` varchar(50) DEFAULT NULL,
  `VERSION` varchar(50) DEFAULT NULL,
  `REMARK` varchar(50) DEFAULT NULL,
  `JOB_UUID` varchar(64) NOT NULL COMMENT '任务唯一ID',
  `DOC_UUID` varchar(64) NOT NULL COMMENT '文档UUID',
  `JOB_TYPE` varchar(64) NOT NULL DEFAULT 'DOCUMENT_INGESTION',
  `STATUS` varchar(32) NOT NULL COMMENT 'PENDING/RUNING/SUCCESS/FAILED/CANCELED',
  `FILE_PATH` varchar(1024) DEFAULT NULL COMMENT '源文件路径',
  `FILE_NAME` varchar(255) NOT NULL COMMENT '文件名',
  `TAGS` json DEFAULT NULL COMMENT '文档标签',
  `RETRY_COUNT` int NOT NULL DEFAULT '0' COMMENT '重试次数',
  `MAX_RETRY_COUNT` int NOT NULL DEFAULT '3' COMMENT '最大重试次数',
  `NEXT_RETRY_TIME` datetime DEFAULT NULL COMMENT '下次重试时间',
  `LOCKED_BY` varchar(128) DEFAULT NULL COMMENT '哪个worker抢到了任务',
  `LOCKED_UNTIL` datetime DEFAULT NULL COMMENT '锁过期时间',
  `STARTED_AT` datetime DEFAULT NULL COMMENT '任务开始时间',
  `FINISHED_AT` datetime DEFAULT NULL COMMENT '任务结束时间',
  `LAST_ERROR` varchar(1000) DEFAULT NULL COMMENT '最新错误信息',
  `ERROR_STACK` text COMMENT '错误堆栈',
  `ACTIVE_KEY` varchar(160) DEFAULT NULL COMMENT '活跃任务唯一键',
  `OBJECT_KEY` varchar(1024) DEFAULT NULL COMMENT '文件密钥',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_job_uuid` (`JOB_UUID`),
  UNIQUE KEY `uk_etl_job_active_key` (`ACTIVE_KEY`),
  KEY `idx_status_retry_time` (`STATUS`,`NEXT_RETRY_TIME`),
  KEY `idx_doc_uuid` (`DOC_UUID`),
  KEY `idx_locked_until` (`LOCKED_UNTIL`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_acl_refresh_task` (
  `id` varchar(64) NOT NULL,
  `doc_uuid` varchar(64) NOT NULL,
  `target_acl_version` int NOT NULL,
  `status` varchar(32) NOT NULL,
  `retry_count` int NOT NULL DEFAULT '0',
  `last_error` varchar(1000) DEFAULT NULL,
  `next_retry_time` datetime DEFAULT NULL,
  `create_user_id` varchar(64) DEFAULT NULL,
  `create_user_name` varchar(128) DEFAULT NULL,
  `create_date` datetime DEFAULT NULL,
  `update_user_id` varchar(64) DEFAULT NULL,
  `update_user_name` varchar(128) DEFAULT NULL,
  `update_date` datetime DEFAULT NULL,
  `remark` varchar(1000) DEFAULT NULL,
  `version` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_acl_refresh_task_doc_version` (`doc_uuid`,`target_acl_version`),
  KEY `idx_acl_refresh_task_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_parent_block` (
  `ID` varchar(64) NOT NULL,
  `parent_block_id` varchar(128) NOT NULL,
  `doc_uuid` varchar(64) NOT NULL,
  `parent_index` int NOT NULL,
  `content` mediumtext NOT NULL,
  `file_name` varchar(512) NOT NULL,
  `page_start` int DEFAULT NULL,
  `page_end` int DEFAULT NULL,
  `space_code` varchar(64) DEFAULT NULL,
  `tags` json DEFAULT NULL,
  `acl_version` int NOT NULL DEFAULT '1',
  `chunk_schema_version` int NOT NULL DEFAULT '2',
  `CREATE_USER_ID` varchar(64) DEFAULT NULL,
  `CREATE_USER_NAME` varchar(128) DEFAULT NULL,
  `CREATE_DATE` datetime DEFAULT NULL,
  `UPDATE_USER_ID` varchar(64) DEFAULT NULL,
  `UPDATE_USER_NAME` varchar(128) DEFAULT NULL,
  `UPDATE_DATE` datetime DEFAULT NULL,
  `REMARK` varchar(512) DEFAULT NULL,
  `VERSION` int DEFAULT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_parent_block_id` (`parent_block_id`),
  UNIQUE KEY `uk_doc_parent_index` (`doc_uuid`,`parent_index`),
  KEY `idx_doc_uuid` (`doc_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Parent context blocks for child evidence chunks';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_session` (
  `id` varchar(64) NOT NULL,
  `conversation_id` varchar(160) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `title` varchar(255) NOT NULL DEFAULT '新对话',
  `title_status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `last_message_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_session_conversation` (`conversation_id`),
  KEY `idx_chat_session_user_deleted_time` (`user_id`,`deleted`,`last_message_at`),
  KEY `idx_chat_session_user_title` (`user_id`,`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_message` (
  `id` varchar(96) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `conversation_id` varchar(160) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `role` varchar(32) NOT NULL,
  `content` mediumtext NOT NULL,
  `message_blob` mediumblob NOT NULL,
  `message_index` int NOT NULL,
  `model_id` varchar(32) DEFAULT NULL,
  `mode` varchar(16) DEFAULT NULL,
  `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_message_session_index` (`session_id`,`message_index`),
  KEY `idx_chat_message_conversation_index` (`conversation_id`,`message_index`),
  KEY `idx_chat_message_user_time` (`user_id`,`create_date`),
  FULLTEXT KEY `ft_chat_message_content` (`content`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_memory_snapshot` (
  `conversation_id` varchar(160) NOT NULL,
  `message_blob` mediumblob NOT NULL,
  `message_count` int NOT NULL,
  `serializer` varchar(32) NOT NULL DEFAULT 'kryo-message-v1',
  `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_evaluation` (
  `id` varchar(64) NOT NULL COMMENT 'msgId 复用为主键',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `question` text NOT NULL COMMENT '用户问题',
  `answer` text NOT NULL COMMENT 'AI回答',
  `model_id` varchar(32) DEFAULT NULL COMMENT '模型标识',
  `mode` varchar(16) DEFAULT NULL COMMENT 'rag / agent',
  `context_snippets` json DEFAULT NULL COMMENT '检索片段 [{docId, text, score}]',
  `reference` text COMMENT 'RAGAS reference answer, manually provided by admin',
  `reference_update_date` datetime DEFAULT NULL,
  `reference_updated_by` varchar(64) DEFAULT NULL,
  `trace_id` varchar(64) DEFAULT NULL COMMENT 'Langfuse trace ID',
  `rating` varchar(16) DEFAULT NULL COMMENT 'positive / negative',
  `failure_mode` varchar(64) DEFAULT NULL COMMENT '6种失败模式或空',
  `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `used_sources` json DEFAULT NULL COMMENT 'Answer sources actually used by the final answer',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_date` (`create_date`),
  KEY `idx_rating` (`rating`),
  KEY `idx_failure_mode` (`failure_mode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_evaluation_auto` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `evaluation_id` varchar(64) NOT NULL COMMENT 'FK -> chat_evaluation.id',
  `run_id` varchar(32) NOT NULL COMMENT '批次标识',
  `faithfulness` decimal(5,4) DEFAULT NULL,
  `answer_relevancy` decimal(5,4) DEFAULT NULL,
  `context_precision` decimal(5,4) DEFAULT NULL,
  `context_recall` decimal(5,4) DEFAULT NULL,
  `answer_correctness` decimal(5,4) DEFAULT NULL,
  `answer_similarity` decimal(5,4) DEFAULT NULL,
  `reference_answer_hash` varchar(64) NOT NULL DEFAULT 'NO_REFERENCE',
  `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_reference` (`evaluation_id`,`reference_answer_hash`),
  KEY `idx_evaluation_id` (`evaluation_id`),
  KEY `idx_run_id` (`run_id`),
  KEY `idx_create_date` (`create_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_evaluation_auto_run` (
  `run_id` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING',
  `total_samples` int NOT NULL DEFAULT '0',
  `success_count` int NOT NULL DEFAULT '0',
  `failure_count` int NOT NULL DEFAULT '0',
  `avg_faithfulness` decimal(5,4) DEFAULT NULL,
  `avg_answer_relevancy` decimal(5,4) DEFAULT NULL,
  `avg_context_precision` decimal(5,4) DEFAULT NULL,
  `avg_context_recall` decimal(5,4) DEFAULT NULL,
  `avg_answer_correctness` decimal(5,4) DEFAULT NULL,
  `avg_answer_similarity` decimal(5,4) DEFAULT NULL,
  `started_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` datetime DEFAULT NULL,
  `error_message` text,
  PRIMARY KEY (`run_id`),
  KEY `idx_status_started_at` (`status`,`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_evaluation_auto_lock` (
  `lock_name` varchar(64) NOT NULL,
  `owner_id` varchar(64) NOT NULL,
  `locked_until` datetime NOT NULL,
  `update_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`lock_name`),
  KEY `idx_locked_until` (`locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
