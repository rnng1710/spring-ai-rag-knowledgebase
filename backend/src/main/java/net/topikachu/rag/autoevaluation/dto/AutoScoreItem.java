package net.topikachu.rag.autoevaluation.dto;

import java.time.LocalDateTime;

public record AutoScoreItem(
        Long id,
        String evaluationId,
        String runId,
        Double faithfulness,
        Double answerRelevancy,
        Double contextPrecision,
        Double contextRecall,
        Double answerCorrectness,
        Double answerSimilarity,
        String referenceAnswerHash,
        LocalDateTime createDate) {
}
