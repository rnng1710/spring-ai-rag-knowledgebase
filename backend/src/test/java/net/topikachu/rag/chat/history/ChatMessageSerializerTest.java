package net.topikachu.rag.chat.history;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChatMessageSerializerTest {

    private final ChatMessageSerializer serializer = new ChatMessageSerializer();

    @Test
    void roundTripsSingleMessage() {
        Message message = new UserMessage("你好");

        Message restored = serializer.deserializeMessage(serializer.serializeMessage(message));

        assertInstanceOf(UserMessage.class, restored);
        assertEquals("你好", restored.getText());
    }

    @Test
    void roundTripsMessageList() {
        List<Message> messages = List.of(
                new UserMessage("问题"),
                new AssistantMessage("答案"));

        List<Message> restored = serializer.deserializeMessages(serializer.serializeMessages(messages));

        assertEquals(2, restored.size());
        assertInstanceOf(UserMessage.class, restored.get(0));
        assertInstanceOf(AssistantMessage.class, restored.get(1));
        assertEquals("问题", restored.get(0).getText());
        assertEquals("答案", restored.get(1).getText());
    }
}
