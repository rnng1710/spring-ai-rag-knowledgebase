package net.topikachu.rag.autoevaluation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RagasEvaluationResponse(List<RagasEvaluationResult> items) {

    public record RagasEvaluationResult(
            @JsonProperty("evaluation_id") String evaluationId,
            Double faithfulness,
            @JsonProperty("answer_relevancy") Double answerRelevancy,
            @JsonProperty("context_precision") Double contextPrecision,
            @JsonProperty("context_recall") Double contextRecall,
            @JsonProperty("answer_correctness") Double answerCorrectness,
            @JsonProperty("answer_similarity") Double answerSimilarity,
            String error) {
    }
}
