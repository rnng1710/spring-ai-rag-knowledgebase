package net.topikachu.rag.agent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class RetrievalAssessmentPolicy {

    private static final Pattern TIME_PATTERN = Pattern.compile("(19|20)\\d{2}|\\d+月|\\d+日|今年|去年|目前|现在|当时");
    private static final List<String> PROCEDURE_KEYWORDS = List.of("流程", "程序", "审批", "复审", "申诉", "听证", "步骤");

    private RetrievalAssessmentPolicy() {
    }

    static RetrievalAssessment assess(String query, int resultCount) {
        if (resultCount <= 0) {
            return new RetrievalAssessment(RetrievalGapType.MISSING_SCOPE, true, List.of("scope", "time"));
        }
        if (containsProcedureKeyword(query)) {
            return new RetrievalAssessment(RetrievalGapType.MISSING_PROCEDURE_BRANCH, true, List.of("procedure", "scope"));
        }
        if (!containsExplicitTime(query)) {
            return new RetrievalAssessment(RetrievalGapType.MISSING_TIME, true, List.of("time", "scope"));
        }
        if (resultCount <= 2) {
            return new RetrievalAssessment(RetrievalGapType.AMBIGUOUS_SUBJECT, true, List.of("subject", "scope"));
        }
        return new RetrievalAssessment(RetrievalGapType.MISSING_SCOPE, true, List.of("scope", "procedure"));
    }

    private static boolean containsProcedureKeyword(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return PROCEDURE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private static boolean containsExplicitTime(String query) {
        return query != null && TIME_PATTERN.matcher(query).find();
    }
}
