package net.topikachu.rag.agent;

import net.topikachu.rag.ai.memory.BlockingChatMemoryService;
import net.topikachu.rag.evaluation.service.EvaluationPersistenceService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ContextFormatter;
import net.topikachu.rag.service.chat.ParentContextBlock;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.UsedSourceValidator;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatServiceParentContextTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private ChatModelStrategyFactory strategyFactory;

    @Mock
    private BlockingChatMemoryService blockingChatMemoryService;

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private ConversationExecutionGuard conversationExecutionGuard;

    @Mock
    private AgentTurnStateStore agentTurnStateStore;

    @Mock
    private EvaluationPersistenceService persistenceService;

    @Mock
    private TracingSupport tracingSupport;

    @Mock
    private ChatModelStrategy strategy;

    @Mock
    private org.springframework.ai.chat.client.ChatClient chatClient;

    @Test
    void finalAnswerPromptUsesParentContext() {
        ContextFormatter contextFormatter = new ContextFormatter();
        ReflectionTestUtils.setField(contextFormatter, "maxContextChars", 40000);
        AgentChatService service = new AgentChatService(
                executor,
                strategyFactory,
                blockingChatMemoryService,
                reactiveChatGateway,
                conversationExecutionGuard,
                agentTurnStateStore,
                persistenceService,
                tracingSupport,
                contextFormatter,
                new UsedSourceValidator());
        ParentContextBlock parent = new ParentContextBlock(
                "parent-1",
                "doc-1",
                "policy.pdf",
                "parent full text",
                1,
                2,
                2,
                List.of("ev-1"),
                1);
        AgentExecutionResult result = new AgentExecutionResult(
                List.of(new EvidenceSnapshot("ev-1", "child text", Map.of("evidence_id", "ev-1"))),
                List.of(parent),
                List.of(),
                null,
                List.of(),
                "draft",
                "",
                "normal",
                false);

        when(strategy.getChatClient()).thenReturn(chatClient);
        when(reactiveChatGateway.streamFinalAnswer(eq(chatClient), anyString(), argThat(params ->
                String.valueOf(params.get("context")).contains("parent full text")
                        && String.valueOf(params.get("context")).contains("ev-1")
                        && !String.valueOf(params.get("context")).contains("child text")), anyString()))
                .thenReturn(Flux.just("answer"));

        Flux<String> stream = ReflectionTestUtils.invokeMethod(
                service,
                "buildAnswerStream",
                strategy,
                "问题",
                result,
                result.sources());
        stream.collectList().block();

        verify(reactiveChatGateway).streamFinalAnswer(eq(chatClient), anyString(), argThat(params ->
                String.valueOf(params.get("context")).contains("parent full text")
                        && String.valueOf(params.get("context")).contains("ev-1")
                        && !String.valueOf(params.get("context")).contains("child text")), eq("问题"));
    }
}
