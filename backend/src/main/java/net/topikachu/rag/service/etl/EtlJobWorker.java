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
    @Value("${rag.etl.worker.concurrency:4}")
    private int concurrency;
    private final EtlJobLeaseService etlJobLeaseService;
    private final EtlJobService etlJobService;
    private final EtlJobMapper etlJobMapper;
    private final ObjectStorageService objectStorageService;
    private final String workerId = UUID.randomUUID().toString().replace("-","");

    @Scheduled(fixedDelayString = "${rag.etl.worker.fixed-delay-ms:5000}")
    public void poll() {
        pollOnce().subscribe(null, error -> log.error("ETL worker poll failed", error));
    }

    Mono<Void> pollOnce() {
        return etlJobService.findRunnableJobs(batchsize)
                .flatMap(this::processJobs);
    }

    /**
     * Process a batch of ETL jobs with bounded concurrency.
     * Returns a composable Mono for testability — callers can block() on it
     * to await deterministic completion instead of relying on fire-and-forget races.
     */
    Mono<Void> processJobs(List<EtlJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(jobs)
                .flatMap(this::tryClaimAndRun, Math.max(1, concurrency))
                .doOnError(error -> log.error("ETL worker poll batch failed", error))
                .then();
    }

    private Mono<Void> tryClaimAndRun(EtlJob job) {
        if(job == null){
            log.warn("Document is empty");
            return Mono.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        return etlJobService.markRunning(job.getId(), workerId, now.plusMinutes(lockMinutes))
                .flatMap(claimed -> {
                    if (claimed) {
                        return runJob(job.getId());
                    }
                    log.warn("Failed to update Document status : {}", job.getId());
                    return Mono.empty();
                })
                .onErrorResume(error -> {
                    log.error("Failed to claim or run ETL job: {}", job.getId(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> runJob(String jobId) {
        return Mono.fromCallable(() -> etlJobMapper.selectById(jobId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::runJobInternal);
    }

    private Mono<Void> runJobInternal(EtlJob job) {
        if (job == null) {
            log.warn("Not found job worker");
            return Mono.empty();
        }

        Disposable heartbeat = startHeartbeat(job.getId());
        return prepareJobFile(job)
                .flatMap(jobFile -> runIngestion(job, jobFile))
                .onErrorResume(error -> handleFailure(job, error))
                .doFinally(signalType -> heartbeat.dispose());
    }

    private Mono<Void> runIngestion(EtlJob job, JobFile jobFile) {
        return etlPipeline.ingestionByPath(
                        jobFile.path(),
                        job.getDocUuid(),
                        job.getCreateUserId(),
                        job.getTags())
                .then(markSuccess(job))
                .doFinally(signalType -> {
                    if (jobFile.tempDownloaded()) {
                        deleteTempFileWithRetry(jobFile.path()).subscribe();
                    }
                });
    }

    private Mono<Void> handleFailure(EtlJob job, Throwable error) {
        log.info("Failed to execute the task: {}", job.getDocUuid());
        return markFailed(job, error);
    }

    private Mono<JobFile> prepareJobFile(EtlJob job) {
        return Mono.fromCallable(() -> {
                    Document doc = documentMapper.selectOne(
                            Wrappers.<Document>lambdaQuery().eq(Document::getDocUuid, job.getDocUuid()).last("LIMIT 1")
                    );
                    if (doc == null) {
                        throw new IllegalStateException("Document not found: " + job.getDocUuid());
                    }
                    return doc;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(doc -> {
                    if (StringUtils.hasText(job.getObjectKey())) {
                        return objectStorageService.downloadToTempFile(job.getObjectKey(), job.getFileName())
                                .map(path -> new JobFile(path, true));
                    }
                    Path path = Paths.get(job.getFilePath());
                    if (!Files.exists(path)) {
                        return Mono.error(new IllegalStateException("Source file missing: " + path));
                    }
                    return Mono.just(new JobFile(path, false));
                });
    }

    private record JobFile(Path path, boolean tempDownloaded) {
    }

    private Mono<Void> deleteTempFileWithRetry(Path path) {
        return deleteTempFileAttempt(path, 1)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> deleteTempFileAttempt(Path path, int attempt) {
        return Mono.fromCallable(() -> Files.deleteIfExists(path))
                .subscribeOn(Schedulers.boundedElastic())
                .then()
                .onErrorResume(error -> {
                    if (attempt >= 5) {
                        path.toFile().deleteOnExit();
                        log.warn("Failed to delete ETL temp file after {} attempts, scheduled deleteOnExit: {}",
                                5, path, error);
                        return Mono.empty();
                    }
                    return Mono.delay(Duration.ofMillis(200L * attempt))
                            .then(deleteTempFileAttempt(path, attempt + 1));
                });
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

    private Mono<Void> markSuccess(EtlJob job){
        return Mono.fromRunnable(() -> {
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
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<Void> markFailed(EtlJob job, Throwable error){
        return Mono.fromRunnable(() -> {
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
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
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
