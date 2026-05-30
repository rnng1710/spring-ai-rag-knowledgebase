package net.topikachu.rag.autoevaluation.service;

import net.topikachu.rag.autoevaluation.dto.RagasEvaluationRequest;
import net.topikachu.rag.autoevaluation.dto.RagasEvaluationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class RagasEvaluationClient {

    private final WebClient webClient;
    private final String ragasUrl;
    private final Duration timeout;

    public RagasEvaluationClient(
            WebClient.Builder webClientBuilder,
            @Value("${rag.evaluation.ragas.url:http://192.168.193.128:8101/evaluate}") String ragasUrl,
            @Value("${rag.evaluation.ragas.timeout-ms:300000}") long timeoutMs) {
        this.webClient = webClientBuilder.build();
        this.ragasUrl = ragasUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public Mono<RagasEvaluationResponse> evaluate(RagasEvaluationRequest request) {
        return webClient.post()
                .uri(ragasUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RagasEvaluationResponse.class)
                .timeout(timeout);
    }
}
