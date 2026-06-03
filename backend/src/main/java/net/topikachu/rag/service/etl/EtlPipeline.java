package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.fileParseStrategy.FileParseStrategy;
import net.topikachu.rag.service.etl.fileParseStrategy.FileParseStrategyFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ETL 摄取管线：驱动文档状态机 UPLOADED → SPLITTING → VECTORIZING → COMPLETED/FAILED，
 * 协调读取、分块、向量化写入及失败补偿清理。
 */
@Component
@Slf4j
public class EtlPipeline {

    private final DocReader documentReader;
    private final HybridVectorWriter hybridVectorWriter;
    private final TextSplitter textSplitter;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMetadataBuilder metadataBuilder;
    private final TracingSupport tracingSupport;
    private final FileParseStrategyFactory fileParseStrategyFactory;
    private final EtlStatusManager etlStatusManager;
    private final KnowledgeParentBlockService parentBlockService;

    // 各阶段独立超时：read/split 受 CPU 和 Tika 解析影响，vectorize 受网络和 Milvus 写入影响，需分别控制
    @org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-split-seconds:60}")
    private long SplitTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.vectorize-seconds:300}")
    private long vectorizeTimeoutSeconds;

    // reading 阶段超时最长（900s），因为 Tika 解析大文件（如扫描 PDF OCR）可能耗时数分钟
    @org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-seconds:900}")
    private long readingTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Value("${rag.retrieval.parent-block-size:1200}")
    private int parentBlockSize;

    @org.springframework.beans.factory.annotation.Value("${rag.retrieval.parent-block-overlap:200}")
    private int parentBlockOverlap;

    public EtlPipeline(HybridVectorWriter hybridVectorWriter,
                       TextSplitter textSplitter,
                       DocReader documentReader,
                       DocumentMapper documentMapper,
                       DocumentChunkMetadataBuilder metadataBuilder,
                       TracingSupport tracingSupport,
                       FileParseStrategyFactory fileParseStrategyFactory,
                       EtlStatusManager etlStatusManager,
                       KnowledgeParentBlockService parentBlockService) {
        this.hybridVectorWriter = hybridVectorWriter;
        this.textSplitter = textSplitter;
        this.documentReader = documentReader;
        this.documentMapper = documentMapper;
        this.metadataBuilder = metadataBuilder;
        this.tracingSupport = tracingSupport;
        this.fileParseStrategyFactory = fileParseStrategyFactory;
        this.etlStatusManager = etlStatusManager;
        this.parentBlockService = parentBlockService;
    }

    // 目录扫描式批量摄入的预留入口，当前未实现具体逻辑，始终返回空流
    public Flux<Document> ingestionFlux() {
        return documentReader.scanDirectory()
                .flatMap(path -> Mono.fromCallable(() -> {
                            String hash = computeHash(path);
                            Long count = documentMapper.selectCount(
                                    Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaQuery()
                                            .eq(net.topikachu.rag.business.document.entity.Document::getFileHash, hash));
                            if (count > 0)
                                return null;
                            return hash;
                        }).subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(hash -> {
                            if (hash == null)
                                return Mono.empty();
                            return Mono.empty();
                        }));
    }

    public Mono<Void> ingestionByPath(Path path, String docUuid, String userId, List<String> tags) {
        EtlContext ctx = EtlContext.of(path, docUuid, userId, tags);

        // 每个 stage 独立超时（非总超时）：readAndSplit 和 writeParentBlocksAndVectors 各自有截止时间
        Mono<Void> pipeline = readAndSplit(ctx)
                .timeout(java.time.Duration.ofSeconds(SplitTimeoutSeconds))
                .flatMap(parentChildDocs -> writeParentBlocksAndVectors(ctx, parentChildDocs))
                .timeout(java.time.Duration.ofSeconds(vectorizeTimeoutSeconds))
                .then(completeStatus(ctx))
                .doOnSuccess(v -> log.info("IngestionByPath finished: {}", ctx.path()))
                .onErrorResume(error -> failAndCleanup(ctx, error));

        return tracingSupport.traceMono("etl.ingestion", ctx.traceTags(), pipeline);
    }

    public record EtlContext(
            Path path,
            String docUuid,
            String userId,
            List<String> tags,
            String fileName,
            String fileExtension,
            Map<String, Object> traceTags
    ) {
        public static EtlContext of(Path path, String docUuid, String userId, List<String> tags) {
            String fileName = path.getFileName().toString();
            String fileExt = extension(fileName);
            Map<String, Object> traceTags = etlTraceTags(path, docUuid, userId, tags);
            traceTags.put("document.file_ext", fileExt);
            return new EtlContext(
                    path,
                    docUuid,
                    userId,
                    tags,
                    fileName,
                    fileExt,
                    traceTags
            );
        }

        Map<String, Object> tagsWith(String key, Object value) {
            Map<String, Object> merged = new LinkedHashMap<>(traceTags);
            merged.put(key, value);
            return merged;
        }

        Map<String, Object> tagsWith(Map<String, Object> extra) {
            Map<String, Object> merged = new LinkedHashMap<>(traceTags);
            merged.putAll(extra);
            return merged;
        }
    }

    private Mono<ChunkUtils.ParentChildDocuments> readAndSplit(EtlContext ctx) {
        return tracingSupport.traceMono("etl.read_split",
                ctx.tagsWith(Map.of("etl.stage", "read_split")),
                etlStatusManager.transitionTo(ctx.docUuid(), ctx.userId, DocumentStatus.SPLITTING, "Reading and splitting...")
                        .then(Mono.fromCallable(() -> {
                            FileParseStrategy strategy = fileParseStrategyFactory
                                    .getFileParseStrategyOrNull(ctx.fileExtension());
                            if (strategy != null) {
                                return strategy.readAndSplit(ctx.fileExtension(), ctx);
                            }
                            return genericReadAndSplit(ctx);
                        }).subscribeOn(Schedulers.boundedElastic()))
                        .flatMap(parentChildDocs -> loadDocumentByDocUuid(ctx.docUuid())
                                .map(storedDoc -> enrichMetadata(ctx, parentChildDocs, storedDoc)))
                        .doOnNext(parentChildDocs -> log.info(
                                "Read and split: docUuid={}, parentBlocks={}, childChunks={}",
                                ctx.docUuid(),
                                parentChildDocs.parentBlocks().size(),
                                parentChildDocs.childDocuments().size())));
    }

    // Tika 通用解析兜底：当文件类型无专用解析策略时（如 txt, rtf），走固定窗口分块
    private ChunkUtils.ParentChildDocuments genericReadAndSplit(EtlContext ctx) {
        List<Document> docs = new TikaDocumentReader(ctx.path().toUri().toString()).get();
        docs = sanitizeDocuments(ctx.path(), docs);
        if (docs.isEmpty()) {
            throw new IllegalStateException("No usable text extracted: " + ctx.path());
        }
        return buildFixedWindowParentChildDocuments(ctx, docs);
    }

    private ChunkUtils.ParentChildDocuments buildFixedWindowParentChildDocuments(EtlContext ctx, List<Document> docs) {
        Map<String, Object> baseMetadata = new LinkedHashMap<>(docs.get(0).getMetadata());
        String joinedText = docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");

        List<String> pieces = ChunkUtils.splitByFixedWindow(joinedText, parentBlockSize, parentBlockOverlap);
        List<net.topikachu.rag.business.document.entity.KnowledgeParentBlock> parentBlocks = new ArrayList<>();
        List<Document> childDocuments = new ArrayList<>();
        int childIndex = 1;

        for (int i = 0; i < pieces.size(); i++) {
            int parentIndex = i + 1;
            Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
            metadata.put("parent_index", parentIndex);
            metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
            metadata.put("parent_block_id", ChunkUtils.buildParentBlockId(
                    ctx.docUuid(), pieces.get(i), parentIndex));
            Document parentDocument = new Document(pieces.get(i), metadata);
            parentBlocks.add(ChunkUtils.toKnowledgeParentBlock(parentDocument));

            // 父块 → 子块：递归切分为 200 字片段，子块继承父块的 metadata
            List<Document> children = textSplitter.apply(List.of(parentDocument));
            for (Document child : children) {
                Map<String, Object> childMeta = child.getMetadata();
                childMeta.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
                childMeta.put("parent_block_id", metadata.get("parent_block_id"));
                childMeta.put("parent_index", parentIndex);
                childMeta.put("child_index", childIndex);
                childMeta.put("evidence_id", ChunkUtils.buildEvidenceId(ctx.docUuid(), child, childIndex));
                childMeta.put("source_location", "片段" + parentIndex);
                childDocuments.add(child);
                childIndex++;
            }
        }

        return new ChunkUtils.ParentChildDocuments(parentBlocks, childDocuments);
    }

    private ChunkUtils.ParentChildDocuments enrichMetadata(EtlContext etlContext,
                                                           ChunkUtils.ParentChildDocuments docs,
                                                           net.topikachu.rag.business.document.entity.Document storedDocument) {
        List<String> effectiveTags = etlContext.tags;
        if (storedDocument != null && storedDocument.getTags() != null) {
            effectiveTags = storedDocument.getTags();
        }
        for (KnowledgeParentBlock parentBlock : docs.parentBlocks()) {
            parentBlock.setDocUuid(etlContext.docUuid());
            parentBlock.setFileName(etlContext.fileName());
            parentBlock.setSpaceCode(resolveSpaceCode(storedDocument));
            parentBlock.setTags(effectiveTags == null ? List.of() : List.copyOf(effectiveTags));
            parentBlock.setAclVersion(storedDocument == null ? 1 : storedDocument.getAclVersion());
            parentBlock.setChunkSchemaVersion(KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
        }
        for (Document child : docs.childDocuments()) {
            Map<String, Object> rebuiltMetadata = metadataBuilder.build(
                    storedDocument,
                    etlContext.docUuid,
                    etlContext.fileName(),
                    effectiveTags,
                    child.getMetadata(),
                    storedDocument == null ? 1 : storedDocument.getAclVersion());
            child.getMetadata().clear();
            child.getMetadata().putAll(rebuiltMetadata);
        }
        return docs;
    }

    // 未指定 spaceCode 时默认归入 "public"，避免向量数据无法按空间检索
    private String resolveSpaceCode(net.topikachu.rag.business.document.entity.Document storedDocument) {
        if (storedDocument == null || storedDocument.getSpaceCode() == null || storedDocument.getSpaceCode().isBlank()) {
            return "public";
        }
        return storedDocument.getSpaceCode().trim();
    }

    private Mono<Void> writeParentBlocksAndVectors(EtlContext ctx, ChunkUtils.ParentChildDocuments parentChildDocs) {
        Map<String, Object> traceTags = ctx.tagsWith(Map.of(
                "etl.stage", "vectorize",
                "etl.parent_block_count", parentChildDocs.parentBlocks().size(),
                "etl.chunk_count", parentChildDocs.childDocuments().size()
        ));
        return tracingSupport.traceMono("etl.vectorize", traceTags,
                etlStatusManager.transitionTo(ctx.docUuid, ctx.userId, DocumentStatus.VECTORIZING, "Writing to Vector Store...")
                        // 先写 MySQL 父块，再写 Milvus 子向量：子块通过 parent_block_id 引用父块，顺序不能颠倒
                        .then(parentBlockService.replaceForDocument(ctx.docUuid(), parentChildDocs.parentBlocks()))
                        .then(Mono.defer(() -> {
                            // 空文档（如全图片 PDF 无文字）跳过 Milvus 写入，否则会报参数错误
                            if (!parentChildDocs.childDocuments().isEmpty()) {
                                return hybridVectorWriter.write(parentChildDocs.childDocuments());
                            }
                            return Mono.empty();
                        })));
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

    // 补偿清理：先删父块（MySQL），再标记 FAILED；即使删块失败也会继续更新状态，避免卡在中间态
    private Mono<Void> failAndCleanup(EtlContext ctx, Throwable error) {
        log.error("Error during ingestionByPath: {}", ctx.path, error);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("etl.stage", "failed");
        extra.put("error.type", error == null ? "" : error.getClass().getSimpleName());
        extra.put("error.message", extractUserMessage(error));
        return tracingSupport.traceMono("etl.fail_cleanup", ctx.tagsWith(extra),
                parentBlockService.deleteByDocUuid(ctx.docUuid())
                        .then(etlStatusManager.transitionToFailed(ctx.docUuid(), ctx.userId(), error))
                        .then(Mono.error(error)));
    }

    private Mono<Void> completeStatus(EtlContext ctx) {
        return tracingSupport.traceMono("etl.finish",
                ctx.tagsWith("etl.stage", "finish"),
                etlStatusManager.transitionToCompleted(ctx.docUuid(), ctx.userId()));
    }

    private static Map<String, Object> etlTraceTags(Path path, String docUuid, String userId, List<String> tags) {
        Map<String, Object> traceTags = new LinkedHashMap<>();
        traceTags.put("document.doc_uuid", docUuid);
        traceTags.put("document.file_name", path == null ? "" : path.getFileName());
        traceTags.put("document.user_id", userId);
        traceTags.put("document.tags", tags == null ? "" : String.join(",", tags));
        return traceTags;
    }

    private static String extension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private Mono<net.topikachu.rag.business.document.entity.Document> loadDocumentByDocUuid(String docUuid) {
        return Mono.fromCallable(() -> loadDocumentByDocUuidBlocking(docUuid))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private net.topikachu.rag.business.document.entity.Document loadDocumentByDocUuidBlocking(String docUuid) {
        if (docUuid == null || docUuid.isBlank()) {
            return null;
        }
        return documentMapper.selectOne(
                Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaQuery()
                        .eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid)
                        .last("LIMIT 1"));
    }

    private String extractUserMessage(Throwable e) {
        if (e == null)
            return "Unknown error";
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }
        // 截断到 500 字符：数据库 status_message 字段长度有限，完整堆栈会撑爆列
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private String computeHash(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path);
             DigestInputStream dis = new DigestInputStream(in, md)) {
            byte[] buffer = new byte[8192];
            while (dis.read(buffer) != -1)
                ;
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
