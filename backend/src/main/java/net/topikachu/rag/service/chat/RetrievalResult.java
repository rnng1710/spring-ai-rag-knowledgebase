package net.topikachu.rag.service.chat;

import org.springframework.ai.document.Document;

import java.util.List;

public record RetrievalResult(
        List<Document> childCandidates,
        List<ParentContextBlock> parentContexts
) {
}
