package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

@Component
@Slf4j
public class EtlStatusManager {

    private final DocumentMapper documentMapper;
    private final HybridVectorWriter hybridVectorWriter;
    private final KnowledgeParentBlockService parentBlockService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public EtlStatusManager(DocumentMapper documentMapper,
                            HybridVectorWriter hybridVectorWriter,
                            KnowledgeParentBlockService parentBlockService,
                            StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.hybridVectorWriter = hybridVectorWriter;
        this.parentBlockService = parentBlockService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> transitionTo(String docUuid, String userId,
                                   DocumentStatus status, String message) {
        if (docUuid == null) return Mono.empty();
        return Mono.fromRunnable(() -> {
            documentMapper.update(null,
                    Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaUpdate()
                            .set(net.topikachu.rag.business.document.entity.Document::getStatus, status.name())
                            .set(net.topikachu.rag.business.document.entity.Document::getUpdateDate, LocalDateTime.now())
                            .eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid));
            sendRedisMessage(docUuid, userId, status, message);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> transitionToCompleted(String docUuid, String userId) {
        if (docUuid == null) return Mono.empty();
        return Mono.fromCallable(() -> {
            int rows = documentMapper.update(null,
                    Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaUpdate()
                            .set(net.topikachu.rag.business.document.entity.Document::getStatus, DocumentStatus.COMPLETED.name())
                            .set(net.topikachu.rag.business.document.entity.Document::getAclRefreshStatus, AclRefreshStatus.SUCCESS.name())
                            .set(net.topikachu.rag.business.document.entity.Document::getAclRefreshError, null)
                            .set(net.topikachu.rag.business.document.entity.Document::getAclRefreshTime, LocalDateTime.now())
                            .set(net.topikachu.rag.business.document.entity.Document::getUpdateDate, LocalDateTime.now())
                            .eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid)
                            // CAS：ne(FAILED) 防止将已被 Watchdog 标记为 FAILED 的文档错误地设为 COMPLETED
                            .ne(net.topikachu.rag.business.document.entity.Document::getStatus, DocumentStatus.FAILED.name()));
            if (rows == 0) {
                String msg = "CAS conflict: document " + docUuid + " is already FAILED.";
                log.warn(msg);
                throw new IllegalStateException(msg);
            }
            return true;
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> sendRedisMessage(docUuid, userId, DocumentStatus.COMPLETED, "Processing finished"))
                .then();
    }

    public Mono<Boolean> transitionToFailed(String docUuid, String userId, Throwable error) {
        if (docUuid == null) return Mono.just(false);
        String userMsg = extractUserMessage(error);
        String techStack = formatStackTrace(error);
        return Mono.fromCallable(() -> {
            int rows = documentMapper.update(null,
                    Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaUpdate()
                            .set(net.topikachu.rag.business.document.entity.Document::getStatus, DocumentStatus.FAILED.name())
                            .set(net.topikachu.rag.business.document.entity.Document::getAclRefreshStatus, AclRefreshStatus.FAILED.name())
                            .set(net.topikachu.rag.business.document.entity.Document::getAclRefreshError, userMsg)
                            .set(net.topikachu.rag.business.document.entity.Document::getErrorMessage, userMsg)
                            .set(net.topikachu.rag.business.document.entity.Document::getErrorStack, techStack)
                            .setSql("retry_count = IFNULL(retry_count, 0) + 1")
                            .set(net.topikachu.rag.business.document.entity.Document::getUpdateDate, LocalDateTime.now())
                            .eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid)
                            // 只有非终态才能转为 FAILED：UPLOADED/READING/SPLITTING/VECTORIZING
                            .in(net.topikachu.rag.business.document.entity.Document::getStatus,
                                    DocumentStatus.UPLOADED.name(),
                                    DocumentStatus.READING.name(),
                                    DocumentStatus.SPLITTING.name(),
                                    DocumentStatus.VECTORIZING.name()));
            return rows;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(rows -> {
                    if (rows > 0) {
                        // 补偿清理顺序：先删 Milvus 向量，再删 MySQL 父块；任一失败不影响状态更新
                        return hybridVectorWriter.deleteByDocUuid(docUuid)
                                .onErrorResume(ex -> {
                                    log.warn("Vector cleanup failed for {}: {}", docUuid, ex.getMessage());
                                    return Mono.empty();
                                })
                                .then(parentBlockService.deleteByDocUuid(docUuid)
                                        .onErrorResume(ex -> {
                                            log.warn("Parent block cleanup failed for {}: {}", docUuid, ex.getMessage());
                                            return Mono.empty();
                                        }))
                                .then(Mono.fromRunnable(() -> sendRedisMessage(docUuid, userId, DocumentStatus.FAILED, userMsg)))
                                .thenReturn(true);
                    }
                    log.warn("transitionToFailed no-op: doc {} is already terminal", docUuid);
                    return Mono.just(false);
                });
    }

    // 同步版本供 Watchdog 等非 reactive 调用方使用，.block() 在 boundedElastic 线程上安全
    public boolean transitionToFailedSync(String docUuid, String userId, Throwable error) {
        return Boolean.TRUE.equals(transitionToFailed(docUuid, userId, error).block());
    }

    private void sendRedisMessage(String docUuid, String userId, DocumentStatus status, String msg) {
        if (userId == null) return;
        try {
            net.topikachu.rag.business.document.event.EtlStatusMessage message =
                    new net.topikachu.rag.business.document.event.EtlStatusMessage(docUuid, userId, status, msg);
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(net.topikachu.rag.config.RedisPubSubConfig.TOPIC_ETL_STATUS, json);
        } catch (Exception e) {
            log.error("Failed to publish Redis message for {}", docUuid, e);
        }
    }

    private String extractUserMessage(Throwable e) {
        if (e == null) return "Unknown error";
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }
        // 截断到 500 字符：数据库 error_message 列长度有限
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private String formatStackTrace(Throwable e) {
        if (e == null) return null;
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        // 截断到 2000 字符：数据库 error_stack 列长度有限
        String full = sw.toString();
        return full.length() > 2000 ? full.substring(0, 2000) : full;
    }
}
