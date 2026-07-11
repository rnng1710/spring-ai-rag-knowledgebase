package net.topikachu.rag.service.etl.fileParseStrategy;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.service.etl.ChunkUtils;
import net.topikachu.rag.service.etl.EtlPipeline;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.parser", name = "provider", havingValue = "spring-ai-alibaba-poc")
@Slf4j
public class PocMarkdownParseStrategy implements FileParseStrategy {

    private final MarkdownParseStrategy delegate;

    public PocMarkdownParseStrategy(TextSplitter textSplitter) {
        this.delegate = new MarkdownParseStrategy(textSplitter);
    }

    @Override
    public ChunkUtils.ParentChildDocuments readAndSplit(String fileType, EtlPipeline.EtlContext ctx) {
        ChunkUtils.ParentChildDocuments result = delegate.readAndSplit(fileType, ctx);
        log.info("Parser POC kept legacy Markdown output. file={}, parentBlocks={}, childChunks={}",
                ctx.fileName(), result.parentBlocks().size(), result.childDocuments().size());
        return result;
    }

    @Override
    public boolean supports(String fileType) {
        return "md".equals(fileType) || "markdown".equals(fileType);
    }
}
