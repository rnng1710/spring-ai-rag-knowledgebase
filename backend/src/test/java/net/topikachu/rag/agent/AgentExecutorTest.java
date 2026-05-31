package net.topikachu.rag.agent;

import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentExecutorTest {

    @Mock
    private AgentOrchestrator agentOrchestrator;

    @Mock
    private ChatModelStrategyFactory strategyFactory;

    @Mock
    private ChatModelStrategy strategy;

    @Mock
    private TracingSupport tracingSupport;

    private AgentExecutor agentExecutor;

    @BeforeEach
    void setUp() {
        when(tracingSupport.traceMono(anyString(), anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(strategyFactory.getStrategy(anyString())).thenReturn(strategy);
        agentExecutor = new AgentExecutor(agentOrchestrator, strategyFactory, tracingSupport);
    }

    @Test
    void delegatesToOrchestratorWithResolvedStrategy() {
        AgentExecutionResult expected = new AgentExecutionResult(
                List.of(),
                List.of(),
                List.of(),
                "请继续检索",
                List.of("问题一？", "问题二？"),
                null,
                null,
                "normal",
                false);

        when(agentOrchestrator.orchestrate(strategy, "问题", "conv-1", "msg-1", null, new SearchScope(List.of(), List.of("tag1"))))
                .thenReturn(Mono.just(expected));

        AgentExecutionResult result = agentExecutor.execute("问题", "conv-1", "msg-1", List.of("tag1"), "ollama")
                .block(Duration.ofSeconds(2));

        org.junit.jupiter.api.Assertions.assertSame(expected, result);
        verify(strategyFactory).getStrategy("ollama");
        verify(agentOrchestrator).orchestrate(strategy, "问题", "conv-1", "msg-1", null, new SearchScope(List.of(), List.of("tag1")));
    }
}
