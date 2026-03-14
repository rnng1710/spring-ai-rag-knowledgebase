package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Rerank service using local BGE Reranker (BAAI/bge-reranker-base) via TEI.
 * Includes circuit breaker for resilience.
 */
@Service
@Slf4j
public class RerankService {

    @Value("${rag.rerank.url:http://localhost:8099/rerank}")
    private String rerankUrl;

    @Value("${rag.rerank.timeout-ms:1500}")
    private int timeoutMs;

    private final WebClient webClient;

    public RerankService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    // Reactive rerank with hard timeout to avoid blocking request threads
    // indefinitely.
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "rerankService", fallbackMethod = "rerankFallback")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "rerankService", type = io.github.resilience4j.bulkhead.annotation.Bulkhead.Type.SEMAPHORE, fallbackMethod = "rerankFallback")
    public Mono<List<Document>> rerank(String query, List<Document> docs, int topN) {
        if (docs == null || docs.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }

        long startTime = System.currentTimeMillis();

        List<String> texts = docs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        requestBody.put("texts", texts); // TEI uses "texts" not "documents"
        requestBody.put("truncate", false);

        return webClient.post()
                .uri(rerankUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(results -> {
                    List<Document> rerankedDocs = new ArrayList<>();
                    if (results != null && results.isArray()) {
                        List<Map.Entry<Integer, Double>> scored = new ArrayList<>();
                        for (JsonNode result : results) {
                            int index = result.get("index").asInt();
                            double score = result.get("score").asDouble();
                            scored.add(Map.entry(index, score));
                        }
                        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                        for (int i = 0; i < Math.min(scored.size(), topN); i++) {
                            int index = scored.get(i).getKey();
                            if (index < 0 || index >= docs.size()) {
                                continue;
                            }
                            double score = scored.get(i).getValue();
                            Document originalDoc = docs.get(index);
                            originalDoc.getMetadata().put("rerank_score", score);
                            rerankedDocs.add(originalDoc);
                        }
                    }
                    return rerankedDocs;
                })
                .doOnNext(rerankedDocs -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("Rerank completed in {}ms, returned {} docs", elapsed, rerankedDocs.size());
                })
                .doOnError(e -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (e instanceof TimeoutException) {
                        log.warn("Rerank timeout after {}ms (limit={}ms), triggering circuit breaker", elapsed,
                                timeoutMs);
                    } else {
                        log.error("Rerank failed after {}ms, triggering circuit breaker: {}", elapsed, e.getMessage(),
                                e);
                    }
                });
    }

    public Mono<List<Document>> rerankFallback(String query, List<Document> docs, int topN, Throwable t) {
        log.warn("▇▇ Rerank 降级触发 ▇▇ 原因: {} - 仅返回 Top{} 原始检索结果", t.getMessage(), topN);

        if (docs == null || docs.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        return Mono.just(docs.subList(0, Math.min(docs.size(), topN)));
    }
}
