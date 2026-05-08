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
    public void queueDocumentIngestion(Document doc, String objectKey, String createUserId) {
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
        etlJob.setActiveKey(activeKey(doc.getDocUuid()));
        etlJob.setObjectKey(objectKey);

        try {
            int insert = etlJobMapper.insert(etlJob);
            if(insert != 1){
                throw new IllegalStateException("Insert etl_job failed: docUuid=" + etlJob.getDocUuid());
            }
        } catch (DuplicateKeyException duplicateKeyException) {
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
                                EtlJobStatus.RUNNING.name()
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
    public List<EtlJob> findRunnableJobs(int limit) {
        return etlJobMapper.selectList(
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
        );
    }

    //update task status
    @Override
    public boolean markRunning(String jobId, String workerId, LocalDateTime lockedUntil) {
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
    }

    @Override
    public void retryDocumentIngestion(Document doc, String objectKey, String userId) {
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

         queueDocumentIngestion(doc, objectKey, userId);
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

}
