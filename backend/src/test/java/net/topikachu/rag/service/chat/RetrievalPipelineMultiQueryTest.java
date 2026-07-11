package net.topikachu.rag.service.chat;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalPipelineMultiQueryTest {

    private HybridSearchService searchService;
    private RerankService rerankService;
    private KnowledgeParentBlockService parentBlockService;
    private RetrievalPipeline pipeline;

    @BeforeEach
    void setUp() {
        searchService = mock(HybridSearchService.class);
        rerankService = mock(RerankService.class);
        TracingSupport tracingSupport = mock(TracingSupport.class);
        parentBlockService = mock(KnowledgeParentBlockService.class);
        pipeline = new RetrievalPipeline(searchService, rerankService, tracingSupport, parentBlockService);

        when(tracingSupport.traceMono(anyString(), anyMap(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(parentBlockService.findByParentBlockIds(anyList())).thenAnswer(invocation -> {
            List<String> parentIds = invocation.getArgument(0);
            Map<String, KnowledgeParentBlock> parents = new LinkedHashMap<>();
            parentIds.forEach(parentId -> parents.put(parentId, parent(parentId)));
            return Mono.just(parents);
        });
    }

    @Test
    void fusesRankingsDeduplicatesEvidenceAndReranksOnceWithOriginalQuestion() {
        CurrentUserContext user = user();
        SearchScope scope = new SearchScope(List.of("space-a"), List.of("tag-a"));
        List<Document> existing = List.of(child("a existing", "a"), child("b existing", "b"));

        when(searchService.hybridSearch(eq("rewrite"), same(user), same(scope), eq(20), eq(true)))
                .thenReturn(Mono.just(List.of(child("b rewrite", "b"), child("c rewrite", "c"))));
        when(searchService.hybridSearch(eq("detail"), same(user), same(scope), eq(20), eq(true)))
                .thenReturn(Mono.just(List.of(child("b detail", "b"), child("a detail", "a"))));
        when(rerankService.rerank(eq("original question"), anyList(), eq(3)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(1)));

        StepVerifier.create(pipeline.refineWithQueries(
                        "original question", List.of("rewrite", "detail"), existing,
                        user, scope, 20, 3))
                .assertNext(result -> assertEquals(List.of("ev-b", "ev-a", "ev-c"),
                        evidenceIds(result.childCandidates())))
                .verifyComplete();

        verify(searchService).hybridSearch(eq("rewrite"), same(user), same(scope), eq(20), eq(true));
        verify(searchService).hybridSearch(eq("detail"), same(user), same(scope), eq(20), eq(true));
        verify(rerankService).rerank(eq("original question"), anyList(), eq(3));
    }

    @Test
    void fallsBackToFusedOrderWithoutMutatingInputMetadata() {
        CurrentUserContext user = user();
        SearchScope scope = SearchScope.empty();
        Document existing = child("existing", "a");
        Document searched = child("searched", "b");

        when(searchService.hybridSearch(eq("rewrite"), same(user), same(scope), eq(20), eq(true)))
                .thenReturn(Mono.just(List.of(searched)));
        when(rerankService.rerank(eq("original"), anyList(), eq(2))).thenAnswer(invocation -> {
            List<Document> fused = invocation.getArgument(1);
            fused.get(0).getMetadata().put("rerank_score", 0.9d);
            return Mono.error(new IllegalStateException("reranker unavailable"));
        });

        StepVerifier.create(pipeline.refineWithQueries(
                        "original", List.of("rewrite"), List.of(existing),
                        user, scope, 20, 2))
                .assertNext(result -> {
                    assertEquals(List.of("ev-a", "ev-b"), evidenceIds(result.childCandidates()));
                    assertNotSame(existing, result.childCandidates().get(0));
					assertTrue(result.childCandidates().stream()
							.allMatch(document -> Boolean.TRUE.equals(document.getMetadata().get("rerank_fallback"))));
					assertTrue(result.childCandidates().stream()
							.noneMatch(document -> document.getMetadata().containsKey("rerank_score")));
                })
                .verifyComplete();

        assertFalse(existing.getMetadata().containsKey("rerank_score"));
        assertFalse(searched.getMetadata().containsKey("rerank_score"));
    }

    @Test
    void emptyQueriesOnlyTruncateAndExpandExistingCandidates() {
        Document first = child("first", "a");
        Document second = child("second", "b");

        StepVerifier.create(pipeline.refineWithQueries(
                        "original", List.of(), List.of(first, second),
                        user(), SearchScope.empty(), 20, 1))
                .assertNext(result -> {
                    assertEquals(List.of("ev-a"), evidenceIds(result.childCandidates()));
                    assertNotSame(first, result.childCandidates().get(0));
                })
                .verifyComplete();

        verifyNoInteractions(searchService, rerankService);
    }

    @Test
    void failsWholeRoundWhenAnyQuerySearchFails() {
        CurrentUserContext user = user();
        SearchScope scope = SearchScope.empty();
        when(searchService.hybridSearch(eq("good"), same(user), same(scope), anyInt(), eq(true)))
                .thenReturn(Mono.just(List.of(child("good", "a"))));
        when(searchService.hybridSearch(eq("bad"), same(user), same(scope), anyInt(), eq(true)))
                .thenReturn(Mono.error(new RetrievalException("failed", new IllegalStateException("offline"))));

        StepVerifier.create(pipeline.refineWithQueries(
                        "original", List.of("good", "bad"), List.of(),
                        user, scope, 20, 2))
                .expectError(RetrievalException.class)
                .verify();

        verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        verify(parentBlockService, never()).findByParentBlockIds(anyList());
    }

	@Test
	void capsQueriesAndConcurrentSearchesAtFour() {
		CurrentUserContext user = user();
		SearchScope scope = SearchScope.empty();
		AtomicInteger active = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		when(searchService.hybridSearch(anyString(), same(user), same(scope), eq(20), eq(true)))
				.thenAnswer(invocation -> Mono.defer(() -> {
					int current = active.incrementAndGet();
					peak.accumulateAndGet(current, Math::max);
					String query = invocation.getArgument(0);
					return Mono.delay(Duration.ofMillis(20))
							.map(ignored -> List.of(child(query, query)))
							.doFinally(ignored -> active.decrementAndGet());
				}));
		when(rerankService.rerank(eq("original"), anyList(), eq(6)))
				.thenAnswer(invocation -> Mono.just(invocation.getArgument(1)));

		RetrievalResult result = pipeline.refineWithQueries(
				"original",
				List.of("q1", "q2", "q3", "q4", "q5", "q6"),
				List.of(),
				user,
				scope,
				20,
				6).block();

		assertEquals(4, result.childCandidates().size());
		assertEquals(4, peak.get());
	}

    private List<String> evidenceIds(List<Document> documents) {
        return documents.stream()
                .map(document -> String.valueOf(document.getMetadata().get("evidence_id")))
                .toList();
    }

    private Document child(String text, String suffix) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evidence_id", "ev-" + suffix);
        metadata.put("parent_block_id", "parent-" + suffix);
        metadata.put("doc_uuid", "doc-" + suffix);
        return new Document(text, metadata);
    }

    private KnowledgeParentBlock parent(String parentId) {
        String suffix = parentId.substring("parent-".length());
        KnowledgeParentBlock block = new KnowledgeParentBlock();
        block.setParentBlockId(parentId);
        block.setDocUuid("doc-" + suffix);
        block.setFileName("handbook.pdf");
        block.setContent("parent " + suffix);
        block.setParentIndex(1);
        block.setPageStart(1);
        block.setPageEnd(1);
        block.setChunkSchemaVersion(KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
        return block;
    }

    private CurrentUserContext user() {
        return new CurrentUserContext("user-1", "alice", "USER", "dept-1", "Engineering", "space-a", false);
    }
}
