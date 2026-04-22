package net.topikachu.rag.agent;

import java.util.List;

public record FollowupOptionsResult(
        String status,
        List<String> options,
        List<String> focusTypes,
        String rationale,
        String errorCode,
        String errorMessage
) {
    public static FollowupOptionsResult ok(List<String> options, List<String> focusTypes, String rationale) {
        return new FollowupOptionsResult(
                "ok",
                options == null ? List.of() : List.copyOf(options),
                focusTypes == null ? List.of() : List.copyOf(focusTypes),
                rationale,
                null,
                null);
    }

    public static FollowupOptionsResult toolError(String errorCode, String errorMessage) {
        return new FollowupOptionsResult("tool_error", List.of(), List.of(), null, errorCode, errorMessage);
    }
}
