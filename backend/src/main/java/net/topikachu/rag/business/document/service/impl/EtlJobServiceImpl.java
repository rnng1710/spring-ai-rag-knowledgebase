package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.entity.EtlJobType;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class EtlJobServiceImpl implements EtlJobService {

    private final EtlJobMapper etlJobMapper;


    private static final int MAX_RETRY_COUNT = 3;


    @Override
    public Mono<Void> queueDocumentIngestion(Document doc, String objectKey, String createUserId) {
        return Mono.fromRunnable(() -> queueDocumentIngestionBlocking(doc, objectKey, createUserId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public void queueDocumentIngestionSync(Document doc, String objectKey, String createUserId) {
        queueDocumentIngestionBlocking(doc, objectKey, createUserId);
    }

    void queueDocumentIngestionBlocking(Document doc, String objectKey, String createUserId) {
        validateDoc(doc, objectKey);

        EtlJob existing = findActiveEtlJob(doc.getDocUuid());
        if(existing != null){
            log.info("ETL job already queued: docUuid={}, jobId={}, status={}",
                    doc.getDocUuid(), existing.getId(), existing.getStatus());
            return;
        }


        EtlJob etlJob = new EtlJob();
        etlJob.setDocUuid(doc.getDocUuid());
        etlJob.setJobUuid(UUID.randomUUID().toString().replace("-", ""));
        etlJob.setJobType(EtlJobType.DOCUMENT_INGESTION.name());
        etlJob.setStatus(EtlJobStatus.PENDING.name());
        etlJob.setFilePath(objectKey);
        etlJob.setFileName(doc.getFileName());
        etlJob.setTags(doc.getTags());
        etlJob.setMaxRetryCount(MAX_RETRY_COUNT);
        etlJob.setNextRetryTime(LocalDateTime.now());
        etlJob.setCreateDate(LocalDateTime.now());
        etlJob.setCreateUserId(createUserId);
        etlJob.setCreateUserName(createUserId);
        etlJob.setUpdateDate(LocalDateTime.now());
        etlJob.setUpdateUserId(createUserId);
        etlJob.setUpdateUserName(createUserId);
        // activeKey = docUuid:JOB_TYPE，复合唯一键保证同文档同类型不重复入队
        etlJob.setActiveKey(activeKey(doc.getDocUuid()));
        etlJob.setObjectKey(objectKey);

        try {
            int insert = etlJobMapper.insert(etlJob);
            if(insert != 1){
                throw new IllegalStateException("Insert etl_job failed: docUuid=" + etlJob.getDocUuid());
            }
        } catch (DuplicateKeyException duplicateKeyException) {
            // 唯一键冲突说明并发请求已入队，重新查询确认：乐观锁风格的竞态防护，不抛异常
            EtlJob activeJob = findActiveEtlJob(doc.getDocUuid());
            if (activeJob != null) {
                log.info("ETL job already queued by concurrent request: docUuid={}, jobId={}, status={}",
                        doc.getDocUuid(), activeJob.getId(), activeJob.getStatus());
                return;
            }
            throw duplicateKeyException;
        }

        log.info("ETL job queued: jobUuid={}, docUuid={}, ObjectKey={}",
                etlJob.getJobUuid(), etlJob.getDocUuid(), etlJob.getObjectKey());
    }

    private EtlJob findActiveEtlJob(String docUuid) {
        return etlJobMapper.selectOne(
                Wrappers.<EtlJob>lambdaQuery()
                        .eq(EtlJob::getActiveKey, activeKey(docUuid))
                        .in(EtlJob::getStatus, List.of(
                                EtlJobStatus.PENDING.name(),
                                EtlJobStatus.RUNNING.name(),
                                EtlJobStatus.FAILED.name()
                        ))
                        .last("LIMIT 1")
        );
    }

    private void validateDoc(Document doc, String objectKey) {
        if (doc == null){
            throw new IllegalArgumentException("Document is required");
        }
        if(!StringUtils.hasText(doc.getDocUuid())){
            throw new IllegalArgumentException("Document docUuid is required");
        }
        if(!StringUtils.hasText(doc.getFileName())){
            throw new IllegalArgumentException("Filename is required");
        }
        if(!StringUtils.hasText(objectKey)){
            throw new IllegalArgumentException("ObjectKey is required");
        }

    }

    //find the task that needs to be performed
    @Override
    public Mono<List<EtlJob>> findRunnableJobs(int limit) {
        return Mono.fromCallable(() -> etlJobMapper.selectList(
                        Wrappers.<EtlJob>lambdaQuery()
                                .and(w -> w
                                        .eq(EtlJob::getStatus, EtlJobStatus.PENDING.name())
                                        .or(w2 -> w2
                                                        .eq(EtlJob::getStatus, EtlJobStatus.FAILED.name())
                                                        .le(EtlJob::getNextRetryTime, LocalDateTime.now())
                                                        .apply("retry_count < max_retry_count")
                                        )
                                        .or(w3 -> w3
                                                .eq(EtlJob::getStatus, EtlJobStatus.RUNNING.name())
                                                .le(EtlJob::getLockedUntil, LocalDateTime.now())
                                                .apply("retry_count < max_retry_count")
                                        )
                                )
                                .orderByAsc(EtlJob::getCreateDate)
                                .last("LIMIT " + limit)
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    //update task status
    @Override
    public Mono<Boolean> markRunning(String jobId, String workerId, LocalDateTime lockedUntil) {
        return Mono.fromCallable(() -> {
                    int updated = etlJobMapper.update(null,
                            Wrappers.<EtlJob>lambdaUpdate()
                                    .set(EtlJob::getStatus, EtlJobStatus.RUNNING.name())
                                    .set(EtlJob::getLockedBy, workerId)
                                    .set(EtlJob::getLockedUntil, lockedUntil)
                                    .set(EtlJob::getStartedAt, LocalDateTime.now())
                                    .set(EtlJob::getUpdateDate, LocalDateTime.now())
                                    .eq(EtlJob::getId, jobId)
                                    .and(w -> w
                                            .eq(EtlJob::getStatus, EtlJobStatus.PENDING.name())
                                            .or(w2 ->w2
                                                    .eq(EtlJob::getStatus, EtlJobStatus.FAILED.name())
                                                    .le(EtlJob::getNextRetryTime, LocalDateTime.now()))
                                                    .apply("retry_count < max_retry_count")
                                            .or(w3 -> w3
                                                    .eq(EtlJob::getStatus, EtlJobStatus.RUNNING.name())
                                                    .le(EtlJob::getLockedUntil, LocalDateTime.now()))
                                                    .apply("retry_count < max_retry_count")
                                    )
                    );
                    return updated == 1;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Integer> markExpiredRunningLeasesFailed(LocalDateTime now, String lastError, String errorStack) {
        return Mono.fromCallable(() -> markExpiredRunningLeasesFailedSync(now, lastError, errorStack))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public int markExpiredRunningLeasesFailedSync(LocalDateTime now, String lastError, String errorStack) {
        LocalDateTime updateTime = now;

        return etlJobMapper.update(null,
                Wrappers.<EtlJob>lambdaUpdate()
                        .set(EtlJob::getFinishedAt, updateTime)
                        .set(EtlJob::getStatus, EtlJobStatus.FAILED.name())
                        .set(EtlJob::getLockedBy, null)
                        .set(EtlJob::getLockedUntil, null)
                        .set(EtlJob::getUpdateDate, updateTime)
                        .set(EtlJob::getLastError, trimToLength(lastError, 1000))
                        .set(EtlJob::getErrorStack, trimToLength(errorStack, 2000))
                        // 用 SQL CASE 批量计算重试时间和 activeKey 清空：一次原子更新避免与任务调度器并发冲突
                        // 指数退避：第 1 次 5min → 第 2 次 15min → 第 3 次 60min，超过 max_retry_count 则清空 activeKey 不再调度
                .setSql("next_retry_time = CASE (IFNULL(retry_count, 0) + 1) " +
                                "WHEN 1 THEN DATE_ADD(NOW(), INTERVAL 5 MINUTE) " +
                                "WHEN 2 THEN DATE_ADD(NOW(), INTERVAL 15 MINUTE) " +
                                "WHEN 3 THEN DATE_ADD(NOW(), INTERVAL 60 MINUTE) " +
                                "ELSE DATE_ADD(NOW(), INTERVAL 360 MINUTE) END")
                        .setSql("active_key = CASE WHEN (IFNULL(retry_count, 0) + 1) < max_retry_count " +
                                "THEN active_key ELSE NULL END")
                        .setSql("retry_count = IFNULL(retry_count, 0) + 1")
                        .eq(EtlJob::getStatus, EtlJobStatus.RUNNING.name())
                        .lt(EtlJob::getLockedUntil, updateTime)
                        .apply("retry_count < max_retry_count")
        );
    }

    @Override
    public Mono<Void> retryDocumentIngestion(Document doc, String objectKey, String userId) {
        return Mono.fromRunnable(() -> retryDocumentIngestionBlocking(doc, objectKey, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void retryDocumentIngestionBlocking(Document doc, String objectKey, String userId) {
        validateDoc(doc, objectKey);
        EtlJob activeJob = findActiveIngestionJob(doc.getDocUuid());
        if (activeJob != null) {
            log.info("ETL job already active, skip retry queue: docUuid={}, jobId={}, status={}",
                    doc.getDocUuid(), activeJob.getId(), activeJob.getStatus());
            return ;
        }

        EtlJob failedJob = findLatestFailedIngestionJob(doc.getDocUuid());
        if (failedJob != null) {
            resetFailedJobToPending(failedJob, doc, objectKey, userId);
            return;
        }

         queueDocumentIngestionBlocking(doc, objectKey, userId);
    }

    private EtlJob findActiveIngestionJob(String docUuid) {
        return etlJobMapper.selectOne(
                Wrappers.<EtlJob>lambdaQuery()
                        .eq(EtlJob::getDocUuid, docUuid)
                        .eq(EtlJob::getJobType, EtlJobType.DOCUMENT_INGESTION.name())
                        .in(EtlJob::getStatus, List.of(
                                EtlJobStatus.PENDING.name(),
                                EtlJobStatus.RUNNING.name()
                        ))
                        .last("LIMIT 1")
        );
    }

    private EtlJob findLatestFailedIngestionJob(String docUuid) {
        return etlJobMapper.selectOne(
                Wrappers.<EtlJob>lambdaQuery()
                        .eq(EtlJob::getDocUuid, docUuid)
                        .eq(EtlJob::getJobType, EtlJobType.DOCUMENT_INGESTION.name())
                        .eq(EtlJob::getStatus, EtlJobStatus.FAILED.name())
                        .orderByDesc(EtlJob::getUpdateDate)
                        .last("LIMIT 1")
        );
    }

    private EtlJob resetFailedJobToPending(EtlJob job, Document doc, String objectKey, String userId) {
        LocalDateTime now = LocalDateTime.now();

        job.setStatus(EtlJobStatus.PENDING.name());
        job.setFilePath(objectKey);
        job.setObjectKey(objectKey);
        job.setFileName(doc.getFileName());
        job.setTags(doc.getTags());
        job.setActiveKey(activeKey(doc.getDocUuid()));

        job.setLockedBy(null);
        job.setLockedUntil(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);

        job.setNextRetryTime(null);
        job.setLastError(null);
        job.setErrorStack(null);
        job.setUpdateDate(now);
        job.setUpdateUserId(userId);
        job.setUpdateUserName(userId);

        int rows = etlJobMapper.updateById(job);
        if (rows != 1) {
            throw new IllegalStateException("Reset failed etl_job to PENDING failed: jobId=" + job.getId());
        }

        log.info("Failed ETL job reset to PENDING: jobId={}, docUuid={}",
                job.getId(), job.getDocUuid());

        return job;
    }

    private String activeKey(String docUuid) {
        return docUuid + ":" + EtlJobType.DOCUMENT_INGESTION.name();
    }

    private String trimToLength(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

}
