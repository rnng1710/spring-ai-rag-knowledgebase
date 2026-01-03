package net.topikachu.rag.service.etl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;

@Component
@Slf4j
public class EtlPipeline {

	private final DocReader documentReader;
	private final VectorStore vectorStore;
	private final TextSplitter textSplitter;

	public EtlPipeline(VectorStore vectorStore,
	                   TextSplitter textSplitter,
	                   DocReader documentReader) {
		this.vectorStore = vectorStore;
		this.textSplitter = textSplitter;
		this.documentReader = documentReader;
	}

	public Flux<Document> ingestionFlux() {
		return documentReader.getDocuments()
				.flatMap(document -> {
					var processChunks = Mono.fromRunnable(() -> {
						var chunks = textSplitter.apply(List.of(document));
						vectorStore.write(chunks); // expensive operation
					}).subscribeOn(Schedulers.boundedElastic());

					return Flux.concat(
							Flux.just(document),
							processChunks.then(Mono.empty()) // suppress downstream emission
					);


				})

				.doOnComplete(() -> log.info("RunIngestion() finished"))
				.doOnError(e -> log.error("Error during ingestion", e));

	}

	public Mono<Void> ingestionByPath(Path path) {
		return Mono.fromCallable(() -> new TikaDocumentReader(path.toUri().toString()).get())
				.subscribeOn(Schedulers.boundedElastic()) // Tika 解析也放到 elastic
				.map(textSplitter::apply)                 // List<Document> -> chunks(List<Document>)
				.doOnNext(vectorStore::write)             // 写入向量库（阻塞/昂贵）
				.then()
				.doOnSuccess(v -> log.info("RunIngestionByPath() finished: {}", path))
				.doOnError(e -> log.error("Error during ingestionByPath: {}", path, e))
				.subscribeOn(Schedulers.boundedElastic()); // 确保 write 也不会跑到事件线程
	}

	// Keep the original ingestion() method for backward compatibility
	public List<Document> ingestion() {
		return ingestionFlux().collectList().block();
	}


}
