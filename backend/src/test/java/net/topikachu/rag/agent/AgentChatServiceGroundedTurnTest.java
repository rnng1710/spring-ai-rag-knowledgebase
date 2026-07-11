package net.topikachu.rag.agent;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.chat.history.ChatHistoryService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.GroundedTurnModule;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatServiceGroundedTurnTest {

    @Mock
    private AdaptiveEvidenceWorkflow workflow;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private AgentTurnStateStore agentTurnStateStore;

    @Mock
    private TracingSupport tracingSupport;

    @Mock
    private ChatHistoryService chatHistoryService;

    private AgentChatService service;

    @BeforeEach
    void setUp() {
        service = new AgentChatService(
                workflow,
                chatMemory,
                new ConversationExecutionGuard(),
                agentTurnStateStore,
                tracingSupport,
                chatHistoryService);
    }

    @Test
    void publishesOnlyValidatedUsedSourcesAfterGroundedTurnCompletes() {
        UsedSource usedSource = new UsedSource("ev-2", "doc-2", "second.pdf", 2, "pdf");
        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        when(workflow.execute(any())).thenReturn(Mono.just(new AdaptiveEvidenceWorkflow.Answer(
                new GroundedTurnModule.Result("answer", "factual", List.of(usedSource), 0),
                List.of())));

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

        ArgumentCaptor<AdaptiveEvidenceWorkflow.AgentRequest> requestCaptor =
                ArgumentCaptor.forClass(AdaptiveEvidenceWorkflow.AgentRequest.class);
        verify(workflow).execute(requestCaptor.capture());
        assertEquals("question", requestCaptor.getValue().userInput());
        assertEquals("trace-1", requestCaptor.getValue().traceId());
        verify(agentTurnStateStore).complete("msg-1");
    }

    @Test
    void validationFailurePublishesNoSourcesOrAnswer() {
        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        when(workflow.execute(any())).thenReturn(Mono.error(new SourceValidationException(
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
    void missingEvidenceGateCalibrationReturnsActionableError() {
        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        EvidenceGate uncalibrated = new EvidenceGate(null, null, null);
        IllegalStateException calibrationError = assertThrows(
                IllegalStateException.class,
                uncalibrated::requireCalibration);
        when(workflow.execute(any())).thenReturn(Mono.error(calibrationError));

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
        assertEquals(
                "Agent 证据门尚未校准，请联系管理员完成 T/N/G 校准后重试。",
                ((Map<?, ?>) events.get(0).data()).get("message"));
        assertFalse(events.stream().anyMatch(event ->
                "sources".equals(event.event()) || "message".equals(event.event())));
        verify(agentTurnStateStore).fail("msg-1");
    }

	@Test
	void knowledgeRefusalUsesSourcesMessageDoneContract() {
		when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
		when(workflow.execute(any())).thenReturn(Mono.just(new AdaptiveEvidenceWorkflow.Refusal(
				new GroundedTurnModule.Result(
						UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
						"refusal",
						List.of(),
						0),
				List.of())));

		List<ServerSentEvent<Object>> events = service.streamEvents(
						"question", "conversation-1", currentUser(), SearchScope.empty(), "model-1", "msg-1")
				.collectList()
				.block();

		assertEquals(List.of("sources", "message", "done"),
				events.stream().map(ServerSentEvent::event).toList());
		assertEquals(UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
				((Map<?, ?>) events.get(1).data()).get("chunk"));
	}

    @Test
    void clarificationUsesExistingFollowupContract() {
        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        when(workflow.execute(any())).thenReturn(Mono.just(new AdaptiveEvidenceWorkflow.Clarify(
                "请选择范围",
                List.of("本科生", "研究生"),
                List.of())));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "请选择范围", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());

        List<ServerSentEvent<Object>> events = service.streamEvents(
                        "question", "conversation-1", currentUser(), SearchScope.empty(), "model-1", "msg-1")
                .collectList()
                .block();

        assertEquals(List.of("followup", "done"), events.stream().map(ServerSentEvent::event).toList());
    }

    private CurrentUserContext currentUser() {
        return new CurrentUserContext("user-1", "user", "USER", "dept-1", "Dept", "space-1", false);
    }
}
