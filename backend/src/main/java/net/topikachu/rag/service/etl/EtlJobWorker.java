package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.service.impl.EtlJobLeaseService;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class EtlJobWorker {

    private final DocumentMapper documentMapper;
    private final EtlPipeline etlPipeline;
    @Value("${rag.etl.worker.batch-size:10}")
    private int batchsize;
    @Value("${rag.etl.worker.lock-minutes:10}")
    private int lockMinutes;
    private final EtlJobLeaseService etlJobLeaseService;
    private final EtlJobService etlJobService;
    private final EtlJobMapper etlJobMapper;
    private final ObjectStorageService objectStorageService;
    private final String workerId = UUID.randomUUID().toString().replace("-","");

    @Scheduled(fixedDelayString = "${rag.etl.worker.fixed-delay-ms:5000}")
    public void poll() {
        List<EtlJob> jobs = etlJobService.findRunnableJobs(batchsize);

        for(EtlJob job : jobs){
            tryClaimAndRun(job);
        }
    }

    private void tryClaimAndRun(EtlJob job) {
        if(job == null){
            log.warn("Document is empty");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean change = etlJobService.markRunning(job.getId(),workerId, now.plusMinutes(lockMinutes));
        if (change){
            runJob(job.getId());
        } else {
            log.warn("Failed to update Document status : {}", job.getId());
        }

    }

    private void runJob(String jobId) {
        EtlJob job = etlJobMapper.selectById(jobId);
        if (job == null){
            log.warn("Not found job worker");
            return;
        }

        Disposable heartbeat = startHeartbeat(job.getId());
        Path path = null;
        boolean tempDownloaded = false;

        try {
            Document doc = documentMapper.selectOne(
                    Wrappers.<Document>lambdaQuery().eq(Document::getDocUuid, job.getDocUuid()).last("LIMIT 1")
            );
            if(doc == null){
                markFailed(job, new IllegalStateException("Document not found: " + job.getDocUuid()));
                return;
            }
            if (StringUtils.hasText(job.getObjectKey())) {
                path = objectStorageService.downloadToTempFile(job.getObjectKey(), job.getFileName());
                tempDownloaded = true;
            } else {
                path = Paths.get(job.getFilePath());
                if(!Files.exists(path)){
                    throw new IllegalStateException("Source file missing: " + path);
                }
            }
            etlPipeline.ingestionByPath(path,job.getDocUuid(),job.getCreateUserId(),job.getTags()).block();
            markSuccess(job);
        } catch (Exception e){
            log.info("Failed to execute the task: {}", job.getDocUuid());
            markFailed(job, e);
        } finally {
            if (tempDownloaded && path != null) {
                deleteTempFileWithRetry(path);
            }
            heartbeat.dispose();
        }
    }

    private void deleteTempFileWithRetry(Path path) {
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (Files.deleteIfExists(path)) {
                    return;
                }
                return;
            } catch (Exception cleanupError) {
                if (attempt == maxAttempts) {
                    path.toFile().deleteOnExit();
                    log.warn("Failed to delete ETL temp file after {} attempts, scheduled deleteOnExit: {}",
                            maxAttempts, path, cleanupError);
                    return;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    path.toFile().deleteOnExit();
                    log.warn("Interrupted while deleting ETL temp file, scheduled deleteOnExit: {}",
                            path, cleanupError);
                    return;
                }
            }
        }
    }

    private Disposable startHeartbeat(String jobId) {
        long intervalSeconds = Math.max(30, lockMinutes * 60L / 3);

        return Flux.interval(Duration.ofSeconds(intervalSeconds))
                .flatMap(tick -> Mono.fromCallable(() ->
                                etlJobLeaseService.renewLease(
                                        jobId,
                                        workerId,
                                        LocalDateTime.now().plusMinutes(lockMinutes)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnNext(renewed -> {
                    if (!renewed) {
                        log.warn("ETL job lease heartbeat lost: jobId={}, workerId={}", jobId, workerId);
                    }
                })
                .onErrorContinue((error, value) ->
                        log.warn("ETL job lease heartbeat failed: jobId={}, workerId={}", jobId, workerId, error))
                .subscribe();
    }

    private void markSuccess(EtlJob job){
        LocalDateTime now = LocalDateTime.now();
        int updated = etlJobMapper.update(null,
                Wrappers.<EtlJob>lambdaUpdate()
                        .set(EtlJob::getStatus, EtlJobStatus.SUCCESS.name())
                        .set(EtlJob::getLockedBy, null)
                        .set(EtlJob::getLockedUntil, null)
                        .set(EtlJob::getLastError, null)
                        .set(EtlJob::getErrorStack, null)
                        .set(EtlJob::getUpdateDate, now)
                        .set(EtlJob::getFinishedAt, now)
                        .set(EtlJob::getActiveKey, null)
                        .eq(EtlJob::getId, job.getId())
                        .eq(EtlJob::getStatus, EtlJobStatus.RUNNING.name())
                        .eq(EtlJob::getLockedBy, workerId)
        );
        if (updated != 1) {
            log.warn("Skip marking ETL job success because lease was lost: jobId={}, workerId={}",
                    job.getId(), workerId);
        }
    }

    private void markFailed(EtlJob job, Throwable error){
        LocalDateTime now = LocalDateTime.now();

        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        int nextRetryCount = retryCount + 1;

        int updated = etlJobMapper.update(null,
                Wrappers.<EtlJob>lambdaUpdate()
                        .set(EtlJob::getFinishedAt, now)
                        .set(EtlJob::getStatus, EtlJobStatus.FAILED.name())
                        .set(EtlJob::getLockedBy, null)
                        .set(EtlJob::getLockedUntil, null)
                        .set(EtlJob::getRetryCount, nextRetryCount)
                        .set(EtlJob::getUpdateDate, now)
                        .set(EtlJob::getNextRetryTime, calculateNextRetryTime(nextRetryCount))
                        .set(EtlJob::getLastError, summarizeError(error))
                        .set(EtlJob::getErrorStack, formatStackTrace(error))
                        .set(EtlJob::getActiveKey, null)
                        .eq(EtlJob::getId, job.getId())
                        .eq(EtlJob::getStatus, EtlJobStatus.RUNNING.name())
                        .eq(EtlJob::getLockedBy, workerId)
        );
        if (updated != 1) {
            log.warn("Skip marking ETL job failed because lease was lost: jobId={}, workerId={}",
                    job.getId(), workerId);
        }
    }

    private LocalDateTime calculateNextRetryTime(int retryCount) {
        int minutes = switch (retryCount) {
            case 1 -> 5;
            case 2 -> 15;
            case 3 -> 60;
            default -> 360;
        };

        return LocalDateTime.now().plusMinutes(minutes);
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
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }

        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private String formatStackTrace(Throwable error) {
        if (error == null) {
            return null;
        }

        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));

        String stack = sw.toString();
        return stack.length() > 2000 ? stack.substring(0, 2000) : stack;
    }
}
