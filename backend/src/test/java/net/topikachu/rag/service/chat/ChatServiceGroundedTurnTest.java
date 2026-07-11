package net.topikachu.rag.service.chat;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceGroundedTurnTest {

    @Mock
    private RetrievalPipeline retrievalPipeline;

    @Mock
    private ChatModelStrategyFactory strategyFactory;

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private TracingSupport tracingSupport;

    @Mock
    private GroundedTurnModule groundedTurnModule;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(
                retrievalPipeline,
                strategyFactory,
                reactiveChatGateway,
                tracingSupport,
                groundedTurnModule);
        ReflectionTestUtils.setField(service, "hybridTopK", 20);
        ReflectionTestUtils.setField(service, "rerankTopK", 10);
    }

    @Test
    void delegatesRetrievedEvidenceToGroundedTurn() {
        Document candidate = new Document("child", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "policy.pdf"));
        ParentContextBlock parent = new ParentContextBlock(
                "parent-1", "doc-1", "policy.pdf", "parent text",
                1, 1, 1, List.of("ev-1"), 1);
        UsedSource usedSource = new UsedSource("ev-1", "doc-1", "policy.pdf", 1, "pdf");
        CurrentUserContext user = new CurrentUserContext(
                "user-1", "user", "USER", "dept-1", "Dept", "space-1", false);

        when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        when(retrievalPipeline.retrieveWithParentContexts(
                eq("question"), eq(user), eq(SearchScope.empty()), eq(20), eq(10), anyMap()))
                .thenReturn(Mono.just(new RetrievalResult(List.of(candidate), List.of(parent))));
        when(groundedTurnModule.execute(any()))
                .thenReturn(Mono.just(new GroundedTurnModule.Result("answer", "factual", List.of(usedSource))));

        ChatService.ChatStreamResponse response = service.streamWithSources(
                        "question", "conversation-1", user, SearchScope.empty(), "model-1", "msg-1")
                .block();

        assertEquals(List.of("answer"), response.flux().collectList().block());
        assertEquals(List.of(usedSource), response.usedSources());
        ArgumentCaptor<GroundedTurnModule.Command> commandCaptor = ArgumentCaptor.forClass(GroundedTurnModule.Command.class);
        verify(groundedTurnModule).execute(commandCaptor.capture());
        assertEquals("rag", commandCaptor.getValue().mode());
        assertEquals(List.of(candidate), commandCaptor.getValue().candidateEvidence());
        assertEquals(List.of(parent), commandCaptor.getValue().parentContexts());
    }
}
