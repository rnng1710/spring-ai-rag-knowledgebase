package net.topikachu.rag.api;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.agent.AgentChatService;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.CurrentUserContextService;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ChatService;
import net.topikachu.rag.service.chat.SourceValidationException;
import net.topikachu.rag.service.etl.EtlPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.client.ResourceAccessException;
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
	private final CurrentUserContextService currentUserContextService;
	private final DocumentService documentService;

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
			CurrentUserContext currentUserContext = currentUserContextService.resolveByUsername(principal.getName());
			var conversationKey = String.format("%s:%s", principal.getName(), conversationId);
			SearchScope requestedScope = new SearchScope(chatRequest.spaceCodes(), chatRequest.tags());

			String requestedMode = chatRequest.mode();
			String mode = StringUtils.hasText(requestedMode) ? requestedMode : agentDefaultMode;

			boolean useAgent = agentEnabled && "agent".equalsIgnoreCase(mode);
			String msgId = StringUtils.hasText(chatRequest.msgId()) ? chatRequest.msgId() : ("msg-" + System.currentTimeMillis());
			return documentService.resolveEffectiveSearchScope(currentUserContext, requestedScope)
					.flatMapMany(searchScope -> {
						tagCurrentChatTrace(principal.getName(), conversationId, conversationKey, mode, chatRequest.modelId(),
								locustRunId, questionId, questionBucket, searchScope);

						if (!agentEnabled && "agent".equalsIgnoreCase(mode)) {
							log.warn("Agent mode requested while disabled. conversationId={}, user={}", conversationId, principal.getName());
							return Flux.just(errorEvent(msgId, "当前系统未启用 Agent 模式，请切换到快速模式后重试。"),
									doneEvent(msgId));
						}

						if (useAgent) {
							return agentChatService.streamEvents(
									chatRequest.userInput(),
									conversationKey,
									currentUserContext,
									searchScope,
									chatRequest.modelId(),
									msgId);
						}

						return chatService.streamWithSources(
										chatRequest.userInput(),
										conversationKey,
										currentUserContext,
										searchScope,
										chatRequest.modelId(),
										msgId)
					.flatMapMany(response -> {
						List<Map<String, Object>> sourceMetadata = response.usedSources().stream()
								.map(source -> {
									Map<String, Object> item = new LinkedHashMap<>();
									item.put("evidenceId", source.evidenceId());
									item.put("doc_uuid", source.docUuid());
									item.put("file_name", source.fileName());
									item.put("page_number", source.pageNumber());
									item.put("file_type", source.fileType());
									return item;
								})
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

						return Flux.concat(Flux.just(sourceEvent), messageStream, Flux.just(doneEvent(msgId)));
					})
					.onErrorResume(exp -> {
						String message = "系统繁忙，请稍后重试。";
						if (exp instanceof net.topikachu.rag.service.chat.RetrievalException retrievalException) {
							message = retrievalException.getUserMessage();
						} else if (exp instanceof SourceValidationException sourceValidationException) {
							message = sourceValidationException.getUserMessage();
						} else if (exp instanceof ResourceAccessException) {
							message = "模型服务响应超时，请稍后重试或切换模型。";
						}
						if (exp instanceof SourceValidationException
								|| exp instanceof net.topikachu.rag.service.chat.RetrievalException) {
							log.warn("Chat request failed with user-facing error: {}", message);
						} else {
							log.error("Error in chat", exp);
						}
						ServerSentEvent<Object> fallbackEvent = ServerSentEvent.builder()
								.event("message")
								.data((Object) message)
								.build();
						return Flux.just(errorEvent(msgId, message), fallbackEvent, doneEvent(msgId));
					});
					})
					.onErrorResume(exp -> Flux.just(
							errorEvent(msgId, exp.getMessage() == null ? "请先选择知识空间。" : exp.getMessage()),
							doneEvent(msgId)));
		});
	}

	@PostMapping(path = "/index", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@PreAuthorize("hasRole('ADMIN')")
	public Flux<String> index() {
		return etlPipeline.ingestionFlux()
				.map(document -> (String) document.getMetadata().get(METADATA_SOURCE));
	}

	record ChatRequest(String userInput, List<String> tags, List<String> spaceCodes, String modelId, String mode,
			String msgId) implements Serializable {
	}

	private ServerSentEvent<Object> errorEvent(String msgId, String message) {
		return ServerSentEvent.builder()
				.event("error")
				.data((Object) Map.of("msgId", msgId, "message", message))
				.build();
	}

	private ServerSentEvent<Object> doneEvent(String msgId) {
		return ServerSentEvent.builder()
				.event("done")
				.data((Object) Map.of("msgId", msgId))
				.build();
	}

	private void tagCurrentChatTrace(String username,
			String conversationId,
			String conversationKey,
			String mode,
			String modelId,
			String locustRunId,
			String questionId,
			String questionBucket,
			SearchScope searchScope) {
		Map<String, Object> tags = new LinkedHashMap<>();
		tags.put("langfuse.user.id", username);
		tags.put("langfuse.session.id", conversationKey);
		tags.put("conversation.id", conversationId);
		tags.put("chat.mode", mode);
		tags.put("chat.model_id", modelId);
		tags.put("chat.requested_spaces", searchScope == null ? "" : String.join(",", searchScope.requestedSpaceCodes()));
		tags.put("chat.requested_tags", searchScope == null ? "" : String.join(",", searchScope.requestedTags()));
		tags.put("locust.run_id", locustRunId);
		tags.put("question.id", questionId);
		tags.put("question.bucket", questionBucket);
		tracingSupport.tagCurrent(tags);
	}
}
