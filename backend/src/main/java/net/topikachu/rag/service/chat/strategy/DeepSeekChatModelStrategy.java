package net.topikachu.rag.service.chat.strategy;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.topikachu.rag.config.LlmProperties;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.SourcedAnswerPrompts;
import net.topikachu.rag.service.chat.SourcedAnswerResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class DeepSeekChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final OpenAiApi openAiApi;
    private final String model;

    public DeepSeekChatModelStrategy(LlmProperties properties, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        // 同时配置 WebClient 和 RestClient 超时：Spring AI 的 OpenAiApi 内部同时使用反应式和阻塞 HTTP 客户端，两者需一致
        int readTimeoutMs = properties.getReadTimeoutMs();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, readTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)));

        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(readTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();
        this.openAiApi = openAiApi;
        this.model = properties.getChat().getOptions().getModel();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String getModelId() {
        return "deepseek";
    }

    @Override
    public ChatClient getChatClient() {
        return this.chatClient;
    }

    @Override
    public Mono<SourcedAnswerResult> callSourcedAnswer(ReactiveChatGateway reactiveChatGateway,
                                                       String context,
                                                       String userInput,
                                                       String conversationId,
                                                       MessageChatMemoryAdvisor chatMemoryAdvisor) {
        return Mono.fromCallable(() -> conversationId == null
                        ? List.<org.springframework.ai.chat.messages.Message>of()
                        : chatMemory.get(conversationId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(history -> reactiveChatGateway.callSourcedAnswerTool(
                        openAiApi,
                        model,
                        SourcedAnswerPrompts.toolPrompt(),
                        Map.of("context", context),
                        history,
                        userInput));
    }

    @Override
    public void saveSourcedAnswerMemory(String conversationId, String userInput, String answer) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        chatMemory.add(conversationId, List.of(new UserMessage(userInput), new AssistantMessage(answer)));
    }
}
