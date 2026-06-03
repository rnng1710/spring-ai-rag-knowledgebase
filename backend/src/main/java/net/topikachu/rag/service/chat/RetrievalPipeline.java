package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class RetrievalPipeline {

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final TracingSupport tracingSupport;
    private final KnowledgeParentBlockService parentBlockService;

    public RetrievalPipeline(HybridSearchService hybridSearchService,
                             RerankService rerankService,
                             TracingSupport tracingSupport,
                             KnowledgeParentBlockService parentBlockService) {
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.tracingSupport = tracingSupport;
        this.parentBlockService = parentBlockService;
    }

    public Mono<List<Document>> retrieve(String query,
                                          CurrentUserContext currentUserContext,
                                          SearchScope searchScope,
                                          int hybridTopK,
                                          int rerankTopK) {
        return retrieveInternal(query, currentUserContext, searchScope, hybridTopK, rerankTopK, true, true, Map.of());
    }

    public Mono<List<Document>> retrieve(String query,
                                          CurrentUserContext currentUserContext,
                                          SearchScope searchScope,
                                          int hybridTopK,
                                          int rerankTopK,
                                          Map<String, Object> extraTags) {
        return retrieveInternal(query, currentUserContext, searchScope, hybridTopK, rerankTopK, true, true, extraTags);
    }

    public Mono<RetrievalResult> retrieveWithParentContexts(String query,
                                                            CurrentUserContext currentUserContext,
                                                            SearchScope searchScope,
                                                            int hybridTopK,
                                                            int rerankTopK,
                                                            Map<String, Object> extraTags) {
        return retrieveInternal(query, currentUserContext, searchScope, hybridTopK, rerankTopK, true, true, extraTags)
                .flatMap(childCandidates -> expandParentContexts(childCandidates)
                        .map(parentContexts -> new RetrievalResult(childCandidates, parentContexts)));
    }

    public Mono<List<Document>> retrieve(String query,
                                          int hybridTopK,
                                          int rerankTopK,
                                          boolean useSparseSearch,
                                          boolean useRerank) {
        return retrieveInternal(query, null, SearchScope.empty(), hybridTopK, rerankTopK, useSparseSearch, useRerank, Map.of());
    }

    private Mono<List<Document>> retrieveInternal(String query,
                                                   CurrentUserContext currentUserContext,
                                                   SearchScope searchScope,
                                                   int hybridTopK,
                                                   int rerankTopK,
                                                   boolean useSparseSearch,
                                                   boolean useRerank,
                                                   Map<String, Object> extraTags) {
        long searchStart = System.currentTimeMillis();
        Map<String, Object> traceTags = new java.util.HashMap<>(Map.of(
                "rag.hybrid_topk", hybridTopK,
                "rag.rerank_topk", rerankTopK,
                "rag.filter_tags", searchScope == null ? "" : String.join(",", searchScope.requestedTags()),
                "rag.requested_spaces", searchScope == null ? "" : String.join(",", searchScope.requestedSpaceCodes())));
        traceTags.putAll(extraTags);

        return tracingSupport.traceMono("rag.hybrid_search", traceTags,
                        hybridSearchService.hybridSearch(query, currentUserContext, searchScope, hybridTopK, useSparseSearch))
                .doOnNext(candidates -> log.debug("Hybrid search returned {} candidates in {}ms",
                        candidates.size(), System.currentTimeMillis() - searchStart))
                .doOnError(error -> log.error("Retrieval stage failed for query='{}', searchScope={}",
                        query, searchScope, error))
                .flatMap(candidates -> {
                    if (candidates == null || candidates.isEmpty()) {
                        return Mono.just(Collections.emptyList());
                    }
                    if (!useRerank) {
                        // rerankTopK 在此兼任截断上限：跳过重排序时直接按此数量截断候选集
                        return Mono.just(candidates.subList(0, Math.min(candidates.size(), rerankTopK)));
                    }
                    long rerankStart = System.currentTimeMillis();
                    return tracingSupport.traceMono("rag.rerank",
                                    Map.of(
                                            "rag.candidate_count", candidates.size(),
                                            "rag.rerank_topk", rerankTopK),
                                    rerankService.rerank(query, candidates, rerankTopK))
                            .doOnNext(docs -> log.debug("Rerank returned {} docs in {}ms",
                                    docs.size(), System.currentTimeMillis() - rerankStart))
                            .onErrorResume(error -> {
                                log.warn("Rerank failed, fallback to raw candidates: {}", error.getMessage());
                                return Mono.just(candidates.subList(0, Math.min(candidates.size(), rerankTopK)));
                            });
                });
    }

    // 子块回查父块：将检索命中的子块按 parent_block_id 去重聚合，批量查询 MySQL 获取完整父块上下文
    private Mono<List<ParentContextBlock>> expandParentContexts(List<Document> childCandidates) {
        if (childCandidates == null || childCandidates.isEmpty()) {
            return Mono.just(List.of());
        }

        // 按 parent_block_id 去重聚合，同时收集每个父块下所有命中子块的 evidence_id
        Map<String, ParentAccumulator> byParentId = new LinkedHashMap<>();
        int rank = 0;
        for (Document child : childCandidates) {
            rank++;
            Map<String, Object> metadata = child.getMetadata();
            String parentBlockId = stringValue(metadata.get("parent_block_id"));
            String evidenceId = evidenceId(child);
            if (!StringUtils.hasText(parentBlockId) || !StringUtils.hasText(evidenceId)) {
                return Mono.error(new ParentContextMissingException("知识库索引数据不一致，请重建该文档索引后重试。"));
            }
            int currentRank = rank;
            // 首次遇到该 parent_block_id → 创建累加器，记录最佳排名
            // 重复遇到 → 仅追加 evidence_id（同一父块下的不同子块均被命中）
            ParentAccumulator accumulator = byParentId.computeIfAbsent(parentBlockId,
                    ignored -> new ParentAccumulator(parentBlockId, stringValue(metadata.get("doc_uuid")), currentRank));
            accumulator.evidenceIds().add(evidenceId);
        }

        // 批量查询 MySQL，一次取出所有去重后的父块
        List<String> parentBlockIds = new ArrayList<>(byParentId.keySet());
        return parentBlockService.findByParentBlockIds(parentBlockIds)
                .map(parentBlocks -> toParentContextBlocks(byParentId, parentBlocks));
    }

    // 将 MySQL 查询结果与累加器合并，校验 schema 版本和 docUuid 一致性
    private List<ParentContextBlock> toParentContextBlocks(Map<String, ParentAccumulator> accumulators,
                                                           Map<String, KnowledgeParentBlock> parentBlocks) {
        List<ParentContextBlock> contexts = new ArrayList<>();
        for (ParentAccumulator accumulator : accumulators.values()) {
            KnowledgeParentBlock parentBlock = parentBlocks.get(accumulator.parentBlockId());
            // 校验：父块必须存在、schema 版本匹配、docUuid 一致（防止跨文档误关联）
            if (parentBlock == null
                    || parentBlock.getChunkSchemaVersion() == null
                    || parentBlock.getChunkSchemaVersion() != KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION
                    || !Objects.equals(parentBlock.getDocUuid(), accumulator.docUuid())) {
                throw new ParentContextMissingException("知识库索引数据不一致，请重建该文档索引后重试。");
            }
            contexts.add(new ParentContextBlock(
                    parentBlock.getParentBlockId(),
                    parentBlock.getDocUuid(),
                    parentBlock.getFileName(),
                    parentBlock.getContent(),          // 1200 字完整段落，发给 LLM 推理
                    parentBlock.getParentIndex(),
                    parentBlock.getPageStart(),
                    parentBlock.getPageEnd(),
                    List.copyOf(accumulator.evidenceIds()),  // 该父块下所有被命中的子块 evidence_id，供 LLM 引用
                    accumulator.bestRank()));                  // 该父块下最佳排名的子块排名，用于最终排序
        }
        return contexts;
    }

    private String evidenceId(Document document) {
        if (document == null) {
            return null;
        }
        Object metadataEvidenceId = document.getMetadata().get("evidence_id");
        if (metadataEvidenceId != null && StringUtils.hasText(metadataEvidenceId.toString())) {
            return metadataEvidenceId.toString().trim();
        }
        return document.getId();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private record ParentAccumulator(
            String parentBlockId,
            String docUuid,
            int bestRank,              // 该父块下最早被命中的子块排名（数字越小越靠前）
            List<String> evidenceIds   // 该父块下所有被命中子块的 evidence_id 集合
    ) {
        private ParentAccumulator(String parentBlockId, String docUuid, int bestRank) {
            this(parentBlockId, docUuid, bestRank, new ArrayList<>());
        }
    }
}
