package net.topikachu.rag.service.chat;

import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.service.etl.TeiEmbeddingClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

@Service
@Slf4j
public class HybridSearchService {

    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
    private String collectionName;

    @Value("${rag.retrieval.dense-vector-field:embedding}")
    private String denseVectorField;

    @Value("${rag.retrieval.sparse-vector-field:sparse_vector}")
    private String sparseVectorField;

    @Value("${rag.retrieval.dense-topk:50}")
    private int topK;

    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;

    private final TeiEmbeddingClient teiEmbeddingClient;
    private final MilvusSearchGateway milvusSearchGateway;

    public HybridSearchService(TeiEmbeddingClient teiEmbeddingClient, MilvusSearchGateway milvusSearchGateway) {
        this.teiEmbeddingClient = teiEmbeddingClient;
        this.milvusSearchGateway = milvusSearchGateway;
    }

    public Mono<List<Document>> hybridSearch(String query, List<String> filterTags, int topK) {
        return hybridSearch(query, filterTags, topK, true);
    }

    public Mono<List<Document>> hybridSearch(String query, List<String> filterTags, int topK, boolean useSparse) {
        String filterExpr = buildFilterExpr(filterTags);
        if (!useSparse) {
            return teiEmbeddingClient.embedDense(query)
                    .flatMap(denseVector -> {
                        SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                                .collectionName(collectionName)
                                .annsField(denseVectorField)
                                .data(Collections.singletonList(new FloatVec(denseVector)))
                                .outputFields(Arrays.asList("doc_id", "content", "metadata"))
                                .topK(topK);
                        if (filterExpr != null) {
                            builder.filter(filterExpr);
                        }
                        return milvusSearchGateway.search(builder.build());
                    })
                    .doOnError(e -> log.error("Dense search failed. query='{}', filterTags={}", query, filterTags, e))
                    .onErrorMap(e -> new RetrievalException("知识库检索失败，请检查向量服务、Milvus 连接或筛选条件后重试。", e));
        }

        return teiEmbeddingClient.embed(query)
                .map(response -> {
                    List<Float> denseVector = Collections.emptyList();
                    if (response.denseVecs() != null && !response.denseVecs().isEmpty()) {
                        denseVector = response.denseVecs().get(0);
                    }

                    SortedMap<Long, Float> sparseMap = new TreeMap<>();
                    if (response.sparseVecs() != null && !response.sparseVecs().isEmpty()) {
                        sparseMap = teiEmbeddingClient.parseSparse(response.sparseVecs().get(0));
                    }

                    AnnSearchReq.AnnSearchReqBuilder<?, ?> denseReqBuilder = AnnSearchReq.builder()
                            .vectorFieldName(denseVectorField)
                            .vectors(Collections.singletonList(new FloatVec(denseVector)))
                            .topK(this.topK);

                    AnnSearchReq.AnnSearchReqBuilder<?, ?> sparseReqBuilder = AnnSearchReq.builder()
                            .vectorFieldName(sparseVectorField)
                            .vectors(Collections.singletonList(new SparseFloatVec(sparseMap)))
                            .topK(this.topK);

                    if (filterExpr != null) {
                        denseReqBuilder.expr(filterExpr);
                        sparseReqBuilder.expr(filterExpr);
                    }

                    return HybridSearchReq.builder()
                            .collectionName(collectionName)
                            .searchRequests(Arrays.asList(denseReqBuilder.build(), sparseReqBuilder.build()))
                            .ranker(new RRFRanker(rrfK))
                            .topK(topK)
                            .outFields(Arrays.asList("doc_id", "content", "metadata"))
                            .build();
                })
                .flatMap(milvusSearchGateway::hybridSearch)
                .doOnError(e -> log.error("Hybrid search failed. query='{}', filterTags={}", query, filterTags, e))
                .onErrorMap(e -> new RetrievalException("知识库检索失败，请检查向量服务、Milvus 连接或筛选条件后重试。", e));
    }

    public Mono<Document> getByDocId(String docId) {
        QueryReq queryReq = QueryReq.builder()
                .collectionName(collectionName)
                .filter(String.format("doc_id == \"%s\"", docId))
                .outputFields(Arrays.asList("doc_id", "content", "metadata"))
                .build();
        return milvusSearchGateway.queryByDocId(queryReq)
                .onErrorResume(e -> {
                    log.warn("Query by doc_id failed: {}", docId, e);
                    return Mono.empty();
                });
    }

    public Mono<Void> warmup() {
        return hybridSearch("warmup query", null, 1).then();
    }

    private String buildFilterExpr(List<String> filterTags) {
        if (filterTags == null || filterTags.isEmpty()) {
            return null;
        }
        return String.format("JSON_CONTAINS(metadata[\"tags\"], \"%s\")", filterTags.get(0));
    }
}
