package net.topikachu.rag.business.document.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AclRefreshRetryJob {

    private final AclRefreshManager aclRefreshManager;

    @Scheduled(fixedDelayString = "${rag.acl-refresh.retry.fixed-delay-ms:300000}")
    public void retryPendingTasks() {
        int processed = aclRefreshManager.processPendingTasks();
        if (processed > 0) {
            log.info("Processed {} ACL refresh task(s)", processed);
        }
    }
}
