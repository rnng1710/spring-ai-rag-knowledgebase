package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.DocumentStatus;
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

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-split-seconds:60}")
	private long SplitTimeoutSeconds;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.vectorize-seconds:300}")
	private long vectorizeTimeoutSeconds;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-seconds:900}")
	private long readingTimeoutSeconds;

	public EtlPipeline(HybridVectorWriter hybridVectorWriter,
			TextSplitter textSplitter,
			DocReader documentReader,
			DocumentMapper documentMapper,
			DocumentChunkMetadataBuilder metadataBuilder,
			TracingSupport tracingSupport,
			FileParseStrategyFactory fileParseStrategyFactory,
			EtlStatusManager etlStatusManager) {
		this.hybridVectorWriter = hybridVectorWriter;
		this.textSplitter = textSplitter;
		this.documentReader = documentReader;
		this.documentMapper = documentMapper;
		this.metadataBuilder = metadataBuilder;
		this.tracingSupport = tracingSupport;
		this.fileParseStrategyFactory = fileParseStrategyFactory;
		this.etlStatusManager = etlStatusManager;
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
				.flatMap(splitDocs -> writeVectors(ctx, splitDocs))
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
	private Mono<List<Document>> splitDocuments(EtlContext ctx, List<Document> docs) {
		Map<String, Object> traceTags = ctx.tagsWith(Map.of(
				"etl.stage", "split",
				"etl.input_doc_count", docs.size()
		));
		return tracingSupport.traceMono("etl.split", traceTags,
				etlStatusManager.transitionTo(ctx.docUuid, ctx.userId, DocumentStatus.SPLITTING, "Splitting content...")
						.then(Mono.fromCallable(() -> textSplitter.apply(docs))
								.subscribeOn(Schedulers.boundedElastic()))
						.map(splitDocs -> assignEvidenceMetadata(ctx, splitDocs))
						.doOnNext(splitDocs -> log.info("Split documents: docUuid={}, inputDocs={}, chunks={}",
								ctx.docUuid(), docs.size(), splitDocs.size())));
	}

	private List<Document> assignEvidenceMetadata(EtlContext ctx, List<Document> splitDocs) {
		for (int i = 0; i < splitDocs.size(); i++) {
			Document doc = splitDocs.get(i);
			int chunkIndex = i + 1;
			doc.getMetadata().put("chunk_index", chunkIndex);
			doc.getMetadata().put("evidence_id", buildEvidenceId(ctx.docUuid(), doc, chunkIndex));
		}
		return splitDocs;
	}

	private String buildEvidenceId(String docUuid, Document doc, int chunkIndex) {
		Object page = doc.getMetadata().getOrDefault("page_number", doc.getMetadata().get("page"));
		String pageValue = page == null ? "0" : page.toString();
		String normalizedText = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ").trim();
		return docUuid + ":" + pageValue + ":" + chunkIndex + ":" + sha256Short(normalizedText);
	}

	private String sha256Short(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to build evidence id", e);
		}
	}
	private Mono<Void> writeVectors(EtlContext ctx, List<Document> splitDocs) {
		Map<String, Object> traceTags = ctx.tagsWith(Map.of(
				"etl.stage", "vectorize",
				"etl.chunk_count", splitDocs.size()
		));
		return tracingSupport.traceMono("etl.vectorize", traceTags,
				etlStatusManager.transitionTo(ctx.docUuid, ctx.userId, DocumentStatus.VECTORIZING, "Writing to Vector Store...")
						.then(Mono.defer(() -> {
							if (!splitDocs.isEmpty()) {
								return hybridVectorWriter.write(splitDocs);
							}
							return Mono.empty();
						})));
	}
	private Mono<Void> failAndCleanup(EtlContext ctx, Throwable error) {
		log.error("Error during ingestionByPath: {}", ctx.path, error);

		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("etl.stage", "failed");
		extra.put("error.type", error == null ? "" : error.getClass().getSimpleName());
		extra.put("error.message", extractUserMessage(error));
		return tracingSupport.traceMono("etl.fail_cleanup", ctx.tagsWith(extra),
				etlStatusManager.transitionToFailed(ctx.docUuid(), ctx.userId(), error)
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
