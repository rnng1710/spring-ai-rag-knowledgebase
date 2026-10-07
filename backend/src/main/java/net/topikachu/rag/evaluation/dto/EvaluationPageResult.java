package net.topikachu.rag.evaluation.dto;

import java.util.List;

public record EvaluationPageResult(
        EvaluationStats stats,
        List<ConversationItem> items,
        long total) {
}
