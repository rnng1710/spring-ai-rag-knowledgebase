package net.topikachu.rag.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentReviewDecision(
        String verdict,
        @JsonAlias({"revision_suggestion", "reason"}) String critique,
        String query,
        String followup
) {
}
