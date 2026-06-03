package net.topikachu.rag.service.etl.fileParseStrategy;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.config.OcrProperties;
import net.topikachu.rag.service.etl.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

@Component
@Slf4j
public class PdfParseStrategy implements FileParseStrategy {

    // 与 EtlPipeline 中的 parent-block-size/overlap 配置保持一致的默认值，但允许单独覆盖
    private static final int PARENT_BLOCK_SIZE = 1200;
    private static final int PARENT_BLOCK_OVERLAP = 200;

    private final TextSplitter textSplitter;
    private final PdfScanDetector pdfScanDetector;
    private final OcrProperties ocrProperties;
    private final OcrPdfDocumentReader ocrPdfDocumentReader;

    @Value("${rag.retrieval.parent-block-size:1200}")
    private int parentBlockSize = PARENT_BLOCK_SIZE;

    @Value("${rag.retrieval.parent-block-overlap:200}")
    private int parentBlockOverlap = PARENT_BLOCK_OVERLAP;

    public PdfParseStrategy(TextSplitter textSplitter,
                            PdfScanDetector pdfScanDetector,
                            OcrProperties ocrProperties,
                            OcrPdfDocumentReader ocrPdfDocumentReader) {
        this.textSplitter = textSplitter;
        this.pdfScanDetector = pdfScanDetector;
        this.ocrProperties = ocrProperties;
        this.ocrPdfDocumentReader = ocrPdfDocumentReader;
    }

    @Override
    public ChunkUtils.ParentChildDocuments readAndSplit(String fileType, EtlPipeline.EtlContext ctx) {
        try {
            List<Document> rawDocs = readPdfDocuments(ctx.path());
            rawDocs = applyPdfPageAnchors(rawDocs);
            rawDocs = sanitizeDocuments(ctx.path(), rawDocs);
            if (rawDocs.isEmpty()) {
                throw new IllegalStateException("No usable text extracted after sanitization: " + ctx.path());
            }
            return buildParentChildDocuments(ctx, rawDocs);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse file: " + ctx.path(), e);
        }
    }

    @Override
    public boolean supports(String fileType) {
        return "pdf".equals(fileType);
    }

    private List<Document> readPdfDocuments(Path path) throws Exception {
        PdfScanDetector.ScanAnalysis scanAnalysis = pdfScanDetector.analyze(path);
        log.info("PDF scan analysis for {}: scanDetected={}, meaningfulChars={}, pageCount={}, avgCharsPerPage={}",
                path.getFileName(),
                scanAnalysis.scanDetected(),
                scanAnalysis.meaningfulChars(),
                scanAnalysis.pageCount(),
                String.format(Locale.ROOT, "%.2f", scanAnalysis.averageMeaningfulCharsPerPage()));

        if (scanAnalysis.scanDetected()) {
            if (!ocrProperties.isEnabled()) {
                throw new IllegalStateException("OCR is disabled for scanned PDF: " + path.getFileName());
            }
            return ocrPdfDocumentReader.read(path);
        }

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        // 删除每页底部最后一行：PDF 页脚通常包含页码/版权信息，对检索无意义
                        .withNumberOfBottomTextLinesToDelete(1)
                        .build())
                .build();
        return new PagePdfDocumentReader(path.toUri().toString(), config).get();
    }

    // 为每页内容注入页码锚点标记，LLM 在引用溯源时可直接定位到具体页码
    private List<Document> applyPdfPageAnchors(List<Document> docs) {
        List<Document> pageDocs = new ArrayList<>();
        int pageCounter = 1;
        for (Document pageDoc : docs) {
            Object pageNumObj = pageDoc.getMetadata().get("page_number");
            String pageNumStr = pageNumObj != null ? pageNumObj.toString() : String.valueOf(pageCounter);
            String anchoredText = "[--- 以下为文件第 " + pageNumStr + " 页内容 ---]\n" + pageDoc.getText();
            Map<String, Object> pageMetadata = new HashMap<>(pageDoc.getMetadata());
            pageDocs.add(new Document(anchoredText, pageMetadata));
            pageCounter++;
        }
        return pageDocs;
    }

    private List<Document> sanitizeDocuments(Path path, List<Document> docs) {
        List<Document> sanitizedDocs = new ArrayList<>();
        for (Document doc : docs) {
            TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(doc.getText());
            Object pageNumber = doc.getMetadata().get("page_number");
            String sourceLabel = pageNumber != null ? "page " + pageNumber : "document chunk";

            if (result.wasModified()) {
                log.info("Sanitized {} from {}: removedChars={}, normalizedWhitespace={}, originalLength={}, sanitizedLength={}",
                        sourceLabel, path.getFileName(), result.removedChars(), result.normalizedWhitespace(),
                        result.originalLength(), result.text().length());
            }
            if (result.isLowQualityExtraction()) {
                log.warn("Low-quality {} extraction for {}: removedRatio={}, meaningfulCodePoints={}",
                        sourceLabel, path.getFileName(), result.removedRatioPercent(), result.meaningfulCodePoints());
            }
            if (result.isEffectivelyEmpty()) {
                log.warn("Dropping {} from {} after sanitization", sourceLabel, path.getFileName());
                continue;
            }
            sanitizedDocs.add(new Document(result.text(), new HashMap<>(doc.getMetadata())));
        }
        return sanitizedDocs;
    }

    private ChunkUtils.ParentChildDocuments buildParentChildDocuments(EtlPipeline.EtlContext ctx, List<Document> docs) {
        List<Document> parentDocuments = buildPdfParentDocuments(ctx, docs);
        List<KnowledgeParentBlock> parentBlocks = new ArrayList<>();
        List<Document> childDocuments = new ArrayList<>();
        int childIndex = 1;

        for (Document parentDocument : parentDocuments) {
            parentBlocks.add(ChunkUtils.toKnowledgeParentBlock(parentDocument));
            // 父块 → 子块：每个 PDF 页级父块递归切分为 200 字子块
            List<Document> children = textSplitter.apply(List.of(parentDocument));
            for (Document child : children) {
                Map<String, Object> metadata = child.getMetadata();
                metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
                metadata.put("parent_block_id", parentDocument.getMetadata().get("parent_block_id"));
                metadata.put("parent_index", parentDocument.getMetadata().get("parent_index"));
                metadata.put("child_index", childIndex);
                metadata.put("evidence_id", ChunkUtils.buildEvidenceId(ctx.docUuid(), child, childIndex));
                ChunkUtils.copyIfPresent(parentDocument.getMetadata(), metadata, "page_start");
                ChunkUtils.copyIfPresent(parentDocument.getMetadata(), metadata, "page_end");
                childDocuments.add(child);
                childIndex++;
            }
        }

        return new ChunkUtils.ParentChildDocuments(parentBlocks, childDocuments);
    }

    private List<Document> buildPdfParentDocuments(EtlPipeline.EtlContext ctx, List<Document> docs) {
        List<Document> parents = new ArrayList<>();
        int parentIndex = 1;
        for (Document doc : docs) {
            Object page = doc.getMetadata().getOrDefault("page_number", doc.getMetadata().get("page"));
            Integer pageNumber = ChunkUtils.parseInteger(page);
            List<String> pieces = ChunkUtils.splitByFixedWindow(doc.getText(), parentBlockSize, parentBlockOverlap);
            for (String piece : pieces) {
                Map<String, Object> metadata = new LinkedHashMap<>(doc.getMetadata());
                metadata.put("parent_index", parentIndex);
                if (pageNumber != null) {
                    metadata.put("page_start", pageNumber);
                    metadata.put("page_end", pageNumber);
                }
                metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
                metadata.put("parent_block_id", ChunkUtils.buildParentBlockId(
                        ctx.docUuid(), piece, parentIndex));
                parents.add(new Document(piece, metadata));
                parentIndex++;
            }
        }
        return parents;
    }
}
