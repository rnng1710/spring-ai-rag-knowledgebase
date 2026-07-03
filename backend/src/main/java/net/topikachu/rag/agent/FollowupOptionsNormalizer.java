package net.topikachu.rag.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class FollowupOptionsNormalizer {

    private FollowupOptionsNormalizer() {
    }

    static FollowupOptionsResult normalize(FollowupOptionsResult raw) {
        if (raw == null || !"ok".equals(raw.status())) {
            return FollowupOptionsResult.toolError("INVALID_FOLLOWUP_OPTIONS", "followup options response is invalid");
        }

        List<String> normalizedOptions = normalizeQuestions(raw.options());
        List<String> normalizedFocusTypes = normalizeFocusTypes(raw.focusTypes());
        if (normalizedOptions.size() != 2 || normalizedFocusTypes.size() != 2) {
            return FollowupOptionsResult.toolError("INVALID_FOLLOWUP_OPTIONS", "followup options must contain exactly 2 items");
        }
        if (normalizedFocusTypes.get(0).equals(normalizedFocusTypes.get(1))) {
            return FollowupOptionsResult.toolError("INVALID_FOCUS_TYPES", "followup focus types must cover two different dimensions");
        }
        return FollowupOptionsResult.ok(normalizedOptions, normalizedFocusTypes, sanitizeRationale(raw.rationale()));
    }

    private static List<String> normalizeQuestions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String option : options) {
            if (option == null) {
                continue;
            }
            String trimmed = option.trim();
            if (trimmed.isBlank() || trimmed.startsWith("请补充")) {
                continue;
            }
            if (!trimmed.endsWith("？") && !trimmed.endsWith("?")) {
                trimmed = trimmed + "？";
            }
            if (trimmed.length() < 8 || !unique.add(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeFocusTypes(List<String> focusTypes) {
        if (focusTypes == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String focusType : focusTypes) {
            if (focusType == null || focusType.isBlank()) {
                continue;
            }
            normalized.add(focusType.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private static String sanitizeRationale(String rationale) {
        if (rationale == null || rationale.isBlank()) {
            return null;
        }
        return rationale.length() > 300 ? rationale.substring(0, 300) : rationale;
    }
}
