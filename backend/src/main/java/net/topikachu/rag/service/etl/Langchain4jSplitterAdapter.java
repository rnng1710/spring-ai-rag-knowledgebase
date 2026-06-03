package net.topikachu.rag.service.etl;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class Langchain4jSplitterAdapter extends TextSplitter {

    private final dev.langchain4j.data.document.DocumentSplitter internalSplitter;

    public Langchain4jSplitterAdapter(int chunkSize, int chunkOverlap) {
        // 使用 LangChain4j 的递归切分器：优先按段落/句子边界切分，超长时才在字符中间切断
        this.internalSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
    }

    @Override
    protected List<String> splitText(String text) {
        // 切分前先清洗：去除控制字符、规范化空白
        TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(text);
        if (result.isEffectivelyEmpty()) {
            return List.of();
        }
        dev.langchain4j.data.document.Document lcDoc = dev.langchain4j.data.document.Document.from(result.text());
        // 递归切分：优先按段落(\n\n) → 句子(。！？) → 空格 → 字符边界逐级尝试
        return internalSplitter.split(lcDoc).stream()
                .map(TextSegment::text)
                .map(TextSanitizer::sanitize)       // 切分后再次清洗，去除边界产生的空片段
                .filter(resultItem -> !resultItem.isEffectivelyEmpty())
                .map(TextSanitizer.SanitizationResult::text)
                .collect(Collectors.toList());
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        return documents.stream()
                .flatMap(springDoc -> {
                    // 1. 元数据转换：Spring AI (Map<String, Object>) → LangChain4j (Map<String, String>)
                    Map<String, String> lcMetadata = new HashMap<>();
                    if (springDoc.getMetadata() != null) {
                        springDoc.getMetadata().forEach((k, v) -> lcMetadata.put(k, v != null ? v.toString() : ""));
                    }

                    dev.langchain4j.data.document.Document lcDoc = dev.langchain4j.data.document.Document
                            .from(springDoc.getText(), dev.langchain4j.data.document.Metadata.from(lcMetadata));

                    // 2. 核心切分：一个 Parent Document → 多个 TextSegment
                    List<TextSegment> segments = internalSplitter.split(lcDoc);

                    // 3. 每个 TextSegment 清洗后转为 Spring AI Document，子块继承父块的全部 metadata
                    return segments.stream()
                            .map(segment -> TextSanitizer.sanitize(segment.text()))
                            .filter(result -> !result.isEffectivelyEmpty())
                            .peek(result -> {
                                if (result.isLowQualityExtraction()) {
                                    Object pageNumber = springDoc.getMetadata().get("page_number");
                                    log.warn(
                                            "Low-quality chunk after split: page={}, removedRatio={}, " +
                                                    "meaningfulCodePoints={}, sanitizedLength={}, preview={}",
                                            pageNumber != null ? pageNumber : "unknown",
                                            result.removedRatioPercent(),
                                            result.meaningfulCodePoints(),
                                            result.text().length(),
                                            TextSanitizer.preview(result.text()));
                                }
                            })
                            .map(result -> {
                                Map<String, Object> springMetadata = sanitizeMetadata(springDoc.getMetadata());
                                // 子块继承父块的全部 metadata（parent_block_id、page_start 等）
                                return new Document(result.text(), springMetadata);
                            });
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> sanitized = new HashMap<>();
        if (metadata == null || metadata.isEmpty()) {
            return sanitized;
        }
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized;
    }
}
