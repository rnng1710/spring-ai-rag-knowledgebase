package net.topikachu.rag.agent;

import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ParentContextBlock;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorParentContextTest {

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private AgentKnowledgeService knowledgeService;

    @Mock
    private AgentToolBridge agentToolBridge;

    @Mock
    private AgentHistorySnapshotBuilder historySnapshotBuilder;

    @Mock
    private TracingSupport tracingSupport;

    @Test
    void selectedChildEvidenceCarriesParentContextsIntoExecutionResult() {
        AgentExecutionContext context = newContext();
        context.addRetrievedEvidence(List.of(new EvidenceSnapshot("ev-1", "child text", Map.of("evidence_id", "ev-1"))), 12);
        context.addRetrievedParentContexts(List.of(parent("parent-1", List.of("ev-1", "ev-2"))));

        AgentExecutionResult result = toExecutionResult(
                context,
                new AgentResolution("answer", "normal", "draft", "", List.of("ev-1")));

        assertEquals(1, result.sources().size());
        assertEquals(1, result.parentContexts().size());
        assertEquals(List.of("ev-1"), result.parentContexts().get(0).evidenceIds());
    }

    @Test
    void selectingParentBlockIdDoesNotProduceValidEvidence() {
        AgentExecutionContext context = newContext();
        context.addRetrievedEvidence(List.of(new EvidenceSnapshot("ev-1", "child text", Map.of("evidence_id", "ev-1"))), 12);
        context.addRetrievedParentContexts(List.of(parent("parent-1", List.of("ev-1"))));

        AgentExecutionResult result = toExecutionResult(
                context,
                new AgentResolution("answer", "normal", "draft", "", List.of("parent-1")));

        assertTrue(result.isFollowup());
        assertTrue(result.sources().isEmpty());
        assertTrue(result.parentContexts().isEmpty());
    }

    private AgentExecutionResult toExecutionResult(AgentExecutionContext context, AgentResolution resolution) {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                reactiveChatGateway,
                knowledgeService,
                agentToolBridge,
                historySnapshotBuilder,
                Schedulers.immediate(),
                tracingSupport);
        return ReflectionTestUtils.invokeMethod(orchestrator, "toExecutionResult", context, resolution);
    }

    private AgentExecutionContext newContext() {
        return new AgentExecutionContext("req-1", "conv-1", "msg-1", "问题", List.of());
    }

    private ParentContextBlock parent(String parentBlockId, List<String> evidenceIds) {
        return new ParentContextBlock(
                parentBlockId,
                "doc-1",
                "policy.pdf",
                "parent text",
                1,
                1,
                1,
                evidenceIds,
                1);
    }
}
