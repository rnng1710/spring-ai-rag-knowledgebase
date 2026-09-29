package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServicePromptTest {

    @Test
    void sourcedAnswerPromptUsesDedicatedJsonContract() {
        String prompt = SourcedAnswerPrompts.jsonPrompt();

        assertTrue(prompt.contains("JSON 字段固定为 answer、answerType、usedSources"));
        assertTrue(prompt.contains("usedSources 必须是字符串数组"));
        assertTrue(prompt.contains("会话历史只用于理解指代"));
        assertTrue(prompt.contains("最终答案实际采用的 evidenceId"));
        assertTrue(prompt.contains("不要输出对象数组"));
        assertTrue(prompt.contains("{context}"));
        assertEquals(1, countOccurrences(prompt, "{context}"));
        assertFalse(prompt.contains("原系统要求"));
        assertFalse(prompt.contains("请直接回答"));
    }

    @Test
    void repairInstructionIsSharedByJsonAndForcedToolPrompts() {
        String repair = SourcedAnswerPrompts.repairInstruction("bad_sources", List.of("ev-1"));

        String jsonPrompt = SourcedAnswerPrompts.jsonPrompt(repair);
        String toolPrompt = SourcedAnswerPrompts.toolPrompt(repair);

        assertTrue(jsonPrompt.endsWith(repair));
        assertTrue(toolPrompt.endsWith(repair));
        assertTrue(repair.contains("bad_sources"));
        assertTrue(repair.contains("ev-1"));
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
