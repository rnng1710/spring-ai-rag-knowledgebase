package net.topikachu.rag.chat.history.dto;

import java.time.LocalDateTime;

public record ChatSessionItem(
        String conversationId,
        String title,
        String titleStatus,
        LocalDateTime lastMessageAt,
        LocalDateTime createDate) {
}
