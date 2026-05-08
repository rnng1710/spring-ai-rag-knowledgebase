package net.topikachu.rag.agent;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
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
                                              CurrentUserContext currentUserContext,
                                              SearchScope searchScope,
                                              String modelId) {
        ChatModelStrategy strategy = strategyFactory.getStrategy(modelId);
        return tracingSupport.traceMono("agent.execute",
                Map.of(
                        "chat.mode", "agent",
                        "chat.model_id", modelId == null ? "" : modelId,
                        "agent.tags", searchScope == null ? "" : String.join(",", searchScope.requestedTags()),
                        "agent.requested_spaces", searchScope == null ? "" : String.join(",", searchScope.requestedSpaceCodes()),
                        "agent.input_chars", userInput == null ? 0 : userInput.length()),
                agentOrchestrator.orchestrate(strategy, userInput, conversationId, msgId, currentUserContext, searchScope));
    }

    public Mono<AgentExecutionResult> execute(String userInput,
                                              String conversationId,
                                              String msgId,
                                              List<String> tags,
                                              String modelId) {
        return execute(
                userInput,
                conversationId,
                msgId,
                null,
                new SearchScope(List.of(), tags),
                modelId);
    }
}
