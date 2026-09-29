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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        if (command.answerPolicy() == AnswerPolicy.KNOWLEDGE_REFUSAL) {
            return validatedResult(
                            new SourcedAnswerResult(
                                    UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                                    "refusal",
                                    List.of()),
                            command,
                            0)
                    .flatMap(result -> commit(command, result).thenReturn(result));
        }
        return loadHistory(command.conversationId())
                .flatMap(history -> {
                    String context = contextFormatter.formatParentContexts(command.parentContexts());
                    ChatModelStrategy strategy = strategyFactory.getStrategy(command.modelId());
                    return generateValidated(command, strategy, context, history, 0, null);
                })
                .flatMap(result -> commit(command, result).thenReturn(result));
    }

    private Mono<Result> generateValidated(Command command,
                                           ChatModelStrategy strategy,
                                           String context,
                                           List<Message> history,
                                           int repairCount,
                                           String repairInstruction) {
        return Mono.defer(() -> command.answerPolicy() == AnswerPolicy.REVIEWED_GROUNDED
                        ? strategy.callReviewedAnswer(
                                reactiveChatGateway,
                                context,
                                command.userInput(),
                                command.conversationId(),
                                history,
                                command.reviewedCandidateAnswer(),
                                command.reviewedEvidenceIds(),
                                repairInstruction)
                        : strategy.callSourcedAnswer(
                                reactiveChatGateway,
                                context,
                                command.userInput(),
                                command.conversationId(),
                                history,
                                repairInstruction))
                .onErrorMap(this::toSourceValidationError)
                .flatMap(answer -> validatedResult(answer, command, repairCount))
                .onErrorResume(SourceValidationException.class, error -> {
                    if (repairCount > 0 || command.maxAnswerRepairs() <= 0) {
                        return Mono.error(error);
                    }
                    String instruction = SourcedAnswerPrompts.repairInstruction(
                            error.getReason(),
                            allowedEvidenceIds(command.candidateEvidence()));
                    return generateValidated(command, strategy, context, history, 1, instruction);
                });
    }

    private Mono<Result> validatedResult(SourcedAnswerResult answer, Command command, int repairCount) {
        return Mono.fromCallable(() -> {
            validateReviewedAnswer(answer, command);
            return new Result(
                    answer.answer(),
                    answer.answerType(),
                    usedSourceValidator.validate(answer, command.candidateEvidence()),
                    repairCount);
        });
    }

    private void validateReviewedAnswer(SourcedAnswerResult answer, Command command) {
        if (command.answerPolicy() != AnswerPolicy.REVIEWED_GROUNDED) {
            return;
        }
        if (answer == null || !"factual".equalsIgnoreCase(answer.answerType())) {
            throw new SourceValidationException(
                    UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                    UsedSourceValidator.REASON_REVIEWED_ANSWER_REFUSED);
        }
        Set<String> usedEvidenceIds = new LinkedHashSet<>();
        for (String evidenceId : answer.usedSources() == null ? List.<String>of() : answer.usedSources()) {
            if (evidenceId != null && !evidenceId.isBlank()) {
                usedEvidenceIds.add(evidenceId.strip());
            }
        }
        if (!usedEvidenceIds.containsAll(command.reviewedEvidenceIds())) {
            throw new SourceValidationException(
                    UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                    UsedSourceValidator.REASON_REQUIRED_EVIDENCE_NOT_USED);
        }
    }

    private List<String> allowedEvidenceIds(List<Document> candidates) {
        return candidates.stream()
                .map(candidate -> {
                    Object evidenceId = candidate.getMetadata().get("evidence_id");
                    return evidenceId == null ? candidate.getId() : evidenceId.toString().trim();
                })
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
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
        if (error instanceof StructuredResponseException) {
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
            List<ParentContextBlock> parentContexts,
            AnswerPolicy answerPolicy,
            int maxAnswerRepairs,
            String reviewedCandidateAnswer,
            List<String> reviewedEvidenceIds) {

        public Command {
            candidateEvidence = candidateEvidence == null ? List.of() : List.copyOf(candidateEvidence);
            parentContexts = parentContexts == null ? List.of() : List.copyOf(parentContexts);
            Objects.requireNonNull(answerPolicy, "answerPolicy must not be null");
            reviewedCandidateAnswer = reviewedCandidateAnswer == null ? "" : reviewedCandidateAnswer.strip();
            reviewedEvidenceIds = reviewedEvidenceIds == null
                    ? List.of()
                    : reviewedEvidenceIds.stream()
                            .filter(Objects::nonNull)
                            .map(String::strip)
                            .filter(id -> !id.isEmpty())
                            .distinct()
                            .toList();
            if (maxAnswerRepairs < 0 || maxAnswerRepairs > 1) {
                throw new IllegalArgumentException("maxAnswerRepairs must be 0 or 1");
            }
            if (answerPolicy == AnswerPolicy.REVIEWED_GROUNDED
                    && (reviewedCandidateAnswer.isEmpty() || reviewedEvidenceIds.isEmpty())) {
                throw new IllegalArgumentException("Reviewed answer and evidence ids are required");
            }
        }

        public Command(String userInput,
                       String conversationId,
                       String userId,
                       String modelId,
                       String mode,
                       String msgId,
                       String traceId,
                       List<Document> candidateEvidence,
                       List<ParentContextBlock> parentContexts,
                       AnswerPolicy answerPolicy,
                       int maxAnswerRepairs) {
            this(
                    userInput,
                    conversationId,
                    userId,
                    modelId,
                    mode,
                    msgId,
                    traceId,
                    candidateEvidence,
                    parentContexts,
                    answerPolicy,
                    maxAnswerRepairs,
                    "",
                    List.of());
        }
    }

    public enum AnswerPolicy {
        GROUNDED,
        REVIEWED_GROUNDED,
        KNOWLEDGE_REFUSAL
    }

    public record Result(String answer, String answerType, List<UsedSource> usedSources, int repairCount) {

        public Result {
            usedSources = usedSources == null ? List.of() : List.copyOf(usedSources);
        }
    }
}
