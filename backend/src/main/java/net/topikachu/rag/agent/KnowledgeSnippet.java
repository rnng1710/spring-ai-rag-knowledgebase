package net.topikachu.rag.agent;

import java.util.List;
import java.util.Map;

public record KnowledgeSnippet(
        String parentBlockId,
        String text,
        List<String> citableEvidenceIds,
        Map<String, Object> metadataSnapshot
) {
}
