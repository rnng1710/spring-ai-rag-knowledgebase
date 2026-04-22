package net.topikachu.rag.agent;

import java.util.List;

public record AgentResolution(
        String type,
        String answerMode,
        String draftAnswer,
        String finalInstruction,
        List<String> selectedEvidenceIds
) {
}
