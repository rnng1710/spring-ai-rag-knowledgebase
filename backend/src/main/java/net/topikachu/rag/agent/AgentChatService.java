package net.topikachu.rag.agent;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.chat.history.ChatHistoryService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.GroundedTurnModule;
import net.topikachu.rag.service.chat.SourceValidationException;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
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
    private final ChatMemory chatMemory;
    private final ConversationExecutionGuard conversationExecutionGuard;
    private final AgentTurnStateStore agentTurnStateStore;
    private final TracingSupport tracingSupport;
    private final GroundedTurnModule groundedTurnModule;
    private final ChatHistoryService chatHistoryService;

    public AgentChatService(AgentExecutor executor,
                            ChatMemory chatMemory,
                            ConversationExecutionGuard conversationExecutionGuard,
                            AgentTurnStateStore agentTurnStateStore,
                            TracingSupport tracingSupport,
                            GroundedTurnModule groundedTurnModule,
                            ChatHistoryService chatHistoryService) {
        this.executor = executor;
        this.chatMemory = chatMemory;
        this.conversationExecutionGuard = conversationExecutionGuard;
        this.agentTurnStateStore = agentTurnStateStore;
        this.tracingSupport = tracingSupport;
        this.groundedTurnModule = groundedTurnModule;
        this.chatHistoryService = chatHistoryService;
    }

    public Flux<ServerSentEvent<Object>> streamEvents(String userInput,
                                                      String conversationId,
                                                      CurrentUserContext currentUserContext,
                                                      SearchScope searchScope,
                                                      String modelId,
                                                      String msgId) {
        log.info("Agent processing query: '{}', conversationId: {}, spaces: {}, tags: {}, modelId: {}, user={}",
                userInput, conversationId,
                searchScope == null ? List.of() : searchScope.requestedSpaceCodes(),
                searchScope == null ? List.of() : searchScope.requestedTags(),
                modelId,
                currentUserContext == null ? null : currentUserContext.username());

        String traceId = tracingSupport.getCurrentTraceId();

        return Mono.fromCallable(() -> conversationExecutionGuard.acquire(conversationId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(lease -> {
                    agentTurnStateStore.createPending(msgId, conversationId, userInput);
                    return executor.execute(userInput, conversationId, msgId, currentUserContext, searchScope, modelId)
                            .flatMapMany(result -> {
                                Flux<ServerSentEvent<Object>> traceEvents = buildTraceEvents(result.notes(), msgId);
                                if (result.isFollowup()) {
                                    ServerSentEvent<Object> followupEvent = buildEvent("followup",
                                            Map.of(
                                                    "msgId", msgId,
                                                    "text", result.followupPrompt(),
                                                    "options", result.followupOptions()));
                                    Mono<ServerSentEvent<Object>> completion = addChatMemory(conversationId, List.of(
                                                    new UserMessage(userInput),
                                                    new AssistantMessage(result.followupPrompt())))
                                            .then(chatHistoryService.saveTurn(
                                                    conversationId, currentUserContext.userId(),
                                                    userInput, result.followupPrompt(), modelId, "agent", msgId))
                                            .doOnSuccess(ignored -> agentTurnStateStore.complete(msgId))
                                            .thenReturn(buildEvent("done", Map.of("msgId", msgId)));

                                    return Flux.concat(
                                                    traceEvents,
                                                    Flux.just(followupEvent),
                                                    completion.flux())
                                            .onErrorResume(exp -> failRequest(msgId, "追问结果保存失败。"));
                                }

                                List<Document> candidateEvidence = result.candidateEvidence().stream()
                                        .map(source -> Document.builder()
                                                .id(source.id())
                                                .text(source.text())
                                                .metadata(source.metadataSnapshot())
                                                .build())
                                        .toList();
                                GroundedTurnModule.Command command = new GroundedTurnModule.Command(
                                        userInput,
                                        conversationId,
                                        currentUserContext.userId(),
                                        modelId,
                                        "agent",
                                        msgId,
                                        traceId,
                                        candidateEvidence,
                                        result.parentContexts());
                                Flux<ServerSentEvent<Object>> groundedEvents = Mono.defer(() -> groundedTurnModule.execute(command))
                                        .doOnSuccess(ignored -> agentTurnStateStore.complete(msgId))
                                        .flatMapMany(grounded -> {
                                            Map<String, Object> sourcePayload = new LinkedHashMap<>();
                                            sourcePayload.put("msgId", msgId);
                                            sourcePayload.put("sources", grounded.usedSources());
                                            return Flux.just(
                                                    buildEvent("sources", sourcePayload),
                                                    buildEvent("message", Map.of("msgId", msgId, "chunk", grounded.answer())),
                                                    buildEvent("done", Map.of("msgId", msgId)));
                                        })
                                        .onErrorResume(error -> failRequest(msgId, groundedFailureMessage(error)));

                                return Flux.concat(traceEvents, groundedEvents);
                            })
                            .onErrorResume(exp -> failRequest(msgId, "工具阶段执行失败。"))
                            .doFinally(signalType -> {
                                agentTurnStateStore.cleanupExpired();
                                lease.close();
                            });
                });
    }

    private Mono<Void> addChatMemory(String conversationId, List<Message> messages) {
        return Mono.fromRunnable(() -> chatMemory.add(conversationId, messages))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
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

    private String groundedFailureMessage(Throwable error) {
        if (error instanceof SourceValidationException sourceValidationException) {
            return sourceValidationException.getUserMessage();
        }
        return "答案生成或持久化失败。";
    }

    private ServerSentEvent<Object> buildEvent(String event, Object data) {
        return ServerSentEvent.builder()
                .event(event)
                .data(data)
                .build();
    }

}
