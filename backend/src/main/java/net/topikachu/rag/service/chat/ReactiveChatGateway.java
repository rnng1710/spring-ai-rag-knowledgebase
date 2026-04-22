package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.observability.TracingSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
public class ReactiveChatGateway {

    private final TracingSupport tracingSupport;
    private final ObjectMapper objectMapper;

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
        return tracingSupport.traceMono("llm.chat_call_structured",
                Map.of(
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.user_chars", userText == null ? 0 : userText.length()),
                Mono.fromCallable(() -> buildPrompt(chatClient, systemText, systemParams, userText, null, null)
                                .call()
                                .content())
                        .map(raw -> decodeStructuredResponse(raw, responseType, objectMapper))
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
                                .map(raw -> decodeStructuredResponse(raw, responseType, objectMapper)));
    }

    public Flux<String> streamFinalAnswer(ChatClient chatClient,
                                          String systemText,
                                          Map<String, Object> systemParams,
                                          String userText) {
        return tracingSupport.traceFlux("llm.chat_stream",
                Map.of(
                        "llm.prompt_chars", systemText == null ? 0 : systemText.length(),
                        "llm.user_chars", userText == null ? 0 : userText.length()),
                buildPrompt(chatClient, systemText, systemParams, userText, null, null)
                        .stream()
                        .content());
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

    static <T> T decodeStructuredResponse(String raw, Class<T> responseType, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM returned blank structured response.");
        }

        try {
            return objectMapper.readValue(raw, responseType);
        } catch (JsonProcessingException firstError) {
            String extractedJson = extractStructuredJson(raw);
            if (extractedJson != null) {
                try {
                    return objectMapper.readValue(extractedJson, responseType);
                } catch (JsonProcessingException ignored) {
                    throw new IllegalArgumentException("Could not parse structured tool-phase response.", firstError);
                }
            }
            throw new IllegalArgumentException("Could not parse structured tool-phase response.", firstError);
        }
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
}
