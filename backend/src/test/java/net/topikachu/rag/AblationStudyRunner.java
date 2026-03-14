package net.topikachu.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.evaluation.BenchmarkRunConfig;
import net.topikachu.rag.evaluation.BenchmarkVariant;
import net.topikachu.rag.evaluation.EvaluationResultItem;
import net.topikachu.rag.evaluation.GenerationCaseResult;
import net.topikachu.rag.evaluation.RetrievalCaseResult;
import net.topikachu.rag.service.chat.ChatService;
import net.topikachu.rag.service.etl.HybridVectorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles({ "ollama-openai", "benchmark-ecom" })
@Slf4j
public class AblationStudyRunner {

    @Autowired
    private ChatService chatService;

    @Autowired
    private HybridVectorWriter hybridVectorWriter;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("classpath:prompts/baseline_rag.st")
    private Resource baselinePrompt;

    @Value("classpath:prompts/optimized_rag.st")
    private Resource optimizedPrompt;

    @Value("${rag.benchmark.dataset-name:ecom}")
    private String datasetName;

    @Value("${rag.benchmark.corpus-file:}")
    private String corpusFilePath;

    @Value("${rag.benchmark.query-file:}")
    private String queryFilePath;

    @Value("${rag.benchmark.qrels-file:}")
    private String qrelsFilePath;

    @Value("${rag.benchmark.sample-size:80}")
    private int sampleSize;

    @Value("${rag.benchmark.generation-sample-size:20}")
    private int generationSampleSize;

    @Value("${rag.benchmark.seed:42}")
    private long seed;

    @Value("${rag.benchmark.top-k:10}")
    private int topK;

    @Value("${rag.benchmark.retrieval-timeout-ms:10000}")
    private long retrievalTimeoutMs;

    @Value("${rag.benchmark.generation-timeout-ms:40000}")
    private long generationTimeoutMs;

    @Value("${rag.benchmark.retries:1}")
    private int retries;

    @Value("${rag.benchmark.concurrency:1}")
    private int concurrency;

    @Value("${rag.benchmark.cooldown-ms:2000}")
    private long cooldownMs;

    @Value("${rag.benchmark.failure-window-size:10}")
    private int failureWindowSize;

    @Value("${rag.benchmark.failure-rate-threshold:0.30}")
    private double failureRateThreshold;

    @Value("${rag.benchmark.run-generation:false}")
    private boolean runGenerationStage;

    @Value("${rag.benchmark.output-dir:evaluation_results}")
    private String outputDir;

    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
    private String benchmarkCollectionName;

    @Value("${rag.benchmark.auto-import-corpus:true}")
    private boolean autoImportCorpus;

    @Value("${rag.benchmark.force-import:false}")
    private boolean forceImportCorpus;

    @Value("${rag.benchmark.import-batch-size:64}")
    private int importBatchSize;

    @Value("${rag.benchmark.import-max-rows:-1}")
    private long importMaxRows;

    @Value("${rag.benchmark.import-timeout-minutes:30}")
    private long importBatchTimeoutMinutes;

    @Test
    public void runAblationStudy() throws Exception {
        if (autoImportCorpus) {
            importCorpusIfNeeded();
        }

        BenchmarkRunConfig config = new BenchmarkRunConfig(
                datasetName,
                sampleSize,
                generationSampleSize,
                seed,
                topK,
                retrievalTimeoutMs,
                generationTimeoutMs,
                retries,
                concurrency,
                cooldownMs,
                failureWindowSize,
                failureRateThreshold,
                runGenerationStage);

        BenchmarkDataset dataset = loadDataset();
        if (dataset == null) {
            return;
        }

        List<String> retrievalSampleQids = sampleRetrievalQids(dataset, config);
        if (retrievalSampleQids.isEmpty()) {
            log.warn("No eligible qids found (query + qrels overlap is empty), benchmark exits.");
            return;
        }

        log.info(
                "Benchmark config: dataset={}, sampleSize={}, generationSampleSize={}, seed={}, topK={}, retries={}, cooldownMs={}, runGeneration={}",
                config.datasetName(), config.sampleSize(), config.generationSampleSize(), config.seed(), config.topK(),
                config.retries(), config.cooldownMs(), config.runGenerationStage());

        runRetrievalBenchmark(dataset, config, retrievalSampleQids);

        if (config.runGenerationStage()) {
            List<String> generationSampleQids = sampleGenerationQids(retrievalSampleQids, config);
            runGenerationSpotCheck(dataset, config, generationSampleQids);
        } else {
            log.info("Generation spot-check stage skipped. Set rag.benchmark.run-generation=true to enable.");
        }
    }

    private void runRetrievalBenchmark(BenchmarkDataset dataset, BenchmarkRunConfig config, List<String> sampledQids)
            throws IOException {
        Path outputDirectory = Paths.get(outputDir);
        Files.createDirectories(outputDirectory);

        Set<String> sampledQidSet = new HashSet<>(sampledQids);
        List<RetrievalVariantSummary> variantSummaries = new ArrayList<>();

        for (BenchmarkVariant variant : BenchmarkVariant.fixedMatrix()) {
            Path variantOutput = outputDirectory.resolve("retrieval_" + variant.id() + ".jsonl");
            ensureFileExists(variantOutput);

            Set<String> completedKeys = loadRetrievalResults(variantOutput).stream()
                    .filter(result -> belongsToCurrentRun(result, config, sampledQidSet))
                    .filter(RetrievalCaseResult::success)
                    .map(RetrievalCaseResult::cacheKey)
                    .collect(Collectors.toSet());

            boolean earlyStopped = false;
            Deque<Boolean> recentOutcomes = new ArrayDeque<>();

            for (String qid : sampledQids) {
                String query = dataset.queryByQid().get(qid);
                if (query == null || query.isBlank()) {
                    continue;
                }

                String cacheKey = buildCacheKey(config.datasetName(), qid, variant.id(), config.topK(), config.seed());
                if (completedKeys.contains(cacheKey)) {
                    continue;
                }

                RetrievalCaseResult caseResult = runRetrievalCaseWithRetry(config, variant, qid, query, cacheKey);
                appendJsonl(variantOutput, caseResult);
                completedKeys.add(cacheKey);

                updateRollingWindow(recentOutcomes, caseResult.success(), config.failureWindowSize());
                if (shouldEarlyStop(recentOutcomes, config)) {
                    earlyStopped = true;
                    log.warn("Early stop triggered for variant={} due to high recent failure rate.", variant.id());
                    break;
                }
                sleepCooldown(config.cooldownMs());
            }

            List<RetrievalCaseResult> runResults = dedupeByCacheKey(loadRetrievalResults(variantOutput).stream()
                    .filter(result -> belongsToCurrentRun(result, config, sampledQidSet))
                    .toList());

            RetrievalVariantSummary summary = buildVariantSummary(
                    variant.id(),
                    sampledQids.size(),
                    runResults,
                    dataset.qrelsByQid(),
                    earlyStopped);
            variantSummaries.add(summary);

            log.info(
                    "Retrieval variant finished: {} | completed={} success={} failure={} recall@10={} mrr@10={} ndcg@10={}",
                    variant.id(), summary.completedCases(), summary.successCount(), summary.failureCount(),
                    summary.recallAt10(), summary.mrrAt10(), summary.ndcgAt10());
        }

        RetrievalSummary summary = new RetrievalSummary(
                config.datasetName(),
                config.seed(),
                sampledQids.size(),
                config.topK(),
                Instant.now(),
                sampledQids,
                variantSummaries);

        Path summaryPath = outputDirectory.resolve("retrieval_summary.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
        log.info("Retrieval summary written to {}", summaryPath.toAbsolutePath());
    }

    private void runGenerationSpotCheck(BenchmarkDataset dataset, BenchmarkRunConfig config, List<String> sampledQids)
            throws IOException {
        if (sampledQids.isEmpty()) {
            log.warn("Generation stage skipped because sampled qids is empty.");
            return;
        }

        Path outputDirectory = Paths.get(outputDir);
        Files.createDirectories(outputDirectory);
        Path generationOutput = outputDirectory.resolve("generation_spotcheck.jsonl");
        ensureFileExists(generationOutput);

        Set<String> sampledQidSet = new HashSet<>(sampledQids);
        Set<String> completedKeys = loadGenerationResults(generationOutput).stream()
                .filter(result -> belongsToCurrentRun(result, config, sampledQidSet))
                .filter(GenerationCaseResult::success)
                .map(GenerationCaseResult::cacheKey)
                .collect(Collectors.toSet());

        for (BenchmarkVariant variant : BenchmarkVariant.fixedMatrix()) {
            boolean earlyStopped = false;
            Deque<Boolean> recentOutcomes = new ArrayDeque<>();

            for (String qid : sampledQids) {
                String query = dataset.queryByQid().get(qid);
                if (query == null || query.isBlank()) {
                    continue;
                }

                String cacheKey = buildCacheKey(config.datasetName(), qid, variant.id(), config.topK(), config.seed());
                if (completedKeys.contains(cacheKey)) {
                    continue;
                }

                GenerationCaseResult result = runGenerationCaseWithRetry(config, variant, qid, query, cacheKey);
                appendJsonl(generationOutput, result);
                completedKeys.add(cacheKey);

                updateRollingWindow(recentOutcomes, result.success(), config.failureWindowSize());
                if (shouldEarlyStop(recentOutcomes, config)) {
                    earlyStopped = true;
                    log.warn("Generation early stop triggered for variant={}.", variant.id());
                    break;
                }
                sleepCooldown(config.cooldownMs());
            }

            List<GenerationCaseResult> variantResults = dedupeByCacheKey(loadGenerationResults(generationOutput).stream()
                    .filter(result -> belongsToCurrentRun(result, config, sampledQidSet))
                    .filter(result -> variant.id().equals(result.variant()))
                    .toList());

            long successCount = variantResults.stream().filter(GenerationCaseResult::success).count();
            log.info("Generation variant finished: {} | completed={} success={} failure={} earlyStopped={}",
                    variant.id(), variantResults.size(), successCount, (variantResults.size() - successCount),
                    earlyStopped);
        }
    }

    private RetrievalCaseResult runRetrievalCaseWithRetry(
            BenchmarkRunConfig config, BenchmarkVariant variant, String qid, String query, String cacheKey) {
        long startedAt = System.currentTimeMillis();
        Throwable lastError = null;

        for (int attempt = 1; attempt <= config.retries() + 1; attempt++) {
            try {
                List<Document> docs = chatService.retrieveForEvaluation(
                        query, variant.useSparseSearch(), variant.useRerank(), config.topK())
                        .block(Duration.ofMillis(config.retrievalTimeoutMs()));

                if (docs == null) {
                    throw new IllegalStateException("retrieveForEvaluation returned null");
                }

                List<String> docIds = docs.stream()
                        .limit(config.topK())
                        .map(this::extractDocId)
                        .filter(id -> id != null && !id.isBlank())
                        .toList();

                return new RetrievalCaseResult(
                        cacheKey,
                        config.datasetName(),
                        variant.id(),
                        qid,
                        query,
                        docIds,
                        config.topK(),
                        config.seed(),
                        System.currentTimeMillis() - startedAt,
                        true,
                        null);
            } catch (Exception ex) {
                lastError = unwrap(ex);
                if (attempt <= config.retries()) {
                    log.warn("Retrieval failed, retrying... variant={}, qid={}, attempt={}/{}",
                            variant.id(), qid, attempt, config.retries() + 1, lastError);
                }
            }
        }

        return new RetrievalCaseResult(
                cacheKey,
                config.datasetName(),
                variant.id(),
                qid,
                query,
                List.of(),
                config.topK(),
                config.seed(),
                System.currentTimeMillis() - startedAt,
                false,
                safeError(lastError));
    }

    private GenerationCaseResult runGenerationCaseWithRetry(
            BenchmarkRunConfig config, BenchmarkVariant variant, String qid, String query, String cacheKey) {
        long startedAt = System.currentTimeMillis();
        Throwable lastError = null;

        for (int attempt = 1; attempt <= config.retries() + 1; attempt++) {
            try {
                EvaluationResultItem resultItem = chatService.evaluateQuery(
                        query,
                        "",
                        variant.toEvaluationConfig(),
                        baselinePrompt,
                        optimizedPrompt,
                        config.topK(),
                        "ollama")
                        .block(Duration.ofMillis(config.generationTimeoutMs()));

                if (resultItem == null || resultItem.generatedAnswer() == null) {
                    throw new IllegalStateException("evaluateQuery returned null result");
                }

                return new GenerationCaseResult(
                        cacheKey,
                        config.datasetName(),
                        variant.id(),
                        qid,
                        query,
                        resultItem.generatedAnswer(),
                        config.topK(),
                        config.seed(),
                        System.currentTimeMillis() - startedAt,
                        true,
                        null);
            } catch (Exception ex) {
                lastError = unwrap(ex);
                if (attempt <= config.retries()) {
                    log.warn("Generation failed, retrying... variant={}, qid={}, attempt={}/{}",
                            variant.id(), qid, attempt, config.retries() + 1, lastError);
                }
            }
        }

        return new GenerationCaseResult(
                cacheKey,
                config.datasetName(),
                variant.id(),
                qid,
                query,
                "",
                config.topK(),
                config.seed(),
                System.currentTimeMillis() - startedAt,
                false,
                safeError(lastError));
    }

    private void importCorpusIfNeeded() throws IOException {
        Path corpusPath = requirePath(corpusFilePath, "rag.benchmark.corpus-file");
        if (corpusPath == null) {
            throw new IllegalStateException("Cannot import corpus: corpus file is missing.");
        }

        Path markerPath = getImportMarkerPath();
        String markerLine = buildImportMarkerLine(corpusPath);
        if (!forceImportCorpus && Files.exists(markerPath)) {
            String existingMarker = Files.readString(markerPath, StandardCharsets.UTF_8).trim();
            if (markerLine.equals(existingMarker)) {
                log.info("Corpus import skipped (marker matched): {}", markerPath.toAbsolutePath());
                return;
            }
        }

        int batchSize = Math.max(importBatchSize, 1);
        Duration batchTimeout = Duration.ofMinutes(Math.max(importBatchTimeoutMinutes, 1));

        long importedRows = 0L;
        long start = System.currentTimeMillis();
        List<Document> batch = new ArrayList<>(batchSize);

        log.info("Starting corpus import to Milvus. collection={}, corpus={}, batchSize={}, maxRows={}, forceImport={}",
                benchmarkCollectionName, corpusPath.toAbsolutePath(), batchSize, importMaxRows, forceImportCorpus);

        try (BufferedReader reader = Files.newBufferedReader(corpusPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeLine(line);
                if (normalized.isBlank()) {
                    continue;
                }

                String[] parts = normalized.split("\\t", 2);
                if (parts.length < 2) {
                    continue;
                }

                String docId = parts[0].trim();
                String content = parts[1].trim();
                if (docId.isBlank() || content.isBlank()) {
                    continue;
                }
                if ("id".equalsIgnoreCase(docId) || "doc_id".equalsIgnoreCase(docId)) {
                    continue;
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("dataset", datasetName);
                metadata.put("source", "corpus.tsv");
                metadata.put("doc_id", docId);

                batch.add(Document.builder()
                        .id(docId)
                        .text(content)
                        .metadata(metadata)
                        .build());

                if (batch.size() >= batchSize) {
                    hybridVectorWriter.write(batch).block(batchTimeout);
                    importedRows += batch.size();
                    batch = new ArrayList<>(batchSize);
                    if (importedRows % 1000 == 0) {
                        log.info("Corpus import progress: importedRows={} elapsedMs={}",
                                importedRows, (System.currentTimeMillis() - start));
                    }
                    if (importMaxRows > 0 && importedRows >= importMaxRows) {
                        break;
                    }
                }
            }
        }

        if (!batch.isEmpty() && (importMaxRows <= 0 || importedRows < importMaxRows)) {
            hybridVectorWriter.write(batch).block(batchTimeout);
            importedRows += batch.size();
        }

        Files.createDirectories(markerPath.getParent());
        Files.writeString(markerPath, markerLine, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("Corpus import finished. importedRows={}, elapsedMs={}, marker={}",
                importedRows, (System.currentTimeMillis() - start), markerPath.toAbsolutePath());
    }

    private Path getImportMarkerPath() {
        String markerName = String.format("import_marker_%s_%s.txt", datasetName, benchmarkCollectionName);
        return Paths.get(outputDir).resolve(markerName);
    }

    private String buildImportMarkerLine(Path corpusPath) throws IOException {
        long size = Files.size(corpusPath);
        FileTime modifiedTime = Files.getLastModifiedTime(corpusPath);
        return String.join("|",
                "dataset=" + datasetName,
                "collection=" + benchmarkCollectionName,
                "path=" + corpusPath.toAbsolutePath(),
                "size=" + size,
                "mtime=" + modifiedTime.toMillis(),
                "batch=" + Math.max(importBatchSize, 1),
                "maxRows=" + importMaxRows);
    }

    private BenchmarkDataset loadDataset() throws IOException {
        Path corpusPath = requirePath(corpusFilePath, "rag.benchmark.corpus-file");
        Path queryPath = requirePath(queryFilePath, "rag.benchmark.query-file");
        Path qrelsPath = requirePath(qrelsFilePath, "rag.benchmark.qrels-file");
        if (corpusPath == null || queryPath == null || qrelsPath == null) {
            return null;
        }

        Set<String> corpusDocIds = loadCorpusDocIds(corpusPath);
        Map<String, String> queryByQid = loadQueryMap(queryPath);
        Map<String, Map<String, Integer>> qrelsByQid = loadQrelsMap(qrelsPath);

        if (queryByQid.isEmpty() || qrelsByQid.isEmpty()) {
            log.error("Dataset parsing failed. queryCount={} qrelsCount={}", queryByQid.size(), qrelsByQid.size());
            return null;
        }

        long missingInCorpus = qrelsByQid.values().stream()
                .flatMap(map -> map.keySet().stream())
                .filter(docId -> !corpusDocIds.contains(docId))
                .count();
        if (missingInCorpus > 0) {
            log.warn("Found {} qrels doc ids not present in corpus.tsv. Metrics may look lower than expected.",
                    missingInCorpus);
        }

        log.info("Dataset loaded: corpusDocCount={}, queryCount={}, qrelsQueryCount={}",
                corpusDocIds.size(), queryByQid.size(), qrelsByQid.size());
        return new BenchmarkDataset(datasetName, queryByQid, qrelsByQid, corpusDocIds);
    }

    private Path requirePath(String rawPath, String propertyName) {
        if (rawPath == null || rawPath.isBlank()) {
            log.error("Missing required property: {}", propertyName);
            return null;
        }
        Path path = Paths.get(rawPath);
        if (!Files.exists(path)) {
            log.error("Dataset file does not exist: {} ({})", path.toAbsolutePath(), propertyName);
            return null;
        }
        return path;
    }

    private Set<String> loadCorpusDocIds(Path corpusPath) throws IOException {
        Set<String> docIds = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(corpusPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeLine(line);
                if (normalized.isBlank()) {
                    continue;
                }
                String[] parts = normalized.split("\\t", 2);
                String docId = parts[0].trim();
                if (docId.isBlank() || "id".equalsIgnoreCase(docId) || "doc_id".equalsIgnoreCase(docId)) {
                    continue;
                }
                docIds.add(docId);
            }
        }
        return docIds;
    }

    private Map<String, String> loadQueryMap(Path queryPath) throws IOException {
        Map<String, String> queryByQid = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(queryPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeLine(line);
                if (normalized.isBlank()) {
                    continue;
                }

                String[] parts = normalized.split("\\t", 2);
                if (parts.length < 2) {
                    int firstWhitespace = normalized.indexOf(' ');
                    if (firstWhitespace <= 0 || firstWhitespace == normalized.length() - 1) {
                        continue;
                    }
                    parts = new String[] {
                            normalized.substring(0, firstWhitespace),
                            normalized.substring(firstWhitespace + 1)
                    };
                }

                String qid = parts[0].trim();
                String query = parts[1].trim();
                if (qid.isBlank() || query.isBlank() || "qid".equalsIgnoreCase(qid)) {
                    continue;
                }
                queryByQid.put(qid, query);
            }
        }
        return queryByQid;
    }

    private Map<String, Map<String, Integer>> loadQrelsMap(Path qrelsPath) throws IOException {
        Map<String, Map<String, Integer>> qrelsByQid = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(qrelsPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeLine(line);
                if (normalized.isBlank()) {
                    continue;
                }

                String[] parts = normalized.split("\\t");
                if (parts.length < 4) {
                    parts = normalized.split("\\s+");
                }
                if (parts.length < 4) {
                    continue;
                }

                String qid = parts[0].trim();
                String docId = parts[2].trim();
                if (qid.isBlank() || docId.isBlank() || "qid".equalsIgnoreCase(qid)) {
                    continue;
                }

                int relevance;
                try {
                    relevance = Integer.parseInt(parts[3].trim());
                } catch (NumberFormatException ex) {
                    continue;
                }

                qrelsByQid.computeIfAbsent(qid, ignored -> new HashMap<>()).put(docId, relevance);
            }
        }
        return qrelsByQid;
    }

    private List<String> sampleRetrievalQids(BenchmarkDataset dataset, BenchmarkRunConfig config) {
        List<String> qids = dataset.queryByQid().keySet().stream()
                .filter(dataset.qrelsByQid()::containsKey)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(qids, new Random(config.seed()));
        return qids.subList(0, Math.min(config.sampleSize(), qids.size()));
    }

    private List<String> sampleGenerationQids(List<String> retrievalQids, BenchmarkRunConfig config) {
        List<String> copied = new ArrayList<>(retrievalQids);
        Collections.shuffle(copied, new Random(config.seed() + 1));
        return copied.subList(0, Math.min(config.generationSampleSize(), copied.size()));
    }

    private void ensureFileExists(Path filePath) throws IOException {
        Files.createDirectories(filePath.getParent());
        if (!Files.exists(filePath)) {
            Files.createFile(filePath);
        }
    }

    private void appendJsonl(Path outputFile, Object result) throws IOException {
        String line = objectMapper.writeValueAsString(result) + System.lineSeparator();
        Files.writeString(outputFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private List<RetrievalCaseResult> loadRetrievalResults(Path outputFile) throws IOException {
        return loadJsonl(outputFile, RetrievalCaseResult.class);
    }

    private List<GenerationCaseResult> loadGenerationResults(Path outputFile) throws IOException {
        return loadJsonl(outputFile, GenerationCaseResult.class);
    }

    private <T> List<T> loadJsonl(Path outputFile, Class<T> clazz) throws IOException {
        if (!Files.exists(outputFile)) {
            return List.of();
        }

        List<T> results = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(outputFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    results.add(objectMapper.readValue(line, clazz));
                } catch (Exception ex) {
                    log.warn("Skip malformed JSONL line in {}: {}", outputFile.toAbsolutePath(), ex.getMessage());
                }
            }
        }
        return results;
    }

    private boolean belongsToCurrentRun(
            RetrievalCaseResult result, BenchmarkRunConfig config, Set<String> sampledQids) {
        return config.datasetName().equals(result.dataset())
                && config.seed() == result.seed()
                && config.topK() == result.topK()
                && sampledQids.contains(result.qid());
    }

    private boolean belongsToCurrentRun(
            GenerationCaseResult result, BenchmarkRunConfig config, Set<String> sampledQids) {
        return config.datasetName().equals(result.dataset())
                && config.seed() == result.seed()
                && config.topK() == result.topK()
                && sampledQids.contains(result.qid());
    }

    private <T> List<T> dedupeByCacheKey(List<T> results) {
        Map<String, T> deduped = new LinkedHashMap<>();
        for (T result : results) {
            String key;
            if (result instanceof RetrievalCaseResult retrievalCaseResult) {
                key = retrievalCaseResult.cacheKey();
            } else if (result instanceof GenerationCaseResult generationCaseResult) {
                key = generationCaseResult.cacheKey();
            } else {
                continue;
            }
            deduped.put(key, result);
        }
        return new ArrayList<>(deduped.values());
    }

    private RetrievalVariantSummary buildVariantSummary(
            String variant,
            int plannedCases,
            List<RetrievalCaseResult> runResults,
            Map<String, Map<String, Integer>> qrelsByQid,
            boolean earlyStopped) {
        int completedCases = runResults.size();
        int successCount = (int) runResults.stream().filter(RetrievalCaseResult::success).count();
        int failureCount = completedCases - successCount;

        List<Long> successfulLatencies = runResults.stream()
                .filter(RetrievalCaseResult::success)
                .map(RetrievalCaseResult::latencyMs)
                .sorted()
                .toList();

        double recallAt5 = averageRecallAtK(runResults, qrelsByQid, 5);
        double recallAt10 = averageRecallAtK(runResults, qrelsByQid, 10);
        double mrrAt10 = averageMrrAtK(runResults, qrelsByQid, 10);
        double ndcgAt10 = averageNdcgAtK(runResults, qrelsByQid, 10);

        return new RetrievalVariantSummary(
                variant,
                plannedCases,
                completedCases,
                successCount,
                failureCount,
                earlyStopped,
                round4(completedCases == 0 ? 0.0 : successCount / (double) completedCases),
                round4(recallAt5),
                round4(recallAt10),
                round4(mrrAt10),
                round4(ndcgAt10),
                round4(successfulLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0)),
                percentile(successfulLatencies, 0.95));
    }

    private double averageRecallAtK(List<RetrievalCaseResult> results, Map<String, Map<String, Integer>> qrelsByQid,
            int k) {
        double sum = 0.0;
        int count = 0;

        for (RetrievalCaseResult result : results) {
            if (!result.success()) {
                continue;
            }
            Map<String, Integer> relMap = qrelsByQid.get(result.qid());
            if (relMap == null || relMap.isEmpty()) {
                continue;
            }
            Set<String> relevantDocs = relMap.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (relevantDocs.isEmpty()) {
                continue;
            }

            long hits = result.retrievedDocIds().stream()
                    .limit(k)
                    .filter(relevantDocs::contains)
                    .count();
            sum += hits / (double) relevantDocs.size();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double averageMrrAtK(List<RetrievalCaseResult> results, Map<String, Map<String, Integer>> qrelsByQid,
            int k) {
        double sum = 0.0;
        int count = 0;

        for (RetrievalCaseResult result : results) {
            if (!result.success()) {
                continue;
            }
            Map<String, Integer> relMap = qrelsByQid.get(result.qid());
            if (relMap == null || relMap.isEmpty()) {
                continue;
            }
            Set<String> relevantDocs = relMap.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (relevantDocs.isEmpty()) {
                continue;
            }

            double rr = 0.0;
            List<String> ranked = result.retrievedDocIds();
            for (int i = 0; i < Math.min(ranked.size(), k); i++) {
                if (relevantDocs.contains(ranked.get(i))) {
                    rr = 1.0 / (i + 1);
                    break;
                }
            }
            sum += rr;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double averageNdcgAtK(List<RetrievalCaseResult> results, Map<String, Map<String, Integer>> qrelsByQid,
            int k) {
        double sum = 0.0;
        int count = 0;

        for (RetrievalCaseResult result : results) {
            if (!result.success()) {
                continue;
            }
            Map<String, Integer> relMap = qrelsByQid.get(result.qid());
            if (relMap == null || relMap.isEmpty()) {
                continue;
            }

            double dcg = 0.0;
            List<String> ranked = result.retrievedDocIds();
            for (int i = 0; i < Math.min(ranked.size(), k); i++) {
                int rel = relMap.getOrDefault(ranked.get(i), 0);
                if (rel > 0) {
                    dcg += (Math.pow(2.0, rel) - 1.0) / log2(i + 2.0);
                }
            }

            List<Integer> idealRel = relMap.values().stream()
                    .filter(rel -> rel > 0)
                    .sorted(Collections.reverseOrder())
                    .limit(k)
                    .toList();
            if (idealRel.isEmpty()) {
                continue;
            }

            double idcg = 0.0;
            for (int i = 0; i < idealRel.size(); i++) {
                idcg += (Math.pow(2.0, idealRel.get(i)) - 1.0) / log2(i + 2.0);
            }
            if (idcg > 0) {
                sum += (dcg / idcg);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private long percentile(List<Long> sortedLatencies, double percentile) {
        if (sortedLatencies == null || sortedLatencies.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * sortedLatencies.size()) - 1;
        index = Math.max(0, Math.min(index, sortedLatencies.size() - 1));
        return sortedLatencies.get(index);
    }

    private boolean shouldEarlyStop(Deque<Boolean> recentOutcomes, BenchmarkRunConfig config) {
        if (recentOutcomes.size() < config.failureWindowSize()) {
            return false;
        }
        long failed = recentOutcomes.stream().filter(success -> !success).count();
        double failureRate = failed / (double) recentOutcomes.size();
        return failureRate > config.earlyStopFailureRate();
    }

    private void updateRollingWindow(Deque<Boolean> recentOutcomes, boolean success, int windowSize) {
        recentOutcomes.addLast(success);
        while (recentOutcomes.size() > windowSize) {
            recentOutcomes.removeFirst();
        }
    }

    private void sleepCooldown(long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(cooldownMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildCacheKey(String dataset, String qid, String variant, int topK, long seed) {
        return String.join("|", dataset, qid, variant, "topK=" + topK, "seed=" + seed);
    }

    private String extractDocId(Document document) {
        if (document == null) {
            return "";
        }
        if (document.getId() != null && !document.getId().isBlank()) {
            return document.getId();
        }
        Object docIdInMeta = document.getMetadata().get("doc_id");
        if (docIdInMeta != null && !docIdInMeta.toString().isBlank()) {
            return docIdInMeta.toString();
        }
        Object source = document.getMetadata().get("file_name");
        return source == null ? "" : source.toString();
    }

    private String normalizeLine(String line) {
        if (line == null) {
            return "";
        }
        String cleaned = line.trim();
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current instanceof RuntimeException) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    private String safeError(Throwable throwable) {
        if (throwable == null) {
            return "unknown-error";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        } else {
            message = throwable.getClass().getSimpleName() + ": " + message;
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0d);
    }

    private record BenchmarkDataset(
            String datasetName,
            Map<String, String> queryByQid,
            Map<String, Map<String, Integer>> qrelsByQid,
            Set<String> corpusDocIds) {
    }

    private record RetrievalVariantSummary(
            String variant,
            int plannedCases,
            int completedCases,
            int successCount,
            int failureCount,
            boolean earlyStopped,
            double successRate,
            double recallAt5,
            double recallAt10,
            double mrrAt10,
            double ndcgAt10,
            double avgLatencyMs,
            long p95LatencyMs) {
    }

    private record RetrievalSummary(
            String dataset,
            long seed,
            int sampleSize,
            int topK,
            Instant generatedAt,
            List<String> sampledQids,
            List<RetrievalVariantSummary> variants) {
    }
}
