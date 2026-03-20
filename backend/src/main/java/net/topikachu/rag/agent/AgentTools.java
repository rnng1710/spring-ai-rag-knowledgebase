package net.topikachu.rag.agent;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.service.chat.HybridSearchService;
import net.topikachu.rag.service.chat.RerankService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class AgentTools {

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final DocumentService documentService;

    @Value("${rag.retrieval.hybrid-topk:30}")
    private int hybridTopK;

    @Value("${rag.retrieval.rerank-topk:10}")
    private int rerankTopK;

    public AgentTools(HybridSearchService hybridSearchService,
                      RerankService rerankService,
                      DocumentService documentService) {
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.documentService = documentService;
    }

    public Mono<List<Document>> retrieve(String query, List<String> tags, Integer topK) {
        int effectiveTopK = (topK == null || topK <= 0) ? rerankTopK : topK;

        return hybridSearchService.hybridSearch(query, tags, hybridTopK)
                .flatMap(candidates -> {
                    if (candidates == null || candidates.isEmpty()) {
                        return Mono.just(Collections.emptyList());
                    }
                    return rerankService.rerank(query, candidates, effectiveTopK)
                            .onErrorResume(e -> {
                                log.warn("Rerank failed in retrieve tool, falling back to candidates: {}", e.getMessage());
                                return Mono.just(candidates.subList(0, Math.min(candidates.size(), effectiveTopK)));
                            });
                });
    }

    public Mono<Document> getDocById(String docId) {
        return hybridSearchService.getByDocId(docId);
    }

    public Mono<List<String>> listTags() {
        return documentService.getAllTags();
    }
}
