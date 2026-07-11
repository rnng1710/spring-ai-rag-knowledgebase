package net.topikachu.rag.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FollowupOptionsNormalizerTest {

    @Test
    void normalizeRequiresTwoUsefulQuestionsAndAddsQuestionMark() {
        FollowupOptionsResult result = FollowupOptionsNormalizer.normalize(FollowupOptionsResult.ok(
                List.of("请补充更多背景", "围绕适用对象继续检索", "围绕审批流程继续检索？"),
                List.of(" Scope ", "PROCEDURE"),
                "debug"));

        assertEquals("ok", result.status());
        assertEquals(List.of("围绕适用对象继续检索？", "围绕审批流程继续检索？"), result.options());
        assertEquals(List.of("scope", "procedure"), result.focusTypes());
    }

    @Test
    void normalizeRejectsDuplicateFocusTypes() {
        FollowupOptionsResult result = FollowupOptionsNormalizer.normalize(FollowupOptionsResult.ok(
                List.of("围绕适用对象继续检索", "围绕适用时间继续检索"),
                List.of("scope", "scope"),
                "debug"));

        assertEquals("tool_error", result.status());
        assertEquals("INVALID_FOCUS_TYPES", result.errorCode());
    }

    @Test
    void normalizeTrimsBlankRationaleToNull() {
        FollowupOptionsResult result = FollowupOptionsNormalizer.normalize(FollowupOptionsResult.ok(
                List.of("围绕适用对象继续检索", "围绕适用时间继续检索"),
                List.of("scope", "time"),
                " "));

        assertEquals("ok", result.status());
        assertNull(result.rationale());
    }
}
