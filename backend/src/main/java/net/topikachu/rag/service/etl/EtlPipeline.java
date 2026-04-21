package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class EtlPipeline {

	private final DocReader documentReader;
	private final HybridVectorWriter hybridVectorWriter; // Replace VectorStore
	private final TextSplitter textSplitter;
	// Injected mapper for database operations
	private final DocumentMapper documentMapper;
	private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
	private final TracingSupport tracingSupport;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.read-split-seconds:60}")
	private long readSplitTimeoutSeconds;

	@org.springframework.beans.factory.annotation.Value("${rag.etl.timeout.vectorize-seconds:300}")
	private long vectorizeTimeoutSeconds;

	public EtlPipeline(HybridVectorWriter hybridVectorWriter,
			TextSplitter textSplitter,
			DocReader documentReader,
			DocumentMapper documentMapper,
			org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
			com.fasterxml.jackson.databind.ObjectMapper objectMapper,
			TracingSupport tracingSupport) {
		this.hybridVectorWriter = hybridVectorWriter;
		this.textSplitter = textSplitter;
		this.documentReader = documentReader;
		this.documentMapper = documentMapper;
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

	// TODO::重构解析不同文件格式的策略，（考虑使用Python来处理数据的清洗和切分）
	// Compatible with legacy single path ingestion, now accepts docUuid and userId
	public Mono<Void> ingestionByPath(Path path, String docUuid, String userId, List<String> tags) {
		Map<String, Object> traceTags = etlTraceTags(path, docUuid, userId, tags);
		Mono<Void> pipeline = Mono.defer(() -> {
			// 1. Reading
			return tracingSupport.traceMono("etl.read", traceTags,
					publishStatus(docUuid, userId, DocumentStatus.READING, "Reading file...")
					.then(Mono.fromCallable(() -> {
						DocumentReader reader;
						String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);

						if (fileName.endsWith(".pdf")) {
							// PDF: 强制按页切分，自动提取 page_number
							PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
									.withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
											.withNumberOfBottomTextLinesToDelete(1) // 可选：删除页脚干扰
											.build())
									.build();
							reader = new PagePdfDocumentReader(path.toUri().toString(), config);
						} else {
							// 其他: 保持原样使用 Tika
							reader = new TikaDocumentReader(path.toUri().toString());
						}

						List<Document> docs = reader.get();

						// ======== 优化：单页保留与页首锚点注入 (Page-Document Hybrid) ========
						if (path.toString().toLowerCase().endsWith(".pdf") && !docs.isEmpty()) {
							java.util.List<Document> pageDocs = new java.util.ArrayList<>();
							int pageCounter = 1;
							for (Document pageDoc : docs) {
								// 从元数据获取真实页码，若无则使用计数器
								Object pageNumObj = pageDoc.getMetadata().get("page_number");
								String pageNumStr = pageNumObj != null ? pageNumObj.toString()
										: String.valueOf(pageCounter);

								// 注入给大模型阅读的物理页眉锚点
								String anchoredText = "[--- 以下为文件第 " + pageNumStr + " 页内容 ---]\n" + pageDoc.getText();

								// 构造带有锚点且继承了原始 Metadata 的独立页 Document
								java.util.Map<String, Object> pageMetadata = new java.util.HashMap<>(
										pageDoc.getMetadata());
								pageDocs.add(new Document(anchoredText, pageMetadata));
								pageCounter++;
							}
							docs = pageDocs;
						}
						// ===================================================================

						boolean isPdf = fileName.endsWith(".pdf");
						docs = sanitizeDocuments(path, docs, isPdf);
						if (docs.isEmpty()) {
							throw new IllegalStateException("No usable text extracted after sanitization: " + path);
						}

						// 2. 注入 Metadata (Tags, UUID, FileName)
						for (Document doc : docs) {
							doc.getMetadata().put("doc_uuid", docUuid);
							doc.getMetadata().put("file_name", path.getFileName().toString());
							if (tags != null && !tags.isEmpty()) {
								// Store as List object for Milvus JSON_CONTAINS array compatibility
								doc.getMetadata().put("tags", tags);
							}
						}
						return docs;
					})
							.subscribeOn(Schedulers.boundedElastic())));
		})
				.flatMap(docs -> {
					// 2. Splitting
					return tracingSupport.traceMono("etl.split", traceTags,
							publishStatus(docUuid, userId, DocumentStatus.SPLITTING, "Splitting content...")
									.then(Mono.fromCallable(() -> textSplitter.apply(docs))
											.subscribeOn(Schedulers.boundedElastic())));
				})
				.timeout(java.time.Duration.ofSeconds(readSplitTimeoutSeconds))
				.flatMap(splitDocs -> {
					// 3. Vectorizing - Use HybridVectorWriter for dual vector storage
					return publishStatus(docUuid, userId, DocumentStatus.VECTORIZING, "Writing to Vector Store...")
							.then(Mono.defer(() -> {
								if (!splitDocs.isEmpty()) {
									// Write both dense and sparse vectors
									return hybridVectorWriter.write(splitDocs);
								}
								return Mono.empty();
							}).subscribeOn(Schedulers.boundedElastic()));
				})
				.timeout(java.time.Duration.ofSeconds(vectorizeTimeoutSeconds))
				.then(tracingSupport.traceMono("etl.finish", traceTags, completeStatus(docUuid, userId)))
				.doOnSuccess(v -> log.info("IngestionByPath finished: {}", path))
//				.doOnError(e -> {
//					log.error("Error during ingestionByPath: {}", path, e);
//					// 1. Cleanup Milvus vectors to prevent orphan data
//					hybridVectorWriter.deleteByDocUuid(docUuid)
//							.doOnError(ex -> log.warn("Cleanup failed for {}", docUuid, ex))
//							.subscribe();
//					// 2. Update DB with detailed error info
//					updateFailedStatus(docUuid, userId, e).subscribe();
//				})
				.onErrorResume(e -> {
					log.error("Error during ingestionByPath: {}", path, e);

					// 1. Cleanup Milvus vectors to prevent orphan data
					// 2. Update DB with detailed error info
					// 使用 thenCombine 或 zip 等将多个异步任务合并，最后再抛出原始错误
					return hybridVectorWriter.deleteByDocUuid(docUuid)
							.onErrorResume(ex -> {
								log.warn("Cleanup failed for {}", docUuid, ex);
								return Mono.empty();
							})
							.then(updateFailedStatus(docUuid, userId, e))
							.then(Mono.error(e));
				});
		return tracingSupport.traceMono("etl.ingestion", traceTags, pipeline);
	}

	private Map<String, Object> etlTraceTags(Path path, String docUuid, String userId, List<String> tags) {
		Map<String, Object> traceTags = new LinkedHashMap<>();
		traceTags.put("document.doc_uuid", docUuid);
		traceTags.put("document.file_name", path == null ? "" : path.getFileName());
		traceTags.put("document.user_id", userId);
		traceTags.put("document.tags", tags == null ? "" : String.join(",", tags));
		return traceTags;
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

	// Helper to publish status to DB and Redis non-blocking
	private Mono<Void> publishStatus(String docUuid, String userId, DocumentStatus status, String msg) {
		if (docUuid == null)
			return Mono.empty();

		return Mono.fromRunnable(() -> {
			// 1. Update DB
			try {
				net.topikachu.rag.business.document.entity.Document update = new net.topikachu.rag.business.document.entity.Document();
				update.setStatus(status.name());
				// We use UpdateWrapper to update by docUuid (which is stored in ID or we need
				// to find ID first)
				// Assuming docUuid is NOT the PK 'id' but a field 'doc_uuid'.
				// Actually SysUser id is UUID string, Document id is Long (auto-inc)?
				// Let's check Document entity. Assuming it has doc_uuid field.
				Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaUpdate();

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
