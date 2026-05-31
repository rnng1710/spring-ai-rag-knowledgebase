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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

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

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-split-seconds:60}")
	private long SplitTimeoutSeconds;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.vectorize-seconds:300}")
	private long vectorizeTimeoutSeconds;

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

		Mono<Void> pipeline = readDocuments(ctx)
				.timeout(java.time.Duration.ofSeconds(readingTimeoutSeconds))
				.flatMap(docs -> splitDocuments(ctx, docs))
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
			boolean pdf,
			Map<String, Object> traceTags
	) {
		public static EtlContext of(Path path, String docUuid, String userId, List<String> tags) {
			String fileName = path.getFileName().toString();
			boolean pdf = fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
			Map<String, Object> traceTags = etlTraceTags(path, docUuid, userId, tags);
			traceTags.put("document.file_ext", extension(fileName));
			traceTags.put("document.is_pdf", pdf);
			return new EtlContext(
					path,
					docUuid,
					userId,
					tags,
					fileName,
					pdf,
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

	private Mono<List<Document>> readDocuments(EtlContext ctx) {
		return tracingSupport.traceMono("etl.read", ctx.tagsWith("etl.stage", "read"),
				etlStatusManager.transitionTo(ctx.docUuid(), ctx.userId, DocumentStatus.READING, "Reading file...")
						.then(Mono.fromCallable(() -> {
							String ext = extension(ctx.fileName());
							FileParseStrategy strategy = fileParseStrategyFactory.getFileParseStrategyOrNull(ext);
							if (strategy != null) {
								return strategy.readFile(ext, ctx);
							}
							return readAndSanitize(ctx);
						})
								.subscribeOn(Schedulers.boundedElastic()))
						.flatMap(docs -> loadDocumentByDocUuid(ctx.docUuid)
								.map(storedDoc -> enrichMetadata(ctx, docs, storedDoc)))
		);
	}

	private List<Document> readAndSanitize(EtlContext ctx) throws Exception {
		List<Document> docs = new TikaDocumentReader(ctx.path().toUri().toString()).get();

		docs = sanitizeDocuments(ctx.path(), docs, false);
		if (docs.isEmpty()) {
			throw new IllegalStateException(
					"No usable text extracted after sanitization: " + ctx.path());
		}
		return docs;
	}

	private List<Document> enrichMetadata(EtlContext etlContext, List<Document> docs,
			net.topikachu.rag.business.document.entity.Document storedDocument) {
		List<String> effectiveTags = etlContext.tags;
		if (storedDocument != null && storedDocument.getTags() != null) {
			effectiveTags = storedDocument.getTags();
		}
		for (Document doc : docs) {
			Map<String, Object> rebuiltMetadata = metadataBuilder.build(
					storedDocument,
					etlContext.docUuid,
					etlContext.path.getFileName().toString(),
					effectiveTags,
					doc.getMetadata(),
					storedDocument == null ? 1 : storedDocument.getAclVersion());
			doc.getMetadata().clear();
			doc.getMetadata().putAll(rebuiltMetadata);
		}

		return docs;
	}
	private Mono<ParentChildDocuments> splitDocuments(EtlContext ctx, List<Document> docs) {
		Map<String, Object> traceTags = ctx.tagsWith(Map.of(
				"etl.stage", "split",
				"etl.input_doc_count", docs.size()
		));
		return tracingSupport.traceMono("etl.split", traceTags,
				etlStatusManager.transitionTo(ctx.docUuid, ctx.userId, DocumentStatus.SPLITTING, "Splitting content...")
						.then(Mono.fromCallable(() -> buildParentChildDocuments(ctx, docs))
								.subscribeOn(Schedulers.boundedElastic()))
						.doOnNext(parentChildDocs -> log.info(
								"Split documents: docUuid={}, inputDocs={}, parentBlocks={}, childChunks={}",
								ctx.docUuid(), docs.size(),
								parentChildDocs.parentBlocks().size(),
								parentChildDocs.childDocuments().size())));
	}

	private ParentChildDocuments buildParentChildDocuments(EtlContext ctx, List<Document> docs) {
		List<Document> parentDocuments = ctx.pdf()
				? buildPdfParentDocuments(docs)
				: buildNonPdfParentDocuments(docs);

		List<KnowledgeParentBlock> parentBlocks = new ArrayList<>();
		List<Document> childDocuments = new ArrayList<>();
		int childIndex = 1;

		for (Document parentDocument : parentDocuments) {
			parentBlocks.add(toParentBlock(parentDocument));
			List<Document> children = textSplitter.apply(List.of(parentDocument));
			for (Document child : children) {
				Map<String, Object> metadata = child.getMetadata();
				metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
				metadata.put("parent_block_id", parentDocument.getMetadata().get("parent_block_id"));
				metadata.put("parent_index", parentDocument.getMetadata().get("parent_index"));
				metadata.put("child_index", childIndex);
				metadata.put("evidence_id", buildEvidenceId(ctx.docUuid(), child, childIndex));
				copyIfPresent(parentDocument.getMetadata(), metadata, "page_start");
				copyIfPresent(parentDocument.getMetadata(), metadata, "page_end");
				childDocuments.add(child);
				childIndex++;
			}
		}

		return new ParentChildDocuments(parentBlocks, childDocuments);
	}

	private String buildEvidenceId(String docUuid, Document doc, int chunkIndex) {
		String normalizedText = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ").trim();
		return docUuid + ":child:" + chunkIndex + ":" + sha256Short(normalizedText);
	}

	private String sha256Short(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to build evidence id", e);
		}
	}
	private Mono<Void> writeParentBlocksAndVectors(EtlContext ctx, ParentChildDocuments parentChildDocs) {
		Map<String, Object> traceTags = ctx.tagsWith(Map.of(
				"etl.stage", "vectorize",
				"etl.parent_block_count", parentChildDocs.parentBlocks().size(),
				"etl.chunk_count", parentChildDocs.childDocuments().size()
		));
		return tracingSupport.traceMono("etl.vectorize", traceTags,
				etlStatusManager.transitionTo(ctx.docUuid, ctx.userId, DocumentStatus.VECTORIZING, "Writing to Vector Store...")
						.then(parentBlockService.replaceForDocument(ctx.docUuid(), parentChildDocs.parentBlocks()))
						.then(Mono.defer(() -> {
							if (!parentChildDocs.childDocuments().isEmpty()) {
								return hybridVectorWriter.write(parentChildDocs.childDocuments());
							}
							return Mono.empty();
						})));
	}

	private List<Document> buildPdfParentDocuments(List<Document> docs) {
		List<Document> parents = new ArrayList<>();
		int parentIndex = 1;
		for (Document doc : docs) {
			Object page = doc.getMetadata().getOrDefault("page_number", doc.getMetadata().get("page"));
			Integer pageNumber = parseInteger(page);
			List<String> pieces = splitByFixedWindow(doc.getText(), parentBlockSize, parentBlockOverlap);
			for (String piece : pieces) {
				Map<String, Object> metadata = new LinkedHashMap<>(doc.getMetadata());
				metadata.put("parent_index", parentIndex);
				if (pageNumber != null) {
					metadata.put("page_start", pageNumber);
					metadata.put("page_end", pageNumber);
				}
				metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
				metadata.put("parent_block_id", buildParentBlockId(metadata, piece, parentIndex));
				parents.add(new Document(piece, metadata));
				parentIndex++;
			}
		}
		return parents;
	}

	private List<Document> buildNonPdfParentDocuments(List<Document> docs) {
		if (docs == null || docs.isEmpty()) {
			return List.of();
		}
		Map<String, Object> baseMetadata = new LinkedHashMap<>(docs.get(0).getMetadata());
		String joinedText = docs.stream()
				.map(Document::getText)
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(text -> !text.isBlank())
				.reduce((left, right) -> left + "\n\n" + right)
				.orElse("");
		List<String> pieces = splitByFixedWindow(joinedText, parentBlockSize, parentBlockOverlap);
		List<Document> parents = new ArrayList<>();
		for (int i = 0; i < pieces.size(); i++) {
			int parentIndex = i + 1;
			Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
			metadata.put("parent_index", parentIndex);
			metadata.put("chunk_schema_version", KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
			metadata.put("parent_block_id", buildParentBlockId(metadata, pieces.get(i), parentIndex));
			parents.add(new Document(pieces.get(i), metadata));
		}
		return parents;
	}

	private List<String> splitByFixedWindow(String text, int size, int overlap) {
		TextSanitizer.SanitizationResult sanitized = TextSanitizer.sanitize(text);
		if (sanitized.isEffectivelyEmpty()) {
			return List.of();
		}
		String value = sanitized.text();
		int safeSize = Math.max(1, size);
		int safeOverlap = Math.max(0, Math.min(overlap, safeSize - 1));
		List<String> pieces = new ArrayList<>();
		int start = 0;
		while (start < value.length()) {
			int end = Math.min(value.length(), start + safeSize);
			String piece = value.substring(start, end).trim();
			if (!piece.isBlank()) {
				pieces.add(piece);
			}
			if (end >= value.length()) {
				break;
			}
			start = end - safeOverlap;
		}
		return pieces;
	}

	private String buildParentBlockId(Map<String, Object> metadata, String text, int parentIndex) {
		String docUuid = String.valueOf(metadata.getOrDefault("doc_uuid", ""));
		String normalizedText = text == null ? "" : text.replaceAll("\\s+", " ").trim();
		return docUuid + ":parent:" + parentIndex + ":" + sha256Short(normalizedText);
	}

	private KnowledgeParentBlock toParentBlock(Document parentDocument) {
		Map<String, Object> metadata = parentDocument.getMetadata();
		KnowledgeParentBlock block = new KnowledgeParentBlock();
		block.setId(UUID.randomUUID().toString().replace("-", ""));
		block.setParentBlockId(stringValue(metadata.get("parent_block_id")));
		block.setDocUuid(stringValue(metadata.get("doc_uuid")));
		block.setParentIndex(parseInteger(metadata.get("parent_index")));
		block.setContent(parentDocument.getText());
		block.setFileName(stringValue(metadata.get("file_name")));
		block.setPageStart(parseInteger(metadata.get("page_start")));
		block.setPageEnd(parseInteger(metadata.get("page_end")));
		block.setSpaceCode(stringValue(metadata.get("space_code")));
		block.setTags(toStringList(metadata.get("tags")));
		block.setAclVersion(parseInteger(metadata.get("acl_version")));
		block.setChunkSchemaVersion(KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
		block.setCreateDate(LocalDateTime.now());
		block.setUpdateDate(LocalDateTime.now());
		return block;
	}

	private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
		if (source != null && source.get(key) != null) {
			target.put(key, source.get(key));
		}
	}

	private Integer parseInteger(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Integer.parseInt(value.toString());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private String stringValue(Object value) {
		return value == null ? null : value.toString();
	}

	private List<String> toStringList(Object value) {
		if (value instanceof List<?> list) {
			return list.stream()
					.filter(Objects::nonNull)
					.map(Object::toString)
					.filter(item -> !item.isBlank())
					.toList();
		}
		return List.of();
	}

	private record ParentChildDocuments(
			List<KnowledgeParentBlock> parentBlocks,
			List<Document> childDocuments
	) {
	}
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

	private List<Document> sanitizeDocuments(Path path, List<Document> docs, boolean isPdf) {
		java.util.List<Document> sanitizedDocs = new java.util.ArrayList<>();
		int droppedCount = 0;

		for (Document doc : docs) {
			TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(doc.getText());
			Object pageNumber = doc.getMetadata().get("page_number");
			String sourceLabel = isPdf && pageNumber != null
					? "page " + pageNumber
					: "document chunk";

			if (result.wasModified()) {
				log.info(
						"Sanitized {} from {}: removedChars={}, normalizedWhitespace={}, originalLength={}, sanitizedLength={}, preview={}",
						sourceLabel,
						path.getFileName(),
						result.removedChars(),
						result.normalizedWhitespace(),
						result.originalLength(),
						result.text().length(),
						TextSanitizer.preview(result.text()));
			}

			if (result.isLowQualityExtraction()) {
				log.warn(
						"Low-quality {} extraction for {}: removedRatio={}, meaningfulCodePoints={}, sanitizedLength={}, preview={}",
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

			sanitizedDocs.add(new Document(result.text(), new java.util.HashMap<>(doc.getMetadata())));
		}

		if (droppedCount > 0) {
			log.info("Dropped {} sanitized document(s) for {}", droppedCount, path.getFileName());
		}

		return sanitizedDocs;
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
