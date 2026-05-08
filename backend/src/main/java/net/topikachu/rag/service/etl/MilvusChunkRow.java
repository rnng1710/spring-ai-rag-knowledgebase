package net.topikachu.rag.service.etl;

import java.util.Map;

public record MilvusChunkRow(
        String docId,
        String content,
        Map<String, Object> metadata,
        Object embedding,
        Object sparseVector) {
}
