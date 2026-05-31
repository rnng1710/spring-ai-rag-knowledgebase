package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
public class ContextFormatter {

    @Value("${rag.retrieval.max-context-chars:40000}")
    private int maxContextChars;

    public String format(List<Document> docs) {
        return format(docs, Document::getText, Document::getMetadata);
    }

    public String formatParentContexts(List<ParentContextBlock> parentContexts) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < parentContexts.size(); i++) {
            ParentContextBlock block = parentContexts.get(i);
            String structuredEntry = String.format(
                    """
                                    【上下文块 %d】
                                    来源: %s
                                    parent_block_id: %s
                                    可引用 evidence_id:
                                    %s
                                    内容: %s
                                    ------------------------
                                    """,
                    i + 1,
                    sourceLabel(block),
                    block.parentBlockId(),
                    formatEvidenceIds(block.evidenceIds()),
                    block.content());

            if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                log.warn("Parent context limit reached, dropping remaining parent blocks from rank {}", i);
                break;
            }
            contextBuilder.append(structuredEntry);
        }
        return contextBuilder.toString();
    }

    public <T> String format(List<T> docs,
                             Function<T, String> textExtractor,
                             Function<T, Map<String, Object>> metadataExtractor) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            T doc = docs.get(i);
            Map<String, Object> metadata = metadataExtractor.apply(doc);
            String filename = String.valueOf(metadata.getOrDefault("file_name", "Unknown Source"));
            String docUuid = String.valueOf(metadata.getOrDefault("doc_uuid", ""));
            String evidenceId = String.valueOf(metadata.getOrDefault("evidence_id", ""));
            Object page = metadata.getOrDefault("page_number", metadata.get("page"));
            String pageLabel = (page == null) ? ("片段" + (i + 1)) : ("第" + formatPageValue(page) + "页");

            String structuredEntry = String.format(
                    """
                                    【%s】(来源: %s, doc_uuid: %s, evidence_id: %s)
                                    内容: %s
                                    ------------------------
                                    """,
                    pageLabel, filename, docUuid, evidenceId, textExtractor.apply(doc));

            if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                log.warn("Context limit reached, dropping remaining documents from rank {}", i);
                break;
            }
            contextBuilder.append(structuredEntry);
        }
        return contextBuilder.toString();
    }

    private String formatPageValue(Object page) {
        if (page instanceof Number number) {
            double value = number.doubleValue();
            if (Math.rint(value) == value) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
        return page.toString();
    }

    private String sourceLabel(ParentContextBlock block) {
        String filename = block.fileName() == null ? "Unknown Source" : block.fileName();
        if (block.pageStart() != null && block.pageEnd() != null) {
            if (block.pageStart().equals(block.pageEnd())) {
                return filename + " · 第" + block.pageStart() + "页";
            }
            return filename + " · 第" + block.pageStart() + "-" + block.pageEnd() + "页";
        }
        return filename + " · 片段" + block.parentIndex();
    }

    private String formatEvidenceIds(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "- 无";
        }
        return evidenceIds.stream()
                .map(id -> "- " + id)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }
}
