package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class EtlWatchdog {

    private final DocumentMapper documentMapper;
    private final HybridVectorWriter hybridVectorWriter;
    private final EtlJobStarter etlJobStarter;

    /**
     * Watchdog task to clean up stuck ETL processes.
     * Runs every 5 minutes.
     * Checks for documents in intermediate states that haven't been updated for 15
     * minutes.
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void cleanupStuckDocuments() {
        log.info("Dogwatch scan in progress...");
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(15);

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

        log.info("Watchdog detected {} stuck documents...", stuckDocs.size());

        for (Document doc : stuckDocs) {
            try {
                // 1. Mark as FAILED in DB
                int rows = documentMapper.update(null, Wrappers.<Document>lambdaUpdate()
                        .set(Document::getStatus, DocumentStatus.FAILED.name())
                        .set(Document::getErrorMessage, "System Watchdog: Process timeout (15min). Please retry.")
                        .set(Document::getUpdateDate, LocalDateTime.now())
                        .eq(Document::getDocUuid, doc.getDocUuid())
                        // CAS safety: only update if it's still in the stuck state we queried
                        .eq(Document::getStatus, doc.getStatus()));

                if (rows > 0) {
                    log.warn("Watchdog marked document {} as FAILED", doc.getDocUuid());
                    etlJobStarter.start(
                            hybridVectorWriter.deleteByDocUuid(doc.getDocUuid()),
                            "Watchdog cleanup " + doc.getDocUuid());
                }
            } catch (Exception e) {
                log.error("Watchdog failed to process stuck document {}", doc.getDocUuid(), e);
            }
        }
    }
}
