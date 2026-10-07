package net.topikachu.rag.evaluation.dto;

import java.util.List;

public record EvaluationStats(
        long total,
        double approvalRate,
        int approvalTrend,
        long pending,
        String topFailureMode,
        long topFailureCount,
        List<TrendPoint> trend) {
}
