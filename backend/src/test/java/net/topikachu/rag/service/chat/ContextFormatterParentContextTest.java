package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextFormatterParentContextTest {

    @Test
    void formatsParentTextWithCitableChildEvidenceIds() {
        ContextFormatter formatter = new ContextFormatter();
        ReflectionTestUtils.setField(formatter, "maxContextChars", 40000);
        ParentContextBlock block = new ParentContextBlock(
                "doc-1:parent:1:aaaa",
                "doc-1",
                "handbook.pdf",
                "完整父块内容，包含命中 child 的上下文。",
                1,
                3,
                3,
                List.of("doc-1:child:1:bbbb", "doc-1:child:2:cccc"),
                1);

        String context = formatter.formatParentContexts(List.of(block));

        assertTrue(context.contains("parent_block_id: doc-1:parent:1:aaaa"));
        assertTrue(context.contains("doc-1:child:1:bbbb"));
        assertTrue(context.contains("完整父块内容"));
        assertTrue(context.contains("handbook.pdf · 第3页"));
        assertFalse(context.contains("child text:"));
    }
}
