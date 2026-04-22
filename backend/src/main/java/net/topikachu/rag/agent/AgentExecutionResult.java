package net.topikachu.rag.agent;

import java.util.List;

public record AgentExecutionResult(
        List<EvidenceSnapshot> sources,
        List<AgentNote> notes,
        String followupPrompt,
        List<String> followupOptions,
        String draft,
        String finalInstruction,
        String answerMode,
        boolean revised
) {
    public boolean isFollowup() {
        return followupOptions != null && !followupOptions.isEmpty();
    }

    public boolean isRefusal() {
        return "refusal".equalsIgnoreCase(answerMode);
    }
}
