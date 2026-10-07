package net.topikachu.rag.evaluation.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.evaluation.ContextNode;
import net.topikachu.rag.evaluation.entity.ChatEvaluationEntity;
import net.topikachu.rag.evaluation.mapper.ChatEvaluationMapper;
import net.topikachu.rag.service.chat.UsedSource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

@Service
@Slf4j
public class EvaluationPersistenceService {

    private final ChatEvaluationMapper mapper;
    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_RATINGS = Set.of("positive", "negative");
    private static final Set<String> VALID_FAILURE_MODES = Set.of(
            "short_text_rerank_bias", "synonym_mismatch", "chunk_boundary",
            "multi_document_gap", "irrelevant_retrieval", "generation_hallucination"
    );

    public EvaluationPersistenceService(ChatEvaluationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> saveConversation(
            String msgId, String conversationId, String userId,
            String question, String answer, String modelId, String mode,
            List<ContextNode> snippets, String traceId) {
        return saveConversation(msgId, conversationId, userId, question, answer, modelId, mode, snippets, List.of(), traceId);
    }

    public Mono<Void> saveConversation(
            String msgId, String conversationId, String userId,
            String question, String answer, String modelId, String mode,
            List<ContextNode> snippets, List<UsedSource> usedSources, String traceId) {
        return Mono.fromRunnable(() -> {
                    ChatEvaluationEntity entity = new ChatEvaluationEntity();
                    entity.setId(msgId);
                    entity.setConversationId(conversationId);
                    entity.setUserId(userId);
                    entity.setQuestion(question);
                    entity.setAnswer(answer);
                    entity.setModelId(modelId);
                    entity.setMode(mode);
                    entity.setContextSnippets(toJson(snippets));
                    entity.setUsedSources(toJson(usedSources));
                    entity.setTraceId(traceId);
                    mapper.insert(entity);
                })
                .doOnError(error -> log.warn("Failed to persist conversation msgId={}", msgId, error))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<Boolean> updateFeedback(String msgId, String userId, boolean isAdmin,
                                         String rating, String failureMode) {
        return Mono.fromCallable(() -> {
                    if (rating != null && !VALID_RATINGS.contains(rating)) {
                        return false;
                    }
                    if (failureMode != null && !failureMode.isEmpty()
                            && !VALID_FAILURE_MODES.contains(failureMode)) {
                        return false;
                    }
                    LambdaUpdateWrapper<ChatEvaluationEntity> wrapper = Wrappers.lambdaUpdate();
                    wrapper.eq(ChatEvaluationEntity::getId, msgId);
                    if (!isAdmin) {
                        wrapper.eq(ChatEvaluationEntity::getUserId, userId);
                    }
                    wrapper.set(ChatEvaluationEntity::getRating, rating);
                    wrapper.set(ChatEvaluationEntity::getFailureMode,
                            failureMode != null && !failureMode.isEmpty() ? failureMode : null);
                    return mapper.update(null, wrapper) > 0;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Boolean> updateReference(String evaluationId, String adminUserId, String reference) {
        return Mono.fromCallable(() -> {
                    String normalized = reference != null && !reference.isBlank()
                            ? reference.trim()
                            : null;
                    LambdaUpdateWrapper<ChatEvaluationEntity> wrapper = Wrappers.lambdaUpdate();
                    wrapper.eq(ChatEvaluationEntity::getId, evaluationId);
                    wrapper.set(ChatEvaluationEntity::getReference, normalized);
                    wrapper.set(ChatEvaluationEntity::getReferenceUpdateDate, LocalDateTime.now());
                    wrapper.set(ChatEvaluationEntity::getReferenceUpdatedBy, adminUserId);
                    return mapper.update(null, wrapper) > 0;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String toJson(List<?> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize evaluation JSON field", e);
            return "[]";
        }
    }
}
