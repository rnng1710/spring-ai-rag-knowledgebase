package net.topikachu.rag.evaluation;

import java.util.Arrays;
import java.util.List;

/**
 * Fixed benchmark matrix for retrieval/rerank ablation.
 */
public enum BenchmarkVariant {
    DENSE_NO_RERANK("dense_no_rerank", false, false),
    HYBRID_NO_RERANK("hybrid_no_rerank", true, false),
    DENSE_RERANK("dense_rerank", false, true),
    HYBRID_RERANK("hybrid_rerank", true, true);

    private final String id;
    private final boolean useSparseSearch;
    private final boolean useRerank;

    BenchmarkVariant(String id, boolean useSparseSearch, boolean useRerank) {
        this.id = id;
        this.useSparseSearch = useSparseSearch;
        this.useRerank = useRerank;
    }

    public String id() {
        return id;
    }

    public boolean useSparseSearch() {
        return useSparseSearch;
    }

    public boolean useRerank() {
        return useRerank;
    }

    public EvaluationConfig toEvaluationConfig() {
        return new EvaluationConfig(useSparseSearch, useRerank, false);
    }

    public static List<BenchmarkVariant> fixedMatrix() {
        return Arrays.asList(values());
    }
}
