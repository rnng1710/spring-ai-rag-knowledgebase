package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;

import java.util.List;

public record AgentExecutionResult(
        List<EvidenceSnapshot> sources,
        List<ParentContextBlock> parentContexts,
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
