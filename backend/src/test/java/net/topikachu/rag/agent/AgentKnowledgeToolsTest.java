package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentKnowledgeToolsTest {

    @Mock
    private AgentKnowledgeService knowledgeService;

    @Mock
    private AgentToolBridge toolBridge;

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private ChatModelStrategy strategy;

    @Mock
    private org.springframework.ai.chat.client.ChatClient chatClient;

    @Test
    void generateFollowupOptionsFailsWithoutRetrievalHistory() {
        AgentKnowledgeTools tools = newTools();

        FollowupOptionsResult result = tools.generateFollowupOptions();

        assertEquals("tool_error", result.status());
        assertEquals("SEARCH_REQUIRED", result.errorCode());
    }

    @Test
    void generateFollowupOptionsReturnsTwoNormalizedQuestions() {
        AgentExecutionContext executionContext = newExecutionContext();
        stubChatClient();
        AgentKnowledgeTools tools = newTools(executionContext);

        executionContext.incrementSearchInvocationCount();
        executionContext.recordRetrieval("原问题", "原问题||5", List.of(), 5, "ok", 2, List.of("e1", "e2"));
        executionContext.updateRetrievalAssessment(RetrievalGapType.MISSING_SCOPE, true, List.of("scope", "procedure"));
        executionContext.addRetrievedEvidence(List.of(
                new EvidenceSnapshot("e1", "证据一", java.util.Map.of("file_name", "doc1")),
                new EvidenceSnapshot("e2", "证据二", java.util.Map.of("file_name", "doc2"))), 12);

        FollowupOptionsResult raw = FollowupOptionsResult.ok(
                List.of("围绕适用对象进一步检索", "围绕审批流程继续检索？"),
                List.of("scope", "procedure"),
                "debug");
        when(reactiveChatGateway.callStructured(any(), anyString(), anyMap(), anyString(), eq(FollowupOptionsResult.class)))
                .thenReturn(Mono.just(raw));
        when(toolBridge.block(any(), any(), anyString())).thenReturn(raw);

        FollowupOptionsResult result = tools.generateFollowupOptions();

        assertEquals("ok", result.status());
        assertEquals(2, result.options().size());
        assertEquals(2, result.focusTypes().size());
        assertTrue(result.options().get(0).endsWith("？"));
        assertEquals(List.of("scope", "procedure"), result.focusTypes());
    }

    private AgentKnowledgeTools newTools() {
        return newTools(newExecutionContext());
    }

    private AgentKnowledgeTools newTools(AgentExecutionContext executionContext) {
        return new AgentKnowledgeTools(
                knowledgeService,
                executionContext,
                toolBridge,
                reactiveChatGateway,
                strategy,
                Duration.ofSeconds(2),
                6,
                12,
                2);
    }

    private AgentExecutionContext newExecutionContext() {
        return new AgentExecutionContext("req-1", "conv-1", "msg-1", "原问题", List.of("tag1"));
    }

    private void stubChatClient() {
        when(strategy.getChatClient()).thenReturn(chatClient);
    }
}
