package net.topikachu.rag.service.sse;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.event.EtlStatusMessage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SseService {

    private final Map<String, Sinks.Many<ServerSentEvent<Object>>> emitters = new ConcurrentHashMap<>();

    public Flux<ServerSentEvent<Object>> subscribe(String userId) {
        Sinks.Many<ServerSentEvent<Object>> sink = Sinks.many().multicast().onBackpressureBuffer();
        Sinks.Many<ServerSentEvent<Object>> previous = emitters.put(userId, sink);
        if (previous != null) {
            previous.tryEmitComplete();
        }

        log.info("User subscribed to SSE: {}", userId);

        ServerSentEvent<Object> initEvent = ServerSentEvent.builder()
                .event("init")
                .data((Object) "connected")
                .build();

        return Flux.concat(Flux.just(initEvent), sink.asFlux())
                .doFinally(signal -> {
                    emitters.remove(userId, sink);
                    log.debug("SSE closed for user={}, signal={}", userId, signal);
                });
    }

    public void broadcastLocal(EtlStatusMessage message) {
        String userId = message.getUserId();
        if (userId == null) {
            return;
        }

        Sinks.Many<ServerSentEvent<Object>> sink = emitters.get(userId);
        if (sink == null) {
            return;
        }

        ServerSentEvent<Object> event = ServerSentEvent.builder()
                .id(message.getDocUuid())
                .event(message.getStatus().name())
                .data((Object) message)
                .build();

        Sinks.EmitResult emitResult = sink.tryEmitNext(event);
        if (emitResult.isFailure()) {
            log.warn("Failed to push SSE event for user={}, status={}, result={}",
                    userId, message.getStatus(), emitResult);
        }
    }
}
