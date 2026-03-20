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
        // 使用 LangChain4j 的递归切分器
        this.internalSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
    }

    @Override
    protected List<String> splitText(String text) {
        TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(text);
        if (result.isEffectivelyEmpty()) {
            return List.of();
        }
        dev.langchain4j.data.document.Document lcDoc = dev.langchain4j.data.document.Document.from(result.text());
        return internalSplitter.split(lcDoc).stream()
                .map(TextSegment::text)
                .map(TextSanitizer::sanitize)
                .filter(resultItem -> !resultItem.isEffectivelyEmpty())
                .map(TextSanitizer.SanitizationResult::text)
                .collect(Collectors.toList());
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        return documents.stream()
                .flatMap(springDoc -> {
                    // 1. 转换 Metadata: Spring AI (Map<String, Object>) -> LangChain4j (Map<String,
                    // String>)
                    Map<String, String> lcMetadata = new HashMap<>();
                    if (springDoc.getMetadata() != null) {
                        springDoc.getMetadata().forEach((k, v) -> lcMetadata.put(k, v != null ? v.toString() : ""));
                    }

                    dev.langchain4j.data.document.Document lcDoc = dev.langchain4j.data.document.Document
                            .from(springDoc.getText(), dev.langchain4j.data.document.Metadata.from(lcMetadata));

                    // 2. 核心切分
                    List<TextSegment> segments = internalSplitter.split(lcDoc);

                    // 3. 结果转换与扁平化 (修复 Java 编译流类型报错)
                    return segments.stream()
                            .map(segment -> TextSanitizer.sanitize(segment.text()))
                            .filter(result -> !result.isEffectivelyEmpty())
                            .peek(result -> {
                                if (result.isLowQualityExtraction()) {
                                    Object pageNumber = springDoc.getMetadata().get("page_number");
                                    log.warn(
                                            "Low-quality chunk after split: page={}, removedRatio={}, meaningfulCodePoints={}, sanitizedLength={}, preview={}",
                                            pageNumber != null ? pageNumber : "unknown",
                                            result.removedRatioPercent(),
                                            result.meaningfulCodePoints(),
                                            result.text().length(),
                                            TextSanitizer.preview(result.text()));
                                }
                            })
                            .map(result -> {
                                Map<String, Object> springMetadata = new HashMap<>(springDoc.getMetadata());
                                return new Document(result.text(), springMetadata);
                            });
                })
                .collect(Collectors.toList()); // 最终聚合成一维 List<Document>要求的签名
    }
}
