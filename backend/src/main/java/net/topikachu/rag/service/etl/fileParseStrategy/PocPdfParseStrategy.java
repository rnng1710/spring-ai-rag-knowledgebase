package net.topikachu.rag.service.etl.fileParseStrategy;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.config.OcrProperties;
import net.topikachu.rag.service.etl.ChunkUtils;
import net.topikachu.rag.service.etl.EtlPipeline;
import net.topikachu.rag.service.etl.OcrPdfDocumentReader;
import net.topikachu.rag.service.etl.PdfScanDetector;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.parser", name = "provider", havingValue = "spring-ai-alibaba-poc")
@Slf4j
public class PocPdfParseStrategy implements FileParseStrategy {

    private final PdfParseStrategy delegate;

    public PocPdfParseStrategy(TextSplitter textSplitter,
                               PdfScanDetector pdfScanDetector,
                               OcrProperties ocrProperties,
                               OcrPdfDocumentReader ocrPdfDocumentReader) {
        this.delegate = new PdfParseStrategy(textSplitter, pdfScanDetector, ocrProperties, ocrPdfDocumentReader);
    }

    @Override
    public ChunkUtils.ParentChildDocuments readAndSplit(String fileType, EtlPipeline.EtlContext ctx) {
        ChunkUtils.ParentChildDocuments result = delegate.readAndSplit(fileType, ctx);
        log.info("Parser POC kept legacy PDF output. file={}, parentBlocks={}, childChunks={}",
                ctx.fileName(), result.parentBlocks().size(), result.childDocuments().size());
        return result;
    }

    @Override
    public boolean supports(String fileType) {
        return "pdf".equals(fileType);
    }
}
