package net.topikachu.rag.agent;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.GroundedTurnModule;
import net.topikachu.rag.service.chat.RetrievalException;
import net.topikachu.rag.service.chat.SourceValidationException;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AgentChatService {

    private final AdaptiveEvidenceWorkflow workflow;
    private final ConversationExecutionGuard conversationExecutionGuard;
    private final AgentTurnStateStore agentTurnStateStore;
    private final TracingSupport tracingSupport;

    public AgentChatService(AdaptiveEvidenceWorkflow workflow,
                            ConversationExecutionGuard conversationExecutionGuard,
                            AgentTurnStateStore agentTurnStateStore,
                            TracingSupport tracingSupport) {
        this.workflow = workflow;
        this.conversationExecutionGuard = conversationExecutionGuard;
        this.agentTurnStateStore = agentTurnStateStore;
        this.tracingSupport = tracingSupport;
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
                    Sinks.Many<AgentNote> noteSink = Sinks.many().multicast().onBackpressureBuffer();

                    Flux<ServerSentEvent<Object>> noteEvents = noteSink.asFlux()
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

                    AdaptiveEvidenceWorkflow.AgentRequest request = new AdaptiveEvidenceWorkflow.AgentRequest(
                            userInput,
                            conversationId,
                            msgId,
                            currentUserContext,
                            searchScope,
                            modelId,
                            traceId,
                            note -> noteSink.tryEmitNext(note));
                    Flux<ServerSentEvent<Object>> outcomeEvents = workflow.execute(request)
                            .doFinally(sig -> noteSink.tryEmitComplete())
                            .flatMapMany(outcome -> {
                                GroundedTurnModule.Result result;
                                if (outcome instanceof AdaptiveEvidenceWorkflow.Answer answer) {
                                    result = answer.result();
                                }
                                else if (outcome instanceof AdaptiveEvidenceWorkflow.Refusal refusal) {
                                    result = refusal.result();
                                }
                                else {
                                    return failRequest(msgId, "Agent 工作流返回了未知结果。");
                                }

                                agentTurnStateStore.complete(msgId);
                                Map<String, Object> sourcePayload = new LinkedHashMap<>();
                                sourcePayload.put("msgId", msgId);
                                sourcePayload.put("sources", result.usedSources());
                                return Flux.just(
                                                buildEvent("sources", sourcePayload),
                                                buildEvent("message", Map.of("msgId", msgId, "chunk", result.answer())),
                                                buildEvent("done", Map.of("msgId", msgId)));
                            })
                            .onErrorResume(error -> failRequest(msgId, workflowFailureMessage(error)));

                    return Flux.merge(noteEvents, outcomeEvents)
                            .doFinally(signalType -> {
                                agentTurnStateStore.cleanupExpired();
                                lease.close();
                            });
                });
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

    private String workflowFailureMessage(Throwable error) {
        if (error instanceof SourceValidationException sourceValidationException) {
            return sourceValidationException.getUserMessage();
        }
        if (error instanceof RetrievalException retrievalException) {
            return retrievalException.getUserMessage();
        }
        return "Agent 工作流执行失败。";
    }

    private ServerSentEvent<Object> buildEvent(String event, Object data) {
        return ServerSentEvent.builder()
                .event(event)
                .data(data)
                .build();
    }

}
