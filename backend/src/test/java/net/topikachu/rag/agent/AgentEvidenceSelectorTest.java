package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEvidenceSelectorTest {

    @Test
    void mergeEvidenceKeepsFirstSnapshotById() {
        List<EvidenceSnapshot> merged = AgentEvidenceSelector.mergeEvidence(
                List.of(new EvidenceSnapshot("ev-1", "first", Map.of())),
                List.of(new EvidenceSnapshot("ev-1", "second", Map.of()), new EvidenceSnapshot("ev-2", "third", Map.of())),
                12);

        assertEquals(List.of("first", "third"), merged.stream().map(EvidenceSnapshot::text).toList());
    }

    @Test
    void selectEvidenceDeduplicatesAndIgnoresUnknownIds() {
        List<EvidenceSnapshot> selected = AgentEvidenceSelector.selectEvidence(
                List.of(
                        new EvidenceSnapshot("ev-1", "first", Map.of()),
                        new EvidenceSnapshot("ev-2", "second", Map.of())),
                List.of("ev-2", "", "missing", "ev-2", "ev-1"));

        assertEquals(List.of("ev-2", "ev-1"), selected.stream().map(EvidenceSnapshot::id).toList());
    }

    @Test
    void filterParentContextsKeepsOnlyCitableEvidenceIds() {
        List<ParentContextBlock> filtered = AgentEvidenceSelector.filterParentContexts(
                List.of(parent("parent-1", List.of("ev-1", "ev-2")), parent("parent-2", List.of("ev-3"))),
                Set.of("ev-2"));

        assertEquals(1, filtered.size());
        assertEquals("parent-1", filtered.get(0).parentBlockId());
        assertEquals(List.of("ev-2"), filtered.get(0).evidenceIds());
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
