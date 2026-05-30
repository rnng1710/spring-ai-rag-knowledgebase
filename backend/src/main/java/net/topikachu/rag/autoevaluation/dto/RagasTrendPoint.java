package net.topikachu.rag.autoevaluation.dto;

public record RagasTrendPoint(
        String day,
        Double faithfulness,
        Double answerRelevancy,
        Double contextPrecision,
        Double contextRecall,
        Double answerCorrectness,
        Double answerSimilarity) {
}
