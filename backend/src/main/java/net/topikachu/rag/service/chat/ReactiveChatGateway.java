package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.observability.TracingSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Slf4j
@Component
public class ReactiveChatGateway {

    static final String SUBMIT_SOURCED_ANSWER_TOOL = "submitSourcedAnswer";

    private final TracingSupport tracingSupport;
    private final ObjectMapper objectMapper;

    @Value("${rag.llm.log-raw-response:true}")
    private boolean logRawResponse;

    public ReactiveChatGateway(TracingSupport tracingSupport, ObjectMapper objectMapper) {
        this.tracingSupport = tracingSupport;
        this.objectMapper = objectMapper;
    }

    public Mono<String> call(ChatClient chatClient,
                             String systemText,
                             Map<String, Object> systemParams,
                             String userText) {
        return call(chatClient, systemText, systemParams, userText, null, null);
    }

    public Mono<String> call(ChatClient chatClient,
                             String systemText,
                             Map<String, Object> systemParams,
                             String userText,
                             String conversationId,
                             MessageChatMemoryAdvisor chatMemoryAdvisor) {
        return tracingSupport.traceMono("llm.chat_call",
                Map.of(
                        "llm.conversation_id", conversationId == null ? "" : conversationId,
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.user_chars", userText == null ? 0 : userText.length()),
                // .call() 是阻塞调用，必须 subscribeOn(boundedElastic) 卸载到弹性线程池，避免卡死 Netty 事件循环
                Mono.fromCallable(() -> buildPrompt(chatClient, systemText, systemParams, userText, conversationId, chatMemoryAdvisor)
                        .call()
                        .content())
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public <T> Mono<T> callStructured(ChatClient chatClient,
                                      String systemText,
                                      Map<String, Object> systemParams,
                                      String userText,
                                      Class<T> responseType) {
        return callStructured(chatClient, systemText, systemParams, userText, null, null, responseType);
    }

    public <T> Mono<T> callStructured(ChatClient chatClient,
                                      String systemText,
                                      Map<String, Object> systemParams,
                                      String userText,
                                      String conversationId,
                                      MessageChatMemoryAdvisor chatMemoryAdvisor,
                                      Class<T> responseType) {
        return tracingSupport.traceMono("llm.chat_call_structured",
                Map.of(
                        "llm.conversation_id", conversationId == null ? "" : conversationId,
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.user_chars", userText == null ? 0 : userText.length()),
                Mono.fromCallable(() -> buildPrompt(chatClient, systemText, systemParams, userText, conversationId, chatMemoryAdvisor)
                                .options(jsonObjectOptions())
                                .call()
                                .content())
                        .doOnNext(raw -> logRawStructuredResponse(conversationId, responseType, raw))
                        .map(raw -> decodeStructuredResponse(raw, responseType, objectMapper))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public <T> Mono<T> callStructured(ChatClient chatClient,
                                      String systemText,
                                      Map<String, Object> systemParams,
                                      List<Message> historyMessages,
                                      String userText,
                                      String conversationId,
                                      Class<T> responseType) {
        List<Message> messages = new ArrayList<>(historyMessages == null ? List.of() : historyMessages);
        messages.add(UserMessage.builder().text(userText).build());
        return tracingSupport.traceMono("llm.chat_call_structured",
                Map.of(
                        "llm.conversation_id", conversationId == null ? "" : conversationId,
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.user_chars", userText == null ? 0 : userText.length()),
                Mono.fromCallable(() -> buildPrompt(
                                chatClient,
                                systemText,
                                systemParams,
                                messages,
                                List.of(),
                                List.of(),
                                Map.of())
                                .options(jsonObjectOptions())
                                .call()
                                .content())
                        .doOnNext(raw -> logRawStructuredResponse(conversationId, responseType, raw))
                        .map(raw -> decodeStructuredResponse(raw, responseType, objectMapper))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<SourcedAnswerResult> callSourcedAnswerTool(OpenAiApi openAiApi,
                                                           String model,
                                                           String systemText,
                                                           Map<String, Object> systemParams,
                                                           List<Message> historyMessages,
                                                           String userText) {
        return tracingSupport.traceMono("llm.sourced_answer_tool",
                Map.of(
                        "llm.model", model == null ? "" : model,
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.user_chars", userText == null ? 0 : userText.length()),
                Mono.fromCallable(() -> {
                            OpenAiApi.ChatCompletionRequest request = new OpenAiApi.ChatCompletionRequest(
                                    sourcedAnswerMessages(systemText, systemParams, historyMessages, userText),
                                    model,
                                    List.of(sourcedAnswerTool()),
                                    OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(SUBMIT_SOURCED_ANSWER_TOOL));
                            ResponseEntity<OpenAiApi.ChatCompletion> response = openAiApi.chatCompletionEntity(request);
                            return decodeSourcedAnswerToolCall(response.getBody(), objectMapper);
                        })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public <T> Mono<T> runToolPhase(ChatClient chatClient,
                                    String systemText,
                                    Map<String, Object> systemParams,
                                    List<Message> messages,
                                    List<Advisor> advisors,
                                    List<ToolCallback> toolCallbacks,
                                    Map<String, Object> toolContext,
                                    Class<T> responseType) {
        return tracingSupport.traceMono("llm.tool_phase",
                Map.of(
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.history_messages", messages == null ? 0 : messages.size(),
                        "llm.tool_count", toolCallbacks == null ? 0 : toolCallbacks.size()),
                        Mono.fromCallable(() -> buildPrompt(chatClient, systemText, systemParams, messages, advisors, toolCallbacks, toolContext)
                                .call()
                                .content())
                                .doOnNext(raw -> logRawToolResponse(responseType, raw))
                                .map(raw -> decodeStructuredResponse(raw, responseType, objectMapper)));
    }

    public Flux<String> stream(ChatClient chatClient,
                               String systemText,
                               Map<String, Object> systemParams,
                               String userText,
                               String conversationId,
                               MessageChatMemoryAdvisor chatMemoryAdvisor) {
        return tracingSupport.traceFlux("llm.chat_stream",
                Map.of(
                        "llm.conversation_id", conversationId == null ? "" : conversationId,
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.user_chars", userText == null ? 0 : userText.length()),
                buildPrompt(chatClient, systemText, systemParams, userText, conversationId, chatMemoryAdvisor)
                        .stream()
                        .content());
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(ChatClient chatClient,
                                                         String systemText,
                                                         Map<String, Object> systemParams,
                                                         String userText,
                                                         String conversationId,
                                                         MessageChatMemoryAdvisor chatMemoryAdvisor) {
        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
        prompt = applySystem(prompt, systemText, systemParams);
        prompt = prompt.user(userText);
        if (chatMemoryAdvisor != null && conversationId != null) {
            prompt = prompt.advisors(chatMemoryAdvisor)
                    .advisors(spec -> spec.param(CONVERSATION_ID, conversationId));
        }
        return prompt;
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(ChatClient chatClient,
                                                         String systemText,
                                                         Map<String, Object> systemParams,
                                                         List<Message> messages,
                                                         List<Advisor> advisors,
                                                         List<ToolCallback> toolCallbacks,
                                                         Map<String, Object> toolContext) {
        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
        prompt = applySystem(prompt, systemText, systemParams);
        if (messages != null && !messages.isEmpty()) {
            prompt = prompt.messages(messages);
        }
        if (advisors != null && !advisors.isEmpty()) {
            prompt = prompt.advisors(advisors);
        }
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            prompt = prompt.toolCallbacks(toolCallbacks);
        }
        if (toolContext != null && !toolContext.isEmpty()) {
            prompt = prompt.toolContext(toolContext);
        }
        return prompt;
    }

    private ChatClient.ChatClientRequestSpec applySystem(ChatClient.ChatClientRequestSpec prompt,
                                                         String systemText,
                                                         Map<String, Object> systemParams) {
        return prompt.system(spec -> {
            spec.text(systemText);
            if (systemParams != null) {
                systemParams.forEach(spec::param);
            }
        });
    }

    private List<OpenAiApi.ChatCompletionMessage> sourcedAnswerMessages(String systemText,
                                                                        Map<String, Object> systemParams,
                                                                        List<Message> historyMessages,
                                                                        String userText) {
        List<OpenAiApi.ChatCompletionMessage> messages = new ArrayList<>();
        messages.add(new OpenAiApi.ChatCompletionMessage(
                renderSystemText(systemText, systemParams),
                OpenAiApi.ChatCompletionMessage.Role.SYSTEM));
        if (historyMessages != null) {
            for (Message historyMessage : historyMessages) {
                OpenAiApi.ChatCompletionMessage.Role role = toOpenAiRole(historyMessage);
                if (role != null && historyMessage.getText() != null && !historyMessage.getText().isBlank()) {
                    messages.add(new OpenAiApi.ChatCompletionMessage(historyMessage.getText(), role));
                }
            }
        }
        messages.add(new OpenAiApi.ChatCompletionMessage(userText, OpenAiApi.ChatCompletionMessage.Role.USER));
        return messages;
    }

    private OpenAiApi.ChatCompletionMessage.Role toOpenAiRole(Message message) {
        if (message == null) {
            return null;
        }
        MessageType messageType = message.getMessageType();
        if (MessageType.USER.equals(messageType)) {
            return OpenAiApi.ChatCompletionMessage.Role.USER;
        }
        if (MessageType.ASSISTANT.equals(messageType)) {
            return OpenAiApi.ChatCompletionMessage.Role.ASSISTANT;
        }
        return null;
    }

    private String renderSystemText(String systemText, Map<String, Object> systemParams) {
        String rendered = systemText == null ? "" : systemText;
        if (systemParams == null || systemParams.isEmpty()) {
            return rendered;
        }
        for (Map.Entry<String, Object> entry : systemParams.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return rendered;
    }

    static OpenAiApi.FunctionTool sourcedAnswerTool() {
        return new OpenAiApi.FunctionTool(new OpenAiApi.FunctionTool.Function(
                "Submit the final grounded answer and the evidence ids actually used.",
                SUBMIT_SOURCED_ANSWER_TOOL,
                sourcedAnswerSchema(),
                true));
    }

    private static Map<String, Object> sourcedAnswerSchema() {
        Map<String, Object> answer = Map.of(
                "type", "string",
                "description", "Final user-facing answer. Include source citations in the text for factual answers.");
        Map<String, Object> answerType = Map.of(
                "type", "string",
                "enum", List.of("factual", "refusal"));
        Map<String, Object> usedSources = Map.of(
                "type", "array",
                "description", "Evidence ids actually used. Empty only when answerType is refusal.",
                "items", Map.of("type", "string"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answer", answer);
        properties.put("answerType", answerType);
        properties.put("usedSources", usedSources);
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("answer", "answerType", "usedSources"),
                "additionalProperties", false);
    }

    static SourcedAnswerResult decodeSourcedAnswerToolCall(OpenAiApi.ChatCompletion completion,
                                                           ObjectMapper objectMapper) {
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            throw new IllegalArgumentException("Missing structured sourced answer tool call.");
        }
        OpenAiApi.ChatCompletionMessage message = completion.choices().get(0).message();
        if (message == null || message.toolCalls() == null || message.toolCalls().isEmpty()) {
            throw new IllegalArgumentException("Missing structured sourced answer tool call.");
        }
        OpenAiApi.ChatCompletionMessage.ToolCall toolCall = message.toolCalls().get(0);
        OpenAiApi.ChatCompletionMessage.ChatCompletionFunction function = toolCall.function();
        log.info("[LLM-RAW-SOURCED-ANSWER-TOOL] name={}, arguments={}",
                function == null ? "" : function.name(),
                function == null ? "" : function.arguments());
        if (function == null || !SUBMIT_SOURCED_ANSWER_TOOL.equals(function.name())) {
            throw new IllegalArgumentException("Unexpected structured sourced answer tool call: "
                    + (function == null ? "" : function.name()));
        }
        try {
            return objectMapper.readValue(function.arguments(), SourcedAnswerResult.class);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Could not parse structured sourced answer tool call.", error);
        }
    }

    private <T> void logRawStructuredResponse(String conversationId, Class<T> responseType, String raw) {
        if (!logRawResponse) {
            return;
        }
        log.info("[LLM-RAW-STRUCTURED] conversationId={}, responseType={}, raw={}",
                conversationId == null ? "" : conversationId,
                responseType == null ? "" : responseType.getSimpleName(),
                raw);
    }

    private <T> void logRawToolResponse(Class<T> responseType, String raw) {
        if (!logRawResponse) {
            return;
        }
        log.info("[LLM-RAW-TOOL] responseType={}, raw={}",
                responseType == null ? "" : responseType.getSimpleName(),
                raw);
    }

    static <T> T decodeStructuredResponse(String raw, Class<T> responseType, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM returned blank structured response.");
        }

        // Step 1: try parsing the raw response directly
        try {
            return objectMapper.readValue(raw, responseType);
        } catch (JsonProcessingException ignored) {
            // fall through to step 2
        }

        // Step 2: strip <think> tags (DeepSeek reasoning models) and retry
        String stripped = stripThinkTags(raw);
        if (!stripped.equals(raw)) {
            try {
                return objectMapper.readValue(stripped, responseType);
            } catch (JsonProcessingException ignored) {
                // fall through to step 3
            }
        }

        // Step 3: extract JSON from markdown fences or bare text
        String extractedJson = extractStructuredJson(stripped);
        if (extractedJson != null) {
            try {
                return objectMapper.readValue(extractedJson, responseType);
            } catch (JsonProcessingException e) {
                log.debug("Structured response parse failed after extraction. Raw (first 500 chars): {}",
                        raw.substring(0, Math.min(raw.length(), 500)));
                throw new IllegalArgumentException("Could not parse structured tool-phase response.", e);
            }
        }

        log.debug("No JSON object found in structured response. Raw (first 500 chars): {}",
                raw.substring(0, Math.min(raw.length(), 500)));
        throw new IllegalArgumentException("Could not parse structured tool-phase response.");
    }

    static String extractStructuredJson(String raw) {
        if (raw == null) {
            return null;
        }

        int fenceStart = raw.indexOf("```");
        while (fenceStart >= 0) {
            int lineEnd = raw.indexOf('\n', fenceStart);
            if (lineEnd < 0) {
                break;
            }
            int fenceEnd = raw.indexOf("```", lineEnd + 1);
            if (fenceEnd < 0) {
                break;
            }
            String fencedBody = raw.substring(lineEnd + 1, fenceEnd).trim();
            String fencedJson = firstBalancedJsonObject(fencedBody);
            if (fencedJson != null) {
                return fencedJson;
            }
            fenceStart = raw.indexOf("```", fenceEnd + 3);
        }

        return firstBalancedJsonObject(raw);
    }

    private static String firstBalancedJsonObject(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = findMatchingBrace(text, start);
            if (end > start) {
                return text.substring(start, end + 1);
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    private static int findMatchingBrace(String text, int start) {
        boolean inString = false;
        boolean escaping = false;
        int depth = 0;

        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (current == '\\') {
                escaping = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Strip DeepSeek reasoning model's {@code <think>...</think>} blocks from LLM output.
     * These tags may appear even when the model is instructed not to output reasoning.
     */
    static String stripThinkTags(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    /**
     * Build {@link OpenAiChatOptions} with {@code response_format: {type: "json_object"}}.
     * Works for all OpenAI-compatible APIs (DeepSeek, Ollama, etc.).
     * For non-OpenAI models (e.g. Gemini via Google GenAI), this option is silently ignored.
     */
    private static OpenAiChatOptions jsonObjectOptions() {
        return OpenAiChatOptions.builder()
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, ""))
                .build();
    }
}
