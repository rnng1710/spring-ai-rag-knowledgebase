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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
                argThat(history -> history.size() == 2)))
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
                anyList()))
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
    void doesNotReturnBeforeEveryCommitCompletes() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));
        Sinks.Empty<Void> historyBarrier = Sinks.empty();

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContexts(anyList())).thenReturn("parent context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"), eq("conversation-1"), anyList()))
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
        return new GroundedTurnModule.Command(
                "question",
                "conversation-1",
                "user-1",
                "model-1",
                "agent",
                "msg-1",
                "trace-1",
                candidates,
                List.of());
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
