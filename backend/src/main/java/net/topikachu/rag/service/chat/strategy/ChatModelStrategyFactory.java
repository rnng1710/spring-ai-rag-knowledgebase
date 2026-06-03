package net.topikachu.rag.service.chat.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ChatModelStrategyFactory {

    private final Map<String, ChatModelStrategy> strategyMap;

    @Autowired
    public ChatModelStrategyFactory(List<ChatModelStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(
                        // modelId 统一转小写：客户端传入的大小写不统一，不敏感匹配避免 404
                        strategy -> strategy.getModelId()
                                .toLowerCase(),
                        strategy -> strategy,
                        (oldVal, newVal)
                                -> newVal));
    }

    public ChatModelStrategy getStrategy(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            log.warn("modelId is null or blank, using default 'ollama' " +
                    "strategy");
            return strategyMap.get("ollama");
        }

        ChatModelStrategy strategy = strategyMap.get(modelId.toLowerCase());
        if (strategy == null) {
            log.warn("Unknown modelId '{}', falling back to 'ollama' strategy"
                    , modelId);
            // 未知 modelId 也回退到 ollama：同上，本地模型是唯一无需外部 API key 的兜底方案
            return strategyMap.get("ollama");
        }

        return strategy;
    }
}
