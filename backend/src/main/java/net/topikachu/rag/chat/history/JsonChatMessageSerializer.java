package net.topikachu.rag.chat.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JsonChatMessageSerializer {

    public static final String SERIALIZER_NAME = "json-message-v1";

    private final ObjectMapper objectMapper;

    public JsonChatMessageSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] serializeMessages(List<Message> messages) {
        try {
            List<JsonMessage> values = (messages == null ? List.<Message>of() : messages).stream()
                    .map(this::toJsonMessage)
                    .toList();
            return objectMapper.writeValueAsBytes(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize chat messages to JSON", ex);
        }
    }

    public List<Message> deserializeMessages(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        try {
            JsonMessage[] values = objectMapper.readValue(bytes, JsonMessage[].class);
            return List.of(values).stream()
                    .map(this::toMessage)
                    .toList();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to deserialize chat messages from JSON", ex);
        }
    }

    public String asString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private JsonMessage toJsonMessage(Message message) {
        String type = message.getMessageType().getValue();
        return new JsonMessage(type, message.getText());
    }

    private Message toMessage(JsonMessage value) {
        String type = value.type() == null ? "" : value.type().toLowerCase();
        String text = value.text() == null ? "" : value.text();
        return switch (type) {
            case "user" -> new UserMessage(text);
            case "assistant" -> new AssistantMessage(text);
            case "system" -> new SystemMessage(text);
            default -> new AssistantMessage(text);
        };
    }

    private record JsonMessage(String type, String text) {
    }
}
