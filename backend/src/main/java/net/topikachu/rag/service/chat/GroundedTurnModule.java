package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.chat.history.ChatHistoryService;
import net.topikachu.rag.evaluation.ContextNode;
import net.topikachu.rag.evaluation.service.EvaluationPersistenceService;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
@Slf4j
public final class GroundedTurnModule {

    private final ContextFormatter contextFormatter;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final UsedSourceValidator usedSourceValidator;
    private final ChatMemory chatMemory;
    private final ChatHistoryService chatHistoryService;
    private final EvaluationPersistenceService persistenceService;

    public GroundedTurnModule(ContextFormatter contextFormatter,
                              ChatModelStrategyFactory strategyFactory,
                              ReactiveChatGateway reactiveChatGateway,
                              UsedSourceValidator usedSourceValidator,
                              ChatMemory chatMemory,
                              ChatHistoryService chatHistoryService,
                              EvaluationPersistenceService persistenceService) {
        this.contextFormatter = contextFormatter;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
        this.usedSourceValidator = usedSourceValidator;
        this.chatMemory = chatMemory;
        this.chatHistoryService = chatHistoryService;
        this.persistenceService = persistenceService;
    }

    public Mono<Result> execute(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        return loadHistory(command.conversationId())
                .flatMap(history -> {
                    String context = contextFormatter.formatParentContexts(command.parentContexts());
                    ChatModelStrategy strategy = strategyFactory.getStrategy(command.modelId());
                    return strategy.callSourcedAnswer(
                            reactiveChatGateway,
                            context,
                            command.userInput(),
                            command.conversationId(),
                            history);
                })
                .onErrorMap(this::toSourceValidationError)
                .map(answer -> new Result(
                        answer.answer(),
                        answer.answerType(),
                        usedSourceValidator.validate(answer, command.candidateEvidence())))
                .flatMap(result -> commit(command, result).thenReturn(result));
    }

    private Mono<List<Message>> loadHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> {
                    List<Message> history = chatMemory.get(conversationId);
                    if (history == null || history.isEmpty()) {
                        return List.<Message>of();
                    }
                    return history.stream()
                            .filter(message -> message instanceof UserMessage || message instanceof AssistantMessage)
                            .toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> commit(Command command, Result result) {
        Mono<Void> memoryCommit = Mono.fromRunnable(() -> chatMemory.add(command.conversationId(), List.of(
                        new UserMessage(command.userInput()),
                        new AssistantMessage(result.answer()))))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
        Mono<Void> historyCommit = chatHistoryService.saveTurn(
                command.conversationId(),
                command.userId(),
                command.userInput(),
                result.answer(),
                command.modelId(),
                command.mode(),
                command.msgId());
        Mono<Void> evaluationCommit = persistenceService.saveConversation(
                command.msgId(),
                command.conversationId(),
                command.userId(),
                command.userInput(),
                result.answer(),
                command.modelId(),
                command.mode(),
                toContextNodes(command.candidateEvidence()),
                result.usedSources(),
                command.traceId());
        // ponytail: completion barrier only; add compensation if partial cross-store writes become an observed problem.
        return Mono.when(memoryCommit, historyCommit, evaluationCommit);
    }

    private Throwable toSourceValidationError(Throwable error) {
        if (error instanceof SourceValidationException) {
            return error;
        }
        if (error instanceof IllegalArgumentException
                && error.getMessage() != null
                && error.getMessage().contains("structured")) {
            log.warn("Structured grounded answer parse failed: {}. Cause: {}",
                    error.getMessage(),
                    error.getCause() == null ? "no cause" : error.getCause().getMessage());
            return new SourceValidationException(
                    UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                    "json_parse_failed");
        }
        return error;
    }

    private List<ContextNode> toContextNodes(List<Document> documents) {
        List<ContextNode> nodes = new ArrayList<>();
        for (Document document : documents) {
            String fileName = String.valueOf(document.getMetadata().getOrDefault("file_name", "Unknown File"));
            Object scoreValue = document.getMetadata().get("score");
            double score = scoreValue instanceof Number number ? number.doubleValue() : 0.0;
            nodes.add(new ContextNode(document.getText(), fileName, score));
        }
        return nodes;
    }

    public record Command(
            String userInput,
            String conversationId,
            String userId,
            String modelId,
            String mode,
            String msgId,
            String traceId,
            List<Document> candidateEvidence,
            List<ParentContextBlock> parentContexts) {

        public Command {
            candidateEvidence = candidateEvidence == null ? List.of() : List.copyOf(candidateEvidence);
            parentContexts = parentContexts == null ? List.of() : List.copyOf(parentContexts);
        }
    }

    public record Result(String answer, String answerType, List<UsedSource> usedSources) {

        public Result {
            usedSources = usedSources == null ? List.of() : List.copyOf(usedSources);
        }
    }
}
