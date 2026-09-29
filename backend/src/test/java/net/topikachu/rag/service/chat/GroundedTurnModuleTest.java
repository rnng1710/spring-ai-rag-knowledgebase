package net.topikachu.rag.service.chat;

import net.topikachu.rag.chat.history.ChatHistoryService;
import net.topikachu.rag.evaluation.service.EvaluationPersistenceService;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroundedTurnModuleTest {

    @Mock
    private ContextFormatter contextFormatter;

    @Mock
    private ChatModelStrategyFactory strategyFactory;

    @Mock
    private ChatModelStrategy strategy;

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private ChatHistoryService chatHistoryService;

    @Mock
    private EvaluationPersistenceService persistenceService;

    private GroundedTurnModule module;

    @BeforeEach
    void setUp() {
        module = new GroundedTurnModule(
                contextFormatter,
                strategyFactory,
                reactiveChatGateway,
                new UsedSourceValidator(),
                chatMemory,
                chatHistoryService,
                persistenceService);
    }

    @Test
    void publishesAndPersistsOnlyEvidenceActuallyUsedByTheAnswer() {
        Document first = candidate("ev-1", "doc-1", "first.pdf");
        Document second = candidate("ev-2", "doc-2", "second.pdf");
        GroundedTurnModule.Command command = command(List.of(first, second));

        when(chatMemory.get("conversation-1")).thenReturn(List.of(
                new UserMessage("previous question"),
                new AssistantMessage("previous answer")));
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway),
                eq("parent context"),
                eq("question"),
                eq("conversation-1"),
                argThat(history -> history.size() == 2),
                isNull()))
                .thenReturn(Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-2"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "answer", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());
        when(persistenceService.saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"), eq("answer"),
                eq("model-1"), eq("agent"), argThat(nodes -> nodes.size() == 2),
                argThat(sources -> sources.size() == 1 && "ev-2".equals(sources.get(0).evidenceId())),
                eq("trace-1")))
                .thenReturn(Mono.empty());

        GroundedTurnModule.Result result = module.execute(command).block();

        assertEquals("answer", result.answer());
        assertEquals(0, result.repairCount());
        assertEquals(List.of("ev-2"), result.usedSources().stream().map(UsedSource::evidenceId).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> memoryCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq("conversation-1"), memoryCaptor.capture());
        assertEquals(2, memoryCaptor.getValue().size());
        assertEquals("question", memoryCaptor.getValue().get(0).getText());
        assertEquals("answer", memoryCaptor.getValue().get(1).getText());
    }

    @Test
    void invalidUsedEvidenceDoesNotCommitTheTurn() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway),
                eq("parent context"),
                eq("question"),
                eq("conversation-1"),
                anyList(),
                isNull()))
                .thenReturn(Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-missing"))));

        StepVerifier.create(module.execute(command))
                .expectErrorMatches(error -> error instanceof SourceValidationException validationError
                        && UsedSourceValidator.REASON_EVIDENCE_ID_NOT_IN_CANDIDATES.equals(validationError.getReason()))
                .verify();

        verify(chatMemory, never()).add(eq("conversation-1"), anyList());
        verify(chatHistoryService, never()).saveTurn(
                eq("conversation-1"), eq("user-1"), eq("question"), eq("answer"),
                eq("model-1"), eq("agent"), eq("msg-1"));
        verify(persistenceService, never()).saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"), eq("answer"),
                eq("model-1"), eq("agent"), anyList(), anyList(), eq("trace-1"));
    }

    @Test
    void repairsInvalidSourcesOnceWithOnlyReasonAndAllowedEvidenceIds() {
        GroundedTurnModule.Command command = command(
                List.of(candidate("ev-1", "doc-1", "first.pdf")),
                GroundedTurnModule.AnswerPolicy.GROUNDED,
                1);

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), nullable(String.class)))
                .thenReturn(
                        Mono.just(new SourcedAnswerResult("bad", "factual", List.of("ev-missing"))),
                        Mono.just(new SourcedAnswerResult("repaired", "factual", List.of("ev-1"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "repaired", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());
        when(persistenceService.saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"), eq("repaired"),
                eq("model-1"), eq("agent"), anyList(), anyList(), eq("trace-1")))
                .thenReturn(Mono.empty());

        GroundedTurnModule.Result result = module.execute(command).block();

        assertEquals("repaired", result.answer());
        assertEquals(1, result.repairCount());
        ArgumentCaptor<String> repairCaptor = ArgumentCaptor.forClass(String.class);
        verify(strategy, times(2)).callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), repairCaptor.capture());
        assertEquals(null, repairCaptor.getAllValues().get(0));
        String repairInstruction = repairCaptor.getAllValues().get(1);
        assertTrue(repairInstruction.contains(UsedSourceValidator.REASON_EVIDENCE_ID_NOT_IN_CANDIDATES));
        assertTrue(repairInstruction.contains("ev-1"));
        assertFalse(repairInstruction.contains("bad"));
        assertFalse(repairInstruction.contains("ev-missing"));
    }

    @Test
    void reviewedAnswerCannotReconsiderAnswerability() {
        GroundedTurnModule.Command command = reviewedCommand(
                List.of(candidate("ev-1", "doc-1", "first.pdf")),
                "reviewed candidate",
                List.of("ev-1"));

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callReviewedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), eq("reviewed candidate"), eq(List.of("ev-1")),
                nullable(String.class)))
                .thenReturn(
                        Mono.just(new SourcedAnswerResult("refusal", "refusal", List.of())),
                        Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-1"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "answer", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());
        when(persistenceService.saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"), eq("answer"),
                eq("model-1"), eq("agent"), anyList(), anyList(), eq("trace-1")))
                .thenReturn(Mono.empty());

        GroundedTurnModule.Result result = module.execute(command).block();

        assertEquals("answer", result.answer());
        assertEquals(1, result.repairCount());
        ArgumentCaptor<String> repairCaptor = ArgumentCaptor.forClass(String.class);
        verify(strategy, times(2)).callReviewedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), eq("reviewed candidate"), eq(List.of("ev-1")),
                repairCaptor.capture());
        assertTrue(repairCaptor.getAllValues().get(1)
                .contains(UsedSourceValidator.REASON_REVIEWED_ANSWER_REFUSED));
        verify(strategy, never()).callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), nullable(String.class));
    }

    @Test
    void reviewedAnswerMustUseEverySelectedEvidenceId() {
        GroundedTurnModule.Command command = reviewedCommand(
                List.of(
                        candidate("ev-1", "doc-1", "first.pdf"),
                        candidate("ev-2", "doc-2", "second.pdf")),
                "reviewed candidate",
                List.of("ev-1", "ev-2"));

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callReviewedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), eq("reviewed candidate"), eq(List.of("ev-1", "ev-2")),
                nullable(String.class)))
                .thenReturn(
                        Mono.just(new SourcedAnswerResult("incomplete citations", "factual", List.of("ev-1"))),
                        Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-1", "ev-2"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "answer", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());
        when(persistenceService.saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"), eq("answer"),
                eq("model-1"), eq("agent"), anyList(), anyList(), eq("trace-1")))
                .thenReturn(Mono.empty());

        GroundedTurnModule.Result result = module.execute(command).block();

        assertEquals(1, result.repairCount());
        ArgumentCaptor<String> repairCaptor = ArgumentCaptor.forClass(String.class);
        verify(strategy, times(2)).callReviewedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), eq("reviewed candidate"), eq(List.of("ev-1", "ev-2")),
                repairCaptor.capture());
        assertTrue(repairCaptor.getAllValues().get(1)
                .contains(UsedSourceValidator.REASON_REQUIRED_EVIDENCE_NOT_USED));
    }

    @Test
    void repairsStructuredParseFailureOnce() {
        GroundedTurnModule.Command command = command(
                List.of(candidate("ev-1", "doc-1", "first.pdf")),
                GroundedTurnModule.AnswerPolicy.GROUNDED,
                1);

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), nullable(String.class)))
                .thenReturn(
                        Mono.error(new StructuredResponseException("Could not parse structured response.")),
                        Mono.just(new SourcedAnswerResult("repaired", "factual", List.of("ev-1"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "repaired", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());
        when(persistenceService.saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"), eq("repaired"),
                eq("model-1"), eq("agent"), anyList(), anyList(), eq("trace-1")))
                .thenReturn(Mono.empty());

        GroundedTurnModule.Result result = module.execute(command).block();

        assertEquals(1, result.repairCount());
        ArgumentCaptor<String> repairCaptor = ArgumentCaptor.forClass(String.class);
        verify(strategy, times(2)).callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), repairCaptor.capture());
        assertTrue(repairCaptor.getAllValues().get(1).contains("json_parse_failed"));
    }

	@Test
	void infrastructureFailureIsNotRepairedOrCommitted() {
		GroundedTurnModule.Command command = command(
				List.of(candidate("ev-1", "doc-1", "first.pdf")),
				GroundedTurnModule.AnswerPolicy.GROUNDED,
				1);

		when(chatMemory.get("conversation-1")).thenReturn(List.of());
		when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
		when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
		when(strategy.callSourcedAnswer(
				same(reactiveChatGateway), eq("parent context"), eq("question"),
				eq("conversation-1"), anyList(), nullable(String.class)))
				.thenReturn(Mono.error(new IllegalStateException("model unavailable")));

		StepVerifier.create(module.execute(command))
				.expectErrorMatches(error -> error instanceof IllegalStateException
						&& "model unavailable".equals(error.getMessage()))
				.verify();

		verify(strategy).callSourcedAnswer(
				same(reactiveChatGateway), eq("parent context"), eq("question"),
				eq("conversation-1"), anyList(), nullable(String.class));
		verify(chatMemory, never()).add(eq("conversation-1"), anyList());
		verifyNoInteractions(chatHistoryService, persistenceService);
	}

    @Test
    void secondValidationFailureDoesNotCommit() {
        GroundedTurnModule.Command command = command(
                List.of(candidate("ev-1", "doc-1", "first.pdf")),
                GroundedTurnModule.AnswerPolicy.GROUNDED,
                1);

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), nullable(String.class)))
                .thenReturn(Mono.just(new SourcedAnswerResult("bad", "factual", List.of("ev-missing"))));

        StepVerifier.create(module.execute(command))
                .expectError(SourceValidationException.class)
                .verify();

        verify(strategy, times(2)).callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList(), nullable(String.class));
        verify(chatMemory, never()).add(eq("conversation-1"), anyList());
        verifyNoInteractions(chatHistoryService, persistenceService);
    }

    @Test
    void knowledgeRefusalSkipsTheModelAndStillCommits() {
        GroundedTurnModule.Command command = command(
                List.of(),
                GroundedTurnModule.AnswerPolicy.KNOWLEDGE_REFUSAL,
                1);

        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());
        when(persistenceService.saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"),
                eq(UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE), eq("model-1"), eq("agent"),
                anyList(), eq(List.of()), eq("trace-1")))
                .thenReturn(Mono.empty());

        GroundedTurnModule.Result result = module.execute(command).block();

        assertEquals("refusal", result.answerType());
        assertEquals(List.of(), result.usedSources());
        assertEquals(0, result.repairCount());
        verifyNoInteractions(contextFormatter, strategyFactory, strategy, reactiveChatGateway);
        verify(chatMemory).add(eq("conversation-1"), anyList());
    }

    @Test
    void doesNotReturnBeforeEveryCommitCompletes() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));
        Sinks.Empty<Void> historyBarrier = Sinks.empty();

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"), eq("conversation-1"), anyList(), isNull()))
                .thenReturn(Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-1"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "answer", "model-1", "agent", "msg-1"))
                .thenReturn(historyBarrier.asMono());
        when(persistenceService.saveConversation(
                eq("msg-1"), eq("conversation-1"), eq("user-1"), eq("question"), eq("answer"),
                eq("model-1"), eq("agent"), anyList(), anyList(), eq("trace-1")))
                .thenReturn(Mono.empty());

        StepVerifier.create(module.execute(command))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .then(() -> historyBarrier.tryEmitEmpty())
                .assertNext(result -> assertEquals("answer", result.answer()))
                .verifyComplete();
    }

    private GroundedTurnModule.Command command(List<Document> candidates) {
        return command(candidates, GroundedTurnModule.AnswerPolicy.GROUNDED, 0);
    }

    private GroundedTurnModule.Command command(List<Document> candidates,
                                               GroundedTurnModule.AnswerPolicy answerPolicy,
                                               int maxAnswerRepairs) {
        return new GroundedTurnModule.Command(
                "question",
                "conversation-1",
                "user-1",
                "model-1",
                "agent",
                "msg-1",
                "trace-1",
                candidates,
                List.of(),
                answerPolicy,
                maxAnswerRepairs);
    }

    private GroundedTurnModule.Command reviewedCommand(List<Document> candidates,
                                                       String reviewedCandidateAnswer,
                                                       List<String> reviewedEvidenceIds) {
        return new GroundedTurnModule.Command(
                "question",
                "conversation-1",
                "user-1",
                "model-1",
                "agent",
                "msg-1",
                "trace-1",
                candidates,
                List.of(),
                GroundedTurnModule.AnswerPolicy.REVIEWED_GROUNDED,
                1,
                reviewedCandidateAnswer,
                reviewedEvidenceIds);
    }

    private Document candidate(String evidenceId, String docUuid, String fileName) {
        return new Document("content", Map.of(
                "evidence_id", evidenceId,
                "doc_uuid", docUuid,
                "file_name", fileName,
                "page_start", 1,
                "page_end", 1));
    }
}
