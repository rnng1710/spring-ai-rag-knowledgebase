package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.observability.TracingSupport;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RetrievalPipeline {

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final TracingSupport tracingSupport;

    public RetrievalPipeline(HybridSearchService hybridSearchService,
                             RerankService rerankService,
                             TracingSupport tracingSupport) {
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.tracingSupport = tracingSupport;
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
}
