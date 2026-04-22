package net.topikachu.rag.agent;

import java.util.List;

public record KnowledgeSearchRequest(
        String query,
        List<String> tagsAnyOf,
        Integer topK
) {
}
