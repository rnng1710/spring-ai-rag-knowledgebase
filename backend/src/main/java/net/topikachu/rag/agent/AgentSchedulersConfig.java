package net.topikachu.rag.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
public class AgentSchedulersConfig {

    @Bean(destroyMethod = "dispose")
    public Scheduler agentOrchestratorScheduler(
            @Value("${rag.agent.scheduler.orchestrator-size:8}") int threadCap,
            @Value("${rag.agent.scheduler.orchestrator-queue:1000}") int queueCap) {
        return Schedulers.newBoundedElastic(threadCap, queueCap, "agent-orchestrator");
    }

    @Bean(destroyMethod = "dispose")
    public Scheduler agentToolBridgeScheduler(
            @Value("${rag.agent.scheduler.tool-bridge-size:16}") int threadCap,
            @Value("${rag.agent.scheduler.tool-bridge-queue:1000}") int queueCap) {
        return Schedulers.newBoundedElastic(threadCap, queueCap, "agent-tool-bridge");
    }
}
