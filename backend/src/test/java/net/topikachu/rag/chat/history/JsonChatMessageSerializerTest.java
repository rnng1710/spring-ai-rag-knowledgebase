package net.topikachu.rag.chat.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonChatMessageSerializerTest {

    private final JsonChatMessageSerializer serializer = new JsonChatMessageSerializer(new ObjectMapper());

    @Test
    void roundTripsMessageListAsReadableJson() {
        List<Message> messages = List.of(
                new SystemMessage("system"),
                new UserMessage("问题"),
                new AssistantMessage("答案"));

        byte[] bytes = serializer.serializeMessages(messages);
        List<Message> restored = serializer.deserializeMessages(bytes);

        assertEquals("[{\"type\":\"system\",\"text\":\"system\"},{\"type\":\"user\",\"text\":\"问题\"},{\"type\":\"assistant\",\"text\":\"答案\"}]",
                serializer.asString(bytes));
        assertInstanceOf(SystemMessage.class, restored.get(0));
        assertInstanceOf(UserMessage.class, restored.get(1));
        assertInstanceOf(AssistantMessage.class, restored.get(2));
        assertEquals("答案", restored.get(2).getText());
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> serializer.deserializeMessages("not-json".getBytes()));
    }
}
