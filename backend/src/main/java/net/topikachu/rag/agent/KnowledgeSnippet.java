package net.topikachu.rag.agent;

import java.util.Map;

public record KnowledgeSnippet(
        String id,
        String text,
        Map<String, Object> metadataSnapshot
) {
}
