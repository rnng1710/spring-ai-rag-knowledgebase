package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;

import java.time.Instant;
import java.util.List;

public record AgentExecutionSnapshot(
        String requestId,
        String conversationId,
        String msgId,
        String originalUserInput,
        List<String> selectedTags,
        List<String> selectedSpaceCodes,
        Instant startedAt,
        int toolCalls,
        int searchInvocationCount,
        List<AgentNote> notes,
        List<EvidenceSnapshot> retrievedEvidence,
        List<ParentContextBlock> retrievedParentContexts,
        List<RetrievalHistoryEntry> retrievalHistory,
        RetrievalGapType retrievalGapType,
        boolean improvableByFollowup,
        List<String> allowedFocusTypes,
        FollowupOptionsResult followupOptionsCandidate) {

    public AgentExecutionSnapshot {
        selectedTags = selectedTags == null ? List.of() : List.copyOf(selectedTags);
        selectedSpaceCodes = selectedSpaceCodes == null ? List.of() : List.copyOf(selectedSpaceCodes);
        notes = notes == null ? List.of() : List.copyOf(notes);
        retrievedEvidence = retrievedEvidence == null ? List.of() : List.copyOf(retrievedEvidence);
        retrievedParentContexts = retrievedParentContexts == null ? List.of() : List.copyOf(retrievedParentContexts);
        retrievalHistory = retrievalHistory == null ? List.of() : List.copyOf(retrievalHistory);
        retrievalGapType = retrievalGapType == null ? RetrievalGapType.NOT_IMPROVABLE : retrievalGapType;
        allowedFocusTypes = allowedFocusTypes == null ? List.of() : List.copyOf(allowedFocusTypes);
    }

    public boolean hasEffectiveRetrievalHistory() {
        return retrievalHistory.stream()
                .anyMatch(entry -> "ok".equals(entry.status()) || "no_result".equals(entry.status()));
    }
}
