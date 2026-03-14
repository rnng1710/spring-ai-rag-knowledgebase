package net.topikachu.rag.evaluation;

/**
 * Per-query generation spot-check result.
 */
public record GenerationCaseResult(
        String cacheKey,
        String dataset,
        String variant,
        String qid,
        String query,
        String answer,
        int topK,
        long seed,
        long latencyMs,
        boolean success,
        String error) {
}
