package net.topikachu.rag.service.etl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSanitizerTest {

    @Test
    void removesInvalidSurrogatesAndUnsafeControls() {
        String raw = "教育部文件" + '\uDBC0' + '\u0000' + '\u0007' + "\u3000\u3000( ４ )";

        TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(raw);

        assertEquals("教育部文件 ( ４ )", result.text());
        assertTrue(result.removedChars() >= 3);
        assertTrue(result.normalizedWhitespace() >= 1);
        assertFalse(TextSanitizer.containsIllegalCodePoints(result.text()));
    }

    @Test
    void marksNoiseOnlyTextAsEffectivelyEmpty() {
        TextSanitizer.SanitizationResult result = TextSanitizer.sanitize("\uDBC0\uDBC0  \u3000()[]");

        assertTrue(result.isEffectivelyEmpty());
        assertEquals("()[]", result.text());
    }
}
