package net.topikachu.rag.agent;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Component
public class AgentToolBridge {

    private final Scheduler agentToolBridgeScheduler;

    public AgentToolBridge(@Qualifier("agentToolBridgeScheduler") Scheduler agentToolBridgeScheduler) {
        this.agentToolBridgeScheduler = agentToolBridgeScheduler;
    }

    public <T> T block(Mono<T> source, Duration timeout, String operationName) {
        T result = source.subscribeOn(agentToolBridgeScheduler)
                .timeout(timeout)
                .block();
        if (result == null) {
            throw new IllegalStateException("Tool bridge returned null for operation: " + operationName);
        }
        return result;
    }

    public TimeoutException timeout(String operationName, Duration timeout) {
        return new TimeoutException("Tool operation timed out: " + operationName + " after " + timeout.toMillis() + "ms");
    }
}
