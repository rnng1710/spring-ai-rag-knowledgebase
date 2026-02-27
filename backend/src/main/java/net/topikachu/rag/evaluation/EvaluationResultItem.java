package net.topikachu.rag.evaluation;

import java.util.List;

/**
 * The final output format for a single evaluated question.
 * Serialized to JSONL for tools like Ragas.
 */
public record EvaluationResultItem(
        String question,
        String groundTruth, // Golden answer for strict assessment
        String generatedAnswer,
        List<ContextNode> contexts // Ordered list of retrieved chunks with metadata
) {
}
