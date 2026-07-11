package net.topikachu.rag.chat.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.chat.history.entity.ChatMemorySnapshotEntity;
import net.topikachu.rag.chat.history.mapper.ChatMemorySnapshotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonMySqlRedisChatMemoryRepositoryTest {

    private final ChatMemorySnapshotMapper snapshotMapper = mock(ChatMemorySnapshotMapper.class);
    private final RedisTemplate<String, byte[]> redisTemplate = mock(RedisTemplate.class);
    private final ValueOperations<String, byte[]> valueOperations = mock(ValueOperations.class);
    private final JsonChatMessageSerializer serializer = new JsonChatMessageSerializer(new ObjectMapper());
    private final JsonMySqlRedisChatMemoryRepository repository =
            new JsonMySqlRedisChatMemoryRepository(snapshotMapper, redisTemplate, serializer);

    JsonMySqlRedisChatMemoryRepositoryTest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void savesReadableJsonSnapshot() {
        repository.saveAll("c1", List.of(new UserMessage("问题"), new AssistantMessage("答案")));

        verify(snapshotMapper).insert(any(ChatMemorySnapshotEntity.class));
        verify(valueOperations).set(eq("chat-memory:json-snapshot:c1"), any(byte[].class), any());
    }

    @Test
    void readsFromDatabaseWhenCacheMisses() {
        ChatMemorySnapshotEntity entity = new ChatMemorySnapshotEntity();
        entity.setConversationId("c1");
        entity.setMessageBlob(serializer.serializeMessages(List.of(new UserMessage("问题"))));
        when(snapshotMapper.selectById("c1")).thenReturn(entity);

        List<Message> messages = repository.findByConversationId("c1");

        assertEquals(1, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("问题", messages.get(0).getText());
    }

    @Test
    void returnsEmptyListForBadJson() {
        ChatMemorySnapshotEntity entity = new ChatMemorySnapshotEntity();
        entity.setConversationId("c1");
        entity.setMessageBlob("bad-json".getBytes(StandardCharsets.UTF_8));
        when(snapshotMapper.selectById("c1")).thenReturn(entity);

        assertEquals(List.of(), repository.findByConversationId("c1"));
    }

    @Test
    void deletesDatabaseAndCache() {
        repository.deleteByConversationId("c1");

        verify(snapshotMapper).deleteById("c1");
        verify(redisTemplate).delete("chat-memory:json-snapshot:c1");
    }
}
