package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentExecutionContext {

    private final String requestId;
    private final String conversationId;
    private final String msgId;
    private final String originalUserInput;
    private final List<String> selectedTags;
    private final List<String> selectedSpaceCodes;
    private final Instant startedAt;
    private final AgentExecutionAccumulator accumulator = new AgentExecutionAccumulator();

    private static final class AgentExecutionAccumulator {
        private long sequence;
        private int toolCalls;
        private int searchInvocationCount;
        private List<EvidenceSnapshot> retrievedEvidence = List.of();
        private List<ParentContextBlock> retrievedParentContexts = List.of();
        private final Map<String, Integer> repeatedQueryCounts = new LinkedHashMap<>();
        private final List<RetrievalHistoryEntry> retrievalHistory = new ArrayList<>();
        private final List<AgentNote> notes = new ArrayList<>();
        private RetrievalGapType retrievalGapType = RetrievalGapType.NOT_IMPROVABLE;
        private boolean improvableByFollowup;
        private List<String> allowedFocusTypes = List.of();
        private boolean followupToolAttempted;
        private FollowupOptionsResult followupOptionsCandidate;
    }

    public AgentExecutionContext(String requestId,
                                 String conversationId,
                                 String msgId,
                                 String originalUserInput,
                                 List<String> selectedTags,
                                 List<String> selectedSpaceCodes) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.msgId = msgId;
        this.originalUserInput = originalUserInput;
        this.selectedTags = selectedTags == null ? List.of() : List.copyOf(selectedTags);
        this.selectedSpaceCodes = selectedSpaceCodes == null ? List.of() : List.copyOf(selectedSpaceCodes);
        this.startedAt = Instant.now();
    }

    public AgentExecutionContext(String requestId,
                                 String conversationId,
                                 String msgId,
                                 String originalUserInput,
                                 List<String> selectedTags) {
        this(requestId, conversationId, msgId, originalUserInput, selectedTags, List.of());
    }

    public String requestId() {
        return requestId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String msgId() {
        return msgId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String originalUserInput() {
        return originalUserInput;
    }

    public List<String> selectedTags() {
        return selectedTags;
    }

    public List<String> selectedSpaceCodes() {
        return selectedSpaceCodes;
    }

    public synchronized AgentExecutionSnapshot snapshot() {
        return new AgentExecutionSnapshot(
                requestId,
                conversationId,
                msgId,
                originalUserInput,
                selectedTags,
                selectedSpaceCodes,
                startedAt,
                accumulator.toolCalls,
                accumulator.searchInvocationCount,
                accumulator.notes,
                accumulator.retrievedEvidence,
                accumulator.retrievedParentContexts,
                accumulator.retrievalHistory,
                accumulator.retrievalGapType,
                accumulator.improvableByFollowup,
                accumulator.allowedFocusTypes,
                accumulator.followupOptionsCandidate);
    }

    public synchronized void recordNote(AgentStage stage, String kind, String text) {
        accumulator.notes.add(new AgentNote(++accumulator.sequence, stage, kind, text, System.currentTimeMillis()));
    }

    public void addNote(AgentStage stage, String kind, String text) {
        recordNote(stage, kind, text);
    }

    public synchronized void addRetrievedEvidence(List<EvidenceSnapshot> snapshots, int maxEvidenceCount) {
        accumulator.retrievedEvidence = AgentEvidenceSelector.mergeEvidence(
                accumulator.retrievedEvidence,
                snapshots,
                maxEvidenceCount);
    }

    public synchronized void addRetrievedParentContexts(List<ParentContextBlock> contexts) {
        accumulator.retrievedParentContexts = AgentEvidenceSelector.mergeParentContexts(
                accumulator.retrievedParentContexts,
                contexts);
    }

    public synchronized List<AgentNote> notes() {
        return snapshot().notes();
    }

    public synchronized List<EvidenceSnapshot> retrievedEvidence() {
        return snapshot().retrievedEvidence();
    }

    public synchronized List<ParentContextBlock> retrievedParentContexts() {
        return snapshot().retrievedParentContexts();
    }

    public synchronized List<RetrievalHistoryEntry> retrievalHistory() {
        return snapshot().retrievalHistory();
    }

    public synchronized boolean tryConsumeToolBudget(int maxToolCalls) {
        if (accumulator.toolCalls >= maxToolCalls) {
            return false;
        }
        accumulator.toolCalls++;
        return true;
    }

    public synchronized int incrementSearchInvocationCount() {
        accumulator.searchInvocationCount++;
        return accumulator.searchInvocationCount;
    }

    public synchronized int searchInvocationCount() {
        return accumulator.searchInvocationCount;
    }

    public synchronized boolean allowQueryInvocation(String normalizedKey, int maxRepeatedQueryCount) {
        int count = accumulator.repeatedQueryCounts.getOrDefault(normalizedKey, 0);
        if (count >= maxRepeatedQueryCount) {
            return false;
        }
        accumulator.repeatedQueryCounts.put(normalizedKey, count + 1);
        return true;
    }

    public synchronized void recordSearchResult(String query,
                                                String normalizedQueryKey,
                                                List<String> tags,
                                                Integer topK,
                                                String status,
                                                int resultCount,
                                                List<String> retrievedEvidenceIds,
                                                List<EvidenceSnapshot> evidence,
                                                List<ParentContextBlock> parentContexts,
                                                RetrievalAssessment assessment,
                                                int maxEvidenceCount) {
        recordRetrieval(query, normalizedQueryKey, tags, topK, status, resultCount, retrievedEvidenceIds);
        addRetrievedEvidence(evidence, maxEvidenceCount);
        addRetrievedParentContexts(parentContexts);
        if (assessment != null) {
            updateRetrievalAssessment(assessment.gapType(), assessment.improvableByFollowup(), assessment.allowedFocusTypes());
        }
    }

    public synchronized void recordRetrieval(String query,
                                             String normalizedQueryKey,
                                             List<String> tags,
                                             Integer topK,
                                             String status,
                                             int resultCount,
                                             List<String> retrievedEvidenceIds) {
        accumulator.retrievalHistory.add(new RetrievalHistoryEntry(
                query,
                normalizedQueryKey,
                tags == null ? List.of() : List.copyOf(tags),
                topK,
                status,
                resultCount,
                retrievedEvidenceIds == null ? List.of() : List.copyOf(retrievedEvidenceIds),
                System.currentTimeMillis()));
    }

    public synchronized boolean hasEffectiveRetrievalHistory() {
        return snapshot().hasEffectiveRetrievalHistory();
    }

    public synchronized void updateRetrievalAssessment(RetrievalGapType gapType,
                                                       boolean improvableByFollowup,
                                                       List<String> allowedFocusTypes) {
        accumulator.retrievalGapType = gapType == null ? RetrievalGapType.NOT_IMPROVABLE : gapType;
        accumulator.improvableByFollowup = improvableByFollowup;
        accumulator.allowedFocusTypes = allowedFocusTypes == null ? List.of() : List.copyOf(allowedFocusTypes);
    }

    public synchronized RetrievalGapType retrievalGapType() {
        return accumulator.retrievalGapType;
    }

    public synchronized boolean improvableByFollowup() {
        return accumulator.improvableByFollowup;
    }

    public synchronized List<String> allowedFocusTypes() {
        return List.copyOf(accumulator.allowedFocusTypes);
    }

    public synchronized boolean markFollowupToolAttempted() {
        if (accumulator.followupToolAttempted) {
            return false;
        }
        accumulator.followupToolAttempted = true;
        return true;
    }

    public synchronized FollowupOptionsResult followupOptionsCandidate() {
        return accumulator.followupOptionsCandidate;
    }

    public synchronized void recordFollowupCandidate(FollowupOptionsResult candidate) {
        accumulator.followupOptionsCandidate = candidate;
    }

}
