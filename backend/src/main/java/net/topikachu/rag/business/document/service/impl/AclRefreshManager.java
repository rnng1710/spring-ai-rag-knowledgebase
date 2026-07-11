package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.KnowledgeAclRefreshTask;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.KnowledgeAclRefreshTaskMapper;
import net.topikachu.rag.service.etl.DocumentChunkMetadataBuilder;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import net.topikachu.rag.service.etl.MilvusChunkRow;
import net.topikachu.rag.service.etl.MilvusWriteGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class AclRefreshManager {

    private final DocumentMapper documentMapper;
    private final KnowledgeAclRefreshTaskMapper aclRefreshTaskMapper;
    private final MilvusWriteGateway milvusWriteGateway;
    private final DocumentChunkMetadataBuilder metadataBuilder;
    private final KnowledgeParentBlockService parentBlockService;
    private final Gson gson = new Gson();

    @Value("${rag.acl-refresh.retry.max-retries:5}")
    private int aclRefreshMaxRetries;

    @Value("${rag.acl-refresh.retry.batch-size:20}")
    private int aclRefreshBatchSize;

    void enqueue(Document doc) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeAclRefreshTask existing = findAclRefreshTask(doc.getDocUuid(), doc.getAclVersion());
        if (existing == null) {
            KnowledgeAclRefreshTask task = new KnowledgeAclRefreshTask();
            task.setId(UUID.randomUUID().toString().replace("-", ""));
            task.setDocUuid(doc.getDocUuid());
            task.setTargetAclVersion(doc.getAclVersion());
            task.setStatus(AclRefreshStatus.PENDING.name());
            task.setRetryCount(0);
            task.setNextRetryTime(now);
            task.setCreateDate(now);
            task.setUpdateDate(now);
            aclRefreshTaskMapper.insert(task);
            return;
        }
        existing.setStatus(AclRefreshStatus.PENDING.name());
        existing.setRetryCount(0);
        existing.setLastError(null);
        existing.setNextRetryTime(now);
        existing.setUpdateDate(now);
        aclRefreshTaskMapper.updateById(existing);
    }

    int backfillAclMetadata() {
        List<Document> docs = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                .isNotNull(Document::getDocUuid)
                .isNotNull(Document::getFileName));
        int refreshed = 0;
        for (Document doc : docs) {
            prepareBackfillRefresh(doc);
            enqueue(doc);
            if (processSingle(doc.getDocUuid(), doc.getAclVersion())) {
                refreshed++;
            }
        }
        return refreshed;
    }

    int processPendingTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeAclRefreshTask> tasks = aclRefreshTaskMapper.selectList(Wrappers.<KnowledgeAclRefreshTask>lambdaQuery()
                .in(KnowledgeAclRefreshTask::getStatus, List.of(AclRefreshStatus.PENDING.name(), AclRefreshStatus.FAILED.name()))
                .and(wrapper -> wrapper.isNull(KnowledgeAclRefreshTask::getNextRetryTime)
                        .or()
                        .le(KnowledgeAclRefreshTask::getNextRetryTime, now))
                .orderByAsc(KnowledgeAclRefreshTask::getNextRetryTime)
                .last("LIMIT " + aclRefreshBatchSize));

        int processed = 0;
        for (KnowledgeAclRefreshTask task : tasks) {
            if (task.getRetryCount() != null && task.getRetryCount() >= aclRefreshMaxRetries) {
                continue;
            }
            processSingle(task.getDocUuid(), task.getTargetAclVersion());
            processed++;
        }
        return processed;
    }

    boolean processSingle(String docUuid, Integer targetAclVersion) {
        KnowledgeAclRefreshTask task = findAclRefreshTask(docUuid, targetAclVersion);
        if (task == null) {
            return false;
        }

        Document doc = documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getDocUuid, docUuid)
                .last("LIMIT 1"));
        if (doc == null) {
            markTaskFailed(task, "Document not found");
            return false;
        }
        if (!Objects.equals(doc.getAclVersion(), targetAclVersion)) {
            aclRefreshTaskMapper.deleteById(task.getId());
            log.info("Dropped stale ACL refresh task: docUuid={}, targetVersion={}, currentVersion={}",
                    docUuid, targetAclVersion, doc.getAclVersion());
            return false;
        }

        if (!markTaskRunning(task)) {
            return false;
        }
        markDocumentAclRefreshStatus(doc, AclRefreshStatus.RUNNING, null, null);
        try {
            refreshVectorMetadata(doc).toFuture().join();
            markTaskSuccess(task);
            markDocumentAclRefreshStatus(doc, AclRefreshStatus.SUCCESS, null, LocalDateTime.now());
            return true;
        } catch (Exception error) {
            String summary = summarizeError(error);
            markTaskFailed(task, summary);
            markDocumentAclRefreshStatus(doc, AclRefreshStatus.FAILED, summary, null);
            log.warn("ACL metadata refresh failed: docUuid={}, aclVersion={}, error={}",
                    docUuid, targetAclVersion, summary, error);
            return false;
        }
    }

    private void prepareBackfillRefresh(Document doc) {
        if (doc.getAclVersion() == null || doc.getAclVersion() < 1) {
            doc.setAclVersion(1);
        }
        doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
        doc.setAclRefreshError(null);
        doc.setAclRefreshTime(null);
        doc.setUpdateDate(LocalDateTime.now());
        documentMapper.updateById(doc);
    }

    private Mono<Void> refreshVectorMetadata(Document doc) {
        return milvusWriteGateway.queryChunksByDocUuid(doc.getDocUuid())
                .flatMap(chunks -> {
                    if (chunks == null || chunks.isEmpty()) {
                        return Mono.error(new IllegalStateException(
                                "No Milvus chunks found for doc_uuid=" + doc.getDocUuid()));
                    }
                    List<JsonObject> rows = chunks.stream()
                            .map(chunk -> toAclRefreshRow(doc, chunk))
                            .toList();
                    return milvusWriteGateway.upsert(rows)
                            .then(parentBlockService.refreshMetadata(doc));
                });
    }

    private JsonObject toAclRefreshRow(Document doc, MilvusChunkRow chunk) {
        Map<String, Object> metadata = metadataBuilder.build(
                doc,
                doc.getDocUuid(),
                doc.getFileName(),
                doc.getTags(),
                chunk.metadata(),
                doc.getAclVersion());
        JsonObject row = new JsonObject();
        row.addProperty("doc_id", chunk.docId());
        row.addProperty("content", chunk.content());
        row.add("metadata", gson.toJsonTree(metadata));
        row.add("embedding", gson.toJsonTree(chunk.embedding()));
        row.add("sparse_vector", gson.toJsonTree(chunk.sparseVector()));
        return row;
    }

    private KnowledgeAclRefreshTask findAclRefreshTask(String docUuid, Integer targetAclVersion) {
        return aclRefreshTaskMapper.selectOne(Wrappers.<KnowledgeAclRefreshTask>lambdaQuery()
                .eq(KnowledgeAclRefreshTask::getDocUuid, docUuid)
                .eq(KnowledgeAclRefreshTask::getTargetAclVersion, targetAclVersion)
                .last("LIMIT 1"));
    }

    private boolean markTaskRunning(KnowledgeAclRefreshTask task) {
        LocalDateTime now = LocalDateTime.now();
        int updated = aclRefreshTaskMapper.update(null, new UpdateWrapper<KnowledgeAclRefreshTask>()
                .set("status", AclRefreshStatus.RUNNING.name())
                .set("update_date", now)
                .eq("id", task.getId())
                .in("status", List.of(AclRefreshStatus.PENDING.name(), AclRefreshStatus.FAILED.name()))
                .and(wrapper -> wrapper.isNull("next_retry_time")
                        .or()
                        .le("next_retry_time", now)));
        if (updated != 1) {
            log.info("Skipped ACL refresh task because it was already claimed or not due: taskId={}", task.getId());
            return false;
        }
        task.setStatus(AclRefreshStatus.RUNNING.name());
        task.setUpdateDate(now);
        return true;
    }

    private void markTaskSuccess(KnowledgeAclRefreshTask task) {
        task.setStatus(AclRefreshStatus.SUCCESS.name());
        task.setLastError(null);
        task.setNextRetryTime(null);
        task.setUpdateDate(LocalDateTime.now());
        aclRefreshTaskMapper.updateById(task);
    }

    private void markTaskFailed(KnowledgeAclRefreshTask task, String errorMessage) {
        int nextRetryCount = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setStatus(AclRefreshStatus.FAILED.name());
        task.setRetryCount(nextRetryCount);
        task.setLastError(errorMessage);
        task.setNextRetryTime(calculateNextRetryTime(nextRetryCount));
        task.setUpdateDate(LocalDateTime.now());
        aclRefreshTaskMapper.updateById(task);
    }

    private void markDocumentAclRefreshStatus(Document doc,
                                              AclRefreshStatus status,
                                              String errorMessage,
                                              LocalDateTime refreshTime) {
        documentMapper.update(null, new UpdateWrapper<Document>()
                .set("acl_refresh_status", status.name())
                .set("acl_refresh_error", errorMessage)
                .set("acl_refresh_time", refreshTime)
                .set("update_date", LocalDateTime.now())
                .eq("id", doc.getId()));
        doc.setAclRefreshStatus(status.name());
        doc.setAclRefreshError(errorMessage);
        doc.setAclRefreshTime(refreshTime);
    }

    private LocalDateTime calculateNextRetryTime(int retryCount) {
        return LocalDateTime.now().plus(switch (retryCount) {
            case 1 -> 5;
            case 2 -> 15;
            case 3 -> 60;
            case 4 -> 360;
            default -> 1440;
        }, ChronoUnit.MINUTES);
    }

    private String summarizeError(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (!StringUtils.hasText(message)) {
            message = root.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
