package net.topikachu.rag.service.sse;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.event.EtlStatusMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SseService {

    // Maintenance of userId -> Emitter
    // In a real scenario, a user might have multiple connections (multiple tabs),
    // so we might need a List<SseEmitter>
    // For simplicity, we assume one connection per user or replace the old one, but
    // let's use a Map to hold the active emitter.
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId) {
        // Timeout 0 means infinite (or rely on server global config)
        // Usually set to a reasonable value like 30m or use heartbeat
        SseEmitter emitter = new SseEmitter(1800000L); // 30 min

        emitter.onCompletion(() -> {
            log.debug("SSE completed for user: {}", userId);
            emitters.remove(userId);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE timeout for user: {}", userId);
            emitters.remove(userId);
            emitter.complete();
        });
        emitter.onError((e) -> {
            log.debug("SSE error for user: {}", userId);
            emitters.remove(userId);
        });

        emitters.put(userId, emitter);
        log.info("User subscribed to SSE: {}", userId);

        try {
            emitter.send(SseEmitter.event().name("init").data("connected"));
        } catch (IOException e) {
            log.warn("Failed to send init event to user {}", userId);
            emitters.remove(userId);
            return emitter;
        }

        return emitter;
    }

    /**
     * Called when a Redis message is received.
     * Checks if this instance holds a connection for the target user.
     */
    public void broadcastLocal(EtlStatusMessage message) {
        String userId = message.getUserId();
        if (userId == null)
            return;

        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                // Send event: id=docUuid, name=status, data=JSON
                emitter.send(SseEmitter.event()
                        .id(message.getDocUuid())
                        .name(message.getStatus().name())
                        .data(message));
                log.info("Pushed SSE event locally to user={}: status={}", userId, message.getStatus());
            } catch (IOException e) {
                log.warn("Failed to push SSE, removing emitter for user={}", userId);
                emitters.remove(userId);
            }
        }
    }
}
