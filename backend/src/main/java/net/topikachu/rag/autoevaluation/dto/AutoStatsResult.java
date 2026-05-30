package net.topikachu.rag.autoevaluation.dto;

import java.util.List;

public record AutoStatsResult(
        AutoRunItem lastRun,
        long totalEvaluated,
        Double avgFaithfulness,
        Double avgAnswerRelevancy,
        Double avgContextPrecision,
        Double avgContextRecall,
        Double avgAnswerCorrectness,
        Double avgAnswerSimilarity,
        List<RagasTrendPoint> trend) {
}
