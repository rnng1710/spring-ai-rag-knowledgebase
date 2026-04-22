package net.topikachu.rag.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentTurnStateStore {

    private final Duration ttl;
    private final ConcurrentHashMap<String, PendingTurn> pendingTurns = new ConcurrentHashMap<>();

    public AgentTurnStateStore(@Value("${rag.agent.pending-ttl-seconds:600}") long ttlSeconds) {
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public void createPending(String requestId, String conversationId, String userInput) {
        cleanupExpired();
        pendingTurns.put(requestId, new PendingTurn(requestId, conversationId, userInput, Instant.now()));
    }

    public void complete(String requestId) {
        pendingTurns.remove(requestId);
    }

    public void fail(String requestId) {
        pendingTurns.remove(requestId);
    }

    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        for (Map.Entry<String, PendingTurn> entry : pendingTurns.entrySet()) {
            if (entry.getValue().createdAt().isBefore(cutoff)) {
                pendingTurns.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    @Scheduled(fixedDelayString = "${rag.agent.pending-cleanup-ms:60000}")
    public void scheduledCleanup() {
        cleanupExpired();
    }

    private record PendingTurn(String requestId, String conversationId, String userInput, Instant createdAt) {
    }
}
