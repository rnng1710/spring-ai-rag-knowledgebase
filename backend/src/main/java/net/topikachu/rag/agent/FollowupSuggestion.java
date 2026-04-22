package net.topikachu.rag.agent;

import java.util.List;

public record FollowupSuggestion(
        String prompt,
        List<String> options,
        String source
) {
}
