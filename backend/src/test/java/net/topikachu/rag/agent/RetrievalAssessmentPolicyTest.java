package net.topikachu.rag.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalAssessmentPolicyTest {

    @Test
    void assessProcedureQuestionBeforeTimeGap() {
        RetrievalAssessment assessment = RetrievalAssessmentPolicy.assess("处分审批流程是什么", 3);

        assertEquals(RetrievalGapType.MISSING_PROCEDURE_BRANCH, assessment.gapType());
        assertEquals("procedure", assessment.allowedFocusTypes().get(0));
        assertTrue(assessment.improvableByFollowup());
    }

    @Test
    void assessMissingTimeWhenQueryHasNoExplicitTime() {
        RetrievalAssessment assessment = RetrievalAssessmentPolicy.assess("奖学金条件是什么", 3);

        assertEquals(RetrievalGapType.MISSING_TIME, assessment.gapType());
        assertEquals("time", assessment.allowedFocusTypes().get(0));
    }

    @Test
    void assessAmbiguousSubjectForSmallTimedResultSet() {
        RetrievalAssessment assessment = RetrievalAssessmentPolicy.assess("2024年奖学金条件是什么", 2);

        assertEquals(RetrievalGapType.AMBIGUOUS_SUBJECT, assessment.gapType());
        assertEquals("subject", assessment.allowedFocusTypes().get(0));
    }
}
