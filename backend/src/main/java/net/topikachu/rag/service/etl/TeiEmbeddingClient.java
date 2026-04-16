package net.topikachu.rag.service.etl;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.MurmurHash3;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Client for local Windows Python BGE-M3 server.
 * Converts lexical weights (String keys) to Milvus-compatible Long keys via
 * MurmurHash3.
 */
@Component
@Slf4j
public class TeiEmbeddingClient {

    private final WebClient webClient;
    private final Duration timeout;

    public TeiEmbeddingClient(@Value("${rag.embedding.url}") String embeddingUrl,
            @Value("${rag.embedding.timeout-ms:30000}") int timeoutMs,
            WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(embeddingUrl).build();
        this.timeout = Duration.ofMillis(timeoutMs);
        log.info("TeiEmbeddingClient initialized with URL: {}, timeout: {}ms", embeddingUrl, timeoutMs);
    }

    // Request format for Python BGE-M3 server
    public record EmbedRequest(String inputs) {
    }

    // Response format from Python BGE-M3 server
    public record BgeM3Response(
            @JsonProperty("dense_vecs") List<List<Float>> denseVecs,
            @JsonProperty("sparse_vecs") List<Map<String, Float>> sparseVecs) {
    }

    /**
     * Call the unified /embed endpoint and get both dense and sparse vectors.
     */
    public Mono<BgeM3Response> embed(String text) {
        TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(text);
        if (result.wasModified()) {
            log.info(
                    "Sanitized embedding input: removedChars={}, normalizedWhitespace={}, originalLength={}, sanitizedLength={}, preview={}",
                    result.removedChars(),
                    result.normalizedWhitespace(),
                    result.originalLength(),
                    result.text().length(),
                    TextSanitizer.preview(result.text()));
        }
        if (result.isEffectivelyEmpty()) {
            log.warn("Rejecting embedding input after sanitization: preview={}", TextSanitizer.preview(text));
            return Mono.error(new IllegalArgumentException("Embedding input is empty after sanitization"));
        }
        if (TextSanitizer.containsIllegalCodePoints(result.text())) {
            log.warn("Rejecting embedding input that still contains illegal Unicode after sanitization: preview={}",
                    TextSanitizer.preview(result.text()));
            return Mono.error(new IllegalArgumentException("Embedding input still contains illegal Unicode code points"));
        }

        return webClient.post()
                .uri("/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new EmbedRequest(result.text()))
                .retrieve()
                .bodyToMono(BgeM3Response.class)
                .timeout(timeout)
                .doOnError(e -> log.error("BGE-M3 Embedding failed: {}", e.getMessage()));
    }

    /**
     * Get Dense Vector from BGE-M3.
     */
    public Mono<List<Float>> embedDense(String text) {
        return embed(text)
                .map(response -> {
                    if (response.denseVecs() != null && !response.denseVecs().isEmpty()) {
                        return response.denseVecs().get(0);
                    }
                    return Collections.<Float>emptyList();
                })
                .doOnError(e -> log.error("Dense Embedding extraction failed: {}", e.getMessage()));
    }

    public SortedMap<Long, Float> parseSparse(Map<String, Float> lexicalWeights) {
        SortedMap<Long, Float> sparseVector = new TreeMap<>();
        if (lexicalWeights != null) {
            for (Map.Entry<String, Float> entry : lexicalWeights.entrySet()) {
                try {
                    // Keys are tokenizer vocab IDs (e.g., "171815"), parse directly
                    Long key = Long.parseLong(entry.getKey());
                    sparseVector.put(key, entry.getValue());
                } catch (NumberFormatException e) {
                    // Fallback: hash non-numeric keys
                    Long key = hashToken(entry.getKey());
                    sparseVector.put(key, entry.getValue());
                }
            }
        }
        return sparseVector;
    }

    /**
     * Get Sparse Vector from BGE-M3.
     * Keys are already tokenizer vocabulary IDs (as strings), just parse to Long.
     */
    public Mono<SortedMap<Long, Float>> embedSparse(String text) {
        return embed(text)
                .map(response -> {
                    if (response.sparseVecs() != null && !response.sparseVecs().isEmpty()) {
                        return parseSparse(response.sparseVecs().get(0));
                    }
                    return new TreeMap<Long, Float>();
                })
                .doOnError(e -> log.error("Sparse Embedding extraction failed: {}", e.getMessage()));
    }

    /**
     * Hash token string to Long using MurmurHash3.
     * This ensures consistent mapping from tokens to Milvus sparse vector keys.
     */
    private Long hashToken(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        int hash32 = MurmurHash3.hash32x86(bytes, 0, bytes.length, 0);
        return Integer.toUnsignedLong(hash32);
    }
}
