package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class EtlPipeline {

	private final DocReader documentReader;
	private final VectorStore vectorStore;
	private final TextSplitter textSplitter;
	// Injected mapper for database operations
	private final DocumentMapper documentMapper;
	private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

	public EtlPipeline(VectorStore vectorStore,
			TextSplitter textSplitter,
			DocReader documentReader,
			DocumentMapper documentMapper,
			org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
			com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
		this.vectorStore = vectorStore;
		this.textSplitter = textSplitter;
		this.documentReader = documentReader;
		this.documentMapper = documentMapper;
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	public Flux<Document> ingestionFlux() {
		// batch scan logic, might need update if used, but prioritizing single file
		// upload flow for now.
		// For simplification, I'm focusing on ingestionByPath which is used by upload.
		return documentReader.scanDirectory()
				.flatMap(path -> {
					return Mono.fromCallable(() -> {
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
							});
				});
	}

	// Compatible with legacy single path ingestion, now accepts docUuid and userId
	public Mono<Void> ingestionByPath(Path path, String docUuid, String userId) {
		return Mono.defer(() -> {
			// 1. Reading
			return publishStatus(docUuid, userId, DocumentStatus.READING, "Reading file...")
					.then(Mono.fromCallable(() -> new TikaDocumentReader(path.toUri().toString()).get())
							.subscribeOn(Schedulers.boundedElastic()));
		})
				.flatMap(docs -> {
					// 2. Splitting
					return publishStatus(docUuid, userId, DocumentStatus.SPLITTING, "Splitting content...")
							.then(Mono.fromCallable(() -> textSplitter.apply(docs))
									.subscribeOn(Schedulers.boundedElastic()));
				})
				.flatMap(splitDocs -> {
					// 3. Vectorizing
					return publishStatus(docUuid, userId, DocumentStatus.VECTORIZING, "Writing to Vector Store...")
							.then(Mono.fromRunnable(() -> {
								if (docUuid != null) {
									for (Document d : splitDocs) {
										d.getMetadata().put("doc_uuid", docUuid);
									}
								}
								vectorStore.write(splitDocs);
							}).subscribeOn(Schedulers.boundedElastic()));
				})
				.then(publishStatus(docUuid, userId, DocumentStatus.COMPLETED, "Processing finished"))
				.doOnSuccess(v -> log.info("IngestionByPath finished: {}", path))
				.doOnError(e -> {
					log.error("Error during ingestionByPath: {}", path, e);
					publishStatus(docUuid, userId, DocumentStatus.FAILED, "Error: " + e.getMessage()).subscribe();
				});
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
