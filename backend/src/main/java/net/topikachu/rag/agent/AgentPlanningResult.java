package net.topikachu.rag.agent;

import org.springframework.ai.document.Document;

import java.util.List;

public record AgentPlanningResult(List<Document> sources, String followup) {
    public boolean isFollowup() {
        return followup != null && !followup.isBlank();
    }
}
