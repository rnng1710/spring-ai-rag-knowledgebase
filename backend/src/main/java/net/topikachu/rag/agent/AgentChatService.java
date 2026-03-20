package net.topikachu.rag.agent;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.ai.memory.BlockingChatMemoryService;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
@Slf4j
public class AgentChatService {

    //TODO 用户端聊天界面，问题多了之后，无法拉回到之前的问题。
    private final AgentExecutor executor;
    private final ChatModelStrategyFactory strategyFactory;
    private final BlockingChatMemoryService blockingChatMemoryService;
    private final ReactiveChatGateway reactiveChatGateway;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;

    @Value("${rag.retrieval.max-context-chars:8000}")
    private int maxContextChars;

    public AgentChatService(AgentExecutor executor,
                            ChatModelStrategyFactory strategyFactory,
                            ChatMemory chatMemory,
                            BlockingChatMemoryService blockingChatMemoryService,
                            ReactiveChatGateway reactiveChatGateway) {
        this.executor = executor;
        this.strategyFactory = strategyFactory;
        this.blockingChatMemoryService = blockingChatMemoryService;
        this.reactiveChatGateway = reactiveChatGateway;
        this.messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    public Flux<ServerSentEvent<Object>> streamEvents(String userInput, String conversationId, List<String> filterTags,
            String modelId, String msgId) {
        log.info("Agent processing query: '{}', conversationId: {}, tags: {}, modelId: {}", userInput, conversationId,
                filterTags, modelId);

        return executor.execute(userInput, filterTags, modelId)
                .flatMapMany(result -> {
                    Flux<ServerSentEvent<Object>> traceEvents = Flux.fromIterable(result.notes())
                            .flatMap(note -> Flux.just(
                                    buildEvent("agent_stage", Map.of(
                                            "msgId", msgId,
                                            "stage", note.stage().wireValue())),
                                    buildEvent("agent_note", Map.of(
                                            "msgId", msgId,
                                            "stage", note.stage().wireValue(),
                                            "kind", note.kind(),
                                            "text", note.text(),
                                            "timestamp", note.timestamp()))
                            ));

                    if (result.isFollowup()) {
                        return blockingChatMemoryService.add(conversationId, List.of(
                                        new UserMessage(userInput),
                                        new AssistantMessage(result.followup())))
                                .thenMany(Flux.concat(
                                        traceEvents,
                                        Flux.just(
                                                buildEvent("followup", Map.of("msgId", msgId, "text", result.followup())),
                                                buildEvent("done", Map.of("msgId", msgId)))));
                    }

                    List<Document> docs = result.sources();
                    String context = buildContext(docs);
                    ChatModelStrategy strategy = strategyFactory.getStrategy(modelId);

                    Map<String, Object> sourcePayload = new LinkedHashMap<>();
                    sourcePayload.put("msgId", msgId);
                    sourcePayload.put("sources", docs.stream().map(Document::getMetadata).toList());

                    Flux<ServerSentEvent<Object>> sourceEvent = Flux.just(buildEvent("sources", sourcePayload));
                    StringBuilder finalAnswerBuffer = new StringBuilder();
                    Flux<ServerSentEvent<Object>> messageStream = reactiveChatGateway.stream(
                                    strategy.getChatClient(),
                                    buildAnswerPrompt(),
                                    Map.of(
                                            "context", context,
                                            "draft", buildDraftSection(result.draft()),
                                            "revisionInstruction", buildRevisionInstruction(result.finalInstruction())),
                                    userInput,
                                    conversationId,
                                    messageChatMemoryAdvisor)
                            .doOnNext(finalAnswerBuffer::append)
                            .doOnComplete(() -> log.info("Agent {} answer for userInput='{}': {}",
                                    result.revised() ? "second-pass" : "first-pass final",
                                    userInput,
                                    finalAnswerBuffer))
                            .map(content -> buildEvent("message", Map.of("msgId", msgId, "chunk", content)))
                            .concatWithValues(buildEvent("done", Map.of("msgId", msgId)));

                    return Flux.concat(traceEvents, sourceEvent, messageStream);
                })
                .onErrorResume(exp -> {
                    log.error("Agent chat failed", exp);
                    return Flux.just(
                            buildEvent("error", Map.of(
                                    "msgId", msgId,
                                    "message", "系统繁忙，请稍后重试。")),
                            buildEvent("done", Map.of("msgId", msgId)));
                });
    }

    private String buildContext(List<Document> docs) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String filename = (String) doc.getMetadata().getOrDefault("file_name", "Unknown Source");
            Object page = doc.getMetadata().getOrDefault("page_number", doc.getMetadata().get("page"));
            String pageLabel = (page == null) ? ("片段" + (i + 1)) : ("第" + formatPageValue(page) + "页");

            String structuredEntry = String.format(
                    """
                                    【%s】(来源: %s)
                                    内容: %s
                                    ------------------------
                                    """,
                    pageLabel, filename, doc.getText());

            if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                break;
            }
            contextBuilder.append(structuredEntry);
        }
        return contextBuilder.toString();
    }

    private String formatPageValue(Object page) {
        if (page instanceof Number number) {
            double value = number.doubleValue();
            if (Math.rint(value) == value) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
        return page.toString();
    }

    private String buildAnswerPrompt() {
        return """
                你是一个专业的“校园智能知识库问答助手”。你的核心职责是基于提供的【知识库上下文】内容回答问题。

                规则：
                1. 【信息溯源与防幻觉】完全、且仅依赖提供的 [知识库上下文] 内容回答问题。
                2. 【陷阱识别与纠错】在回答前，务必仔细核对用户问题中的预设前提（如数字、时间、处罚措施、执行主体）是否与文档一致。
                    如果用户的提问包含了文档中不存在的设定（例如捏造的罚款金额、不存在的报警时限等），请明确指出该设定在文档中无依据。
                3. 【精准核对细节】高度重视文档中的边界条件和关键词，包括但不限于：
                   - 程度词（如“最多”、“至少”、“必须”、“不可”）。
                   - 例外情况（如“除非……”、“……情况除外”）。
                   - 违规层级（如“首次违禁”与“第二次违禁”、“拥有/藏有”与“使用/表现出状态”的区别）。
                   - 权限主体（注意区分“普通教师”、“教务长”、“校长”、“学监”及“警察”的各自权限）。
                4. 【信息缺失处理】如果上下文中完全没有与问题相关的信息，或者信息不足以得出确定结论，
                    请直接回答：“根据已知文档，无法回答该问题”或“当前相关守则中未包含此具体规定”，严禁为迎合用户而编造细节。
                5. 【草稿优先】必须先审阅【回答草稿】。如果草稿中的结论已有上下文支持，应在此基础上修订和定稿，而不是完全忽略草稿重新作答。
                6.  若提供了【修订要求】，必须优先满足修订要求并修正草稿中存在的问题。
                7.  若草稿整体合理，只需在保留核心结论的前提下做必要的纠偏、压缩和格式修正。
                8.  只有在草稿与上下文明显冲突，或草稿引用完全错误时，才允许大幅重写。
                9.  回答时请直接命中问题核心，不要附带上下文中与问题核心无关的冗余条款或处罚细节。
                10. 【输出规范】回答保持专业、直接、简洁。优先直接给出明确结论，随后可简明扼要地引述文档中的规则依据。严禁输出内部思考或推理过程。
                11. 【引用格式】只有在上下文提供真实页码时，才能使用【第18页】或【第19页】这类页码引用；“片段N”不是页码。
                12. 如果所依据的上下文没有页码，只能写“根据已知文档”或直接陈述依据，严禁编造【第X页】。

                ================ 知识库上下文 ================
                {context}
                ============================================

                ================ 回答草稿 ================
                {draft}
                ==========================================

                ================ 修订要求 ================
                {revisionInstruction}
                ==========================================
                """;
    }

    private String buildDraftSection(String draft) {
        return (draft == null || draft.isBlank())
                ? "无，直接基于上下文生成最终答案。"
                : draft;
    }

    private String buildRevisionInstruction(String finalInstruction) {
        return (finalInstruction == null || finalInstruction.isBlank())
                ? "无，直接基于上下文生成最终答案。"
                : finalInstruction;
    }

    private ServerSentEvent<Object> buildEvent(String event, Object data) {
        return ServerSentEvent.builder()
                .event(event)
                .data(data)
                .build();
    }
}
