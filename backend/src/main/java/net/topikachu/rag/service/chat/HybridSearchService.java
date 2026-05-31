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
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import net.topikachu.rag.service.etl.TeiEmbeddingClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
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

    public Mono<List<Document>> hybridSearch(String query, CurrentUserContext currentUserContext,
            SearchScope searchScope, int topK) {
        return hybridSearch(query, currentUserContext, searchScope, topK, true);
    }

    public Mono<List<Document>> hybridSearch(String query, CurrentUserContext currentUserContext,
            SearchScope searchScope, int topK, boolean useSparse) {
        String filterExpr = buildFilterExpr(currentUserContext, searchScope);
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
                    .doOnError(e -> log.error("Dense search failed. query='{}', searchScope={}", query, searchScope, e))
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

    public Mono<Void> warmup() {
        return hybridSearch("warmup query", null, SearchScope.empty(), 1).then();
    }

    private String buildFilterExpr(CurrentUserContext currentUserContext, SearchScope searchScope) {
        List<String> clauses = new java.util.ArrayList<>();
        clauses.add("metadata[\"chunk_schema_version\"] == " + KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
        String accessClause = buildAccessClause(currentUserContext);
        if (StringUtils.hasText(accessClause)) {
            clauses.add("(" + accessClause + ")");
        }
        String spaceClause = buildSpaceClause(searchScope);
        if (StringUtils.hasText(spaceClause)) {
            clauses.add("(" + spaceClause + ")");
        }
        String tagClause = buildTagClause(searchScope == null ? List.of() : searchScope.requestedTags());
        if (StringUtils.hasText(tagClause)) {
            clauses.add("(" + tagClause + ")");
        }
        if (clauses.isEmpty()) {
            return null;
        }
        return String.join(" AND ", clauses);
    }

    private String buildAccessClause(CurrentUserContext currentUserContext) {
        if (currentUserContext == null || currentUserContext.isAdmin()) {
            return null;
        }

        List<String> allowClauses = new java.util.ArrayList<>();
        allowClauses.add("metadata[\"is_public\"] == true");

        if (StringUtils.hasText(currentUserContext.role())) {
            allowClauses.add(String.format("JSON_CONTAINS(metadata[\"allowed_roles\"], %s)",
                    toJsonStringLiteral(currentUserContext.role())));
        }
        if (StringUtils.hasText(currentUserContext.deptId())) {
            String deptLiteral = quoteStringLiteral(currentUserContext.deptId());
            allowClauses.add(String.format("JSON_CONTAINS(metadata[\"allowed_dept_ids\"], %s)",
                    toJsonStringLiteral(currentUserContext.deptId())));
            allowClauses.add(String.format("metadata[\"owner_dept_id\"] == %s", deptLiteral));
        }

        return String.join(" OR ", allowClauses);
    }

    private String buildSpaceClause(SearchScope searchScope) {
        List<String> normalizedSpaces = normalizeList(searchScope == null ? List.of() : searchScope.requestedSpaceCodes());
        if (normalizedSpaces.isEmpty()) {
            return null;
        }
        return normalizedSpaces.stream()
                .map(spaceCode -> String.format("metadata[\"space_code\"] == %s", quoteStringLiteral(spaceCode)))
                .reduce((left, right) -> left + " OR " + right)
                .orElse(null);
    }

    private String buildTagClause(List<String> filterTags) {
        List<String> normalizedTags = normalizeList(filterTags);
        if (normalizedTags.isEmpty()) {
            return null;
        }
        return normalizedTags.stream()
                .map(tag -> String.format("JSON_CONTAINS(metadata[\"tags\"], %s)", toJsonStringLiteral(tag)))
                .reduce((left, right) -> left + " OR " + right)
                .orElse(null);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
    }

    private String toJsonStringLiteral(String value) {
        return "\"" + escapeFilterLiteral(value) + "\"";
    }

    private String quoteStringLiteral(String value) {
        return "\"" + escapeFilterLiteral(value) + "\"";
    }

    private String escapeFilterLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
