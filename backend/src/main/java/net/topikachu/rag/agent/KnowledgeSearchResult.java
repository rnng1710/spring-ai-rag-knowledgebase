package net.topikachu.rag.agent;

import java.util.List;

public record KnowledgeSearchResult(
        String status,
        List<KnowledgeSnippet> items,
        String errorCode,
        String errorMessage
) {
    public static KnowledgeSearchResult ok(List<KnowledgeSnippet> items) {
        return new KnowledgeSearchResult("ok", items == null ? List.of() : List.copyOf(items), null, null);
    }

    public static KnowledgeSearchResult noResult() {
        return new KnowledgeSearchResult("no_result", List.of(), null, null);
    }

    public static KnowledgeSearchResult toolError(String errorCode, String errorMessage) {
        return new KnowledgeSearchResult("tool_error", List.of(), errorCode, errorMessage);
    }
}
