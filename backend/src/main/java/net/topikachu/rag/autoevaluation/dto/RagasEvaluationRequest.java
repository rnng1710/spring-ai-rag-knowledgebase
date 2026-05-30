package net.topikachu.rag.autoevaluation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RagasEvaluationRequest(List<RagasEvaluationItem> items) {

    public record RagasEvaluationItem(
            @JsonProperty("evaluation_id") String evaluationId,
            @JsonProperty("user_input") String userInput,
            String response,
            @JsonProperty("retrieved_contexts") List<String> retrievedContexts,
            String reference) {
    }
}
