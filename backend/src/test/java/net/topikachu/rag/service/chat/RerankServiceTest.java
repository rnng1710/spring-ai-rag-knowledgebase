package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankServiceTest {

	@Test
	void fallbackMarksCopiesAndClearsStaleScores() {
		WebClient.Builder builder = mock(WebClient.Builder.class);
		when(builder.build()).thenReturn(mock(WebClient.class));
		RerankService service = new RerankService(builder);
		Document original = new Document("text", new LinkedHashMap<>(Map.of("rerank_score", 0.9)));

		List<Document> fallback = service.rerankFallback(
				"question", List.of(original), 1, new IllegalStateException("offline")).block();

		assertEquals(1, fallback.size());
		assertTrue(Boolean.TRUE.equals(fallback.get(0).getMetadata().get("rerank_fallback")));
		assertFalse(fallback.get(0).getMetadata().containsKey("rerank_score"));
		assertTrue(original.getMetadata().containsKey("rerank_score"));
		assertFalse(original.getMetadata().containsKey("rerank_fallback"));
	}
}
