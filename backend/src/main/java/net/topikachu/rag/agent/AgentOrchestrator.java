package net.topikachu.rag.agent;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
public class AgentOrchestrator {

    private static final String FOLLOWUP_PROMPT = "现有证据不足，请选择一个更具体的问题继续检索。";

    private final ReactiveChatGateway reactiveChatGateway;
    private final AgentKnowledgeService knowledgeService;
    private final AgentToolBridge agentToolBridge;
    private final AgentHistorySnapshotBuilder historySnapshotBuilder;
    private final Scheduler agentOrchestratorScheduler;
    private final TracingSupport tracingSupport;
    private final ToolCallAdvisor toolCallAdvisor = ToolCallAdvisor.builder().build();

    @Value("${rag.agent.timeout-ms:12000}")
    private long timeoutMs;

    @Value("${rag.agent.max-steps:6}")
    private int maxToolCalls;

    @Value("${rag.agent.max-evidence-count:12}")
    private int maxEvidenceCount;

    @Value("${rag.agent.max-repeated-query-count:2}")
    private int maxRepeatedQueryCount;

    public AgentOrchestrator(ReactiveChatGateway reactiveChatGateway,
                             AgentKnowledgeService knowledgeService,
                             AgentToolBridge agentToolBridge,
                             AgentHistorySnapshotBuilder historySnapshotBuilder,
                             @Qualifier("agentOrchestratorScheduler") Scheduler agentOrchestratorScheduler,
                             TracingSupport tracingSupport) {
        this.reactiveChatGateway = reactiveChatGateway;
        this.knowledgeService = knowledgeService;
        this.agentToolBridge = agentToolBridge;
        this.historySnapshotBuilder = historySnapshotBuilder;
        this.agentOrchestratorScheduler = agentOrchestratorScheduler;
        this.tracingSupport = tracingSupport;
    }

    public Mono<AgentExecutionResult> orchestrate(ChatModelStrategy strategy,
                                                  String userInput,
                                                  String conversationId,
                                                  String msgId,
                                                  List<String> tags) {
        String requestId = UUID.randomUUID().toString();
        AgentExecutionContext executionContext = new AgentExecutionContext(requestId, conversationId, msgId, userInput, tags);
        executionContext.addNote(AgentStage.PLANNING, "decision", "正在规划检索与回答步骤。");

        AgentKnowledgeTools toolObject = new AgentKnowledgeTools(
                knowledgeService,
                executionContext,
                agentToolBridge,
                reactiveChatGateway,
                strategy,
                Duration.ofMillis(timeoutMs),
                maxToolCalls,
                maxEvidenceCount,
                maxRepeatedQueryCount);
        List<ToolCallback> toolCallbacks = List.of(ToolCallbacks.from(toolObject));
        List<Message> messages = new ArrayList<>(historySnapshotBuilder.build(conversationId));
        messages.add(UserMessage.builder().text(userInput).build());

        Mono<AgentExecutionResult> pipeline = reactiveChatGateway.runToolPhase(
                        strategy.getChatClient(),
                        toolPhasePrompt(),
                        Map.of("preselectedTags", summarizeTags(tags)),
                        messages,
                        List.of(toolCallAdvisor),
                        toolCallbacks,
                        toolContext(executionContext, tags),
                        AgentResolution.class)
                .map(resolution -> toExecutionResult(executionContext, resolution))
                .switchIfEmpty(Mono.fromSupplier(() -> noEvidenceFollowup(executionContext)))
                .doOnError(error -> log.error("Agent tool phase failed. conversationId={}, msgId={}", conversationId, msgId, error))
                .subscribeOn(agentOrchestratorScheduler);

        return tracingSupport.traceMono("agent.orchestrate",
                Map.of(
                        "chat.mode", "agent",
                        "chat.conversation_id", conversationId,
                        "chat.msg_id", msgId,
                        "agent.input_chars", userInput == null ? 0 : userInput.length()),
                pipeline);
    }

    private AgentExecutionResult toExecutionResult(AgentExecutionContext executionContext, AgentResolution resolution) {
        if (resolution == null) {
            return noEvidenceFollowup(executionContext);
        }

        String type = normalizeType(resolution.type());
        String answerMode = normalizeAnswerMode(resolution.answerMode());

        if ("followup".equals(type)) {
            executionContext.addNote(AgentStage.FOLLOWUP, "decision", "当前证据不足，转为点击式追问。");
            FollowupSuggestion suggestion = resolveFollowupSuggestion(executionContext);
            return new AgentExecutionResult(
                    List.of(),
                    sortNotes(executionContext.notes()),
                    suggestion.prompt(),
                    suggestion.options(),
                    null,
                    null,
                    "normal",
                    false);
        }

        if ("refusal".equals(answerMode)) {
            executionContext.addNote(AgentStage.GENERATING_FINAL, "decision", "知识库无法回答该问题，返回拒答说明。");
            return new AgentExecutionResult(
                    List.of(),
                    sortNotes(executionContext.notes()),
                    null,
                    List.of(),
                    defaultRefusalDraft(resolution.draftAnswer()),
                    defaultRefusalInstruction(resolution.finalInstruction()),
                    "refusal",
                    false);
        }

        List<EvidenceSnapshot> selectedEvidence = executionContext.selectEvidence(resolution.selectedEvidenceIds());
        if (selectedEvidence.isEmpty()) {
            executionContext.addNote(AgentStage.FOLLOWUP, "decision", "模型未选中有效证据，回退为点击式追问。");
            return noEvidenceFollowup(executionContext);
        }

        executionContext.addNote(AgentStage.GENERATING_FINAL, "decision", "已完成证据选择，准备生成最终答案。");
        return new AgentExecutionResult(
                List.copyOf(selectedEvidence),
                sortNotes(executionContext.notes()),
                null,
                List.of(),
                resolution.draftAnswer(),
                resolution.finalInstruction(),
                "normal",
                resolution.finalInstruction() != null && !resolution.finalInstruction().isBlank());
    }

    private AgentExecutionResult noEvidenceFollowup(AgentExecutionContext executionContext) {
        FollowupSuggestion suggestion = resolveFollowupSuggestion(executionContext);
        return new AgentExecutionResult(
                List.of(),
                sortNotes(executionContext.notes()),
                suggestion.prompt(),
                suggestion.options(),
                null,
                null,
                "normal",
                false);
    }

    private FollowupSuggestion resolveFollowupSuggestion(AgentExecutionContext executionContext) {
        FollowupOptionsResult candidate = executionContext.followupOptionsCandidate();
        if (candidate != null && "ok".equals(candidate.status()) && isValidFollowupCandidate(candidate)) {
            return new FollowupSuggestion(FOLLOWUP_PROMPT, List.copyOf(candidate.options()), "tool");
        }
        return buildFallbackSuggestion(executionContext.originalUserInput(), executionContext.retrievalGapType(), executionContext.allowedFocusTypes());
    }

    private boolean isValidFollowupCandidate(FollowupOptionsResult candidate) {
        return candidate.options() != null
                && candidate.focusTypes() != null
                && candidate.options().size() == 2
                && candidate.focusTypes().size() == 2;
    }

    private FollowupSuggestion buildFallbackSuggestion(String originalUserInput,
                                                      RetrievalGapType gapType,
                                                      List<String> allowedFocusTypes) {
        List<String> focusTypes = pickFallbackFocusTypes(gapType, allowedFocusTypes);
        List<String> options = focusTypes.stream()
                .map(focusType -> buildFallbackQuestion(originalUserInput, focusType))
                .toList();
        return new FollowupSuggestion(FOLLOWUP_PROMPT, options, "fallback");
    }

    private List<String> pickFallbackFocusTypes(RetrievalGapType gapType, List<String> allowedFocusTypes) {
        Set<String> focusTypes = new LinkedHashSet<>();
        if (allowedFocusTypes != null) {
            focusTypes.addAll(allowedFocusTypes);
        }
        if (focusTypes.size() < 2) {
            switch (gapType == null ? RetrievalGapType.MISSING_SCOPE : gapType) {
                case MISSING_TIME -> {
                    focusTypes.add("time");
                    focusTypes.add("scope");
                }
                case MISSING_PROCEDURE_BRANCH -> {
                    focusTypes.add("procedure");
                    focusTypes.add("scope");
                }
                case AMBIGUOUS_SUBJECT -> {
                    focusTypes.add("subject");
                    focusTypes.add("scope");
                }
                case NOT_IMPROVABLE, MISSING_SCOPE -> {
                    focusTypes.add("scope");
                    focusTypes.add("procedure");
                }
            }
        }
        List<String> selected = new ArrayList<>(focusTypes);
        if (selected.size() < 2) {
            selected = List.of("scope", "procedure");
        }
        return selected.subList(0, 2);
    }

    private String buildFallbackQuestion(String originalUserInput, String focusType) {
        String question = abbreviateQuestion(originalUserInput);
        return switch (focusType == null ? "scope" : focusType.toLowerCase(Locale.ROOT)) {
            case "time" -> "针对“" + question + "”，如果进一步限定适用时间或发生阶段，知识库中有哪些明确规则？";
            case "procedure" -> "针对“" + question + "”，如果进一步限定处分程序、审批条件或复审环节，知识库中有哪些明确规则？";
            case "subject" -> "针对“" + question + "”，如果进一步限定对象、角色或学生身份，知识库中有哪些明确规则？";
            case "scope" -> "针对“" + question + "”，如果进一步限定适用对象或适用范围，知识库中有哪些明确规则？";
            default -> "针对“" + question + "”，如果进一步限定" + focusType + "，知识库中有哪些明确规则？";
        };
    }

    private String abbreviateQuestion(String originalUserInput) {
        if (originalUserInput == null || originalUserInput.isBlank()) {
            return "当前问题";
        }
        String trimmed = originalUserInput.trim();
        return trimmed.length() > 90 ? trimmed.substring(0, 90) + "..." : trimmed;
    }

    private List<AgentNote> sortNotes(List<AgentNote> notes) {
        return notes.stream()
                .sorted((left, right) -> Long.compare(left.sequence(), right.sequence()))
                .toList();
    }

    private Map<String, Object> toolContext(AgentExecutionContext executionContext, List<String> tags) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("requestId", executionContext.requestId());
        context.put("conversationId", executionContext.conversationId());
        context.put("msgId", executionContext.msgId());
        context.put("preselectedTags", tags == null ? List.of() : List.copyOf(tags));
        return context;
    }

    private String summarizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "无";
        }
        return String.join(", ", tags);
    }

    private String normalizeType(String type) {
        if (type == null) {
            return "followup";
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if ("answer".equals(normalized) || "followup".equals(normalized)) {
            return normalized;
        }
        return "followup";
    }

    private String normalizeAnswerMode(String answerMode) {
        if (answerMode == null) {
            return "normal";
        }
        String normalized = answerMode.trim().toLowerCase(Locale.ROOT);
        if ("normal".equals(normalized) || "refusal".equals(normalized)) {
            return normalized;
        }
        return "normal";
    }

    private String defaultRefusalDraft(String draftAnswer) {
        return (draftAnswer == null || draftAnswer.isBlank())
                ? "当前知识库中没有足够证据支持回答该问题，因此不能给出确定结论。"
                : draftAnswer;
    }

    private String defaultRefusalInstruction(String finalInstruction) {
        return (finalInstruction == null || finalInstruction.isBlank())
                ? "明确说明只能依据当前知识库作答，当前证据不足，不能编造或外推。"
                : finalInstruction;
    }

    private String toolPhasePrompt() {
        return """
                你是校园知识库问答系统中的证据编排代理。你的职责是：
                1. 如有需要，主动调用工具检索知识库片段证据。
                2. 只能把工具返回 status=ok 的 items 视为候选证据。
                3. tool_error、no_result 不能作为事实依据。
                4. 当证据不足且可通过缩小检索范围改善时，应调用 generateFollowupOptions。
                5. 当知识库本身无法回答当前问题时，直接返回 type=answer，answerMode=refusal。
                6. 你必须且只能输出一个合法 JSON 对象。
                7. 不要输出任何分析、解释、推理过程、前言、后记、Markdown 代码块。
                8. 输出必须直接从 JSON 对象开始，并直接在 JSON 对象结束处停止。
                9. 你最终输出的 JSON 只包含这些字段：
                   - type: answer 或 followup
                   - answerMode: normal 或 refusal；仅当 type=answer 时使用
                   - draftAnswer: 第二阶段使用的回答草稿，可为空
                   - finalInstruction: 第二阶段的修订或收口指令，可为空
                   - selectedEvidenceIds: 字符串数组，只能从工具返回的片段 id 中选择
                10. 标准输出示例如下。你必须严格按照这个 JSON 结构输出，不能增减字段，不能输出任何额外文字：
                    \\{
                      "type": "followup",
                      "answerMode": "normal",
                      "draftAnswer": "",
                      "finalInstruction": "",
                      "selectedEvidenceIds": []
                    \\}
                11. 如果 type=followup，不要在 JSON 中输出追问文案，追问候选问题由 generateFollowupOptions 工具提供。
                12. 如果 type=answer 且 answerMode=normal，selectedEvidenceIds 必须只选择真正需要的证据子集。
                13. 如果 type=answer 且 answerMode=refusal，也必须输出同样结构的 JSON，只是把 answerMode 设为 refusal。
                14. 不要把未选中的证据事实写入 draftAnswer 或 finalInstruction。
                15. 如果用户已经预选标签，请优先尊重这些标签。

                当前预选标签：
                {preselectedTags}
                """;
    }
}
