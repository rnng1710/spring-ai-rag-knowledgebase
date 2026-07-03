package net.topikachu.rag.chat.history.dto;

import java.time.LocalDateTime;

public record ChatMessageItem(
        String id,
        String role,
        String content,
        String modelId,
        String mode,
        Integer messageIndex,
        LocalDateTime createDate) {
}
