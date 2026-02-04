package net.topikachu.rag.config;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.service.chat.HybridSearchService;
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

    public HybridSearchWarmup(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Starting Hybrid Search Warmup ===");
        try {
            hybridSearchService.warmup();
            log.info("=== Hybrid Search Warmup Complete ===");
        } catch (Exception e) {
            log.warn("Hybrid Search Warmup failed (non-critical): {}", e.getMessage());
        }
    }
}
