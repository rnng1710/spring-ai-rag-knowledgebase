package net.topikachu.rag.business.document.service;

import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.EtlJob;
import reactor.core.publisher.Mono;

import java.util.List;

public interface EtlJobService {
    Mono<Void> queueDocumentIngestion(Document doc, String objectKey, String createUserId);

    void queueDocumentIngestionSync(Document doc, String objectKey, String createUserId);

    Mono<List<EtlJob>> findRunnableJobs(int limit);

    Mono<Boolean> markRunning(String jobId, String workerId, java.time.LocalDateTime lockedUntil);

    Mono<Integer> markExpiredRunningLeasesFailed(java.time.LocalDateTime now, String lastError, String errorStack);

    int markExpiredRunningLeasesFailedSync(java.time.LocalDateTime now, String lastError, String errorStack);

    Mono<Void> retryDocumentIngestion(Document doc, String objectKey, String userId);
}
