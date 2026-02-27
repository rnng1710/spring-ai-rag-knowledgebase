package net.topikachu.rag.evaluation;

/**
 * Represents a single piece of retrieved context with metadata.
 * Sent in the evaluation result to identify the source of the information.
 */
public record ContextNode(
        String text,
        String documentId, // UUID or filename for traceability
        Double score // Milvus or Reranker score
) {
}
