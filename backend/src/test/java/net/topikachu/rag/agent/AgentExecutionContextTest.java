package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void snapshotIsImmutableCopy() {
        AgentExecutionContext context = new AgentExecutionContext("req-1", "conv-1", "msg-1", "问题", List.of());
        context.addNote(AgentStage.PLANNING, "decision", "规划");
        context.addRetrievedEvidence(List.of(new EvidenceSnapshot("ev-1", "text", Map.of())), 12);

        AgentExecutionSnapshot snapshot = context.snapshot();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.notes().add(new AgentNote(2, AgentStage.RETRIEVING, "x", "x", 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.retrievedEvidence().add(new EvidenceSnapshot("ev-2", "text", Map.of())));
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
