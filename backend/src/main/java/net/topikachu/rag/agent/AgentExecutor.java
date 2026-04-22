package net.topikachu.rag.agent;

import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class AgentExecutor {

    private final AgentOrchestrator agentOrchestrator;
    private final ChatModelStrategyFactory strategyFactory;
    private final TracingSupport tracingSupport;

    public AgentExecutor(AgentOrchestrator agentOrchestrator,
                         ChatModelStrategyFactory strategyFactory,
                         TracingSupport tracingSupport) {
        this.agentOrchestrator = agentOrchestrator;
        this.strategyFactory = strategyFactory;
        this.tracingSupport = tracingSupport;
    }

    public Mono<AgentExecutionResult> execute(String userInput,
                                              String conversationId,
                                              String msgId,
                                              List<String> tags,
                                              String modelId) {
        ChatModelStrategy strategy = strategyFactory.getStrategy(modelId);
        return tracingSupport.traceMono("agent.execute",
                Map.of(
                        "chat.mode", "agent",
                        "chat.model_id", modelId == null ? "" : modelId,
                        "agent.tags", tags == null ? "" : String.join(",", tags),
                        "agent.input_chars", userInput == null ? 0 : userInput.length()),
                agentOrchestrator.orchestrate(strategy, userInput, conversationId, msgId, tags));
    }
}
