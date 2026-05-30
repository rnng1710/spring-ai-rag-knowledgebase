package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class EtlWatchdog {

    private final DocumentMapper documentMapper;
    private final EtlJobMapper etlJobMapper;
    private final EtlJobService etlJobService;
    private final EtlStatusManager etlStatusManager;

    @Value("${rag.etl.watchdog.document-timeout-minutes:360}")
    private int documentTimeoutMinutes;

    /**
     * Two-layer watchdog for ETL recovery.
     * Runs every 5 minutes.
     *
     * Primary layer: expire RUNNING jobs whose worker leases have lapsed. The job
     * retry loop owns requeueing, so this path must not delete vectors.
     *
     * Secondary layer: after a much wider timeout, mark dirty document rows failed
     * only when no active PENDING/RUNNING job remains for that document. Vector
     * cleanup is reserved for this document-level fallback.
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void cleanupStuckDocuments() {
        LocalDateTime now = LocalDateTime.now();

        try {
            int expiredJobs = etlJobService.markExpiredRunningLeasesFailedSync(
                    now,
                    "System Watchdog: worker lease expired; scheduled for retry.",
                    null);
            if (expiredJobs > 0) {
                log.warn("Watchdog marked {} expired ETL jobs as FAILED for retry", expiredJobs);
            }
        } catch (Exception e) {
            log.error("Watchdog failed to expire RUNNING ETL jobs", e);
        }

        LocalDateTime timeoutThreshold = now.minusMinutes(documentTimeoutMinutes);

        List<Document> stuckDocs = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                .in(Document::getStatus,
                        DocumentStatus.UPLOADED.name(),
                        DocumentStatus.READING.name(),
                        DocumentStatus.SPLITTING.name(),
                        DocumentStatus.VECTORIZING.name())
                .lt(Document::getUpdateDate, timeoutThreshold));

        if (stuckDocs.isEmpty()) {
            return;
        }

        log.info("Watchdog detected {} document rows beyond fallback timeout", stuckDocs.size());

        for (Document doc : stuckDocs) {
            try {
                if (hasActiveJob(doc.getDocUuid())) {
                    log.debug("Skip document fallback because ETL job is still active: docUuid={}", doc.getDocUuid());
                    continue;
                }

                String errorMsg = "System Watchdog: document state timeout after "
                        + documentTimeoutMinutes + " minutes without an active ETL job. Please retry.";
                boolean applied = etlStatusManager.transitionToFailedSync(
                        doc.getDocUuid(), null,
                        new RuntimeException(errorMsg));
                if (applied) {
                    log.warn("Watchdog marked orphaned document {} as FAILED", doc.getDocUuid());
                }
            } catch (Exception e) {
                log.error("Watchdog failed to process document fallback {}", doc.getDocUuid(), e);
            }
        }
    }

    private boolean hasActiveJob(String docUuid) {
        Long count = etlJobMapper.selectCount(Wrappers.<EtlJob>lambdaQuery()
                .eq(EtlJob::getDocUuid, docUuid)
                .in(EtlJob::getStatus, List.of(
                        EtlJobStatus.PENDING.name(),
                        EtlJobStatus.RUNNING.name())));
        return count != null && count > 0;
    }
}
