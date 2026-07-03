package net.topikachu.rag.chat.history;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.chat.history.dto.ChatMessageItem;
import net.topikachu.rag.chat.history.dto.ChatSessionItem;
import net.topikachu.rag.chat.history.dto.ChatSessionPage;
import net.topikachu.rag.chat.history.entity.ChatMessageEntity;
import net.topikachu.rag.chat.history.entity.ChatSessionEntity;
import net.topikachu.rag.chat.history.mapper.ChatMessageMapper;
import net.topikachu.rag.chat.history.mapper.ChatSessionMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ChatHistoryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMessageSerializer serializer;
    private final ChatSessionTitleService titleService;
    private final ChatMemory chatMemory;
    private final PlatformTransactionManager transactionManager;

    public ChatHistoryService(ChatSessionMapper sessionMapper,
                              ChatMessageMapper messageMapper,
                              ChatMessageSerializer serializer,
                              ChatSessionTitleService titleService,
                              ChatMemory chatMemory,
                              PlatformTransactionManager transactionManager) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.serializer = serializer;
        this.titleService = titleService;
        this.chatMemory = chatMemory;
        this.transactionManager = transactionManager;
    }

    public Mono<Void> saveTurn(String conversationId,
                               String userId,
                               String userInput,
                               String assistantAnswer,
                               String modelId,
                               String mode,
                               String msgId) {
        return Mono.fromRunnable(() -> saveTurnBlocking(
                        conversationId,
                        userId,
                        userInput,
                        assistantAnswer,
                        modelId,
                        mode,
                        msgId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public void saveTurnBlocking(String conversationId,
                                 String userId,
                                 String userInput,
                                 String assistantAnswer,
                                 String modelId,
                                 String mode,
                                 String msgId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            ChatSessionEntity session = ensureSession(conversationId, userId, userInput);
            int maxIndex = messageMapper.selectMaxMessageIndex(session.getId());
            insertMessage(session, uniqueMessageId(msgId, "user"), new UserMessage(userInput), maxIndex + 1, modelId, mode);
            insertMessage(session, msgId, new AssistantMessage(assistantAnswer), maxIndex + 2, modelId, mode);

            ChatSessionEntity update = new ChatSessionEntity();
            update.setId(session.getId());
            update.setLastMessageAt(LocalDateTime.now());
            sessionMapper.updateById(update);

            if ("PENDING".equals(session.getTitleStatus())) {
                titleService.generateTitleIfNeeded(session.getConversationId(), userId, userInput);
            }
        });
    }

    public Mono<ChatSessionPage> listSessions(String userId, String keyword, int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        return Mono.fromCallable(() -> {
                    long total = sessionMapper.countVisibleSessions(userId, normalizedKeyword);
                    List<ChatSessionItem> items = sessionMapper.selectVisibleSessions(
                                    userId,
                                    normalizedKeyword,
                                    normalizedSize,
                                    (normalizedPage - 1) * normalizedSize)
                            .stream()
                            .map(this::toSessionItem)
                            .toList();
                    return new ChatSessionPage(items, total, normalizedPage, normalizedSize);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<ChatMessageItem>> listMessages(String conversationId, String userId) {
        return Mono.fromCallable(() -> {
                    ChatSessionEntity session = findVisibleSession(conversationId, userId);
                    if (session == null) {
                        return List.<ChatMessageItem>of();
                    }
                    return messageMapper.selectBySessionId(session.getId())
                            .stream()
                            .map(this::toMessageItem)
                            .toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Boolean> renameSession(String conversationId, String userId, String title) {
        return Mono.fromCallable(() -> {
                    String normalized = normalizeTitle(title);
                    if (!StringUtils.hasText(normalized)) {
                        return false;
                    }
                    ChatSessionEntity session = findVisibleSession(conversationId, userId);
                    if (session == null) {
                        return false;
                    }
                    ChatSessionEntity update = new ChatSessionEntity();
                    update.setId(session.getId());
                    update.setTitle(normalized);
                    update.setTitleStatus("MANUAL");
                    return sessionMapper.updateById(update) > 0;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Boolean> softDeleteSession(String conversationId, String userId) {
        return Mono.fromCallable(() -> {
                    ChatSessionEntity session = findVisibleSession(conversationId, userId);
                    if (session == null) {
                        return false;
                    }
                    ChatSessionEntity update = new ChatSessionEntity();
                    update.setId(session.getId());
                    update.setDeleted(true);
                    boolean deleted = sessionMapper.updateById(update) > 0;
                    if (deleted) {
                        chatMemory.clear(conversationId);
                    }
                    return deleted;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ChatSessionEntity ensureSession(String conversationId, String userId, String firstQuestion) {
        ChatSessionEntity existing = sessionMapper.selectOne(Wrappers.<ChatSessionEntity>lambdaQuery()
                .eq(ChatSessionEntity::getConversationId, conversationId)
                .eq(ChatSessionEntity::getUserId, userId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(UUID.randomUUID().toString().replace("-", ""));
        session.setConversationId(conversationId);
        session.setUserId(userId);
        session.setTitle(fallbackTitle(firstQuestion));
        session.setTitleStatus("PENDING");
        session.setDeleted(false);
        session.setLastMessageAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    private void insertMessage(ChatSessionEntity session,
                               String id,
                               Message message,
                               int index,
                               String modelId,
                               String mode) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(id);
        entity.setSessionId(session.getId());
        entity.setConversationId(session.getConversationId());
        entity.setUserId(session.getUserId());
        entity.setRole(message.getMessageType().getValue());
        entity.setContent(message.getText() == null ? "" : message.getText());
        entity.setMessageBlob(serializer.serializeMessage(message));
        entity.setMessageIndex(index);
        entity.setModelId(modelId);
        entity.setMode(mode);
        messageMapper.insert(entity);
    }

    private ChatSessionEntity findVisibleSession(String conversationId, String userId) {
        return sessionMapper.selectOne(Wrappers.<ChatSessionEntity>lambdaQuery()
                .eq(ChatSessionEntity::getConversationId, conversationId)
                .eq(ChatSessionEntity::getUserId, userId)
                .eq(ChatSessionEntity::getDeleted, false)
                .last("LIMIT 1"));
    }

    private ChatSessionItem toSessionItem(ChatSessionEntity session) {
        return new ChatSessionItem(
                session.getConversationId(),
                session.getTitle(),
                session.getTitleStatus(),
                session.getLastMessageAt(),
                session.getCreateDate());
    }

    private ChatMessageItem toMessageItem(ChatMessageEntity message) {
        return new ChatMessageItem(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getModelId(),
                message.getMode(),
                message.getMessageIndex(),
                message.getCreateDate());
    }

    private String uniqueMessageId(String msgId, String suffix) {
        return msgId + "-" + suffix;
    }

    private String normalizeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return "";
        }
        String trimmed = title.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    private String fallbackTitle(String question) {
        String normalized = normalizeTitle(question);
        return StringUtils.hasText(normalized) ? normalized : "新对话";
    }
}
