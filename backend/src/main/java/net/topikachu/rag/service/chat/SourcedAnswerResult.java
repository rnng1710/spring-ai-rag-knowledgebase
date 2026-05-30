package net.topikachu.rag.service.chat;

import java.util.List;

public record SourcedAnswerResult(
        String answer,
        String answerType,
        List<String> usedSources
) {
}
