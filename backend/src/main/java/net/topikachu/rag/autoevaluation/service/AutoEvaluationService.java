package net.topikachu.rag.autoevaluation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.autoevaluation.dto.*;
import net.topikachu.rag.autoevaluation.dto.RagasEvaluationRequest.RagasEvaluationItem;
import net.topikachu.rag.autoevaluation.dto.RagasEvaluationResponse.RagasEvaluationResult;
import net.topikachu.rag.autoevaluation.entity.ChatEvaluationAutoEntity;
import net.topikachu.rag.autoevaluation.entity.ChatEvaluationAutoRunEntity;
import net.topikachu.rag.autoevaluation.mapper.ChatEvaluationAutoLockMapper;
import net.topikachu.rag.autoevaluation.mapper.ChatEvaluationAutoMapper;
import net.topikachu.rag.autoevaluation.mapper.ChatEvaluationAutoRunMapper;
import net.topikachu.rag.evaluation.entity.ChatEvaluationEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AutoEvaluationService {

    private static final String LOCK_NAME = "auto_evaluation";
    private static final String NO_REFERENCE = "NO_REFERENCE";
    private static final String RUNNING = "RUNNING";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";

    private final ChatEvaluationAutoMapper autoMapper;
    private final ChatEvaluationAutoRunMapper runMapper;
    private final ChatEvaluationAutoLockMapper lockMapper;
    private final RagasEvaluationClient ragasClient;
    private final ObjectMapper objectMapper;
    private final int sampleSize;
    private final int batchSize;
    private final int sampleWindowHours;
    private final int runTimeoutMinutes;
    private final String instanceId;

    public AutoEvaluationService(
            ChatEvaluationAutoMapper autoMapper,
            ChatEvaluationAutoRunMapper runMapper,
            ChatEvaluationAutoLockMapper lockMapper,
            RagasEvaluationClient ragasClient,
            ObjectMapper objectMapper,
            @Value("${rag.evaluation.auto.sample-size:20}") int sampleSize,
            @Value("${rag.evaluation.auto.batch-size:5}") int batchSize,
            @Value("${rag.evaluation.auto.sample-window-hours:24}") int sampleWindowHours,
            @Value("${rag.evaluation.auto.run-timeout-minutes:120}") int runTimeoutMinutes) {
        this.autoMapper = autoMapper;
        this.runMapper = runMapper;
        this.lockMapper = lockMapper;
        this.ragasClient = ragasClient;
        this.objectMapper = objectMapper;
        this.sampleSize = sampleSize;
        this.batchSize = Math.max(1, batchSize);
        this.sampleWindowHours = sampleWindowHours;
        this.runTimeoutMinutes = runTimeoutMinutes;
        this.instanceId = resolveInstanceId();
    }

    public Mono<String> runAutoEvaluation() {
        return Mono.fromCallable(() -> {
                    String ownerId = instanceId + "-" + newRunId();
                    if (!acquireLock(ownerId)) {
                        throw new IllegalStateException("Auto evaluation is already running.");
                    }

                    String runId = newRunId();
                    ChatEvaluationAutoRunEntity run = new ChatEvaluationAutoRunEntity();
                    run.setRunId(runId);
                    run.setStatus(RUNNING);
                    run.setStartedAt(LocalDateTime.now());
                    runMapper.insert(run);

                    doRun(runId)
                            .doOnError(e -> log.error("RAGAS auto evaluation run failed runId={}", runId, e))
                            .onErrorResume(e -> markRunFailed(runId, e.getMessage()).then())
                            .doFinally(signal -> releaseLock(ownerId))
                            .subscribe();

                    return runId;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<AutoStatsResult> queryAutoStats() {
        return Mono.fromCallable(() -> {
                    ChatEvaluationAutoRunEntity lastRun = runMapper.selectLastRun();
                    Map<String, Object> summary = autoMapper.selectAutoSummary();
                    List<RagasTrendPoint> trend = autoMapper.selectAutoTrend().stream()
                            .map(row -> new RagasTrendPoint(
                                    String.valueOf(row.get("day")),
                                    toNullableDouble(row.get("faithfulness")),
                                    toNullableDouble(row.get("answer_relevancy")),
                                    toNullableDouble(row.get("context_precision")),
                                    toNullableDouble(row.get("context_recall")),
                                    toNullableDouble(row.get("answer_correctness")),
                                    toNullableDouble(row.get("answer_similarity"))))
                            .toList();
                    return new AutoStatsResult(
                            toRunItem(lastRun),
                            toLong(summary.get("total_evaluated")),
                            toNullableDouble(summary.get("avg_faithfulness")),
                            toNullableDouble(summary.get("avg_answer_relevancy")),
                            toNullableDouble(summary.get("avg_context_precision")),
                            toNullableDouble(summary.get("avg_context_recall")),
                            toNullableDouble(summary.get("avg_answer_correctness")),
                            toNullableDouble(summary.get("avg_answer_similarity")),
                            trend);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<AutoScoreItem>> queryScoresByEvaluationId(String evaluationId) {
        return Mono.fromCallable(() -> autoMapper.selectList(
                                com.baomidou.mybatisplus.core.toolkit.Wrappers
                                        .<ChatEvaluationAutoEntity>lambdaQuery()
                                        .eq(ChatEvaluationAutoEntity::getEvaluationId, evaluationId)
                                        .orderByDesc(ChatEvaluationAutoEntity::getCreateDate))
                        .stream()
                        .map(this::toScoreItem)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<AutoRunItem>> queryRunHistory() {
        return Mono.fromCallable(() -> runMapper.selectRunHistory().stream()
                        .map(this::toRunItem)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Scheduled(cron = "${rag.evaluation.auto.cron:0 0 4 * * ?}")
    public void scheduledRun() {
        runAutoEvaluation()
                .doOnSuccess(runId -> log.info("Scheduled RAGAS auto evaluation started runId={}", runId))
                .doOnError(e -> log.warn("Scheduled RAGAS auto evaluation skipped or failed to start: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    @Scheduled(cron = "0 0/10 * * * ?")
    public void markStaleRunsFailed() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusMinutes(runTimeoutMinutes);
        int rows = runMapper.markStaleFailed(
                threshold,
                now,
                "Run timed out (" + runTimeoutMinutes + " minutes)");
        if (rows > 0) {
            log.warn("Marked {} stale RAGAS auto evaluation runs as FAILED", rows);
        }
    }

    private Mono<Void> doRun(String runId) {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        return Mono.fromCallable(() -> {
                    LocalDateTime since = LocalDateTime.now().minusHours(sampleWindowHours);
                    List<ChatEvaluationEntity> samples = autoMapper.selectSamples(since, sampleSize);
                    runMapper.updateTotalSamples(runId, samples.size());
                    return partition(samples, batchSize);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .concatMap(batch -> evaluateBatch(batch, runId, successCount, failureCount))
                .then(Mono.fromRunnable(() -> completeRun(
                                runId,
                                successCount.get(),
                                failureCount.get()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    private Mono<Void> evaluateBatch(
            List<ChatEvaluationEntity> batch,
            String runId,
            AtomicInteger successCount,
            AtomicInteger failureCount) {
        Map<String, ChatEvaluationEntity> byId = batch.stream()
                .collect(Collectors.toMap(ChatEvaluationEntity::getId, Function.identity()));
        RagasEvaluationRequest request = new RagasEvaluationRequest(batch.stream()
                .map(this::toRagasItem)
                .toList());

        return ragasClient.evaluate(request)
                .flatMap(response -> Mono.fromRunnable(() -> persistBatchResults(
                                response,
                                byId,
                                runId,
                                successCount,
                                failureCount))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then())
                .onErrorResume(e -> {
                    failureCount.addAndGet(batch.size());
                    log.warn("RAGAS batch failed size={}: {}", batch.size(), e.getMessage());
                    return Mono.<Void>empty();
                });
    }

    private void persistBatchResults(
            RagasEvaluationResponse response,
            Map<String, ChatEvaluationEntity> byId,
            String runId,
            AtomicInteger successCount,
            AtomicInteger failureCount) {
        Set<String> seen = new HashSet<>();
        List<RagasEvaluationResult> results = response != null && response.items() != null
                ? response.items()
                : List.of();

        for (RagasEvaluationResult result : results) {
            seen.add(result.evaluationId());
            ChatEvaluationEntity evaluation = byId.get(result.evaluationId());
            if (evaluation == null) {
                continue;
            }
            if (StringUtils.hasText(result.error())) {
                failureCount.incrementAndGet();
                log.warn("RAGAS item failed evaluationId={}: {}", result.evaluationId(), result.error());
                continue;
            }
            EvaluationOutcome outcome = insertScore(evaluation, runId, result);
            if (outcome == EvaluationOutcome.SUCCESS) {
                successCount.incrementAndGet();
            }
        }

        int missing = byId.size() - seen.size();
        if (missing > 0) {
            failureCount.addAndGet(missing);
            log.warn("RAGAS response missed {} requested items", missing);
        }
    }

    private EvaluationOutcome insertScore(
            ChatEvaluationEntity evaluation,
            String runId,
            RagasEvaluationResult result) {
        ChatEvaluationAutoEntity entity = new ChatEvaluationAutoEntity();
        entity.setEvaluationId(evaluation.getId());
        entity.setRunId(runId);
        entity.setFaithfulness(normalizeScore(result.faithfulness()));
        entity.setAnswerRelevancy(normalizeScore(result.answerRelevancy()));
        entity.setContextPrecision(normalizeScore(result.contextPrecision()));
        entity.setContextRecall(normalizeScore(result.contextRecall()));
        entity.setAnswerCorrectness(normalizeScore(result.answerCorrectness()));
        entity.setAnswerSimilarity(normalizeScore(result.answerSimilarity()));
        entity.setReferenceAnswerHash(referenceHash(evaluation.getReference()));
        try {
            autoMapper.insert(entity);
            return EvaluationOutcome.SUCCESS;
        } catch (DuplicateKeyException e) {
            log.info("Skip duplicate RAGAS evaluation evaluationId={} referenceHash={}",
                    evaluation.getId(), entity.getReferenceAnswerHash());
            return EvaluationOutcome.SKIPPED;
        }
    }

    private RagasEvaluationItem toRagasItem(ChatEvaluationEntity evaluation) {
        return new RagasEvaluationItem(
                evaluation.getId(),
                evaluation.getQuestion(),
                evaluation.getAnswer(),
                parseRetrievedContexts(evaluation.getContextSnippets()),
                StringUtils.hasText(evaluation.getReference()) ? evaluation.getReference() : null);
    }

    private List<String> parseRetrievedContexts(String snippetsJson) {
        if (snippetsJson == null || snippetsJson.isBlank() || "[]".equals(snippetsJson.trim())) {
            return List.of();
        }
        try {
            List<Map<String, Object>> snippets = objectMapper.readValue(
                    snippetsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            return snippets.stream()
                    .map(snippet -> String.valueOf(snippet.getOrDefault("text", "")))
                    .filter(StringUtils::hasText)
                    .toList();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse context snippets for RAGAS", e);
            return List.of();
        }
    }

    private boolean acquireLock(String ownerId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockedUntil = now.plusMinutes(runTimeoutMinutes);
        lockMapper.acquireOrRefreshExpired(LOCK_NAME, ownerId, lockedUntil, now);
        return ownerId.equals(lockMapper.selectOwner(LOCK_NAME));
    }

    private void releaseLock(String ownerId) {
        try {
            lockMapper.release(LOCK_NAME, ownerId);
        } catch (Exception e) {
            log.warn("Failed to release RAGAS auto evaluation lock ownerId={}: {}", ownerId, e.getMessage());
        }
    }

    private void completeRun(String runId, int successCount, int failureCount) {
        Map<String, Object> averages = runMapper.selectRunAverages(runId);
        String status = successCount > 0 || failureCount == 0 ? COMPLETED : FAILED;
        String errorMessage = successCount > 0 || failureCount == 0
                ? null
                : "All RAGAS evaluation items failed.";
        runMapper.updateCompletion(
                runId,
                status,
                successCount,
                failureCount,
                toScoreBigDecimal(averages.get("avg_faithfulness")),
                toScoreBigDecimal(averages.get("avg_answer_relevancy")),
                toScoreBigDecimal(averages.get("avg_context_precision")),
                toScoreBigDecimal(averages.get("avg_context_recall")),
                toScoreBigDecimal(averages.get("avg_answer_correctness")),
                toScoreBigDecimal(averages.get("avg_answer_similarity")),
                LocalDateTime.now(),
                errorMessage);
    }

    private Mono<Void> markRunFailed(String runId, String message) {
        return Mono.fromRunnable(() -> runMapper.updateCompletion(
                        runId,
                        FAILED,
                        0,
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.now(),
                        message))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private BigDecimal normalizeScore(Double score) {
        if (score == null || score.isNaN() || score.isInfinite()) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(1.0, score));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal toScoreBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(4, RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        }
        return null;
    }

    private String referenceHash(String reference) {
        if (!StringUtils.hasText(reference)) {
            return NO_REFERENCE;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(reference.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private AutoScoreItem toScoreItem(ChatEvaluationAutoEntity entity) {
        return new AutoScoreItem(
                entity.getId(),
                entity.getEvaluationId(),
                entity.getRunId(),
                toNullableDouble(entity.getFaithfulness()),
                toNullableDouble(entity.getAnswerRelevancy()),
                toNullableDouble(entity.getContextPrecision()),
                toNullableDouble(entity.getContextRecall()),
                toNullableDouble(entity.getAnswerCorrectness()),
                toNullableDouble(entity.getAnswerSimilarity()),
                entity.getReferenceAnswerHash(),
                entity.getCreateDate());
    }

    private AutoRunItem toRunItem(ChatEvaluationAutoRunEntity entity) {
        if (entity == null) {
            return null;
        }
        return new AutoRunItem(
                entity.getRunId(),
                entity.getStatus(),
                entity.getTotalSamples(),
                entity.getSuccessCount(),
                entity.getFailureCount(),
                toNullableDouble(entity.getAvgFaithfulness()),
                toNullableDouble(entity.getAvgAnswerRelevancy()),
                toNullableDouble(entity.getAvgContextPrecision()),
                toNullableDouble(entity.getAvgContextRecall()),
                toNullableDouble(entity.getAvgAnswerCorrectness()),
                toNullableDouble(entity.getAvgAnswerSimilarity()),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getErrorMessage());
    }

    private List<List<ChatEvaluationEntity>> partition(List<ChatEvaluationEntity> items, int size) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<List<ChatEvaluationEntity>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return batches;
    }

    private String newRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private Double toNullableDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private enum EvaluationOutcome {
        SUCCESS,
        SKIPPED
    }
}
