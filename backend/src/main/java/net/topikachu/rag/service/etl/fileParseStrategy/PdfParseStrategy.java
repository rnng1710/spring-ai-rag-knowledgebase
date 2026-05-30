package net.topikachu.rag.service.etl.fileParseStrategy;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.config.OcrProperties;
import net.topikachu.rag.service.etl.EtlPipeline;
import net.topikachu.rag.service.etl.OcrPdfDocumentReader;
import net.topikachu.rag.service.etl.PdfScanDetector;
import net.topikachu.rag.service.etl.TextSanitizer;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

@Component
@Slf4j
public class PdfParseStrategy implements FileParseStrategy {

    private final PdfScanDetector pdfScanDetector;
    private final OcrProperties ocrProperties;
    private final OcrPdfDocumentReader ocrPdfDocumentReader;

    public PdfParseStrategy(PdfScanDetector pdfScanDetector,
                            OcrProperties ocrProperties,
                            OcrPdfDocumentReader ocrPdfDocumentReader) {
        this.pdfScanDetector = pdfScanDetector;
        this.ocrProperties = ocrProperties;
        this.ocrPdfDocumentReader = ocrPdfDocumentReader;
    }

    @Override
    public List<Document> readFile(String fileType, EtlPipeline.EtlContext ctx) {
        try {
            return readAndSanitize(ctx);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse file: " + ctx.path(), e);
        }
    }

    @Override
    public String getFileType() {
        return "pdf";
    }

    private List<Document> readAndSanitize(EtlPipeline.EtlContext ctx) throws Exception {
        List<Document> docs = ctx.pdf()
                ? readPdfDocuments(ctx.path())
                : createReader(ctx.path()).get();

        if (ctx.pdf()) {
            docs = applyPdfPageAnchors(docs);
        }

        docs = sanitizeDocuments(ctx.path(), docs, ctx.pdf());
        if (docs.isEmpty()) {
            throw new IllegalStateException(
                    "No usable text extracted after sanitization: " + ctx.path());
        }
        return docs;
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

        return createPdfReader(path).get();
    }

    private DocumentReader createReader(Path path) {
        if (isPdf(path)) {
            return createPdfReader(path);
        } else {
            return new TikaDocumentReader(path.toUri().toString());
        }
    }

    private DocumentReader createPdfReader(Path path) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withNumberOfBottomTextLinesToDelete(1)
                        .build())
                .build();
        return new PagePdfDocumentReader(path.toUri().toString(), config);
    }

    private boolean isPdf(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".pdf");
    }

    private List<Document> applyPdfPageAnchors(List<Document> docs) {
        List<Document> pageDocs = new ArrayList<>();
        int pageCounter = 1;
        for (Document pageDoc : docs) {
            Object pageNumObj = pageDoc.getMetadata().get("page_number");
            String pageNumStr = pageNumObj != null ? pageNumObj.toString()
                    : String.valueOf(pageCounter);

            String anchoredText = "[--- 以下为文件第 " + pageNumStr + " 页内容 ---]\n" + pageDoc.getText();

            Map<String, Object> pageMetadata = new HashMap<>(pageDoc.getMetadata());
            pageDocs.add(new Document(anchoredText, pageMetadata));
            pageCounter++;
        }
        return pageDocs;
    }

    private List<Document> sanitizeDocuments(Path path, List<Document> docs, boolean isPdf) {
        List<Document> sanitizedDocs = new ArrayList<>();
        int droppedCount = 0;

        for (Document doc : docs) {
            TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(doc.getText());
            Object pageNumber = doc.getMetadata().get("page_number");
            String sourceLabel = isPdf && pageNumber != null
                    ? "page " + pageNumber
                    : "document chunk";

            if (result.wasModified()) {
                log.info("Sanitized {} from {}: removedChars={}, normalizedWhitespace={}, originalLength={}, sanitizedLength={}, preview={}",
                        sourceLabel,
                        path.getFileName(),
                        result.removedChars(),
                        result.normalizedWhitespace(),
                        result.originalLength(),
                        result.text().length(),
                        TextSanitizer.preview(result.text()));
            }

            if (result.isLowQualityExtraction()) {
                log.warn("Low-quality {} extraction for {}: removedRatio={}, meaningfulCodePoints={}, sanitizedLength={}, preview={}",
                        sourceLabel,
                        path.getFileName(),
                        result.removedRatioPercent(),
                        result.meaningfulCodePoints(),
                        result.text().length(),
                        TextSanitizer.preview(result.text()));
            }

            if (result.isEffectivelyEmpty()) {
                droppedCount++;
                log.warn("Dropping {} from {} after sanitization: preview={}",
                        sourceLabel,
                        path.getFileName(),
                        TextSanitizer.preview(doc.getText()));
                continue;
            }

            sanitizedDocs.add(new Document(result.text(), new HashMap<>(doc.getMetadata())));
        }

        if (droppedCount > 0) {
            log.info("Dropped {} sanitized document(s) for {}", droppedCount, path.getFileName());
        }

        return sanitizedDocs;
    }
}
