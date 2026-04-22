package net.topikachu.rag.agent;

import java.util.List;

public record RetrievalHistoryEntry(
        String query,
        String normalizedQueryKey,
        List<String> tags,
        Integer topK,
        String status,
        int resultCount,
        List<String> retrievedEvidenceIds,
        long timestamp
) {
}
