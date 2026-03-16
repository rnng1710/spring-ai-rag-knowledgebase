package net.topikachu.rag.agent;

import org.springframework.ai.document.Document;

import java.util.List;

public record AgentExecutionResult(
        List<Document> sources,
        List<AgentNote> notes,
        String followup,
        String draft,
        String finalInstruction,
        boolean revised
) {
    public boolean isFollowup() {
        return followup != null && !followup.isBlank();
    }
}
