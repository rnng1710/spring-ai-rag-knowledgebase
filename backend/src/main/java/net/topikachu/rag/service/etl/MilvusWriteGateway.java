package net.topikachu.rag.service.etl;

import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class MilvusWriteGateway {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
    private String collectionName;

    @Value("${rag.milvus.acl-refresh-query-limit:10000}")
    private long aclRefreshQueryLimit;

    private final com.google.gson.Gson gson = new com.google.gson.Gson();
    private MilvusClientV2 milvusClient;

    @PostConstruct
    public void init() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .build();
        this.milvusClient = new MilvusClientV2(config);
        log.info("MilvusWriteGateway initialized with Milvus at {}:{}", milvusHost, milvusPort);
    }

    @PreDestroy
    public void cleanup() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    public Mono<InsertResp> insert(List<JsonObject> rows) {
        return Mono.fromCallable(() -> milvusClient.insert(InsertReq.builder()
                        .collectionName(collectionName)
                        .data(rows)
                        .build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<UpsertResp> upsert(List<JsonObject> rows) {
        return Mono.fromCallable(() -> milvusClient.upsert(UpsertReq.builder()
                        .collectionName(collectionName)
                        .data(rows)
                        .build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<MilvusChunkRow>> queryChunksByDocUuid(String docUuid) {
        return Mono.fromCallable(() -> {
                    QueryResp response = milvusClient.query(QueryReq.builder()
                            .collectionName(collectionName)
                            .filter("metadata[\"doc_uuid\"] == \"" + escapeFilterLiteral(docUuid) + "\"")
                            .outputFields(List.of("doc_id", "content", "metadata", "embedding", "sparse_vector"))
                            .limit(aclRefreshQueryLimit)
                            .build());
                    List<MilvusChunkRow> rows = new ArrayList<>();
                    if (response.getQueryResults() == null) {
                        return rows;
                    }
                    for (QueryResp.QueryResult result : response.getQueryResults()) {
                        Map<String, Object> entity = result.getEntity();
                        rows.add(new MilvusChunkRow(
                                entity.get("doc_id") == null ? null : String.valueOf(entity.get("doc_id")),
                                entity.get("content") == null ? null : String.valueOf(entity.get("content")),
                                convertMetadata(entity.get("metadata")),
                                entity.get("embedding"),
                                entity.get("sparse_vector")));
                    }
                    return rows;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteByDocUuid(String docUuid) {
        return Mono.fromRunnable(() -> milvusClient.delete(DeleteReq.builder()
                        .collectionName(collectionName)
                        .filter("metadata[\"doc_uuid\"] == \"" + docUuid + "\"")
                        .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Map<String, Object> convertMetadata(Object metadata) {
        if (metadata instanceof JsonObject jsonObject) {
            return gson.fromJson(jsonObject, Map.class);
        }
        if (metadata instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return new HashMap<>();
    }

    private String escapeFilterLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
