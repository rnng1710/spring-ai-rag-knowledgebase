package net.topikachu.rag.service.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import net.topikachu.rag.observability.TracingSupport;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
public class ReactiveChatGateway {

    private final TracingSupport tracingSupport;

    public ReactiveChatGateway(TracingSupport tracingSupport) {
        this.tracingSupport = tracingSupport;
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
        prompt = prompt.system(spec -> {
            spec.text(systemText);
            if (systemParams != null) {
                systemParams.forEach(spec::param);
            }
        });
        prompt = prompt.user(userText);
        if (chatMemoryAdvisor != null && conversationId != null) {
            prompt = prompt.advisors(chatMemoryAdvisor)
                    .advisors(spec -> spec.param(CONVERSATION_ID, conversationId));
        }
        return prompt;
    }
}
