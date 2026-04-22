package net.topikachu.rag.agent;

import java.util.List;

public record KnowledgeTagList(
        String status,
        List<String> tags,
        String errorCode,
        String errorMessage
) {
    public static KnowledgeTagList ok(List<String> tags) {
        return new KnowledgeTagList("ok", tags == null ? List.of() : List.copyOf(tags), null, null);
    }

    public static KnowledgeTagList toolError(String errorCode, String errorMessage) {
        return new KnowledgeTagList("tool_error", List.of(), errorCode, errorMessage);
    }
}
