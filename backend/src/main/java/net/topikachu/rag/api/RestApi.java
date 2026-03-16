package net.topikachu.rag.api;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.agent.AgentChatService;
import net.topikachu.rag.service.chat.ChatService;
import net.topikachu.rag.service.etl.EtlPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.io.Serializable;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import static org.springframework.ai.reader.tika.TikaDocumentReader.METADATA_SOURCE;

@RestController()
@RequestMapping("/api/v1")
@Slf4j
@RequiredArgsConstructor
public class RestApi {

	private final ChatService chatService;
	private final AgentChatService agentChatService;

	private final EtlPipeline etlPipeline;

	@Value("${rag.agent.enable:true}")
	private boolean agentEnabled;

	@Value("${rag.agent.default-mode:rag}")
	private String agentDefaultMode;

	@PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
	public Flux<ServerSentEvent<Object>> chat(@RequestBody ChatRequest chatRequest,
			@RequestParam() String conversationId,
			Principal principal) {
		var conversationKey = String.format("%s:%s", principal.getName(), conversationId);

		String mode = chatRequest.mode();
		if (!StringUtils.hasText(mode)) {
			mode = agentDefaultMode;
		}

		boolean useAgent = agentEnabled && "agent".equalsIgnoreCase(mode);
		String msgId = StringUtils.hasText(chatRequest.msgId()) ? chatRequest.msgId() : ("msg-" + System.currentTimeMillis());

		if (!agentEnabled && "agent".equalsIgnoreCase(mode)) {
			log.warn("Agent mode requested while disabled. conversationId={}, user={}", conversationId, principal.getName());
			return Flux.just(
					ServerSentEvent.builder()
							.event("error")
							.data((Object) Map.of(
									"msgId", msgId,
									"message", "当前系统未启用 Agent 模式，请切换到快速模式后重试。"))
							.build(),
					ServerSentEvent.builder()
							.event("done")
							.data((Object) Map.of("msgId", msgId))
							.build());
		}

		if (useAgent) {
			return agentChatService.streamEvents(
					chatRequest.userInput(),
					conversationKey,
					chatRequest.tags(),
					chatRequest.modelId(),
					msgId);
		}

		return chatService.streamWithSources(
						chatRequest.userInput(),
						conversationKey,
						chatRequest.tags(),
						chatRequest.modelId())
				.flatMapMany(response -> {
					List<Map<String, Object>> sourceMetadata = response.sources().stream()
							.map(doc -> doc.getMetadata())
							.collect(Collectors.toList());

					ServerSentEvent<Object> sourceEvent = ServerSentEvent.builder()
							.event("sources")
							.data(sourceMetadata)
							.build();

					Flux<ServerSentEvent<Object>> messageStream = response.flux()
							.map(content -> ServerSentEvent.builder()
									.event("message")
									.data((Object) content)
									.build());

					return Flux.concat(Flux.just(sourceEvent), messageStream);
				})
				.onErrorResume(exp -> {
					log.error("Error in chat", exp);
					ServerSentEvent<Object> fallbackEvent = ServerSentEvent.builder()
							.event("message")
							.data((Object) "系统繁忙，请稍后重试。")
							.build();
					return Flux.just(fallbackEvent);
				});
	}

	@PostMapping(path = "/index", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@PreAuthorize("hasRole('ADMIN')")
	public Flux<String> index() {
		return etlPipeline.ingestionFlux()
				.map(document -> (String) document.getMetadata().get(METADATA_SOURCE));
	}

	record ChatRequest(String userInput, List<String> tags, String modelId, String mode, String msgId) implements Serializable {
	}
}
