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
                        strategy -> strategy.getModelId().toLowerCase(),
                        strategy -> strategy,
                        (oldVal, newVal) -> newVal));
    }

    public ChatModelStrategy getStrategy(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            log.warn("modelId is null or blank, using default 'ollama' strategy");
            return strategyMap.get("ollama");
        }

        ChatModelStrategy strategy = strategyMap.get(modelId.toLowerCase());
        if (strategy == null) {
            log.warn("Unknown modelId '{}', falling back to 'ollama' strategy", modelId);
            return strategyMap.get("ollama");
        }

        return strategy;
    }
}
