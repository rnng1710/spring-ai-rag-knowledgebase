package net.topikachu.rag.business.document.service;

import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.EtlJob;

import java.time.LocalDateTime;
import java.util.List;

public interface EtlJobService {
    void queueDocumentIngestion(Document doc, String objectKey, String createUserId);

    List<EtlJob> findRunnableJobs(int limit);

    boolean markRunning(String jobId, String workerId, LocalDateTime lockedUntil);

    void retryDocumentIngestion(Document doc, String objectKey, String userId);
}
