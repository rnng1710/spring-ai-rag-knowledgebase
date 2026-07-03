package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentEvidenceSelector {

    private AgentEvidenceSelector() {
    }

    static List<EvidenceSnapshot> mergeEvidence(List<EvidenceSnapshot> existing,
                                                List<EvidenceSnapshot> snapshots,
                                                int maxEvidenceCount) {
        Map<String, EvidenceSnapshot> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (EvidenceSnapshot snapshot : existing) {
                putEvidence(merged, snapshot, maxEvidenceCount);
            }
        }
        if (snapshots != null) {
            for (EvidenceSnapshot snapshot : snapshots) {
                putEvidence(merged, snapshot, maxEvidenceCount);
            }
        }
        return List.copyOf(merged.values());
    }

    static List<EvidenceSnapshot> selectEvidence(List<EvidenceSnapshot> evidence, List<String> selectedEvidenceIds) {
        if (evidence == null || evidence.isEmpty() || selectedEvidenceIds == null || selectedEvidenceIds.isEmpty()) {
            return List.of();
        }
        Map<String, EvidenceSnapshot> byId = new LinkedHashMap<>();
        for (EvidenceSnapshot snapshot : evidence) {
            if (snapshot != null && snapshot.id() != null && !snapshot.id().isBlank()) {
                byId.putIfAbsent(snapshot.id(), snapshot);
            }
        }
        Set<String> uniqueIds = new LinkedHashSet<>();
        List<EvidenceSnapshot> selected = new ArrayList<>();
        for (String selectedEvidenceId : selectedEvidenceIds) {
            if (selectedEvidenceId == null || selectedEvidenceId.isBlank() || !uniqueIds.add(selectedEvidenceId)) {
                continue;
            }
            EvidenceSnapshot snapshot = byId.get(selectedEvidenceId);
            if (snapshot != null) {
                selected.add(snapshot);
            }
        }
        return List.copyOf(selected);
    }

    static List<ParentContextBlock> mergeParentContexts(List<ParentContextBlock> existing,
                                                        List<ParentContextBlock> contexts) {
        Map<String, ParentContextBlock> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (ParentContextBlock context : existing) {
                putParentContext(merged, context);
            }
        }
        if (contexts != null) {
            for (ParentContextBlock context : contexts) {
                putParentContext(merged, context);
            }
        }
        return List.copyOf(merged.values());
    }

    static List<ParentContextBlock> filterParentContexts(List<ParentContextBlock> contexts,
                                                         Set<String> availableEvidenceIds) {
        if (contexts == null || contexts.isEmpty() || availableEvidenceIds == null || availableEvidenceIds.isEmpty()) {
            return List.of();
        }
        List<ParentContextBlock> filtered = new ArrayList<>();
        for (ParentContextBlock context : contexts) {
            List<String> citableEvidenceIds = context.evidenceIds() == null
                    ? List.of()
                    : context.evidenceIds().stream()
                    .filter(availableEvidenceIds::contains)
                    .toList();
            if (citableEvidenceIds.isEmpty()) {
                continue;
            }
            filtered.add(copyWithEvidenceIds(context, citableEvidenceIds));
        }
        return List.copyOf(filtered);
    }

    static List<ParentContextBlock> selectParentContextsForEvidence(List<ParentContextBlock> contexts,
                                                                    List<String> selectedEvidenceIds) {
        if (contexts == null || contexts.isEmpty() || selectedEvidenceIds == null || selectedEvidenceIds.isEmpty()) {
            return List.of();
        }
        Set<String> selectedIds = selectedEvidenceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (selectedIds.isEmpty()) {
            return List.of();
        }
        return filterParentContexts(contexts, selectedIds);
    }

    private static void putEvidence(Map<String, EvidenceSnapshot> evidence,
                                    EvidenceSnapshot snapshot,
                                    int maxEvidenceCount) {
        if (evidence.size() >= maxEvidenceCount
                || snapshot == null
                || snapshot.id() == null
                || snapshot.id().isBlank()) {
            return;
        }
        evidence.putIfAbsent(snapshot.id(), snapshot);
    }

    private static void putParentContext(Map<String, ParentContextBlock> contexts, ParentContextBlock context) {
        if (context == null || context.parentBlockId() == null || context.parentBlockId().isBlank()) {
            return;
        }
        contexts.putIfAbsent(context.parentBlockId(), context);
    }

    private static ParentContextBlock copyWithEvidenceIds(ParentContextBlock context, List<String> evidenceIds) {
        return new ParentContextBlock(
                context.parentBlockId(),
                context.docUuid(),
                context.fileName(),
                context.content(),
                context.parentIndex(),
                context.pageStart(),
                context.pageEnd(),
                evidenceIds,
                context.rank());
    }
}
