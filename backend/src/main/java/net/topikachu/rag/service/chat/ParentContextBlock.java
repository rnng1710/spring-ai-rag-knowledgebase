package net.topikachu.rag.service.chat;

import java.util.List;

public record ParentContextBlock(
        String parentBlockId,
        String docUuid,
        String fileName,
        String content,
        Integer parentIndex,
        Integer pageStart,
        Integer pageEnd,
        List<String> evidenceIds,
        int rank
) {
}
