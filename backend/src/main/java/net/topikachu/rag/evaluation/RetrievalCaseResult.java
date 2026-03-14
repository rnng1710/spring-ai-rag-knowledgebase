package net.topikachu.rag.evaluation;

import java.util.List;

/**
 * Per-query retrieval benchmark result.
 */
public record RetrievalCaseResult(
        String cacheKey,
        String dataset,
        String variant,
        String qid,
        String query,
        List<String> retrievedDocIds,
        int topK,
        long seed,
        long latencyMs,
        boolean success,
        String error) {
}
