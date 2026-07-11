package net.topikachu.rag.chat.history;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.chat.history.entity.ChatMemorySnapshotEntity;
import net.topikachu.rag.chat.history.mapper.ChatMemorySnapshotMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Slf4j
@Repository
@ConditionalOnProperty(prefix = "rag.chat.memory", name = "serializer", havingValue = "legacy", matchIfMissing = true)
public class MySqlRedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat-memory:snapshot:";

    private final ChatMemorySnapshotMapper snapshotMapper;
    private final RedisTemplate<String, byte[]> chatMemoryRedisTemplate;
    private final ChatMessageSerializer serializer;
    private final Duration cacheTtl;

    public MySqlRedisChatMemoryRepository(ChatMemorySnapshotMapper snapshotMapper,
                                          RedisTemplate<String, byte[]> chatMemoryRedisTemplate,
                                          ChatMessageSerializer serializer) {
        this.snapshotMapper = snapshotMapper;
        this.chatMemoryRedisTemplate = chatMemoryRedisTemplate;
        this.serializer = serializer;
        this.cacheTtl = Duration.ofDays(7);
    }

    @Override
    public List<String> findConversationIds() {
        return snapshotMapper.selectList(Wrappers.<ChatMemorySnapshotEntity>lambdaQuery()
                        .select(ChatMemorySnapshotEntity::getConversationId))
                .stream()
                .map(ChatMemorySnapshotEntity::getConversationId)
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        byte[] cached = chatMemoryRedisTemplate.opsForValue().get(redisKey(conversationId));
        if (cached != null && cached.length > 0) {
            return deserialize(conversationId, cached);
        }

        ChatMemorySnapshotEntity snapshot = snapshotMapper.selectById(conversationId);
        if (snapshot == null || snapshot.getMessageBlob() == null) {
            return List.of();
        }

        List<Message> messages = deserialize(conversationId, snapshot.getMessageBlob());
        refreshCache(conversationId, snapshot.getMessageBlob());
        return messages;
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        byte[] bytes = serializer.serializeMessages(messages);

        ChatMemorySnapshotEntity entity = new ChatMemorySnapshotEntity();
        entity.setConversationId(conversationId);
        entity.setMessageBlob(bytes);
        entity.setMessageCount(messages == null ? 0 : messages.size());
        entity.setSerializer(ChatMessageSerializer.SERIALIZER_NAME);

        if (snapshotMapper.selectById(conversationId) == null) {
            snapshotMapper.insert(entity);
        } else {
            snapshotMapper.updateById(entity);
        }
        refreshCache(conversationId, bytes);
    }

    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        snapshotMapper.deleteById(conversationId);
        chatMemoryRedisTemplate.delete(redisKey(conversationId));
    }

    private List<Message> deserialize(String conversationId, byte[] bytes) {
        try {
            return serializer.deserializeMessages(bytes);
        } catch (RuntimeException ex) {
            log.warn("Failed to deserialize chat memory snapshot. conversationId={}", conversationId, ex);
            return List.of();
        }
    }

    private void refreshCache(String conversationId, byte[] bytes) {
        try {
            chatMemoryRedisTemplate.opsForValue().set(redisKey(conversationId), bytes, cacheTtl);
        } catch (RuntimeException ex) {
            log.warn("Failed to refresh chat memory Redis cache. conversationId={}", conversationId, ex);
        }
    }

    private String redisKey(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
