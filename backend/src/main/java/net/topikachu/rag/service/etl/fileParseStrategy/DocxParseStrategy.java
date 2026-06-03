package net.topikachu.rag.service.etl.fileParseStrategy;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.service.etl.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
@Slf4j
public class DocxParseStrategy implements FileParseStrategy {

    // 仅 H1/H2 作为父块边界：H3+ 粒度太细，会破坏 LLM 所需的上下文连贯性
    private static final int HEADING_LEVEL = 2;
    // 500 字符是最小有意义上下文窗口，短于此阈值的节合并到相邻节避免碎片化
    private static final int MIN_PARENT_CHARS = 500;
    private static final int FALLBACK_PARENT_SIZE = 1200;
    private static final int FALLBACK_PARENT_OVERLAP = 200;

    private final TextSplitter textSplitter;

    @Value("${rag.retrieval.parent-block-size:1200}")
    private int fallbackParentSize = FALLBACK_PARENT_SIZE;

    @Value("${rag.retrieval.parent-block-overlap:200}")
    private int fallbackParentOverlap = FALLBACK_PARENT_OVERLAP;

    public DocxParseStrategy(TextSplitter textSplitter) {
        this.textSplitter = textSplitter;
    }

    @Override
    public ChunkUtils.ParentChildDocuments readAndSplit(String fileType, EtlPipeline.EtlContext ctx) {
        try (InputStream is = Files.newInputStream(ctx.path())) {
            XWPFDocument doc = new XWPFDocument(is);
            List<Section> sections = splitByHeadingStyles(doc);

            if (sections.isEmpty()) {
                log.warn("No heading styles found in {}, falling back to fixed-window splitting", ctx.fileName());
                String fullText = extractFullText(doc);
                sections = fallbackSections(fullText);
            }

            sections = mergeShortSections(sections);
            return buildParentChildDocuments(ctx, sections);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse docx file: " + ctx.path(), e);
        }
    }

    @Override
    public boolean supports(String fileType) {
        return "docx".equals(fileType);
    }

    private List<Section> splitByHeadingStyles(XWPFDocument doc) {
        List<IBodyElement> elements = doc.getBodyElements();
        List<Section> sections = new ArrayList<>();
        // 面包屑栈：记录当前所处的一级标题路径（如 ["第三章 员工纪律"]）
        List<String> breadcrumb = new ArrayList<>();

        SectionBuilder currentSection = null;
        // 标记文档是否有任何标题结构，无标题则走固定窗口兜底
        boolean hasHeadingStructure = false;

        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                String styleId = paragraph.getStyleID();
                // 根据 Word 样式 ID（如 "Heading1"、"heading 2"）识别标题层级
                int headingLevel = detectHeadingLevel(styleId);

                if (headingLevel == 1) {
                    // 一级标题：清空面包屑，开始全新的章节上下文
                    if (currentSection != null && currentSection.hasContent()) {
                        sections.add(currentSection.build());
                    }
                    breadcrumb.clear();
                    breadcrumb.add(paragraph.getText().trim());
                    currentSection = null;
                    hasHeadingStructure = true;
                } else if (headingLevel > 1 && headingLevel <= HEADING_LEVEL) {
                    // 二级标题：作为新 Section 的边界，继承当前一级标题面包屑
                    hasHeadingStructure = true;

                    if (currentSection != null && currentSection.hasContent()) {
                        sections.add(currentSection.build());
                    }

                    List<String> sectionBreadcrumb = new ArrayList<>(breadcrumb);
                    sectionBreadcrumb.add(paragraph.getText().trim());
                    currentSection = new SectionBuilder(sectionBreadcrumb, paragraph.getText().trim());
                } else {
                    // 正文段落：归属于当前 Section
                    if (currentSection == null) {
                        currentSection = new SectionBuilder(new ArrayList<>(breadcrumb), null);
                    }
                    currentSection.appendParagraph(paragraph);
                }
            } else if (element instanceof XWPFTable table) {
                // 表格：同样归属于当前 Section，转换为 Markdown 表格
                if (currentSection == null) {
                    currentSection = new SectionBuilder(new ArrayList<>(breadcrumb), null);
                }
                currentSection.appendTable(table);
            }
        }

        // 收尾：提交最后一个 Section
        if (currentSection != null && currentSection.hasContent()) {
            sections.add(currentSection.build());
        }

        // 文档中完全没有标题结构 → 返回空列表，调用方走固定窗口兜底
        if (!hasHeadingStructure) {
            return List.of();
        }

        return sections;
    }

    // 根据 Word 段落样式 ID 识别标题层级。如 "Heading1" → 1，"heading 2" → 2，普通段落 → 0
    private int detectHeadingLevel(String styleId) {
        if (styleId == null) {
            return 0;
        }
        String lower = styleId.toLowerCase(Locale.ROOT);
        if (lower.startsWith("heading") || lower.startsWith("heading ")) {
            String numPart = lower.replace("heading", "").replace(" ", "").trim();
            try {
                int level = Integer.parseInt(numPart);
                return (level >= 1 && level <= 9) ? level : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private String extractFullText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(paragraph.getText());
            } else if (element instanceof XWPFTable table) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(tableToMarkdown(table));
            }
        }
        return sb.toString();
    }

    private List<Section> fallbackSections(String content) {
        List<String> pieces = ChunkUtils.splitByFixedWindow(content, fallbackParentSize, fallbackParentOverlap);
        List<Section> sections = new ArrayList<>();
        int index = 1;
        for (String piece : pieces) {
            sections.add(new Section(List.of(), null, piece, index++));
        }
        return sections;
    }

    // 合并过短的 Section：小于 500 字符的节合并到后续节，避免产生信息量不足的 parent block
    private List<Section> mergeShortSections(List<Section> sections) {
        if (sections.size() <= 1) {
            return sections;
        }
        List<Section> merged = new ArrayList<>();
        Section accumulator = null;
        for (Section section : sections) {
            if (accumulator == null) {
                accumulator = section;
            } else if (accumulator.content().length() < MIN_PARENT_CHARS) {
                accumulator = accumulator.merge(section);
            } else {
                merged.add(accumulator);
                accumulator = section;
            }
        }
        if (accumulator != null) {
            if (!merged.isEmpty() && accumulator.content().length() < MIN_PARENT_CHARS && merged.get(merged.size() - 1).hasHeading()) {
                int lastIdx = merged.size() - 1;
                merged.set(lastIdx, merged.get(lastIdx).merge(accumulator));
            } else {
                merged.add(accumulator);
            }
        }
        return merged;
    }

    private ChunkUtils.ParentChildDocuments buildParentChildDocuments(EtlPipeline.EtlContext ctx, List<Section> sections) {
        List<KnowledgeParentBlock> parentBlocks = new ArrayList<>();
        List<Document> childDocuments = new ArrayList<>();
        int parentIndex = 1;
        int childIndex = 1;

        for (Section section : sections) {
            String parentBlockId = ChunkUtils.buildParentBlockId(ctx.docUuid(), section.content(), parentIndex);

            String parentText = buildParentText(section);
            Document parentDocument = new Document(parentText, parentMetadata(ctx, section, parentBlockId, parentIndex));
            parentBlocks.add(ChunkUtils.toKnowledgeParentBlock(parentDocument));

            // 父块 → 子块：每个标题 Section 父块递归切分为 200 字子块
            List<Document> children = textSplitter.apply(List.of(parentDocument));
            for (Document child : children) {
                Map<String, Object> metadata = child.getMetadata();
                metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
                metadata.put("parent_block_id", parentBlockId);
                metadata.put("parent_index", parentIndex);
                metadata.put("child_index", childIndex);
                metadata.put("evidence_id", ChunkUtils.buildEvidenceId(ctx.docUuid(), child, childIndex));
                metadata.put("source_location", section.sourceLocation());
                childDocuments.add(child);
                childIndex++;
            }
            parentIndex++;
        }

        return new ChunkUtils.ParentChildDocuments(parentBlocks, childDocuments);
    }

    private String buildParentText(Section section) {
        StringBuilder sb = new StringBuilder();
        if (!section.breadcrumb().isEmpty()) {
            sb.append("> ").append(String.join(" > ", section.breadcrumb())).append("\n\n");
        }
        sb.append(section.content());
        return sb.toString();
    }

    private Map<String, Object> parentMetadata(EtlPipeline.EtlContext ctx, Section section,
                                                String parentBlockId, int parentIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_uuid", ctx.docUuid());
        metadata.put("file_name", ctx.fileName());
        metadata.put("parent_block_id", parentBlockId);
        metadata.put("parent_index", parentIndex);
        metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
        return metadata;
    }

    private static String tableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int colCount = rows.stream()
                .mapToInt(row -> (int) row.getTableCells().stream().filter(cell -> {
                    String text = cell.getText().trim();
                    return !text.isBlank();
                }).count())
                .max().orElse(0);
        if (colCount == 0) {
            return "";
        }

        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<String> cells = new ArrayList<>();
            for (int j = 0; j < colCount; j++) {
                if (j < row.getTableCells().size()) {
                    cells.add(row.getCell(j).getText().trim().replace("\n", " "));
                } else {
                    cells.add("");
                }
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (i == 0) {
                sb.append("| ").append("--- | ".repeat(colCount).trim()).append(" |\n");
            }
        }
        return sb.toString();
    }

    private record Section(
            List<String> breadcrumb,
            String headingText,
            String content,
            int fallbackIndex
    ) {
        boolean hasHeading() {
            return headingText != null && !headingText.isBlank();
        }

        String sourceLocation() {
            if (hasHeading()) {
                String path = String.join(" > ", breadcrumb);
                return path.isEmpty() ? headingText : path;
            }
            if (fallbackIndex > 0) {
                return "片段" + fallbackIndex;
            }
            return "未知位置";
        }

        Section merge(Section other) {
            List<String> mergedBreadcrumb = hasHeading() ? breadcrumb : other.breadcrumb();
            String mergedHeading = hasHeading() ? headingText : other.headingText();
            String mergedContent = content + "\n\n" + other.content();
            return new Section(mergedBreadcrumb, mergedHeading, mergedContent, fallbackIndex);
        }
    }

    private static class SectionBuilder {
        private final List<String> breadcrumb;
        private final String headingText;
        private final StringBuilder content = new StringBuilder();

        SectionBuilder(List<String> breadcrumb, String headingText) {
            this.breadcrumb = breadcrumb;
            this.headingText = headingText;
        }

        void appendHeading(XWPFParagraph paragraph) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(paragraph.getText().trim());
        }

        void appendParagraph(XWPFParagraph paragraph) {
            String text = paragraph.getText().trim();
            if (text.isBlank()) {
                return;
            }
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(text);
        }

        void appendTable(XWPFTable table) {
            String markdown = DocxParseStrategy.tableToMarkdown(table);
            if (markdown.isBlank()) {
                return;
            }
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(markdown);
        }

        Section build() {
            return new Section(breadcrumb, headingText, content.toString().trim(), 0);
        }

        boolean hasContent() {
            return !content.toString().trim().isBlank();
        }
    }
}
