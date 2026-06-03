package net.topikachu.rag.service.etl.fileParseStrategy;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.service.etl.*;
import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class MarkdownParseStrategy implements FileParseStrategy {

    // 仅 H1/H2 作为父块边界：H3+ 粒度太细，会破坏 LLM 所需的上下文连贯性
    private static final int HEADING_LEVEL = 2;
    // 500 字符是最小有意义上下文窗口，短于此阈值的节合并到相邻节避免碎片化
    // 注：此常量及 mergeShortSections 与 DocxParseStrategy 重复，因 Section 类型不同刻意保持独立
    private static final int MIN_PARENT_CHARS = 500;
    private static final int FALLBACK_PARENT_SIZE = 1200;
    private static final int FALLBACK_PARENT_OVERLAP = 200;

    private final TextSplitter textSplitter;

    @Value("${rag.retrieval.parent-block-size:1200}")
    private int fallbackParentSize = FALLBACK_PARENT_SIZE;

    @Value("${rag.retrieval.parent-block-overlap:200}")
    private int fallbackParentOverlap = FALLBACK_PARENT_OVERLAP;

    public MarkdownParseStrategy(TextSplitter textSplitter) {
        this.textSplitter = textSplitter;
    }

    @Override
    public ChunkUtils.ParentChildDocuments readAndSplit(String fileType, EtlPipeline.EtlContext ctx) {
        String content = readMarkdownContent(ctx);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("No usable text extracted: " + ctx.path());
        }

        String breadcrumbRoot = extractBreadcrumbRoot(content);
        List<Section> sections = splitByHeadingStructure(content, breadcrumbRoot);

        if (sections.isEmpty()) {
            log.warn("No H{} heading structure found in {}, falling back to fixed-window splitting",
                    HEADING_LEVEL, ctx.fileName());
            sections = fallbackSections(content);
        }

        sections = mergeShortSections(sections);
        return buildParentChildDocuments(ctx, sections);
    }

    @Override
    public boolean supports(String fileType) {
        return "md".equals(fileType) || "markdown".equals(fileType);
    }

    private String readMarkdownContent(EtlPipeline.EtlContext ctx) {
        try {
            return java.nio.file.Files.readString(ctx.path());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read markdown file: " + ctx.path(), e);
        }
    }

    // 提取面包屑根路径：优先取 YAML frontmatter 中的 title，其次取第一个 H1 标题，都没有则返回 null
    private String extractBreadcrumbRoot(String content) {
        List<Extension> extensions = List.of(YamlFrontMatterExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        Node document = parser.parse(content);

        // 解析 YAML frontmatter，提取 title 字段作为根面包屑
        YamlFrontMatterVisitor visitor = new YamlFrontMatterVisitor();
        document.accept(visitor);
        Map<String, List<String>> frontMatter = visitor.getData();

        if (frontMatter.containsKey("title") && !frontMatter.get("title").isEmpty()) {
            return frontMatter.get("title").get(0).trim();
        }

        // 兜底：取第一个一级标题
        Heading firstH1 = findFirstHeading(document, 1);
        if (firstH1 != null) {
            return textContent(firstH1);
        }

        return null;
    }

    // 基于 CommonMark AST 的标题结构切分：遍历 AST 节点树，以 H1/H2 为 Section 边界
    private List<Section> splitByHeadingStructure(String content, String breadcrumbRoot) {
        List<Extension> extensions = List.of(YamlFrontMatterExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        // CommonMark 解析器将 Markdown 文本解析为 AST 节点树
        Node document = parser.parse(content);

        List<Section> sections = new ArrayList<>();
        // 面包屑栈：记录当前所处的标题层级路径如 ["数据安全", "处罚细则"]）
        List<String> breadcrumb = new ArrayList<>();
        if (breadcrumbRoot != null) {
            breadcrumb.add(breadcrumbRoot);
        }

        SectionBuilder currentSection = null;
        // 标记是否至少有一个 H1~H2 标题，没有则走固定窗口兜底
        boolean hasH2Plus = false;

        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            // 跳过 YAML frontmatter 块（已在 extractBreadcrumbRoot 中处理）
            if (node instanceof YamlFrontMatterBlock) {
                continue;
            }

            if (node instanceof Heading heading) {
                int level = heading.getLevel();
                String headingText = textContent(heading);

                if (level <= HEADING_LEVEL) {
                    // H1 或 H2：作为新 Section 边界
                    hasH2Plus = true;

                    if (currentSection != null && currentSection.hasContent()) {
                        sections.add(currentSection.build());
                    }

                    // 构建当前 Section 的面包屑路径
                    List<String> sectionBreadcrumb = new ArrayList<>(breadcrumb);
                    sectionBreadcrumb.add(headingText);
                    currentSection = new SectionBuilder(sectionBreadcrumb, heading);
                }

                // 三级及以上标题也作为内容追加到当前 Section
                if (currentSection != null) {
                    currentSection.appendNode(node);
                }
            } else {
                // 非标题节点（段落、代码块、列表等）：归属于当前 Section
                if (currentSection == null) {
                    currentSection = new SectionBuilder(new ArrayList<>(breadcrumb), null);
                }
                currentSection.appendNode(node);
            }
        }

        // 收尾：提交最后一个 Section
        if (currentSection != null && currentSection.hasContent()) {
            sections.add(currentSection.build());
        }

        // 文档中完全没有 H1~H2 标题 → 返回空列表，调用方走固定窗口兜底
        if (!hasH2Plus) {
            return List.of();
        }

        return sections;
    }

    // 无标题结构时的兜底方案：固定窗口切分
    private List<Section> fallbackSections(String content) {
        List<String> pieces = ChunkUtils.splitByFixedWindow(content, fallbackParentSize, fallbackParentOverlap);
        List<Section> sections = new ArrayList<>();
        int index = 1;
        for (String piece : pieces) {
            sections.add(new Section(List.of(), null, piece, index++));
        }
        return sections;
    }

    // 合并过短的 Section：小于 500 字符的节向前合并，避免产生信息量不足的 parent block
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

    private Heading findFirstHeading(Node document, int targetLevel) {
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading && heading.getLevel() == targetLevel) {
                return heading;
            }
        }
        return null;
    }

    private String textContent(Node node) {
        StringBuilder sb = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text) {
                sb.append(((Text) child).getLiteral());
            } else if (child instanceof Code) {
                sb.append(((Code) child).getLiteral());
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                sb.append(' ');
            }
            child = child.getNext();
        }
        return sb.toString().trim();
    }

    private record Section(
            List<String> breadcrumb,
            Heading heading,
            String content,
            int fallbackIndex
    ) {
        boolean hasHeading() {
            return heading != null;
        }

        String sourceLocation() {
            if (hasHeading()) {
                String path = String.join(" > ", breadcrumb);
                return path.isEmpty() ? textContent(heading) : path;
            }
            if (fallbackIndex > 0) {
                return "片段" + fallbackIndex;
            }
            return "未知位置";
        }

        Section merge(Section other) {
            List<String> mergedBreadcrumb = hasHeading() ? breadcrumb : other.breadcrumb();
            Heading mergedHeading = hasHeading() ? heading : other.heading();
            String mergedContent = content + "\n\n" + other.content();
            return new Section(mergedBreadcrumb, mergedHeading, mergedContent, fallbackIndex);
        }

        private String textContent(Heading h) {
            StringBuilder sb = new StringBuilder();
            Node child = h.getFirstChild();
            while (child != null) {
                if (child instanceof Text) {
                    sb.append(((Text) child).getLiteral());
                } else if (child instanceof Code) {
                    sb.append(((Code) child).getLiteral());
                }
                child = child.getNext();
            }
            return sb.toString().trim();
        }
    }

    private static class SectionBuilder {
        private final List<String> breadcrumb;
        private final Heading heading;
        private final StringBuilder content = new StringBuilder();

        SectionBuilder(List<String> breadcrumb, Heading heading) {
            this.breadcrumb = breadcrumb;
            this.heading = heading;
        }

        void appendNode(Node node) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            appendNodeContent(node);
        }

        private void appendNodeContent(Node node) {
            if (node instanceof Text) {
                content.append(((Text) node).getLiteral());
            } else if (node instanceof Code) {
                content.append(((Code) node).getLiteral());
            } else if (node instanceof FencedCodeBlock fenced) {
                content.append("```");
                if (fenced.getInfo() != null && !fenced.getInfo().isBlank()) {
                    content.append(fenced.getInfo());
                }
                content.append("\n").append(fenced.getLiteral());
                if (!fenced.getLiteral().endsWith("\n")) {
                    content.append("\n");
                }
                content.append("```");
            } else if (node instanceof IndentedCodeBlock indented) {
                content.append(indented.getLiteral());
            } else if (node instanceof BulletList || node instanceof OrderedList) {
                content.append(listText(node));
            } else if (node instanceof Paragraph || node instanceof Heading) {
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    appendInlineContent(child);
                }
            } else if (node instanceof ThematicBreak || node instanceof BlockQuote) {
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    appendNodeContent(child);
                }
            } else {
                // HtmlBlock, CustomBlock, etc. — preserve as-is
                content.append(renderNodeLiteral(node));
            }
        }

        private void appendInlineContent(Node node) {
            if (node instanceof Text) {
                content.append(((Text) node).getLiteral());
            } else if (node instanceof Code) {
                content.append("`").append(((Code) node).getLiteral()).append("`");
            } else if (node instanceof Emphasis) {
                content.append("*");
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    appendInlineContent(child);
                }
                content.append("*");
            } else if (node instanceof StrongEmphasis) {
                content.append("**");
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    appendInlineContent(child);
                }
                content.append("**");
            } else if (node instanceof Link link) {
                content.append("[");
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    appendInlineContent(child);
                }
                content.append("](").append(link.getDestination()).append(")");
            } else if (node instanceof SoftLineBreak) {
                content.append(" ");
            } else if (node instanceof HardLineBreak) {
                content.append("\n");
            } else if (node instanceof HtmlInline) {
                content.append(((HtmlInline) node).getLiteral());
            } else {
                content.append(renderNodeLiteral(node));
            }
        }

        private String listText(Node listNode) {
            StringBuilder sb = new StringBuilder();
            boolean isOrdered = listNode instanceof OrderedList;
            int itemIndex = 1;
            for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
                if (item instanceof ListItem) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    String prefix = isOrdered ? (itemIndex++) + ". " : "- ";
                    sb.append(prefix);
                    for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                        appendListItemContent(sb, child);
                    }
                }
            }
            return sb.toString();
        }

        private void appendListItemContent(StringBuilder sb, Node node) {
            if (node instanceof Paragraph) {
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    sb.append(renderInlineContent(child));
                }
            } else if (node instanceof Text) {
                sb.append(((Text) node).getLiteral());
            } else {
                sb.append(renderNodeLiteral(node));
            }
        }

        private String renderInlineContent(Node node) {
            if (node instanceof Text) return ((Text) node).getLiteral();
            if (node instanceof Code) return "`" + ((Code) node).getLiteral() + "`";
            if (node instanceof Emphasis) return "*" + renderChildren(node) + "*";
            if (node instanceof StrongEmphasis) return "**" + renderChildren(node) + "**";
            if (node instanceof SoftLineBreak) return " ";
            if (node instanceof HardLineBreak) return "\n";
            if (node instanceof HtmlInline) return ((HtmlInline) node).getLiteral();
            return renderNodeLiteral(node);
        }

        private String renderChildren(Node parent) {
            StringBuilder sb = new StringBuilder();
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Text) sb.append(((Text) child).getLiteral());
                else if (child instanceof Code) sb.append(((Code) child).getLiteral());
                else sb.append(renderNodeLiteral(child));
            }
            return sb.toString();
        }

        // 反射调用 getLiteral() 作为末级兜底，覆盖 HtmlBlock/CustomBlock 等未显式处理的节点类型
        private String renderNodeLiteral(Node node) {
            try {
                var method = node.getClass().getMethod("getLiteral");
                Object result = method.invoke(node);
                return result != null ? result.toString() : "";
            } catch (Exception e) {
                return "";
            }
        }

        Section build() {
            String text = content.toString().trim();
            return new Section(breadcrumb, heading, text, 0);
        }

        boolean hasContent() {
            return !content.toString().trim().isBlank();
        }
    }
}
