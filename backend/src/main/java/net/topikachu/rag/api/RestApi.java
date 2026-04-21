package net.topikachu.rag.api;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.agent.AgentChatService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ChatService;
import net.topikachu.rag.service.etl.EtlPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.LinkedHashMap;
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
	private final TracingSupport tracingSupport;

	private final EtlPipeline etlPipeline;

	@Value("${rag.agent.enable:true}")
	private boolean agentEnabled;

	@Value("${rag.agent.default-mode:rag}")
	private String agentDefaultMode;

	@PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
	public Flux<ServerSentEvent<Object>> chat(@RequestBody ChatRequest chatRequest,
			@RequestParam() String conversationId,
			@RequestHeader(value = "X-Locust-Run-Id", required = false) String locustRunId,
			@RequestHeader(value = "X-Question-Id", required = false) String questionId,
			@RequestHeader(value = "X-Question-Bucket", required = false) String questionBucket,
			Mono<Principal> principalMono) {
		return principalMono.flatMapMany(principal -> {
			var conversationKey = String.format("%s:%s", principal.getName(), conversationId);

			String mode = chatRequest.mode();
			if (!StringUtils.hasText(mode)) {
				mode = agentDefaultMode;
			}

			boolean useAgent = agentEnabled && "agent".equalsIgnoreCase(mode);
			String msgId = StringUtils.hasText(chatRequest.msgId()) ? chatRequest.msgId() : ("msg-" + System.currentTimeMillis());
			tagCurrentChatTrace(principal.getName(), conversationId, conversationKey, mode, chatRequest.modelId(),
					locustRunId, questionId, questionBucket);

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

						ServerSentEvent<Object> doneEvent = ServerSentEvent.builder()
								.event("done")
								.data((Object) Map.of("msgId", msgId))
								.build();

						return Flux.concat(Flux.just(sourceEvent), messageStream, Flux.just(doneEvent));
					})
					.onErrorResume(exp -> {
						log.error("Error in chat", exp);
						String message = "系统繁忙，请稍后重试。";
						if (exp instanceof net.topikachu.rag.service.chat.RetrievalException retrievalException) {
							message = retrievalException.getUserMessage();
						}
						ServerSentEvent<Object> errorEvent = ServerSentEvent.builder()
								.event("error")
								.data((Object) Map.of(
										"msgId", msgId,
										"message", message))
								.build();
						ServerSentEvent<Object> fallbackEvent = ServerSentEvent.builder()
								.event("message")
								.data((Object) message)
								.build();
						ServerSentEvent<Object> doneEvent = ServerSentEvent.builder()
								.event("done")
								.data((Object) Map.of("msgId", msgId))
								.build();
						return Flux.just(errorEvent, fallbackEvent, doneEvent);
					});
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

	private void tagCurrentChatTrace(String username,
			String conversationId,
			String conversationKey,
			String mode,
			String modelId,
			String locustRunId,
			String questionId,
			String questionBucket) {
		Map<String, Object> tags = new LinkedHashMap<>();
		tags.put("langfuse.user.id", username);
		tags.put("langfuse.session.id", conversationKey);
		tags.put("conversation.id", conversationId);
		tags.put("chat.mode", mode);
		tags.put("chat.model_id", modelId);
		tags.put("locust.run_id", locustRunId);
		tags.put("question.id", questionId);
		tags.put("question.bucket", questionBucket);
		tracingSupport.tagCurrent(tags);
	}
}
