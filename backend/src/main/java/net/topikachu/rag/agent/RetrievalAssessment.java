package net.topikachu.rag.agent;

import java.util.List;

public record RetrievalAssessment(
        RetrievalGapType gapType,
        boolean improvableByFollowup,
        List<String> allowedFocusTypes) {

    public RetrievalAssessment {
        gapType = gapType == null ? RetrievalGapType.NOT_IMPROVABLE : gapType;
        allowedFocusTypes = allowedFocusTypes == null ? List.of() : List.copyOf(allowedFocusTypes);
    }
}
