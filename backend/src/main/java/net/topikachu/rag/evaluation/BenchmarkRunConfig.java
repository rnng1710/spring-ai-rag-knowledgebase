package net.topikachu.rag.evaluation;

/**
 * Runtime config for reproducible benchmark execution.
 */
public record BenchmarkRunConfig(
        String datasetName,
        int sampleSize,
        int generationSampleSize,
        long seed,
        int topK,
        long retrievalTimeoutMs,
        long generationTimeoutMs,
        int retries,
        int concurrency,
        long cooldownMs,
        int failureWindowSize,
        double earlyStopFailureRate,
        boolean runGenerationStage) {

    public BenchmarkRunConfig {
        if (datasetName == null || datasetName.isBlank()) {
            datasetName = "default-dataset";
        }
        sampleSize = Math.max(sampleSize, 1);
        generationSampleSize = Math.max(generationSampleSize, 1);
        topK = Math.max(topK, 10);
        retrievalTimeoutMs = Math.max(retrievalTimeoutMs, 1_000L);
        generationTimeoutMs = Math.max(generationTimeoutMs, 5_000L);
        retries = Math.max(retries, 0);
        concurrency = 1; // force serial execution for local slow models
        cooldownMs = Math.max(cooldownMs, 0L);
        failureWindowSize = Math.max(failureWindowSize, 1);
        if (earlyStopFailureRate <= 0 || earlyStopFailureRate >= 1) {
            earlyStopFailureRate = 0.30d;
        }
    }
}
