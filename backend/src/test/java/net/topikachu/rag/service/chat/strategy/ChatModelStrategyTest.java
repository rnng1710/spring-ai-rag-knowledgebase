package net.topikachu.rag.service.chat.strategy;

import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.SourcedAnswerPrompts;
import net.topikachu.rag.service.chat.SourcedAnswerResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelStrategyTest {

    @Test
    void defaultSourcedAnswerUsesStructuredJsonCall() {
        ChatClient chatClient = mock(ChatClient.class);
        ReactiveChatGateway gateway = mock(ReactiveChatGateway.class);
        MessageChatMemoryAdvisor advisor = mock(MessageChatMemoryAdvisor.class);
        SourcedAnswerResult expected = new SourcedAnswerResult("answer", "factual", List.of("ev-1"));
        ChatModelStrategy strategy = new ChatModelStrategy() {
            @Override
            public String getModelId() {
                return "test";
            }

            @Override
            public ChatClient getChatClient() {
                return chatClient;
            }
        };

        when(gateway.callStructured(
                same(chatClient),
                eq(SourcedAnswerPrompts.jsonPrompt()),
                eq(Map.of("context", "ctx")),
                eq("question"),
                eq("conversation-1"),
                same(advisor),
                eq(SourcedAnswerResult.class)))
                .thenReturn(Mono.just(expected));

        StepVerifier.create(strategy.callSourcedAnswer(
                        gateway,
                        "ctx",
                        "question",
                        "conversation-1",
                        advisor))
                .expectNext(expected)
                .verifyComplete();
    }
}
