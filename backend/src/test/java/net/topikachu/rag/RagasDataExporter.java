package net.topikachu.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.evaluation.ContextNode;
import net.topikachu.rag.evaluation.EvaluationConfig;
import net.topikachu.rag.evaluation.EvaluationResultItem;
import net.topikachu.rag.service.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exports staged RAG evaluation data as JSONL for Ragas (Python) assessment.
 * <p>
 * Flow:
 * 1. Reads test questions from a CSV file (question, ground_truth)
 * 2. Runs three retrieval stages through ChatService.evaluateQuery()
 * 3. Evaluates Ollama and DeepSeek concurrently with independent pipelines
 * 4. Writes results as JSONL compatible with Ragas evaluate()
 * <p>
 * Usage:
 * mvn test -Dtest=RagasDataExporter
 * Then run: python evaluation/ragas_eval.py
 */
@SpringBootTest
@ActiveProfiles({ "ollama-openai" })
@Slf4j
public class RagasDataExporter {

    private static final String[] MODEL_IDS = { "ollama", "deepseek" };

    @Autowired
    private ChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("classpath:prompts/baseline_rag.st")
    private Resource baselinePrompt;

    @Value("classpath:prompts/optimized_rag.st")
    private Resource optimizedPrompt;

    @Value("${rag.ragas.test-questions-file:../evaluation/test_questions.csv}")
    private String testQuestionsFile;

    @Value("${rag.ragas.output-file-prefix:../evaluation/ragas_eval_data_}")
    private String outputFilePrefix;

    @Value("${rag.ragas.top-k:5}")
    private int topK;

    @Value("${rag.ragas.timeout-seconds:60}")
    private int timeoutSeconds;

    @Test
    void exportRagasData() throws Exception {
        List<QAPair> pairs = loadTestQuestions();
        if (pairs.isEmpty()) {
            log.error("No test questions found in {}. Aborting.", testQuestionsFile);
            return;
        }
        log.info("Loaded {} test questions from {}", pairs.size(), testQuestionsFile);

        List<EvaluationStage> stages = evaluationStages();
        for (String modelId : MODEL_IDS) {
            Path output = Paths.get(outputFilePrefix + modelId + ".jsonl");
            Files.createDirectories(output.getParent());
            Files.writeString(output, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        Map<String, AtomicInteger> successCounts = new LinkedHashMap<>();
        Map<String, AtomicInteger> failureCounts = new LinkedHashMap<>();
        for (String modelId : MODEL_IDS) {
            successCounts.put(modelId, new AtomicInteger());
            failureCounts.put(modelId, new AtomicInteger());
        }

        log.info("=== Starting staged asynchronous evaluation export for models: {} ===",
                String.join(", ", MODEL_IDS));

        Flux.fromIterable(stages)
                .concatMap(stage -> runStage(stage, pairs, successCounts, failureCounts))
                .then()
                .block(calculateOverallTimeout(pairs.size(), stages.size()));

        log.info("=== Ragas data export complete ===");
        for (String modelId : MODEL_IDS) {
            log.info("Model [{}]: Success: {}, Failed: {}, Total Attempts: {}",
                    modelId,
                    successCounts.get(modelId).get(),
                    failureCounts.get(modelId).get(),
                    pairs.size() * stages.size());
        }
    }

    private Mono<Void> runStage(
            EvaluationStage stage,
            List<QAPair> pairs,
            Map<String, AtomicInteger> successCounts,
            Map<String, AtomicInteger> failureCounts) {
        log.info("=== Stage [{}] started. sparse={}, rerank={} ===",
                stage.variant(), stage.config().useSparseSearch(), stage.config().useRerank());

        return Flux.fromArray(MODEL_IDS)
                .flatMap(modelId -> runModelPipeline(
                        modelId,
                        stage,
                        pairs,
                        successCounts.get(modelId),
                        failureCounts.get(modelId)))
                .then()
                .doOnSuccess(ignored -> log.info("=== Stage [{}] completed ===", stage.variant()));
    }

    private Mono<Void> runModelPipeline(
            String modelId,
            EvaluationStage stage,
            List<QAPair> pairs,
            AtomicInteger successCount,
            AtomicInteger failureCount) {
        Path output = Paths.get(outputFilePrefix + modelId + ".jsonl");

        log.info("[{}][{}] Pipeline started with {} questions", modelId, stage.variant(), pairs.size());
        return Flux.fromIterable(pairs)
                .index()
                .concatMap(tuple -> exportSingleQuestion(
                        modelId,
                        stage,
                        tuple.getT1() + 1,
                        pairs.size(),
                        tuple.getT2(),
                        output,
                        successCount,
                        failureCount))
                .then()
                .doOnSuccess(ignored -> log.info("[{}][{}] Pipeline completed", modelId, stage.variant()));
    }

    private Mono<Void> exportSingleQuestion(
            String modelId,
            EvaluationStage stage,
            long questionIndex,
            int totalQuestions,
            QAPair qa,
            Path output,
            AtomicInteger successCount,
            AtomicInteger failureCount) {
        log.info("[{}][{}][{}/{}] Processing: {}",
                modelId,
                stage.variant(),
                questionIndex,
                totalQuestions,
                qa.question.length() > 40 ? qa.question.substring(0, 40) : qa.question);

        return chatService.evaluateQuery(
                        qa.question,
                        qa.groundTruth,
                        stage.config(),
                        baselinePrompt,
                        optimizedPrompt,
                        topK,
                        modelId,
                        false)
                .flatMap(result -> persistResult(output, toRagasFormat(stage.variant(), modelId, questionIndex, result))
                        .doOnSuccess(ignored -> {
                            successCount.incrementAndGet();
                            log.info("[{}][{}][{}/{}] OK. answer_length={}, contexts_count={}",
                                    modelId,
                                    stage.variant(),
                                    questionIndex,
                                    totalQuestions,
                                    result.generatedAnswer().length(),
                                    result.contexts().size());
                        }))
                .onErrorResume(e -> {
                    failureCount.incrementAndGet();
                    log.error("[{}][{}][{}/{}] Failed: {}",
                            modelId,
                            stage.variant(),
                            questionIndex,
                            totalQuestions,
                            e.getMessage(),
                            e);
                    return Mono.empty();
                });
    }

    /**
     * Convert EvaluationResultItem to Ragas-compatible JSON format.
     */
    private Map<String, Object> toRagasFormat(
            String variant,
            String modelId,
            long questionIndex,
            EvaluationResultItem result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("variant", variant);
        record.put("model_id", modelId);
        record.put("question_index", questionIndex);
        record.put("question", result.question());
        record.put("answer", result.generatedAnswer());

        List<String> contextTexts = new ArrayList<>();
        if (result.contexts() != null) {
            for (ContextNode ctx : result.contexts()) {
                contextTexts.add(ctx.text());
            }
        }
        record.put("contexts", contextTexts);
        record.put("ground_truth", result.groundTruth() != null ? result.groundTruth() : "");
        return record;
    }

    private Mono<Void> persistResult(Path output, Map<String, Object> ragasRecord) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(ragasRecord))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(jsonLine -> Mono.fromRunnable(() -> {
                            try {
                                Files.writeString(output, jsonLine + System.lineSeparator(),
                                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to append JSONL record", e);
                            }
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    private List<EvaluationStage> evaluationStages() {
        return Arrays.asList(
                new EvaluationStage("dense_only", new EvaluationConfig(false, false, false)),
                new EvaluationStage("hybrid_no_rerank", new EvaluationConfig(true, false, false)),
                new EvaluationStage("hybrid_with_rerank", new EvaluationConfig(true, true, false)));
    }

    private Duration calculateOverallTimeout(int questionCount, int stageCount) {
        long multiplier = (long) Math.max(questionCount, 1) * Math.max(stageCount, 1) * MODEL_IDS.length;
        return Duration.ofSeconds((long) timeoutSeconds * multiplier);
    }

    /**
     * Load test questions from CSV file.
     * Format: question,ground_truth (header required, ground_truth is optional)
     */
    private List<QAPair> loadTestQuestions() throws IOException {
        Path path = Paths.get(testQuestionsFile);
        if (!Files.exists(path)) {
            log.error("Test questions file not found: {}", path.toAbsolutePath());
            return List.of();
        }

        List<QAPair> pairs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("\uFEFF")) {
                    trimmed = trimmed.substring(1);
                }
                if (!headerSkipped) {
                    if (trimmed.toLowerCase().startsWith("question")) {
                        headerSkipped = true;
                        continue;
                    }
                    headerSkipped = true;
                }

                int commaIdx = trimmed.indexOf(',');
                if (commaIdx <= 0) {
                    pairs.add(new QAPair(trimmed, ""));
                    continue;
                }

                String question = trimmed.substring(0, commaIdx).trim();
                String groundTruth = trimmed.substring(commaIdx + 1).trim();

                question = stripQuotes(question);
                groundTruth = stripQuotes(groundTruth);

                if (!question.isEmpty()) {
                    pairs.add(new QAPair(question, groundTruth));
                }
            }
        }
        return pairs;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record QAPair(String question, String groundTruth) {
    }

    private record EvaluationStage(String variant, EvaluationConfig config) {
    }
}
