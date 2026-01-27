package net.topikachu.rag.api;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.service.chat.ChatService;
import net.topikachu.rag.service.etl.EtlPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.io.Serializable;
import java.security.Principal;

import static org.springframework.ai.reader.tika.TikaDocumentReader.METADATA_SOURCE;

@RestController()
@RequestMapping("/api/v1")
@Slf4j
public class RestApi {

	@Autowired
	private ChatService chatService;

	@Autowired
	private EtlPipeline etlPipeline;

	@PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
	public Flux<ServerSentEvent<Object>> chat(@RequestBody ChatRequest chatRequest,
			@RequestParam() String conversationId,
			Principal principal) {
		var conversationKey = String.format("%s:%s", principal.getName(), conversationId);

		ChatService.ChatStreamResponse response = chatService.streamWithSources(chatRequest.userInput, conversationKey);

		// 1. Prepare Source Event
		List<Map<String, Object>> sourceMetadata = response.sources().stream()
				.map(doc -> doc.getMetadata())
				.collect(Collectors.toList());

		ServerSentEvent<Object> sourceEvent = ServerSentEvent.builder()
				.event("sources")
				.data(sourceMetadata)
				.build();

		// 2. Prepare Message Stream
		Flux<ServerSentEvent<Object>> messageStream = response.flux()
				.map(content -> ServerSentEvent.builder()
						.event("message")
						.data((Object) content)
						.build());

		// 3. Concat: Sources -> Message Stream
		return Flux.concat(
				Flux.just(sourceEvent),
				messageStream)
				.doOnError(exp -> log.error("Error in chat", exp));
	}

	@PostMapping(path = "/index", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@PreAuthorize("hasRole('ADMIN')")
	public Flux<String> index() {
		return etlPipeline.ingestionFlux()
				.map(document -> (String) document.getMetadata().get(METADATA_SOURCE));
	}

	record ChatRequest(String userInput) implements Serializable {
	}
}
