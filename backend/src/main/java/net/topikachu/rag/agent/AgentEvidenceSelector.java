package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ParentContextBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AgentEvidenceSelector {

    private AgentEvidenceSelector() {
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
