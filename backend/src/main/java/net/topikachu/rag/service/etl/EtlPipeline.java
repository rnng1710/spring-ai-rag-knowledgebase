package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.observability.TracingSupport;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.ai.document.DocumentReader;
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

@Component
@Slf4j
public class EtlPipeline {

	private final DocReader documentReader;
	private final HybridVectorWriter hybridVectorWriter; // Replace VectorStore
	private final TextSplitter textSplitter;
	// Injected mapper for database operations
	private final DocumentMapper documentMapper;
	private final DocumentChunkMetadataBuilder metadataBuilder;
	private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
	private final TracingSupport tracingSupport;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-split-seconds:60}")
	private long SplitTimeoutSeconds;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.vectorize-seconds:300}")
	private long vectorizeTimeoutSeconds;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-seconds:30}")
	private long readingTimeoutSeconds;

	public EtlPipeline(HybridVectorWriter hybridVectorWriter,
			TextSplitter textSplitter,
			DocReader documentReader,
			DocumentMapper documentMapper,
			DocumentChunkMetadataBuilder metadataBuilder,
			org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
			com.fasterxml.jackson.databind.ObjectMapper objectMapper,
			TracingSupport tracingSupport) {
		this.hybridVectorWriter = hybridVectorWriter;
		this.textSplitter = textSplitter;
		this.documentReader = documentReader;
		this.documentMapper = documentMapper;
		this.metadataBuilder = metadataBuilder;
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.tracingSupport = tracingSupport;
	}

	public Flux<Document> ingestionFlux() {
		// batch scan logic, might need update if used, but prioritizing single file
		// upload flow for now.
		// For simplification, I'm focusing on ingestionByPath which is used by upload.
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
                            // Simplified adaptation for batch flux if needed, but primary focus is
                            // ingestionByPath
                            return Mono.empty();
                        }));
	}


	// Compatible with legacy single path ingestion, now accepts docUuid and userId
//	public Mono<Void> ingestionByPath(Path path, String docUuid, String userId, List<String> tags) {
//		return newIngestionByPath(path, docUuid, userId, tags);
//	}

	// TODO::重构解析不同文件格式的策略，（考虑使用Python来处理数据的清洗和切分）
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

	private record EtlContext(
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
				publishStatus(ctx.docUuid(), ctx.userId,DocumentStatus.READING, "Reading file...")
						.then(Mono.fromCallable(() -> {
							DocumentReader reader = createReader(ctx.path());
							List<Document> docs = reader.get();

							if (ctx.pdf()) {
								docs = applyPdfPageAnchors(docs);
							}

							docs = sanitizeDocuments(ctx.path(), docs, ctx.pdf());
							if (docs.isEmpty()) {
								throw new IllegalStateException(
										"No usable text extracted after sanitization: " + ctx.path());
							}

							return enrichMetadata(ctx, docs);
						}).subscribeOn(Schedulers.boundedElastic()))
		);
	}

	private DocumentReader createReader(Path path) {

		if (isPdf(path)) {
			// PDF: 强制按页切分，自动提取 page_number
			PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
					.withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
							.withNumberOfBottomTextLinesToDelete(1) // 可选：删除页脚干扰
							.build())
					.build();
			return new PagePdfDocumentReader(path.toUri().toString(), config);
		} else {
			// 其他: 保持原样使用 Tika
			return new TikaDocumentReader(path.toUri().toString());
		}
	}

	private boolean isPdf(Path path) {
		String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return fileName.endsWith(".pdf");
	}

	private List<Document> applyPdfPageAnchors(List<Document> docs) {
			List<Document> pageDocs = new ArrayList<>();
			int pageCounter = 1;
			for (Document pageDoc : docs) {
				// 从元数据获取真实页码，若无则使用计数器
				Object pageNumObj = pageDoc.getMetadata().get("page_number");
				String pageNumStr = pageNumObj != null ? pageNumObj.toString()
						: String.valueOf(pageCounter);

				// 注入给大模型阅读的物理页眉锚点
				String anchoredText = "[--- 以下为文件第 " + pageNumStr + " 页内容 ---]\n" + pageDoc.getText();

				// 构造带有锚点且继承了原始 Metadata 的独立页 Document
				Map<String, Object> pageMetadata = new HashMap<>(
						pageDoc.getMetadata());
				// 拷贝一份map，因为map是共享同一个地址的，直接传入原始map，如果后面对其进行了修改，那么值就不准确了
				pageDocs.add(new Document(anchoredText, pageMetadata));
				pageCounter++;
			}
			return pageDocs;

	}

	private List<Document> enrichMetadata(EtlContext etlContext,List<Document> docs) {
		net.topikachu.rag.business.document.entity.Document storedDocument = loadDocumentByDocUuid(etlContext.docUuid);
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
				publishStatus(ctx.docUuid, ctx.userId, DocumentStatus.SPLITTING, "Splitting content...")
						.then(Mono.fromCallable(() -> textSplitter.apply(docs))
								.subscribeOn(Schedulers.boundedElastic()))
						.doOnNext(splitDocs -> log.info("Split documents: docUuid={}, inputDocs={}, chunks={}",
								ctx.docUuid(), docs.size(), splitDocs.size())));
	}
	private Mono<Void> writeVectors(EtlContext ctx, List<Document> splitDocs) {
		Map<String, Object> traceTags = ctx.tagsWith(Map.of(
				"etl.stage", "vectorize",
				"etl.chunk_count", splitDocs.size()
		));
		return tracingSupport.traceMono("etl.vectorize", traceTags,
				publishStatus(ctx.docUuid, ctx.userId, DocumentStatus.VECTORIZING, "Writing to Vector Store...")
						.then(Mono.defer(() -> {
							if (!splitDocs.isEmpty()) {
								// Write both dense and sparse vectors
								return hybridVectorWriter.write(splitDocs);
							}
							return Mono.empty();
						})));
	}
	private Mono<Void> failAndCleanup(EtlContext ctx, Throwable error) {
		log.error("Error during ingestionByPath: {}", ctx.path, error);

		// 1. Cleanup Milvus vectors to prevent orphan data
		// 2. Update DB with detailed error info
		// 使用 thenCombine 或 zip 等将多个异步任务合并，最后再抛出原始错误
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("etl.stage", "failed");
		extra.put("error.type", error == null ? "" : error.getClass().getSimpleName());
		extra.put("error.message", extractUserMessage(error));
		return tracingSupport.traceMono("etl.fail_cleanup", ctx.tagsWith(extra),
				hybridVectorWriter.deleteByDocUuid(ctx.docUuid)
						.onErrorResume(ex -> {
							log.warn("Cleanup failed for {}", ctx.docUuid, ex);
							return Mono.empty();
						})
						.then(updateFailedStatus(ctx.docUuid, ctx.userId, error))
						.then(Mono.error(error)));
	}

	private Mono<Void> completeStatus(EtlContext ctx) {
		return tracingSupport.traceMono("etl.finish",
				ctx.tagsWith("etl.stage", "finish"),
				completeStatus(ctx.docUuid(), ctx.userId()));
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

	private net.topikachu.rag.business.document.entity.Document loadDocumentByDocUuid(String docUuid) {
		if (docUuid == null || docUuid.isBlank()) {
			return null;
		}
		return documentMapper.selectOne(
				Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaQuery()
						.eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid)
						.last("LIMIT 1"));
	}

	// Helper to publish status to DB and Redis non-blocking
	private Mono<Void> publishStatus(String docUuid, String userId, DocumentStatus status, String msg) {
		if (docUuid == null)
			return Mono.empty();

		return Mono.fromRunnable(() -> {
			// 1. Update DB
			try {
				documentMapper.update(null,
						Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaUpdate()
								.set(net.topikachu.rag.business.document.entity.Document::getStatus, status.name())
								.set(net.topikachu.rag.business.document.entity.Document::getUpdateDate,
										LocalDateTime.now())
								.eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid));
			} catch (Exception e) {
				log.error("Failed to update status in DB for {}", docUuid, e);
			}

			// 2. Publish to Redis
			if (userId != null) {
				try {
					net.topikachu.rag.business.document.event.EtlStatusMessage message = new net.topikachu.rag.business.document.event.EtlStatusMessage(
							docUuid, userId, status, msg);
					String json = objectMapper.writeValueAsString(message);
					redisTemplate.convertAndSend(net.topikachu.rag.config.RedisPubSubConfig.TOPIC_ETL_STATUS, json);
				} catch (Exception e) {
					log.error("Failed to publish Redis message for {}", docUuid, e);
				}
			}
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	// Compatible with legacy interface
	public java.util.List<Document> ingestion() {
		return java.util.Collections.emptyList(); // Deprecated or unused
	}

	/**
	 * Update document status to FAILED with detailed error info.
	 * Stores user-friendly message in error_message and full stack in error_stack.
	 */
	private Mono<Void> updateFailedStatus(String docUuid, String userId, Throwable e) {
		if (docUuid == null)
			return Mono.empty();

		return Mono.fromRunnable(() -> {
			try {
				String userMsg = extractUserMessage(e);
				String techStack = formatStackTrace(e);

				documentMapper.update(null,
						Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaUpdate()
								.set(net.topikachu.rag.business.document.entity.Document::getStatus,
										DocumentStatus.FAILED.name())
								.set(net.topikachu.rag.business.document.entity.Document::getAclRefreshStatus,
										AclRefreshStatus.FAILED.name())
								.set(net.topikachu.rag.business.document.entity.Document::getAclRefreshError, userMsg)
								.set(net.topikachu.rag.business.document.entity.Document::getErrorMessage, userMsg)
								.set(net.topikachu.rag.business.document.entity.Document::getErrorStack, techStack)
								.setSql("retry_count = IFNULL(retry_count, 0) + 1")
								.set(net.topikachu.rag.business.document.entity.Document::getUpdateDate,
										LocalDateTime.now())
								.eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid));

				// Publish to Redis
				if (userId != null) {
					net.topikachu.rag.business.document.event.EtlStatusMessage message = new net.topikachu.rag.business.document.event.EtlStatusMessage(
							docUuid, userId, DocumentStatus.FAILED, userMsg);
					String json = objectMapper.writeValueAsString(message);
					redisTemplate.convertAndSend(net.topikachu.rag.config.RedisPubSubConfig.TOPIC_ETL_STATUS, json);
				}
			} catch (Exception ex) {
				log.error("Failed to update failed status for {}", docUuid, ex);
			}
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	/**
	 * CAS (Compare And Swap) completion.
	 * Only update to COMPLETED if current status is NOT FAILED.
	 * If update fails (meaning Watchdog marked it FAILED), rollback vectors.
	 */
	private Mono<Void> completeStatus(String docUuid, String userId) {
		return Mono.fromCallable(() -> {
			// CAS check: status != FAILED
			// We only want to succeed if the watchdog hasn't killed it.
			int rows = documentMapper.update(null,
					Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaUpdate()
							.set(net.topikachu.rag.business.document.entity.Document::getStatus,
									DocumentStatus.COMPLETED.name())
							.set(net.topikachu.rag.business.document.entity.Document::getAclRefreshStatus,
									AclRefreshStatus.SUCCESS.name())
							.set(net.topikachu.rag.business.document.entity.Document::getAclRefreshError, null)
							.set(net.topikachu.rag.business.document.entity.Document::getAclRefreshTime,
									LocalDateTime.now())
							.set(net.topikachu.rag.business.document.entity.Document::getUpdateDate,
									LocalDateTime.now())
							.eq(net.topikachu.rag.business.document.entity.Document::getDocUuid, docUuid)
							.ne(net.topikachu.rag.business.document.entity.Document::getStatus,
									DocumentStatus.FAILED.name()));

			if (rows == 0) {
				String msg = "CAS conflict: Watchdog already marked doc " + docUuid + " as FAILED. Rolling back.";
				log.warn(msg);
				throw new IllegalStateException(msg);
			}
			return true;
		}).subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(e -> {
					// Rollback: delete vectors
					return hybridVectorWriter.deleteByDocUuid(docUuid)
							.then(Mono.error(e));
				})
				.then(publishRedisMessage(docUuid, userId, DocumentStatus.COMPLETED, "Processing finished"));
	}

	private Mono<Void> publishRedisMessage(String docUuid, String userId, DocumentStatus status, String msg) {
		if (userId == null)
			return Mono.empty();
		return Mono.fromRunnable(() -> {
			try {
				net.topikachu.rag.business.document.event.EtlStatusMessage message = new net.topikachu.rag.business.document.event.EtlStatusMessage(
						docUuid, userId, status, msg);
				String json = objectMapper.writeValueAsString(message);
				redisTemplate.convertAndSend(net.topikachu.rag.config.RedisPubSubConfig.TOPIC_ETL_STATUS, json);
			} catch (Exception e) {
				log.error("Failed to publish Redis message for {}", docUuid, e);
			}
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	// TODO:: 文件上传失败时的用户提示，后端未验证效果，前端页面没有编写
	/**
	 * Extract user-friendly error message from exception.
	 */
	private String extractUserMessage(Throwable e) {
		if (e == null)
			return "Unknown error";
		// Get root cause message
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

	/**
	 * Format stack trace for technical debugging, limit to 2000 chars.
	 */
	private String formatStackTrace(Throwable e) {
		if (e == null)
			return null;
		java.io.StringWriter sw = new java.io.StringWriter();
		e.printStackTrace(new java.io.PrintWriter(sw));
		String full = sw.toString();
		return full.length() > 2000 ? full.substring(0, 2000) : full;
	}

	/**
	 * Compute SHA-256 hash of the file
	 */
	private String computeHash(Path path) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		try (InputStream in = Files.newInputStream(path);
				DigestInputStream dis = new DigestInputStream(in, md)) {
			byte[] buffer = new byte[8192];
			// Read stream to calculate digest
			while (dis.read(buffer) != -1)
				;
		}
		return HexFormat.of().formatHex(md.digest());
	}
}
