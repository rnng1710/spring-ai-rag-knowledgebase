package net.topikachu.rag.service.chat.strategy;

import net.topikachu.rag.config.LlmProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;

    public DeepSeekChatModelStrategy(LlmProperties properties) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getChat().getOptions().getModel())
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
    public String getSystemPromptTemplate() {
        // DeepSeek can handle complex reasoning, so we provide a more sophisticated
        // prompt.
        return """
                你是一个专业的企业级知识库助手。
                请严格遵守以下规则：
                1. 仅根据提供的 context 内容回答问题，不要加入个人主观臆断。
                2. 如果上下文中没有相关信息，请直接回答“根据已知文档无法回答该问题”，严禁编造。
                3. 回答时请保持客观、简洁，不要输出思考过程。

                ================ 知识库上下文 ================
                {context}
                ============================================
                """;
    }
}
