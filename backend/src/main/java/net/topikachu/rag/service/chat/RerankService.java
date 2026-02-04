package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Value("${rag.rerank.timeout-ms:500}")
    private int timeoutMs;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "rerankService", fallbackMethod = "rerankFallback")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "rerankService", type = io.github.resilience4j.bulkhead.annotation.Bulkhead.Type.SEMAPHORE, fallbackMethod = "rerankFallback")
    public List<Document> rerank(String query, List<Document> docs, int topN) {
        if (docs == null || docs.isEmpty()) {
            return new ArrayList<>();
        }

        long startTime = System.currentTimeMillis();

        try {
            // Prepare request body for TEI rerank endpoint
            List<String> docTexts = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.toList());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);
            requestBody.put("texts", docTexts); // TEI uses "texts" not "documents"
            requestBody.put("truncate", true);

            // Prepare headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            // Call local rerank API
            ResponseEntity<String> response = restTemplate.exchange(
                    rerankUrl,
                    HttpMethod.POST,
                    entity,
                    String.class);

            long elapsed = System.currentTimeMillis() - startTime;

            // Check timeout (soft limit for logging)
            if (elapsed > timeoutMs) {
                log.warn("Rerank took {}ms (exceeds {}ms threshold)", elapsed, timeoutMs);
            }

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode results = objectMapper.readTree(response.getBody());

                // TEI returns array of objects with "index" and "score"
                List<Document> rerankedDocs = new ArrayList<>();
                if (results != null && results.isArray()) {
                    // Sort by score descending and take top N
                    List<Map.Entry<Integer, Double>> scored = new ArrayList<>();
                    for (JsonNode result : results) {
                        int index = result.get("index").asInt();
                        double score = result.get("score").asDouble();
                        scored.add(Map.entry(index, score));
                    }
                    scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                    for (int i = 0; i < Math.min(scored.size(), topN); i++) {
                        int index = scored.get(i).getKey();
                        double score = scored.get(i).getValue();

                        Document originalDoc = docs.get(index);
                        originalDoc.getMetadata().put("rerank_score", score);
                        rerankedDocs.add(originalDoc);
                    }
                }

                log.info("Rerank completed in {}ms, returned {} docs", elapsed, rerankedDocs.size());
                return rerankedDocs;
            } else {
                log.error("Rerank API returned non-2xx status: {}", response.getStatusCode());
                throw new RuntimeException("Rerank API failed with status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Rerank failed: {}", e.getMessage());
            throw new RuntimeException("Rerank execution failed", e);
        }
    }

    public List<Document> rerankFallback(String query, List<Document> docs, int topN, Throwable t) {
        log.warn("▇▇ Rerank 降级触发 ▇▇ 原因: {} - 仅返回 Top{} 原始检索结果", t.getMessage(), topN);

        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        return docs.subList(0, Math.min(docs.size(), topN));
    }
}
