package net.topikachu.rag.chat.history;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

@Component
public class ChatMessageSerializer {

    public static final String SERIALIZER_NAME = "kryo-message-v1";

    private final ThreadLocal<Kryo> kryo = ThreadLocal.withInitial(() -> {
        Kryo instance = new Kryo();
        instance.setRegistrationRequired(false);
        instance.setReferences(true);
        instance.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        return instance;
    });

    public byte[] serializeMessage(Message message) {
        return writeObject(message);
    }

    public Message deserializeMessage(byte[] bytes) {
        return readObject(bytes, Message.class);
    }

    public byte[] serializeMessages(List<Message> messages) {
        return writeObject(messages == null ? List.of() : messages);
    }

    @SuppressWarnings("unchecked")
    public List<Message> deserializeMessages(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        Object value = readObject(bytes, Object.class);
        if (value instanceof List<?> list) {
            return (List<Message>) list;
        }
        return List.of();
    }

    private byte[] writeObject(Object value) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (Output output = new Output(stream)) {
            kryo.get().writeClassAndObject(output, value);
        }
        return stream.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private <T> T readObject(byte[] bytes, Class<T> type) {
        try (Input input = new Input(new ByteArrayInputStream(bytes))) {
            Object value = kryo.get().readClassAndObject(input);
            return (T) value;
        }
    }
}
