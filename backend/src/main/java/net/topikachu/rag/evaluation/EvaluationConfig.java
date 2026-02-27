package net.topikachu.rag.evaluation;

/**
 * Configuration for a single RAG evaluation run.
 * Used to turn features on/off for ablation studies.
 */
public record EvaluationConfig(
        boolean useSparseSearch, // If true, use Hybrid Search (Dense + Sparse). If false, use only Dense.
        boolean useRerank, // If true, pass results through BGE-Reranker.
        boolean useOptimizedPrompt// If true, use strict hallucination-prevention prompt. If false, use baseline.
) {
}
