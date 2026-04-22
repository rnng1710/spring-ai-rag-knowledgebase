package net.topikachu.rag.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AgentExecutionContext {

    private final String requestId;
    private final String conversationId;
    private final String msgId;
    private final String originalUserInput;
    private final List<String> selectedTags;
    private final Instant startedAt;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger searchInvocationCount = new AtomicInteger();
    private final Map<String, EvidenceSnapshot> retrievedEvidence = new LinkedHashMap<>();
    private final Map<String, Integer> repeatedQueryCounts = new LinkedHashMap<>();
    private final List<RetrievalHistoryEntry> retrievalHistory = new ArrayList<>();
    private final List<AgentNote> notes = new ArrayList<>();
    private RetrievalGapType retrievalGapType = RetrievalGapType.NOT_IMPROVABLE;
    private boolean improvableByFollowup;
    private List<String> allowedFocusTypes = List.of();
    private boolean followupToolAttempted;
    private FollowupOptionsResult followupOptionsCandidate;

    public AgentExecutionContext(String requestId,
                                 String conversationId,
                                 String msgId,
                                 String originalUserInput,
                                 List<String> selectedTags) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.msgId = msgId;
        this.originalUserInput = originalUserInput;
        this.selectedTags = selectedTags == null ? List.of() : List.copyOf(selectedTags);
        this.startedAt = Instant.now();
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

    public synchronized void addNote(AgentStage stage, String kind, String text) {
        notes.add(new AgentNote(sequence.incrementAndGet(), stage, kind, text, System.currentTimeMillis()));
    }

    public synchronized void addRetrievedEvidence(List<EvidenceSnapshot> snapshots, int maxEvidenceCount) {
        if (snapshots == null) {
            return;
        }
        for (EvidenceSnapshot snapshot : snapshots) {
            if (retrievedEvidence.size() >= maxEvidenceCount) {
                break;
            }
            if (snapshot == null || snapshot.id() == null || snapshot.id().isBlank()) {
                continue;
            }
            retrievedEvidence.putIfAbsent(snapshot.id(), snapshot);
        }
    }

    public synchronized List<EvidenceSnapshot> selectEvidence(List<String> selectedEvidenceIds) {
        if (selectedEvidenceIds == null || selectedEvidenceIds.isEmpty()) {
            return List.of();
        }
        Set<String> uniqueIds = new LinkedHashSet<>();
        List<EvidenceSnapshot> selected = new ArrayList<>();
        for (String selectedEvidenceId : selectedEvidenceIds) {
            if (selectedEvidenceId == null || selectedEvidenceId.isBlank() || !uniqueIds.add(selectedEvidenceId)) {
                continue;
            }
            EvidenceSnapshot snapshot = retrievedEvidence.get(selectedEvidenceId);
            if (snapshot != null) {
                selected.add(snapshot);
            }
        }
        return List.copyOf(selected);
    }

    public synchronized List<AgentNote> notes() {
        return List.copyOf(notes);
    }

    public synchronized List<EvidenceSnapshot> retrievedEvidence() {
        return List.copyOf(retrievedEvidence.values());
    }

    public synchronized List<RetrievalHistoryEntry> retrievalHistory() {
        return List.copyOf(retrievalHistory);
    }

    public int incrementToolCalls() {
        return toolCalls.incrementAndGet();
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int incrementSearchInvocationCount() {
        return searchInvocationCount.incrementAndGet();
    }

    public int searchInvocationCount() {
        return searchInvocationCount.get();
    }

    public synchronized boolean allowQueryInvocation(String normalizedKey, int maxRepeatedQueryCount) {
        int count = repeatedQueryCounts.getOrDefault(normalizedKey, 0);
        if (count >= maxRepeatedQueryCount) {
            return false;
        }
        repeatedQueryCounts.put(normalizedKey, count + 1);
        return true;
    }

    public synchronized void recordRetrieval(String query,
                                             String normalizedQueryKey,
                                             List<String> tags,
                                             Integer topK,
                                             String status,
                                             int resultCount,
                                             List<String> retrievedEvidenceIds) {
        retrievalHistory.add(new RetrievalHistoryEntry(
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
        return retrievalHistory.stream()
                .anyMatch(entry -> "ok".equals(entry.status()) || "no_result".equals(entry.status()));
    }

    public synchronized void updateRetrievalAssessment(RetrievalGapType gapType,
                                                       boolean improvableByFollowup,
                                                       List<String> allowedFocusTypes) {
        this.retrievalGapType = gapType == null ? RetrievalGapType.NOT_IMPROVABLE : gapType;
        this.improvableByFollowup = improvableByFollowup;
        this.allowedFocusTypes = allowedFocusTypes == null ? List.of() : List.copyOf(allowedFocusTypes);
    }

    public synchronized RetrievalGapType retrievalGapType() {
        return retrievalGapType;
    }

    public synchronized boolean improvableByFollowup() {
        return improvableByFollowup;
    }

    public synchronized List<String> allowedFocusTypes() {
        return List.copyOf(allowedFocusTypes);
    }

    public synchronized boolean markFollowupToolAttempted() {
        if (followupToolAttempted) {
            return false;
        }
        followupToolAttempted = true;
        return true;
    }

    public synchronized FollowupOptionsResult followupOptionsCandidate() {
        return followupOptionsCandidate;
    }

    public synchronized void storeFollowupOptionsCandidate(FollowupOptionsResult candidate) {
        this.followupOptionsCandidate = candidate;
    }
}
