package net.topikachu.rag.autoevaluation.dto;

import java.time.LocalDateTime;

public record AutoRunItem(
        String runId,
        String status,
        int totalSamples,
        int successCount,
        int failureCount,
        Double avgFaithfulness,
        Double avgAnswerRelevancy,
        Double avgContextPrecision,
        Double avgContextRecall,
        Double avgAnswerCorrectness,
        Double avgAnswerSimilarity,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage) {
}
