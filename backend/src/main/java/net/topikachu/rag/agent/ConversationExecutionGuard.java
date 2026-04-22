package net.topikachu.rag.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ConversationExecutionGuard {

    private final ConcurrentHashMap<String, GuardEntry> guards = new ConcurrentHashMap<>();

    public Lease acquire(String conversationId) throws InterruptedException {
        GuardEntry entry = guards.compute(conversationId, (key, existing) -> {
            GuardEntry candidate = existing == null ? new GuardEntry() : existing;
            candidate.retain();
            return candidate;
        });
        boolean acquired = false;
        try {
            entry.semaphore().acquire();
            acquired = true;
            return new Lease(conversationId, entry);
        } finally {
            if (!acquired) {
                releaseReference(conversationId, entry);
            }
        }
    }

    private void releaseReference(String conversationId, GuardEntry entry) {
        if (entry.releaseReference() == 0) {
            guards.remove(conversationId, entry);
        }
    }

    public final class Lease implements AutoCloseable {
        private final String conversationId;
        private final GuardEntry entry;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(String conversationId, GuardEntry entry) {
            this.conversationId = conversationId;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            entry.semaphore().release();
            releaseReference(conversationId, entry);
        }
    }

    private static final class GuardEntry {
        private final Semaphore semaphore = new Semaphore(1);
        private final AtomicInteger references = new AtomicInteger();

        private Semaphore semaphore() {
            return semaphore;
        }

        private void retain() {
            references.incrementAndGet();
        }

        private int releaseReference() {
            return references.decrementAndGet();
        }
    }
}
