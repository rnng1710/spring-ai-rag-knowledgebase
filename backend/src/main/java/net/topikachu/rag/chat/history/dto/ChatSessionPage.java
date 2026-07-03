package net.topikachu.rag.chat.history.dto;

import java.util.List;

public record ChatSessionPage(
        List<ChatSessionItem> items,
        long total,
        int page,
        int size) {
}
