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

	public EtlPipeline(VectorStore vectorStore,
			TextSplitter textSplitter,
			DocReader documentReader,
			DocumentMapper documentMapper) {
		this.vectorStore = vectorStore;
		this.textSplitter = textSplitter;
		this.documentReader = documentReader;
		this.documentMapper = documentMapper;
	}

	public Flux<Document> ingestionFlux() {
		return documentReader.scanDirectory()
				.flatMap(path -> {
					return Mono.fromCallable(() -> {
						// 1. Calculate file hash (SHA-256)
						String hash = computeHash(path);

						// 2. Check if the hash exists in the database
						Long count = documentMapper.selectCount(
								Wrappers.<net.topikachu.rag.business.document.entity.Document>lambdaQuery()
										.eq(net.topikachu.rag.business.document.entity.Document::getFileHash, hash));

						if (count > 0) {
							log.info("Skipping existing file: {}", path.getFileName());
							return null; // Return null to signal skipping
						}

						// Return hash for next step
						return hash;
					})
							.subscribeOn(Schedulers.boundedElastic()) // Execute IO/DB in elastic pool
							.flatMapMany(hash -> {
								// If hash is null, it means file exists, return empty to skip
								if (hash == null)
									return Mono.empty();

								// 3. Process new file
								return Mono.fromRunnable(() -> {
									// 3.1 Insert record into DB first, status UPLOADED
									net.topikachu.rag.business.document.entity.Document newDoc = new net.topikachu.rag.business.document.entity.Document();
									String docUuid = UUID.randomUUID().toString().replace("-", "");
									newDoc.setDocUuid(docUuid);
									newDoc.setFileName(path.getFileName().toString());
									newDoc.setStatus(DocumentStatus.UPLOADED.name());
									newDoc.setFileHash(hash);
									newDoc.setCreateDate(LocalDateTime.now());
									newDoc.setUpdateDate(LocalDateTime.now());
									try {
										documentMapper.insert(newDoc);
										log.info("Created database record for: {}", path.getFileName());
									} catch (Exception e) {
										// Likely duplicate key due to concurrent upload; log warning and skip vector
										// ingestion
										log.warn(
												"Failed to insert record, possibly concurrent upload, skipping ingestion: {}",
												path, e);
										return;
									}

									// 3.2 Read file content, split, and write to Vector Store
									// Note: expensive operation
									try {
										List<Document> docs = new TikaDocumentReader(path.toUri().toString()).get();
										List<Document> splitDocs = textSplitter.apply(docs);
										// Inject doc_uuid to metadata for cascading delete
										for (Document d : splitDocs) {
											d.getMetadata().put("doc_uuid", docUuid);
										}
										vectorStore.write(splitDocs);
										log.info("Finished vector ingestion for: {}", path.getFileName());
									} catch (Exception e) {
										log.error("Vector store write failed for: {}", path, e);
										throw new RuntimeException(e);
									}
								})
										.subscribeOn(Schedulers.boundedElastic())
										.thenMany(Flux.defer(() -> {
											// To be compatible with downstream logic (e.g. Controller
											// .map(getMetadata)),
											// we return the Documents processed.
											// Re-read Tika to get metadata (or optimize by passing it through)
											return Flux.fromIterable(
													new TikaDocumentReader(path.toUri().toString()).get());
										}));
							});
				})
				.doOnComplete(() -> log.info("Batch ingestion task finished (RunIngestion finished)"))
				.doOnError(e -> log.error("Error during batch ingestion task", e));
	}

	// Compatible with legacy single path ingestion, now accepts docUuid
	public Mono<Void> ingestionByPath(Path path, String docUuid) {
		return Mono.fromCallable(() -> new TikaDocumentReader(path.toUri().toString()).get())
				.subscribeOn(Schedulers.boundedElastic())
				.map(textSplitter::apply)
				.doOnNext(docs -> {
					if (docUuid != null) {
						for (Document d : docs) {
							d.getMetadata().put("doc_uuid", docUuid);
						}
					}
					vectorStore.write(docs);
				})
				.then()
				.doOnSuccess(v -> log.info("IngestionByPath finished: {}", path))
				.doOnError(e -> log.error("Error during ingestionByPath: {}", path, e))
				.subscribeOn(Schedulers.boundedElastic());
	}

	// Compatible with legacy interface
	public List<Document> ingestion() {
		return ingestionFlux().collectList().block();
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
