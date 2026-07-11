package net.topikachu.rag.agent;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.chat.history.ChatHistoryService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.GroundedTurnModule;
import net.topikachu.rag.service.chat.ParentContextBlock;
import net.topikachu.rag.service.chat.SourceValidationException;
import net.topikachu.rag.service.chat.UsedSource;
import net.topikachu.rag.service.chat.UsedSourceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatServiceGroundedTurnTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private AgentTurnStateStore agentTurnStateStore;

    @Mock
    private TracingSupport tracingSupport;

    @Mock
    private GroundedTurnModule groundedTurnModule;

    @Mock
    private ChatHistoryService chatHistoryService;

    private AgentChatService service;

    @BeforeEach
    void setUp() {
        service = new AgentChatService(
                executor,
                chatMemory,
                new ConversationExecutionGuard(),
                agentTurnStateStore,
                tracingSupport,
                groundedTurnModule,
                chatHistoryService);
    }

    @Test
    void publishesOnlyValidatedUsedSourcesAfterGroundedTurnCompletes() {
        AgentExecutionResult executionResult = answerResult();
        UsedSource usedSource = new UsedSource("ev-2", "doc-2", "second.pdf", 2, "pdf");
        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        when(executor.execute(eq("question"), eq("conversation-1"), eq("msg-1"), any(), any(), eq("model-1")))
                .thenReturn(Mono.just(executionResult));
        when(groundedTurnModule.execute(any()))
                .thenReturn(Mono.just(new GroundedTurnModule.Result("answer", "factual", List.of(usedSource))));

        List<ServerSentEvent<Object>> events = service.streamEvents(
                        "question",
                        "conversation-1",
                        currentUser(),
                        SearchScope.empty(),
                        "model-1",
                        "msg-1")
                .collectList()
                .block();

        assertEquals(List.of("sources", "message", "done"), events.stream().map(ServerSentEvent::event).toList());
        Map<?, ?> sourcePayload = (Map<?, ?>) events.get(0).data();
        assertEquals(List.of(usedSource), sourcePayload.get("sources"));
        Map<?, ?> messagePayload = (Map<?, ?>) events.get(1).data();
        assertEquals("answer", messagePayload.get("chunk"));

        ArgumentCaptor<GroundedTurnModule.Command> commandCaptor = ArgumentCaptor.forClass(GroundedTurnModule.Command.class);
        verify(groundedTurnModule).execute(commandCaptor.capture());
        assertEquals(List.of("ev-1", "ev-2"), commandCaptor.getValue().candidateEvidence().stream()
                .map(document -> String.valueOf(document.getMetadata().get("evidence_id")))
                .toList());
        assertEquals("parent full text", commandCaptor.getValue().parentContexts().get(0).content());
        verify(agentTurnStateStore).complete("msg-1");
    }

    @Test
    void validationFailurePublishesNoSourcesOrAnswer() {
        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        when(executor.execute(eq("question"), eq("conversation-1"), eq("msg-1"), any(), any(), eq("model-1")))
                .thenReturn(Mono.just(answerResult()));
        when(groundedTurnModule.execute(any())).thenReturn(Mono.error(new SourceValidationException(
                UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                UsedSourceValidator.REASON_EVIDENCE_ID_NOT_IN_CANDIDATES)));

        List<ServerSentEvent<Object>> events = service.streamEvents(
                        "question",
                        "conversation-1",
                        currentUser(),
                        SearchScope.empty(),
                        "model-1",
                        "msg-1")
                .collectList()
                .block();

        assertEquals(List.of("error", "done"), events.stream().map(ServerSentEvent::event).toList());
        assertFalse(events.stream().anyMatch(event -> "sources".equals(event.event()) || "message".equals(event.event())));
        verify(agentTurnStateStore).fail("msg-1");
    }

    @Test
    void followupBypassesGroundedTurn() {
        AgentExecutionResult followup = new AgentExecutionResult(
                List.of(), List.of(), List.of(), "请选择范围", List.of("本科生", "研究生"),
                null, null, "normal", false);
        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        when(executor.execute(eq("question"), eq("conversation-1"), eq("msg-1"), any(), any(), eq("model-1")))
                .thenReturn(Mono.just(followup));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "请选择范围", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());

        List<ServerSentEvent<Object>> events = service.streamEvents(
                        "question", "conversation-1", currentUser(), SearchScope.empty(), "model-1", "msg-1")
                .collectList()
                .block();

        assertEquals(List.of("followup", "done"), events.stream().map(ServerSentEvent::event).toList());
        verifyNoInteractions(groundedTurnModule);
    }

    private AgentExecutionResult answerResult() {
        ParentContextBlock parent = new ParentContextBlock(
                "parent-1",
                "doc-1",
                "policy.pdf",
                "parent full text",
                1,
                1,
                2,
                List.of("ev-1", "ev-2"),
                1);
        return new AgentExecutionResult(
                List.of(
                        new EvidenceSnapshot("ev-1", "first child", Map.of("evidence_id", "ev-1")),
                        new EvidenceSnapshot("ev-2", "second child", Map.of("evidence_id", "ev-2"))),
                List.of(parent),
                List.of(),
                null,
                List.of(),
                "ignored draft",
                "ignored instruction",
                "normal",
                true);
    }

    private CurrentUserContext currentUser() {
        return new CurrentUserContext("user-1", "user", "USER", "dept-1", "Dept", "space-1", false);
    }
}
