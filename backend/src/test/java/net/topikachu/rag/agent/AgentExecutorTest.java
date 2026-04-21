package net.topikachu.rag.agent;

import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentExecutorTest {

    @Mock
    private AgentTools agentTools;

    @Mock
    private ChatModelStrategyFactory strategyFactory;

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private ChatModelStrategy strategy;

    @Mock
    private ChatClient chatClient;

    @Mock
    private TracingSupport tracingSupport;

    private AgentExecutor agentExecutor;

    @BeforeEach
    void setUp() {
        when(tracingSupport.traceMono(anyString(), anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        agentExecutor = new AgentExecutor(agentTools, strategyFactory, reactiveChatGateway, tracingSupport);
        ReflectionTestUtils.setField(agentExecutor, "timeoutMs", 1_000L);

        when(strategyFactory.getStrategy(anyString())).thenReturn(strategy);
        when(strategy.getChatClient()).thenReturn(chatClient);
    }

    @Test
    void returnsFollowupWhenPlannerRequestsIt() {
        when(reactiveChatGateway.call(any(), anyString(), anyMap(), anyString()))
                .thenReturn(Mono.just("""
                        {"action":"followup","followup":"请补充年份"}
                        """));

        AgentExecutionResult result = agentExecutor.execute("问题", List.of(), "ollama")
                .block(Duration.ofSeconds(2));
        org.junit.jupiter.api.Assertions.assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertTrue(result.isFollowup());
        org.junit.jupiter.api.Assertions.assertEquals("请补充年份", result.followup());
    }

    @Test
    void retrievesEvidenceAndProducesDraftWhenReviewPasses() {
        when(reactiveChatGateway.call(any(), anyString(), anyMap(), anyString()))
                .thenReturn(
                        Mono.just("{\"action\":\"retrieve\",\"query\":\"校规\",\"tags\":[\"tag1\"]}"),
                        Mono.just("{\"query\":\"校规\",\"note\":\"改写完成\"}"),
                        Mono.just("这是回答草稿"),
                        Mono.just("{\"verdict\":\"pass\"}"));

        when(agentTools.retrieve(anyString(), any(), any()))
                .thenReturn(Mono.just(List.of(Document.builder()
                        .id("doc-1")
                        .text("证据文本")
                        .metadata(Map.of("file_name", "rule.pdf", "page_number", 1))
                        .build())));

        AgentExecutionResult result = agentExecutor.execute("问题", List.of("tag1"), "ollama")
                .block(Duration.ofSeconds(2));
        org.junit.jupiter.api.Assertions.assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertFalse(result.isFollowup());
        org.junit.jupiter.api.Assertions.assertEquals("这是回答草稿", result.draft());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.sources().size());
    }
}
