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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports RAG evaluation data as JSONL for Ragas (Python) assessment.
 * <p>
 * Flow:
 * 1. Reads test questions from a CSV file (question, ground_truth)
 * 2. Calls ChatService.evaluateQuery() for each question (Ollama generates
 * answers)
 * 3. Writes results as JSONL compatible with Ragas evaluate()
 * <p>
 * Usage:
 * mvn test -Dtest=RagasDataExporter
 * Then run: python evaluation/ragas_eval.py
 */
@SpringBootTest
@ActiveProfiles({ "ollama-openai" })
@Slf4j
public class RagasDataExporter {

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

    @Value("${rag.ragas.use-sparse-search:true}")
    private boolean useSparseSearch;

    @Value("${rag.ragas.use-rerank:true}")
    private boolean useRerank;

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

        String[] modelIds = { "ollama", "deepseek", "gemini" };
        
        // 1. Initialize output files and clear previous content
        for (String modelId : modelIds) {
            Path output = Paths.get(outputFilePrefix + modelId + ".jsonl");
            Files.createDirectories(output.getParent());
            Files.writeString(output, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        EvaluationConfig config = new EvaluationConfig(useSparseSearch, useRerank, false);
        
        // Atomic counters for thread safety
        java.util.concurrent.atomic.AtomicInteger[] successCounts = new java.util.concurrent.atomic.AtomicInteger[modelIds.length];
        java.util.concurrent.atomic.AtomicInteger[] failureCounts = new java.util.concurrent.atomic.AtomicInteger[modelIds.length];
        for(int i=0; i<modelIds.length; i++) {
            successCounts[i] = new java.util.concurrent.atomic.AtomicInteger(0);
            failureCounts[i] = new java.util.concurrent.atomic.AtomicInteger(0);
        }

        log.info("=== Starting fully asychronous concurrent evaluation export for models: {} ===", String.join(", ", modelIds));

        // 2 & 3. Create a fully asynchronous reactive pipeline
        reactor.core.publisher.Flux.fromIterable(pairs)
            .index() // keep track of the question index
            .flatMap(tuple -> {
                long questionIndex = tuple.getT1() + 1; // 1-based index
                QAPair qa = tuple.getT2();
                log.info("[_All_] [{}/{}] Processing asynchronously: {}...", questionIndex, pairs.size(),
                        qa.question.length() > 40 ? qa.question.substring(0, 40) : qa.question);

                // For each question, concurrently ask all models
                return reactor.core.publisher.Flux.fromArray(modelIds)
                    .flatMap(modelId -> {
                        int modelIndex = getModelIndex(modelIds, modelId);
                        
                        reactor.core.publisher.Mono<EvaluationResultItem> queryMono = chatService.evaluateQuery(
                                qa.question,
                                qa.groundTruth,
                                config,
                                baselinePrompt,
                                optimizedPrompt,
                                topK,
                                modelId);
                                
                        // Rate limit Gemini to 20 RPM (1 request every 3 seconds minimum)
                        if ("gemini".equals(modelId)) {
                            // Delay based on questionIndex to stagger the requests (3s intervals)
                            long delayMs = (questionIndex - 1) * 3000L;
                            queryMono = queryMono.delaySubscription(Duration.ofMillis(delayMs));
                            log.info("[gemini] Question {} scheduled with a delay of {} ms", questionIndex, delayMs);
                        }

                        return queryMono
                                .doOnNext(result -> {
                                    // 4. Wait for NO other model. Formulate result and write immediately.
                                    if (result == null) {
                                        failureCounts[modelIndex].incrementAndGet();
                                        log.warn("[{}] [{}/{}] evaluateQuery returned null.", modelId, questionIndex, pairs.size());
                                        return;
                                    }
                                    try {
                                        Map<String, Object> ragasRecord = toRagasFormat(result);
                                        String jsonLine = objectMapper.writeValueAsString(ragasRecord);
                                        
                                        // Synchronize exclusively on the specific model's file path to avoid overlapping writes
                                        Path output = Paths.get(outputFilePrefix + modelId + ".jsonl");
                                        synchronized(modelId.intern()) {
                                            Files.writeString(output, jsonLine + System.lineSeparator(),
                                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                        }
                                        
                                        successCounts[modelIndex].incrementAndGet();
                                        log.info("[{}] [{}/{}] OK. answer_length={}, contexts_count={}",
                                                modelId, questionIndex, pairs.size(),
                                                result.generatedAnswer().length(),
                                                result.contexts().size());
                                    } catch (Exception e) {
                                        log.error("[{}] [{}/{}] Failed to write result: {}", modelId, questionIndex, pairs.size(), e.getMessage());
                                        failureCounts[modelIndex].incrementAndGet();
                                    }
                                })
                                .onErrorResume(e -> {
                                    log.error("[{}] [{}/{}] Failed during concurrent execution: {}", modelId, questionIndex, pairs.size(), e.getMessage());
                                    failureCounts[modelIndex].incrementAndGet();
                                    return reactor.core.publisher.Mono.empty(); // Ignore errors explicitly from stream to not break the flatMap
                                });
                    });
            }, 3) // Optional: limit overall concurrency (number of questions concurrently processed) so you don't exhaust API limits instantly
            .blockLast(Duration.ofSeconds(timeoutSeconds * pairs.size())); // Max overall execution time

        log.info("=== Ragas data export complete ===");
        for (int i = 0; i < modelIds.length; i++) {
            log.info("Model [{}]: Success: {}, Failed: {}, Total: {}", 
                modelIds[i], successCounts[i].get(), failureCounts[i].get(), pairs.size());
        }
    }

    private int getModelIndex(String[] models, String target) {
        for (int i = 0; i < models.length; i++) {
            if (models[i].equals(target)) return i;
        }
        return 0;
    }
    
    private record ModelResult(String modelId, EvaluationResultItem result) {}

    /**
     * Convert EvaluationResultItem to Ragas-compatible JSON format.
     */
    private Map<String, Object> toRagasFormat(EvaluationResultItem result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("question", result.question());
        record.put("answer", result.generatedAnswer());

        // Extract context texts as a list of strings
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
                // Remove BOM if present
                if (trimmed.startsWith("\uFEFF")) {
                    trimmed = trimmed.substring(1);
                }
                // Skip header row
                if (!headerSkipped) {
                    if (trimmed.toLowerCase().startsWith("question")) {
                        headerSkipped = true;
                        continue;
                    }
                    headerSkipped = true;
                }

                // Parse CSV (simple split by first comma)
                int commaIdx = trimmed.indexOf(',');
                if (commaIdx <= 0) {
                    // No comma — treat entire line as question, no ground truth
                    pairs.add(new QAPair(trimmed, ""));
                    continue;
                }

                String question = trimmed.substring(0, commaIdx).trim();
                String groundTruth = trimmed.substring(commaIdx + 1).trim();

                // Remove surrounding quotes if present
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
}
