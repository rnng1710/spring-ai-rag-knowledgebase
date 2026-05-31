package net.topikachu.rag.service.chat;

import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalPipelineParentContextTest {

    @Test
    void expandsRerankedChildrenToDeduplicatedParentContexts() {
        HybridSearchService searchService = mock(HybridSearchService.class);
        RerankService rerankService = mock(RerankService.class);
        TracingSupport tracingSupport = mock(TracingSupport.class);
        KnowledgeParentBlockService parentBlockService = mock(KnowledgeParentBlockService.class);
        RetrievalPipeline pipeline = new RetrievalPipeline(searchService, rerankService, tracingSupport, parentBlockService);

        Document first = child("child one", "ev-1", "parent-1", "doc-1");
        Document second = child("child two", "ev-2", "parent-1", "doc-1");
        KnowledgeParentBlock parent = parent("parent-1", "doc-1", 1);

        when(tracingSupport.traceMono(anyString(), anyMap(), any())).thenAnswer(inv -> inv.getArgument(2));
        when(searchService.hybridSearch(anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Mono.just(List.of(first, second)));
        when(rerankService.rerank(anyString(), any(), anyInt()))
                .thenReturn(Mono.just(List.of(first, second)));
        when(parentBlockService.findByParentBlockIds(List.of("parent-1")))
                .thenReturn(Mono.just(Map.of("parent-1", parent)));

        StepVerifier.create(pipeline.retrieveWithParentContexts("query", null, null, 10, 10, Map.of()))
                .assertNext(result -> {
                    assertEquals(2, result.childCandidates().size());
                    assertEquals(1, result.parentContexts().size());
                    assertEquals(List.of("ev-1", "ev-2"), result.parentContexts().get(0).evidenceIds());
                })
                .verifyComplete();
    }

    @Test
    void failsWhenParentContextIsMissing() {
        HybridSearchService searchService = mock(HybridSearchService.class);
        RerankService rerankService = mock(RerankService.class);
        TracingSupport tracingSupport = mock(TracingSupport.class);
        KnowledgeParentBlockService parentBlockService = mock(KnowledgeParentBlockService.class);
        RetrievalPipeline pipeline = new RetrievalPipeline(searchService, rerankService, tracingSupport, parentBlockService);

        Document child = child("child", "ev-1", "missing-parent", "doc-1");

        when(tracingSupport.traceMono(anyString(), anyMap(), any())).thenAnswer(inv -> inv.getArgument(2));
        when(searchService.hybridSearch(anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Mono.just(List.of(child)));
        when(rerankService.rerank(anyString(), any(), anyInt()))
                .thenReturn(Mono.just(List.of(child)));
        when(parentBlockService.findByParentBlockIds(List.of("missing-parent")))
                .thenReturn(Mono.just(Map.of()));

        StepVerifier.create(pipeline.retrieveWithParentContexts("query", null, null, 10, 10, Map.of()))
                .expectError(ParentContextMissingException.class)
                .verify();
    }

    private Document child(String text, String evidenceId, String parentBlockId, String docUuid) {
        return new Document(text, Map.of(
                "evidence_id", evidenceId,
                "parent_block_id", parentBlockId,
                "doc_uuid", docUuid));
    }

    private KnowledgeParentBlock parent(String parentBlockId, String docUuid, int index) {
        KnowledgeParentBlock block = new KnowledgeParentBlock();
        block.setParentBlockId(parentBlockId);
        block.setDocUuid(docUuid);
        block.setFileName("handbook.pdf");
        block.setContent("parent text");
        block.setParentIndex(index);
        block.setPageStart(3);
        block.setPageEnd(3);
        block.setChunkSchemaVersion(2);
        return block;
    }
}
