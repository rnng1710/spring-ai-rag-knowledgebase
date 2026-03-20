package net.topikachu.rag.service.chat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
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
public class MilvusSearchGateway {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    private final Gson gson = new Gson();
    private MilvusClientV2 milvusClient;

    @PostConstruct
    public void init() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .build();
        this.milvusClient = new MilvusClientV2(config);
        log.info("MilvusSearchGateway initialized with Milvus at {}:{}", milvusHost, milvusPort);
    }

    @PreDestroy
    public void cleanup() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    public Mono<List<Document>> search(SearchReq searchReq) {
        return Mono.fromCallable(() -> convertToDocuments(milvusClient.search(searchReq)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Document>> hybridSearch(HybridSearchReq searchReq) {
        return Mono.fromCallable(() -> convertToDocuments(milvusClient.hybridSearch(searchReq)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Document> queryByDocId(QueryReq queryReq) {
        return Mono.fromCallable(() -> {
            List<Document> docs = convertQueryToDocuments(milvusClient.query(queryReq));
            return docs.isEmpty() ? null : docs.get(0);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private List<Document> convertToDocuments(SearchResp response) {
        List<Document> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = response.getSearchResults();
        if (searchResults == null || searchResults.isEmpty()) {
            return results;
        }

        for (SearchResp.SearchResult result : searchResults.get(0)) {
            Map<String, Object> entity = result.getEntity();
            Map<String, Object> metadata = convertMetadata(entity.get("metadata"));
            metadata.put("score", result.getScore());
            results.add(Document.builder()
                    .id((String) entity.get("doc_id"))
                    .text((String) entity.get("content"))
                    .metadata(metadata)
                    .build());
        }
        return results;
    }

    private List<Document> convertQueryToDocuments(QueryResp response) {
        List<Document> results = new ArrayList<>();
        List<QueryResp.QueryResult> queryResults = response.getQueryResults();
        if (queryResults == null || queryResults.isEmpty()) {
            return results;
        }

        for (QueryResp.QueryResult result : queryResults) {
            Map<String, Object> entity = result.getEntity();
            results.add(Document.builder()
                    .id((String) entity.get("doc_id"))
                    .text((String) entity.get("content"))
                    .metadata(convertMetadata(entity.get("metadata")))
                    .build());
        }
        return results;
    }

    private Map<String, Object> convertMetadata(Object metaObj) {
        Map<String, Object> metadata = new HashMap<>();
        if (metaObj instanceof JsonObject jsonObject) {
            metadata = gson.fromJson(jsonObject, Map.class);
        } else if (metaObj instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> castMeta = (Map<String, Object>) map;
            metadata = castMeta;
        }
        return metadata;
    }
}
