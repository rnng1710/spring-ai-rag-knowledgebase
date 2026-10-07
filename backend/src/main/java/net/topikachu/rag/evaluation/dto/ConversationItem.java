package net.topikachu.rag.evaluation.dto;

import java.util.List;

public record ConversationItem(
        String id,
        String question,
        String answer,
        String model,
        String time,
        String rating,
        String failureMode,
        String traceId,
        List<ContextSnippet> contextSnippets,
        String reference) {
}
