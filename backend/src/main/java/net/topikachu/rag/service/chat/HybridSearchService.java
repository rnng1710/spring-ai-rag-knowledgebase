package net.topikachu.rag.service.chat;

import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.access.KnowledgeAccessPolicy;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
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

    // RRF k=60：在融合 Dense 和 Sparse 排名时，较低排名结果不会被过度惩罚，保证召回多样性
    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;

    private final TeiEmbeddingClient teiEmbeddingClient;
    private final MilvusSearchGateway milvusSearchGateway;
    private final KnowledgeAccessPolicy accessPolicy;

    public HybridSearchService(TeiEmbeddingClient teiEmbeddingClient, MilvusSearchGateway milvusSearchGateway,
                               KnowledgeAccessPolicy accessPolicy) {
        this.teiEmbeddingClient = teiEmbeddingClient;
        this.milvusSearchGateway = milvusSearchGateway;
        this.accessPolicy = accessPolicy;
    }

    public Mono<List<Document>> hybridSearch(String query, CurrentUserContext currentUserContext,
            SearchScope searchScope, int topK) {
        return hybridSearch(query, currentUserContext, searchScope, topK, true);
    }

    public Mono<List<Document>> hybridSearch(String query, CurrentUserContext currentUserContext,
            SearchScope searchScope, int topK, boolean useSparse) {
        // 构造 Milvus 标量过滤表达式：schema 版本 + 访问控制 + 知识空间 + 标签
        String filterExpr = accessPolicy.buildMilvusFilterExpr(
                currentUserContext,
                searchScope,
                KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
        // 纯 Dense 模式（useSparse=false）：仅用语义向量检索
        if (!useSparse) {
            return teiEmbeddingClient.embedDense(query)
                    .flatMap(denseVector -> {
                        SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                                .collectionName(collectionName)
                                .annsField(denseVectorField)           // 搜索字段："embedding"（1024 维 Dense 向量）
                                .data(Collections.singletonList(new FloatVec(denseVector)))
                                .outputFields(Arrays.asList("doc_id", "content", "metadata"))
                                .topK(topK);
                        if (filterExpr != null) {
                            builder.filter(filterExpr);
                        }
                        return milvusSearchGateway.search(builder.build());
                    })
                    .doOnError(e -> log.error("Dense search failed. query='{}', searchScope={}", query, searchScope, e))
                    .onErrorMap(e -> new RetrievalException("知识库检索失败，请检查向量服务、Milvus 连接或筛选条件后重试。", e));
        }

        // 混合检索：Dense + Sparse，RRF 融合排序
        return teiEmbeddingClient.embed(query)
                .map(response -> {
                    // 从 TEI 服务获取 Dense 向量（1024 维浮点数数组）
                    List<Float> denseVector = Collections.emptyList();
                    if (response.denseVecs() != null && !response.denseVecs().isEmpty()) {
                        denseVector = response.denseVecs().get(0);
                    }

                    // 从 TEI 服务获取 Sparse 向量（BM25 风格的词项→权重映射）
                    SortedMap<Long, Float> sparseMap = new TreeMap<>();
                    if (response.sparseVecs() != null && !response.sparseVecs().isEmpty()) {
                        sparseMap = teiEmbeddingClient.parseSparse(response.sparseVecs().get(0));
                    }

                    // 构造 Dense 子查询：语义相似度 ANNS
                    AnnSearchReq.AnnSearchReqBuilder<?, ?> denseReqBuilder = AnnSearchReq.builder()
                            .vectorFieldName(denseVectorField)                      // Dense 向量字段："embedding"
                            .vectors(Collections.singletonList(new FloatVec(denseVector)))
                            .topK(this.topK);                                       // denseTopK=50

                    // 构造 Sparse 子查询：词频相似度 ANNS
                    AnnSearchReq.AnnSearchReqBuilder<?, ?> sparseReqBuilder = AnnSearchReq.builder()
                            .vectorFieldName(sparseVectorField)                     // Sparse 向量字段："sparse_vector"
                            .vectors(Collections.singletonList(new SparseFloatVec(sparseMap)))
                            .topK(this.topK);                                       // sparseTopK=50

                    if (filterExpr != null) {
                        denseReqBuilder.expr(filterExpr);
                        sparseReqBuilder.expr(filterExpr);
                    }

                    // 构造 Mixed Search 请求：两路 ANNS + RRF 融合
                    return HybridSearchReq.builder()
                            .collectionName(collectionName)                         // Milvus 集合："vector_store"
                            .searchRequests(Arrays.asList(denseReqBuilder.build(), sparseReqBuilder.build()))
                            .ranker(new RRFRanker(rrfK))                            // RRF k=60，融合 Dense + Sparse 排名
                            .topK(topK)                                             // 最终返回 topK 条（由调用方指定，通常 20）
                            .outFields(Arrays.asList("doc_id", "content", "metadata"))  // 返回字段
                            .build();
                })
                .flatMap(milvusSearchGateway::hybridSearch)
                .doOnError(e -> log.error("Hybrid search failed. query='{}', searchScope={}", query, searchScope, e))
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

    // 预热 TEI embedding 模型和 Milvus 连接池，避免首次真实请求的冷启动延迟
    public Mono<Void> warmup() {
        return hybridSearch("warmup query", null, SearchScope.empty(), 1).then();
    }
}
