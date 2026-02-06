package net.topikachu.rag.service.etl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * Hybrid Vector Writer that writes both dense and sparse vectors to Milvus.
 * Bypasses Spring AI VectorStore to enable sparse vector support.
 */
@Component
@Slf4j
public class HybridVectorWriter {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
    private String collectionName;

    private final TeiEmbeddingClient teiEmbeddingClient;
    private final Gson gson = new Gson();
    private MilvusClientV2 milvusClient;

    public HybridVectorWriter(TeiEmbeddingClient teiEmbeddingClient) {
        this.teiEmbeddingClient = teiEmbeddingClient;
    }

    @PostConstruct
    public void init() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .build();
        this.milvusClient = new MilvusClientV2(config);
        log.info("HybridVectorWriter initialized with Milvus at {}:{}", milvusHost, milvusPort);
    }

    @PreDestroy
    public void cleanup() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    /**
     * Write documents with both dense and sparse vectors to Milvus.
     * Uses TEI for embedding generation.
     */
    public reactor.core.publisher.Mono<Void> write(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return reactor.core.publisher.Mono.empty();
        }

        log.info("Writing {} documents with hybrid vectors via TEI", documents.size());

        return reactor.core.publisher.Flux.fromIterable(documents)
                .flatMap(doc -> {
                    String content = doc.getText();
                    // Parallel call to TEI for Dense and Sparse
                    return reactor.core.publisher.Mono.zip(
                            teiEmbeddingClient.embedDense(content),
                            teiEmbeddingClient.embedSparse(content)).map(tuple -> {
                                List<Float> denseVector = tuple.getT1();
                                SortedMap<Long, Float> sparseVector = tuple.getT2();

                                String docId = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();

                                JsonObject row = new JsonObject();
                                row.addProperty("doc_id", docId);
                                row.addProperty("content", content);
                                row.add("metadata", gson.toJsonTree(doc.getMetadata()));
                                row.add("embedding", gson.toJsonTree(denseVector));
                                row.add("sparse_vector", gson.toJsonTree(sparseVector));

                                return row;
                            });
                }, 4) // Concurrency control: 4 parallel requests to TEI
                .collectList()
                .flatMap(rows -> reactor.core.publisher.Mono.fromRunnable(() -> {
                    try {
                        InsertResp response = milvusClient.insert(InsertReq.builder()
                                .collectionName(collectionName)
                                .data(rows)
                                .build());
                        log.info("Inserted {} documents to Milvus, insertCnt: {}", rows.size(),
                                response.getInsertCnt());
                    } catch (Exception e) {
                        log.error("Failed to insert documents to Milvus", e);
                        throw new RuntimeException("Milvus insert failed", e);
                    }
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .then();
    }

    /**
     * Delete vectors by doc_uuid for rollback on failure.
     * Used to clean up orphan vectors when ingestion fails.
     */
    public reactor.core.publisher.Mono<Void> deleteByDocUuid(String docUuid) {
        if (docUuid == null || docUuid.isEmpty()) {
            return reactor.core.publisher.Mono.empty();
        }

        return reactor.core.publisher.Mono.fromRunnable(() -> {
            try {
                io.milvus.v2.service.vector.request.DeleteReq deleteReq = io.milvus.v2.service.vector.request.DeleteReq
                        .builder()
                        .collectionName(collectionName)
                        .filter("metadata[\"doc_uuid\"] == \"" + docUuid + "\"")
                        .build();
                milvusClient.delete(deleteReq);
                log.info("Cleaned up vectors for docUuid={}", docUuid);
            } catch (Exception e) {
                log.warn("Failed to cleanup vectors for docUuid={}, may not exist", docUuid, e);
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }
}
