package net.topikachu.rag.service.etl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.observability.TracingSupport;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class HybridVectorWriter {

    private final TeiEmbeddingClient teiEmbeddingClient;
    private final MilvusWriteGateway milvusWriteGateway;
    private final TracingSupport tracingSupport;
    private final Gson gson = new Gson();

    public HybridVectorWriter(TeiEmbeddingClient teiEmbeddingClient,
            MilvusWriteGateway milvusWriteGateway,
            TracingSupport tracingSupport) {
        this.teiEmbeddingClient = teiEmbeddingClient;
        this.milvusWriteGateway = milvusWriteGateway;
        this.tracingSupport = tracingSupport;
    }

    public Mono<Void> write(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Mono.empty();
        }

        log.info("Writing {} documents with hybrid vectors via TEI", documents.size());
        AtomicInteger skippedChunks = new AtomicInteger();
        Map<String, Object> traceTags = vectorTraceTags(documents);

        Mono<List<JsonObject>> embedRows = Flux.fromIterable(documents)
                .flatMap(doc -> teiEmbeddingClient.embed(doc.getText())
                        .map(response -> {
                            List<Float> denseVector = Collections.emptyList();
                            if (response.denseVecs() != null && !response.denseVecs().isEmpty()) {
                                denseVector = response.denseVecs().get(0);
                            }

                            SortedMap<Long, Float> sparseVector = new TreeMap<>();
                            if (response.sparseVecs() != null && !response.sparseVecs().isEmpty()) {
                                sparseVector = teiEmbeddingClient.parseSparse(response.sparseVecs().get(0));
                            }

                            JsonObject row = new JsonObject();
                            row.addProperty("doc_id", doc.getId() != null ? doc.getId() : UUID.randomUUID().toString());
                            row.addProperty("content", doc.getText());
                            row.add("metadata", gson.toJsonTree(doc.getMetadata()));
                            row.add("embedding", gson.toJsonTree(denseVector));
                            row.add("sparse_vector", gson.toJsonTree(sparseVector));
                            return row;
                        })
                        .onErrorResume(error -> {
                            skippedChunks.incrementAndGet();
                            log.warn(
                                    "Skipping chunk during hybrid vector write: docUuid={}, pageNumber={}, preview={}, reason={}",
                                    doc.getMetadata().getOrDefault("doc_uuid", "unknown"),
                                    doc.getMetadata().getOrDefault("page_number", "unknown"),
                                    TextSanitizer.preview(doc.getText()),
                                    error.getMessage());
                            return Mono.empty();
                        }), 4)
                .collectList();

        return tracingSupport.traceMono("etl.embed", traceTags, embedRows)
                .flatMap(rows -> {
                    if (rows.isEmpty()) {
                        return Mono.error(new IllegalStateException("All document chunks failed embedding"));
                    }
                    return tracingSupport.traceMono("etl.vector_upsert",
                            Map.of(
                                    "document.doc_uuid", String.valueOf(traceTags.getOrDefault("document.doc_uuid", "")),
                                    "etl.chunk_count", rows.size()),
                            milvusWriteGateway.insert(rows));
                })
                .doOnNext(this::logInsert)
                .doOnSuccess(v -> {
                    int skipped = skippedChunks.get();
                    if (skipped > 0) {
                        log.warn("Skipped {} chunk(s) during hybrid vector write", skipped);
                    }
                })
                .then();
    }

    public Mono<Void> deleteByDocUuid(String docUuid) {
        if (docUuid == null || docUuid.isEmpty()) {
            return Mono.empty();
        }

        return milvusWriteGateway.deleteByDocUuid(docUuid)
                .doOnSuccess(ignored -> log.info("Cleaned up vectors for docUuid={}", docUuid))
                .onErrorResume(e -> {
                    log.warn("Failed to cleanup vectors for docUuid={}, may not exist", docUuid, e);
                    return Mono.empty();
                });
    }

    private void logInsert(InsertResp response) {
        log.info("Inserted documents to Milvus, insertCnt: {}", response.getInsertCnt());
    }

    private Map<String, Object> vectorTraceTags(List<Document> documents) {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("etl.chunk_count", documents.size());
        if (!documents.isEmpty()) {
            Map<String, Object> metadata = documents.get(0).getMetadata();
            tags.put("document.doc_uuid", metadata.get("doc_uuid"));
            tags.put("document.file_name", metadata.get("file_name"));
        }
        return tags;
    }
}
