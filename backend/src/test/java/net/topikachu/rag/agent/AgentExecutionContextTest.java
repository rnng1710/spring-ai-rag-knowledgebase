package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentExecutionContextTest {

    @Test
    void parentContextsAreDeduplicatedByParentBlockId() {
        AgentExecutionContext context = new AgentExecutionContext("req-1", "conv-1", "msg-1", "问题", List.of());
        ParentContextBlock first = parent("parent-1", List.of("ev-1", "ev-2"));
        ParentContextBlock duplicate = parent("parent-1", List.of("ev-3"));

        context.addRetrievedParentContexts(List.of(first, duplicate));

        assertEquals(1, context.retrievedParentContexts().size());
        assertEquals(List.of("ev-1", "ev-2"), context.retrievedParentContexts().get(0).evidenceIds());
    }

    @Test
    void selectParentContextsForEvidenceFiltersCitableEvidenceIds() {
        AgentExecutionContext context = new AgentExecutionContext("req-1", "conv-1", "msg-1", "问题", List.of());
        context.addRetrievedParentContexts(List.of(
                parent("parent-1", List.of("ev-1", "ev-2")),
                parent("parent-2", List.of("ev-3"))));

        List<ParentContextBlock> selected = context.selectParentContextsForEvidence(List.of("ev-2"));

        assertEquals(1, selected.size());
        assertEquals("parent-1", selected.get(0).parentBlockId());
        assertEquals(List.of("ev-2"), selected.get(0).evidenceIds());
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
