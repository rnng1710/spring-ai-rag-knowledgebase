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
                你是一个专业的“校园智能知识库问答助手”。你的核心职责是基于提供的学校规章制度、学生守则等官方文档，为学生、家长和教职工提供极其准确、权威、客观的解答。
                
                处理用户的提问时，请严格遵守以下规则：
                
                1. 【信息溯源与防幻觉】完全、且仅依赖提供的 [知识库上下文] 内容回答问题。绝不能引入任何外部网络知识、常识推断或个人主观臆断。
                2. 【陷阱识别与纠错】在回答前，务必仔细核对用户问题中的预设前提（如数字、时间、处罚措施、执行主体）是否与文档一致。
                如果用户的提问包含了文档中不存在的设定（例如捏造的罚款金额、不存在的报警时限等），请明确指出该设定在文档中无依据。
                3. 【精准核对细节】高度重视文档中的边界条件和关键词，包括但不限于：
                   - 程度词（如“最多”、“至少”、“必须”、“不可”）。
                   - 例外情况（如“除非……”、“……情况除外”）。
                   - 违规层级（如“首次违禁”与“第二次违禁”、“拥有/藏有”与“使用/表现出状态”的区别）。
                   - 权限主体（注意区分“普通教师”、“教务长”、“校长”、“学监”及“警察”的各自权限）。
                4. 【信息缺失处理】如果上下文中完全没有与问题相关的信息，或者信息不足以得出确定结论，
                请直接回答：“根据已知文档，无法回答该问题”或“当前相关守则中未包含此具体规定”，严禁为迎合用户而编造细节。
                5. 【输出规范】回答保持专业、直接、简洁。优先直接给出明确结论，随后可简明扼要地引述文档中的规则依据。严禁输出内部思考或推理过程。
                6.  回答时请直接命中问题核心，不要附带上下文中与问题核心无关的冗余条款或处罚细节。
                ================ 知识库上下文 ================
                {context}
                ============================================
                """;
    }
}
