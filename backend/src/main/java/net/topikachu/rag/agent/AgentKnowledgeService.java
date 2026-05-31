package net.topikachu.rag.agent;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.service.chat.RetrievalPipeline;
import net.topikachu.rag.service.chat.RetrievalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@Slf4j
public class AgentKnowledgeService {

    private final RetrievalPipeline retrievalPipeline;
    private final DocumentService documentService;

    @Value("${rag.retrieval.hybrid-topk:30}")
    private int hybridTopK;

    @Value("${rag.retrieval.rerank-topk:10}")
    private int rerankTopK;

    public AgentKnowledgeService(RetrievalPipeline retrievalPipeline,
                                 DocumentService documentService) {
        this.retrievalPipeline = retrievalPipeline;
        this.documentService = documentService;
    }

    public Mono<RetrievalResult> searchKnowledgeSnippets(String query,
                                                         CurrentUserContext currentUserContext,
                                                         SearchScope searchScope,
                                                         Integer topK) {
        int effectiveTopK = (topK == null || topK <= 0) ? rerankTopK : topK;
        return retrievalPipeline.retrieveWithParentContexts(
                query,
                currentUserContext,
                searchScope,
                hybridTopK,
                effectiveTopK,
                java.util.Map.of("chat.mode", "agent"));
    }

    public Mono<List<String>> listAvailableTags(CurrentUserContext currentUserContext, SearchScope searchScope) {
        SearchScope tagListingScope = searchScope == null
                ? SearchScope.empty()
                : new SearchScope(searchScope.requestedSpaceCodes(), List.of());
        return documentService.getAccessibleTags(currentUserContext, tagListingScope);
    }
}
