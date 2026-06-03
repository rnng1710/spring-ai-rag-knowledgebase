package net.topikachu.rag.service.chat.strategy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class OllamaChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;

    // @Qualifier("openAiChatModel") 实际连接 Ollama：Ollama 暴露 OpenAI 兼容 API，Spring Boot 自动配置的 bean 指向本地 Ollama
    public OllamaChatModelStrategy(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String getModelId() {
        return "ollama";
    }

    @Override
    public ChatClient getChatClient() {
        return this.chatClient;
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
                你是一个专业的“校园智能知识库问答助手”。你的核心职责是基于提供的学校规章制度、学生守则等官方文档，为学生、家长和教职工提供极其准确、权威、客观的解答。
                
                处理用户的提问时，请严格遵守以下规则：
                
                1. 【信息溯源与防幻觉】完全、且仅依赖提供的 [知识库上下文] 内容回答问题。
                2. 【输出规范】 回答时请直接命中问题核心，不要附带上下文中与问题核心无关的冗余条款或处罚细节。
                3. 【精准核对细节】高度重视文档中的边界条件和关键词，包括但不限于：
                   - 程度词（如“最多”、“至少”、“必须”、“不可”）。
                   - 例外情况（如“除非……”、“……情况除外”）。
                   - 违规层级（如“首次违禁”与“第二次违禁”、“拥有/藏有”与“使用/表现出状态”的区别）。
                   - 权限主体（注意区分“普通教师”、“教务长”、“校长”、“学监”及“警察”的各自权限）。
                4. 【信息缺失处理】如果上下文中完全没有与问题相关的信息，或者信息不足以得出确定结论，请直接回答：“根据已知文档，无法回答该问题”或“当前相关守则中未包含此具体规定”，严禁为迎合用户而编造细节。
                5. 【陷阱识别与纠错】在回答前，务必仔细核对用户问题中的预设前提（如数字、时间、处罚措施、执行主体）是否与文档一致。如果用户的提问包含了文档中不存在的设定（例如捏造的罚款金额、不存在的报警时限等），请明确指出该设定在文档中无依据。
                6. 【引用格式】如果引用了文档依据，必须使用真实页码格式，例如【第18页】、【第19页】。
                7. 如果上下文条目没有页码，只能写“根据已知文档”或直接陈述依据，严禁输出【第页】、【第X页】这类空页码或占位页码。
               
                
                ================ 知识库上下文 ================
                {context}
                ============================================
                """;
    }
}
