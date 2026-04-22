package net.topikachu.rag.agent;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.ai.memory.BlockingChatMemoryService;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AgentChatService {

    private final AgentExecutor executor;
    private final ChatModelStrategyFactory strategyFactory;
    private final BlockingChatMemoryService blockingChatMemoryService;
    private final ReactiveChatGateway reactiveChatGateway;
    private final ConversationExecutionGuard conversationExecutionGuard;
    private final AgentTurnStateStore agentTurnStateStore;

    @Value("${rag.retrieval.max-context-chars:8000}")
    private int maxContextChars;

    public AgentChatService(AgentExecutor executor,
                            ChatModelStrategyFactory strategyFactory,
                            BlockingChatMemoryService blockingChatMemoryService,
                            ReactiveChatGateway reactiveChatGateway,
                            ConversationExecutionGuard conversationExecutionGuard,
                            AgentTurnStateStore agentTurnStateStore) {
        this.executor = executor;
        this.strategyFactory = strategyFactory;
        this.blockingChatMemoryService = blockingChatMemoryService;
        this.reactiveChatGateway = reactiveChatGateway;
        this.conversationExecutionGuard = conversationExecutionGuard;
        this.agentTurnStateStore = agentTurnStateStore;
    }

    public Flux<ServerSentEvent<Object>> streamEvents(String userInput,
                                                      String conversationId,
                                                      List<String> filterTags,
                                                      String modelId,
                                                      String msgId) {
        log.info("Agent processing query: '{}', conversationId: {}, tags: {}, modelId: {}", userInput, conversationId,
                filterTags, modelId);

        return Mono.fromCallable(() -> conversationExecutionGuard.acquire(conversationId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(lease -> {
                    agentTurnStateStore.createPending(msgId, conversationId, userInput);
                    return executor.execute(userInput, conversationId, msgId, filterTags, modelId)
                            .flatMapMany(result -> {
                                Flux<ServerSentEvent<Object>> traceEvents = buildTraceEvents(result.notes(), msgId);
                                if (result.isFollowup()) {
                                    ServerSentEvent<Object> followupEvent = buildEvent("followup",
                                            Map.of(
                                                    "msgId", msgId,
                                                    "text", result.followupPrompt(),
                                                    "options", result.followupOptions()));
                                    Mono<ServerSentEvent<Object>> completion = blockingChatMemoryService.add(conversationId, List.of(
                                                    new UserMessage(userInput),
                                                    new AssistantMessage(result.followupPrompt())))
                                            .doOnSuccess(ignored -> agentTurnStateStore.complete(msgId))
                                            .thenReturn(buildEvent("done", Map.of("msgId", msgId)));

                                    return Flux.concat(
                                                    traceEvents,
                                                    Flux.just(followupEvent),
                                                    completion.flux())
                                            .onErrorResume(exp -> failRequest(msgId, "追问结果保存失败。"));
                                }

                                List<EvidenceSnapshot> sources = result.sources();
                                ChatModelStrategy strategy = strategyFactory.getStrategy(modelId);

                                Map<String, Object> sourcePayload = new LinkedHashMap<>();
                                sourcePayload.put("msgId", msgId);
                                sourcePayload.put("sources", sources.stream().map(EvidenceSnapshot::metadataSnapshot).toList());
                                Flux<ServerSentEvent<Object>> sourceEvent = Flux.just(buildEvent("sources", sourcePayload));

                                StringBuilder finalAnswerBuffer = new StringBuilder();
                                Flux<ServerSentEvent<Object>> answerStream = buildAnswerStream(
                                                strategy,
                                                userInput,
                                                result,
                                                sources)
                                        .doOnNext(finalAnswerBuffer::append)
                                        .map(content -> buildEvent("message", Map.of("msgId", msgId, "chunk", content)));

                                Mono<ServerSentEvent<Object>> completion = Mono.defer(() -> {
                                    String finalAnswer = finalAnswerBuffer.toString();
                                    if (finalAnswer.isBlank()) {
                                        return Mono.error(new IllegalStateException("Final answer is blank"));
                                    }
                                    return blockingChatMemoryService.add(conversationId, List.of(
                                                    new UserMessage(userInput),
                                                    new AssistantMessage(finalAnswer)))
                                            .doOnSuccess(ignored -> agentTurnStateStore.complete(msgId))
                                            .thenReturn(buildEvent("done", Map.of("msgId", msgId)));
                                });

                                return Flux.concat(traceEvents, sourceEvent, answerStream, completion.flux())
                                        .onErrorResume(exp -> failRequest(msgId, "答案生成或持久化失败。"));
                            })
                            .onErrorResume(exp -> failRequest(msgId, "工具阶段执行失败。"))
                            .doFinally(signalType -> {
                                agentTurnStateStore.cleanupExpired();
                                lease.close();
                            });
                });
    }

    private Flux<String> buildAnswerStream(ChatModelStrategy strategy,
                                           String userInput,
                                           AgentExecutionResult result,
                                           List<EvidenceSnapshot> sources) {
        if (result.isRefusal()) {
            return reactiveChatGateway.streamFinalAnswer(
                    strategy.getChatClient(),
                    buildRefusalPrompt(),
                    Map.of(
                            "draft", buildDraftSection(result.draft()),
                            "revisionInstruction", buildRevisionInstruction(result.finalInstruction()),
                            "userQuestion", userInput),
                    userInput);
        }

        String context = buildContext(sources);
        return reactiveChatGateway.streamFinalAnswer(
                strategy.getChatClient(),
                buildAnswerPrompt(),
                Map.of(
                        "context", context,
                        "draft", buildDraftSection(result.draft()),
                        "revisionInstruction", buildRevisionInstruction(result.finalInstruction())),
                userInput);
    }

    private Flux<ServerSentEvent<Object>> buildTraceEvents(List<AgentNote> notes, String msgId) {
        return Flux.fromIterable(notes)
                .concatMap(note -> Flux.just(
                        buildEvent("agent_stage", Map.of(
                                "msgId", msgId,
                                "stage", note.stage().wireValue(),
                                "sequence", note.sequence())),
                        buildEvent("agent_note", Map.of(
                                "msgId", msgId,
                                "stage", note.stage().wireValue(),
                                "kind", note.kind(),
                                "text", note.text(),
                                "timestamp", note.timestamp(),
                                "sequence", note.sequence()))));
    }

    private Flux<ServerSentEvent<Object>> failRequest(String msgId, String message) {
        agentTurnStateStore.fail(msgId);
        return Flux.just(
                buildEvent("error", Map.of("msgId", msgId, "message", message)),
                buildEvent("done", Map.of("msgId", msgId)));
    }

    private String buildContext(List<EvidenceSnapshot> docs) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            EvidenceSnapshot doc = docs.get(i);
            Map<String, Object> metadata = doc.metadataSnapshot();
            String filename = String.valueOf(metadata.getOrDefault("file_name", "Unknown Source"));
            Object page = metadata.getOrDefault("page_number", metadata.get("page"));
            String pageLabel = (page == null) ? ("片段" + (i + 1)) : ("第" + formatPageValue(page) + "页");

            String structuredEntry = String.format(
                    """
                                    【%s】(来源: %s)
                                    内容: %s
                                    ------------------------
                                    """,
                    pageLabel, filename, doc.text());

            if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                break;
            }
            contextBuilder.append(structuredEntry);
        }
        return contextBuilder.toString();
    }

    private String formatPageValue(Object page) {
        if (page instanceof Number number) {
            double value = number.doubleValue();
            if (Math.rint(value) == value) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
        return page.toString();
    }

    private String buildAnswerPrompt() {
        return """
                你是一个专业的“校园智能知识库问答助手”。你的核心职责是基于提供的【知识库上下文】内容回答问题。

                规则：
                1. 完全、且仅依赖提供的【知识库上下文】回答问题。
                2. 【回答草稿】和【修订要求】只作为写作提示，不得覆盖、扩展或替代上下文中的事实边界。
                3. 如果草稿或修订要求与上下文冲突，必须以上下文为准。
                4. 不得把未出现在上下文中的结论写入最终答案。
                5. 若上下文不足，请明确说明根据已知文档无法确定。
                6. 只有在上下文提供真实页码时，才能输出【第X页】；没有真实页码时，不得编造页码。
                7. 回答保持专业、直接、简洁，不输出内部思考过程。

                ================ 知识库上下文 ================
                {context}
                ============================================

                ================ 回答草稿 ================
                {draft}
                ==========================================

                ================ 修订要求 ================
                {revisionInstruction}
                ==========================================
                """;
    }

    private String buildRefusalPrompt() {
        return """
                你是一个专业的“校园智能知识库问答助手”。

                当前任务不是给出事实结论，而是明确说明：根据当前知识库内容，无法可靠回答用户问题。

                规则：
                1. 仅基于【拒答草稿】和【修订要求】生成最终说明。
                2. 不要补充上下文中不存在的新事实。
                3. 不要再向用户提出新的追问选项。
                4. 回答保持简洁、直接、专业。

                ================ 用户问题 ================
                {userQuestion}
                =========================================

                ================ 拒答草稿 ================
                {draft}
                =========================================

                ================ 修订要求 ================
                {revisionInstruction}
                =========================================
                """;
    }

    private String buildDraftSection(String draft) {
        return (draft == null || draft.isBlank())
                ? "无，直接基于上下文生成最终答案。"
                : draft;
    }

    private String buildRevisionInstruction(String finalInstruction) {
        return (finalInstruction == null || finalInstruction.isBlank())
                ? "无，直接基于上下文生成最终答案。"
                : finalInstruction;
    }

    private ServerSentEvent<Object> buildEvent(String event, Object data) {
        return ServerSentEvent.builder()
                .event(event)
                .data(data)
                .build();
    }
}
