package net.topikachu.rag.chat.history;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.chat.history.entity.ChatSessionEntity;
import net.topikachu.rag.chat.history.mapper.ChatSessionMapper;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
@Service
public class ChatSessionTitleService {

    private final ChatSessionMapper sessionMapper;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final String titleModelId;

    public ChatSessionTitleService(ChatSessionMapper sessionMapper,
                                   ChatModelStrategyFactory strategyFactory,
                                   ReactiveChatGateway reactiveChatGateway,
                                   @Value("${rag.chat.title.model-id:deepseek}") String titleModelId) {
        this.sessionMapper = sessionMapper;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
        this.titleModelId = titleModelId;
    }

    public void generateTitleIfNeeded(String conversationId, String userId, String firstQuestion) {
        reactiveChatGateway.call(
                        strategyFactory.getStrategy(titleModelId).getChatClient(),
                        buildTitlePrompt(),
                        Map.of(),
                        firstQuestion)
                .map(this::normalizeTitle)
                .filter(StringUtils::hasText)
                .onErrorResume(ex -> {
                    log.warn("Failed to generate chat session title. conversationId={}", conversationId, ex);
                    return reactor.core.publisher.Mono.just(fallbackTitle(firstQuestion));
                })
                .subscribe(title -> updateGeneratedTitle(conversationId, userId, title));
    }

    private void updateGeneratedTitle(String conversationId, String userId, String title) {
        ChatSessionEntity update = new ChatSessionEntity();
        update.setTitle(title);
        update.setTitleStatus("GENERATED");
        sessionMapper.update(update, Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getConversationId, conversationId)
                .eq(ChatSessionEntity::getUserId, userId)
                .eq(ChatSessionEntity::getTitleStatus, "PENDING")
                .eq(ChatSessionEntity::getDeleted, false));
    }

    private String buildTitlePrompt() {
        return """
                你是对话标题生成器。根据用户第一句话生成一个简短中文标题。
                要求：
                1. 只输出标题本身。
                2. 不超过 18 个汉字。
                3. 不要引号、句号、编号或解释。
                """;
    }

    private String normalizeTitle(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String title = raw.trim()
                .replace("```", "")
                .replace("\"", "")
                .replace("“", "")
                .replace("”", "")
                .replaceAll("\\s+", " ");
        return title.length() <= 80 ? title : title.substring(0, 80);
    }

    private String fallbackTitle(String question) {
        String title = normalizeTitle(question);
        return StringUtils.hasText(title) ? title : "新对话";
    }
}
