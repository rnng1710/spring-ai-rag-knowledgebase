package net.topikachu.rag.service.chat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;

import net.topikachu.rag.service.etl.TeiEmbeddingClient;
import org.springframework.ai.document.Document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * Hybrid Search Service that performs dense + sparse vector search with RRF
 * fusion.
 */
@Service
@Slf4j
public class HybridSearchService {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
    private String collectionName;

    @Value("${rag.retrieval.dense-topk:50}")
    private int denseTopK;

    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;

    private final TeiEmbeddingClient teiEmbeddingClient;
    private final Gson gson = new Gson();
    private MilvusClientV2 milvusClient;

    public HybridSearchService(TeiEmbeddingClient teiEmbeddingClient) {
        this.teiEmbeddingClient = teiEmbeddingClient;
    }

    @PostConstruct
    public void init() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .build();
        this.milvusClient = new MilvusClientV2(config);
        log.info("HybridSearchService initialized with Milvus at {}:{}", milvusHost, milvusPort);
    }

    @PreDestroy
    public void cleanup() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    /**
     * Perform hybrid search (dense + sparse) with server-side RRF fusion.
     * Uses TEI for embedding generation.
     */
    public List<Document> hybridSearch(String query, List<String> filterTags, int topK) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. Generate vectors via TEI (Parallel)
            // Block here because ChatService expects a synchronous List<Document>
            return reactor.core.publisher.Mono.zip(
                    teiEmbeddingClient.embedDense(query),
                    teiEmbeddingClient.embedSparse(query)).map(tuple -> {
                        List<Float> denseVector = tuple.getT1();
                        SortedMap<Long, Float> sparseMap = tuple.getT2();

                        FloatVec denseVec = new FloatVec(denseVector);
                        SparseFloatVec sparseVec = new SparseFloatVec(sparseMap);

                        // 2. Build filter expression
                        String filterExpr = null;
                        if (filterTags != null && !filterTags.isEmpty()) {
                            filterExpr = String.format("JSON_CONTAINS(metadata, '\"%s\"', '$.tags')",
                                    filterTags.get(0));
                        }

                        // 3. Build ANN search requests
                        AnnSearchReq.AnnSearchReqBuilder<?, ?> denseReqBuilder = AnnSearchReq.builder()
                                .vectorFieldName("embedding")
                                .vectors(Collections.singletonList(denseVec))
                                .topK(denseTopK);

                        AnnSearchReq.AnnSearchReqBuilder<?, ?> sparseReqBuilder = AnnSearchReq.builder()
                                .vectorFieldName("sparse_vector")
                                .vectors(Collections.singletonList(sparseVec))
                                .topK(denseTopK);

                        if (filterExpr != null) {
                            denseReqBuilder.expr(filterExpr);
                            sparseReqBuilder.expr(filterExpr);
                        }

                        AnnSearchReq denseReq = denseReqBuilder.build();
                        AnnSearchReq sparseReq = sparseReqBuilder.build();

                        // 4. Build hybrid search request
                        return HybridSearchReq.builder()
                                .collectionName(collectionName)
                                .searchRequests(Arrays.asList(denseReq, sparseReq))
                                .ranker(new RRFRanker(rrfK))
                                .topK(topK)
                                .outFields(Arrays.asList("doc_id", "content", "metadata"))
                                .build();

                    }).map(hybridReq -> {
                        // 5. Execute Milvus Search (Blocking IO)
                        try {
                            SearchResp response = milvusClient.hybridSearch(hybridReq);
                            return convertToDocuments(response);
                        } catch (Exception e) {
                            throw new RuntimeException("Milvus hybrid search failed", e);
                        }
                    }).block(); // Block to return List<Document>

        } catch (Exception e) {
            log.error("Hybrid search failed, falling back to empty results", e);
            return Collections.emptyList();
        }
    }

    private List<Document> convertToDocuments(SearchResp response) {
        List<Document> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = response.getSearchResults();
        if (searchResults != null && !searchResults.isEmpty()) {
            for (SearchResp.SearchResult result : searchResults.get(0)) {
                Map<String, Object> entity = result.getEntity();
                String id = (String) entity.get("doc_id");
                String content = (String) entity.get("content");

                // Parse metadata
                Map<String, Object> metadata = new HashMap<>();
                Object metaObj = entity.get("metadata");
                if (metaObj instanceof JsonObject) {
                    JsonObject jsonMeta = (JsonObject) metaObj;
                    metadata = gson.fromJson(jsonMeta, Map.class);
                } else if (metaObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> castMeta = (Map<String, Object>) metaObj;
                    metadata = castMeta;
                }
                metadata.put("score", result.getScore());

                Document doc = Document.builder()
                        .id(id)
                        .text(content)
                        .metadata(metadata)
                        .build();
                results.add(doc);
            }
        }
        return results;
    }

    /**
     * Warmup the connection and caches.
     */
    public void warmup() {
        try {
            log.info("Warming up hybrid search...");
            hybridSearch("warmup query", null, 1);
            log.info("Hybrid search warmup completed.");
        } catch (Exception e) {
            log.warn("Hybrid search warmup failed (non-critical)", e);
        }
    }
}
