package net.topikachu.rag.config;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.service.chat.HybridSearchService;
import net.topikachu.rag.service.etl.EtlJobStarter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Warms up the hybrid search service on application startup.
 * This pre-heats gRPC connections and Milvus caches for better first-query
 * latency.
 */
@Component
@Order(100) // Run after other initializers
@Slf4j
public class HybridSearchWarmup implements CommandLineRunner {

    private final HybridSearchService hybridSearchService;
    private final EtlJobStarter etlJobStarter;

    public HybridSearchWarmup(HybridSearchService hybridSearchService, EtlJobStarter etlJobStarter) {
        this.hybridSearchService = hybridSearchService;
        this.etlJobStarter = etlJobStarter;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Starting Hybrid Search Warmup ===");
        etlJobStarter.start(
                hybridSearchService.warmup(),
                "Hybrid search warmup");
    }
}
