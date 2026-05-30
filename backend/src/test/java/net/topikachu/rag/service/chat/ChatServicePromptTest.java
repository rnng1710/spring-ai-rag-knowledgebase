package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServicePromptTest {

    @Test
    void sourcedAnswerPromptUsesDedicatedJsonContract() {
        String prompt = ChatService.buildSourcedAnswerPrompt();

        assertTrue(prompt.contains("JSON 字段固定为 answer、answerType、usedSources"));
        assertTrue(prompt.contains("usedSources 必须是字符串数组"));
        assertTrue(prompt.contains("不要输出对象数组"));
        assertTrue(prompt.contains("{context}"));
        assertEquals(1, countOccurrences(prompt, "{context}"));
        assertFalse(prompt.contains("原系统要求"));
        assertFalse(prompt.contains("请直接回答"));
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int index = text.indexOf(target);
        while (index >= 0) {
            count++;
            index = text.indexOf(target, index + target.length());
        }
        return count;
    }
}
