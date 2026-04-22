package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class AgentKnowledgeTools {

    private static final Pattern TIME_PATTERN = Pattern.compile("(19|20)\\d{2}|\\d+月|\\d+日|今年|去年|目前|现在|当时");
    private static final List<String> PROCEDURE_KEYWORDS = List.of("流程", "程序", "审批", "复审", "申诉", "听证", "步骤");

    private final AgentKnowledgeService knowledgeService;
    private final AgentExecutionContext executionContext;
    private final AgentToolBridge toolBridge;
    private final ReactiveChatGateway reactiveChatGateway;
    private final ChatModelStrategy strategy;
    private final Duration timeout;
    private final int maxToolCalls;
    private final int maxEvidenceCount;
    private final int maxRepeatedQueryCount;

    public AgentKnowledgeTools(AgentKnowledgeService knowledgeService,
                               AgentExecutionContext executionContext,
                               AgentToolBridge toolBridge,
                               ReactiveChatGateway reactiveChatGateway,
                               ChatModelStrategy strategy,
                               Duration timeout,
                               int maxToolCalls,
                               int maxEvidenceCount,
                               int maxRepeatedQueryCount) {
        this.knowledgeService = knowledgeService;
        this.executionContext = executionContext;
        this.toolBridge = toolBridge;
        this.reactiveChatGateway = reactiveChatGateway;
        this.strategy = strategy;
        this.timeout = timeout;
        this.maxToolCalls = maxToolCalls;
        this.maxEvidenceCount = maxEvidenceCount;
        this.maxRepeatedQueryCount = maxRepeatedQueryCount;
    }

    @Tool(name = "searchKnowledgeSnippets", description = "在知识库中检索与问题相关的片段级证据。tagsAnyOf 表示任一标签命中，不是全部标签同时命中。仅当需要证据时调用。")
    public KnowledgeSearchResult searchKnowledgeSnippets(
            @ToolParam(description = "检索请求，包含 query、tagsAnyOf、topK。")
            KnowledgeSearchRequest request) {
        executionContext.addNote(AgentStage.RETRIEVING, "tool_call", "调用工具 searchKnowledgeSnippets 检索知识库。");

        if (request == null || request.query() == null || request.query().isBlank()) {
            executionContext.addNote(AgentStage.RETRIEVING, "tool_error", "检索请求缺少有效 query。");
            return KnowledgeSearchResult.toolError("INVALID_QUERY", "query is required");
        }

        String normalizedQueryKey = normalizeQueryKey(request);
        if (!executionContext.allowQueryInvocation(normalizedQueryKey, maxRepeatedQueryCount)) {
            executionContext.addNote(AgentStage.RETRIEVING, "budget", "相同检索参数重复次数超限，跳过本次检索。");
            executionContext.recordRetrieval(
                    request.query(),
                    normalizedQueryKey,
                    normalizeTags(request.tagsAnyOf()),
                    request.topK(),
                    "skipped_budget",
                    0,
                    List.of());
            return KnowledgeSearchResult.toolError("REPEATED_QUERY_LIMIT", "repeated query limit exceeded");
        }

        if (executionContext.toolCalls() >= maxToolCalls) {
            executionContext.addNote(AgentStage.RETRIEVING, "budget", "已达到工具调用上限，跳过本次检索。");
            executionContext.recordRetrieval(
                    request.query(),
                    normalizedQueryKey,
                    normalizeTags(request.tagsAnyOf()),
                    request.topK(),
                    "skipped_budget",
                    0,
                    List.of());
            return KnowledgeSearchResult.toolError("TOOL_BUDGET_EXCEEDED", "tool call budget exceeded");
        }

        executionContext.incrementToolCalls();
        executionContext.incrementSearchInvocationCount();

        try {
            List<Document> documents = toolBridge.block(
                    knowledgeService.searchKnowledgeSnippets(request.query(), request.tagsAnyOf(), request.topK()),
                    timeout,
                    "searchKnowledgeSnippets");
            if (documents.isEmpty()) {
                executionContext.recordRetrieval(
                        request.query(),
                        normalizedQueryKey,
                        normalizeTags(request.tagsAnyOf()),
                        request.topK(),
                        "no_result",
                        0,
                        List.of());
                executionContext.updateRetrievalAssessment(
                        RetrievalGapType.MISSING_SCOPE,
                        true,
                        List.of("scope", "time"));
                executionContext.addNote(AgentStage.RETRIEVING, "tool_result", "未检索到相关证据。");
                return KnowledgeSearchResult.noResult();
            }

            List<EvidenceSnapshot> evidence = documents.stream()
                    .map(EvidenceSnapshot::fromDocument)
                    .limit(maxEvidenceCount)
                    .toList();
            executionContext.addRetrievedEvidence(evidence, maxEvidenceCount);
            List<String> evidenceIds = evidence.stream().map(EvidenceSnapshot::id).toList();
            executionContext.recordRetrieval(
                    request.query(),
                    normalizedQueryKey,
                    normalizeTags(request.tagsAnyOf()),
                    request.topK(),
                    "ok",
                    evidence.size(),
                    evidenceIds);
            applyRetrievalAssessment(request.query(), evidence.size());
            executionContext.addNote(AgentStage.RETRIEVING, "tool_result", "已检索到 " + evidence.size() + " 条候选证据。");

            List<KnowledgeSnippet> items = evidence.stream()
                    .map(snapshot -> new KnowledgeSnippet(snapshot.id(), snapshot.text(), snapshot.metadataSnapshot()))
                    .toList();
            return KnowledgeSearchResult.ok(items);
        } catch (Exception error) {
            executionContext.recordRetrieval(
                    request.query(),
                    normalizedQueryKey,
                    normalizeTags(request.tagsAnyOf()),
                    request.topK(),
                    "tool_error",
                    0,
                    List.of());
            executionContext.addNote(AgentStage.RETRIEVING, "tool_error", "检索工具执行失败。");
            return KnowledgeSearchResult.toolError("SEARCH_FAILED", sanitizeError(error));
        }
    }

    @Tool(name = "listAvailableTags", description = "列出知识库当前可用的标签。仅当用户问题依赖标签范围判断时调用。")
    public KnowledgeTagList listAvailableTags() {
        executionContext.addNote(AgentStage.PLANNING, "tool_call", "调用工具 listAvailableTags 获取可用标签。");

        if (executionContext.toolCalls() >= maxToolCalls) {
            executionContext.addNote(AgentStage.PLANNING, "budget", "已达到工具调用上限，跳过本次标签查询。");
            return KnowledgeTagList.toolError("TOOL_BUDGET_EXCEEDED", "tool call budget exceeded");
        }

        executionContext.incrementToolCalls();

        try {
            List<String> tags = toolBridge.block(
                    knowledgeService.listAvailableTags(),
                    timeout,
                    "listAvailableTags");
            executionContext.addNote(AgentStage.PLANNING, "tool_result", "已获取可用标签列表。");
            return KnowledgeTagList.ok(tags);
        } catch (Exception error) {
            executionContext.addNote(AgentStage.PLANNING, "tool_error", "标签工具执行失败。");
            return KnowledgeTagList.toolError("LIST_TAGS_FAILED", sanitizeError(error));
        }
    }

    @Tool(name = "generateFollowupOptions", description = "在已经完成检索但当前证据仍不足时，生成两个可直接点击继续检索的完整问题句。仅当需要缩小检索范围时调用。")
    public FollowupOptionsResult generateFollowupOptions() {
        executionContext.addNote(AgentStage.FOLLOWUP, "tool_call", "调用工具 generateFollowupOptions 生成二次检索候选问题。");

        if (executionContext.searchInvocationCount() < 1) {
            executionContext.addNote(AgentStage.FOLLOWUP, "tool_error", "尚未发生有效检索，不能生成二次检索候选问题。");
            return FollowupOptionsResult.toolError("SEARCH_REQUIRED", "at least one search is required");
        }
        if (!executionContext.improvableByFollowup() || executionContext.retrievalGapType() == RetrievalGapType.NOT_IMPROVABLE) {
            executionContext.addNote(AgentStage.FOLLOWUP, "tool_error", "当前检索缺口不适合通过二次检索改善。");
            return FollowupOptionsResult.toolError("NOT_IMPROVABLE", "retrieval gap is not improvable by followup");
        }
        if (!executionContext.hasEffectiveRetrievalHistory()) {
            executionContext.addNote(AgentStage.FOLLOWUP, "tool_error", "缺少有效检索痕迹，不能生成候选问题。");
            return FollowupOptionsResult.toolError("NO_RETRIEVAL_CONTEXT", "effective retrieval history is required");
        }
        if (executionContext.toolCalls() >= maxToolCalls) {
            executionContext.addNote(AgentStage.FOLLOWUP, "budget", "已达到工具调用上限，跳过候选问题生成。");
            return FollowupOptionsResult.toolError("TOOL_BUDGET_EXCEEDED", "tool call budget exceeded");
        }
        if (!executionContext.markFollowupToolAttempted()) {
            executionContext.addNote(AgentStage.FOLLOWUP, "budget", "二次检索候选问题工具已尝试过，跳过本次调用。");
            return FollowupOptionsResult.toolError("FOLLOWUP_ALREADY_ATTEMPTED", "followup options already attempted");
        }

        executionContext.incrementToolCalls();

        try {
            FollowupOptionsResult raw = toolBridge.block(
                    reactiveChatGateway.callStructured(
                            strategy.getChatClient(),
                            buildFollowupToolPrompt(),
                            buildFollowupToolPromptParams(),
                            executionContext.originalUserInput(),
                            FollowupOptionsResult.class),
                    timeout,
                    "generateFollowupOptions");

            FollowupOptionsResult normalized = normalizeFollowupOptions(raw);
            if (!"ok".equals(normalized.status())) {
                executionContext.addNote(AgentStage.FOLLOWUP, "tool_error", "二次检索候选问题工具返回了无效结果。");
                return normalized;
            }

            executionContext.storeFollowupOptionsCandidate(normalized);
            executionContext.addNote(AgentStage.FOLLOWUP, "tool_result", "已生成 2 个二次检索候选问题。");
            return normalized;
        } catch (Exception error) {
            executionContext.addNote(AgentStage.FOLLOWUP, "tool_error", "二次检索候选问题工具执行失败。");
            return FollowupOptionsResult.toolError("FOLLOWUP_GENERATION_FAILED", sanitizeError(error));
        }
    }

    private void applyRetrievalAssessment(String query, int resultCount) {
        if (resultCount <= 0) {
            executionContext.updateRetrievalAssessment(RetrievalGapType.MISSING_SCOPE, true, List.of("scope", "time"));
            return;
        }
        if (containsProcedureKeyword(query)) {
            executionContext.updateRetrievalAssessment(
                    RetrievalGapType.MISSING_PROCEDURE_BRANCH,
                    true,
                    List.of("procedure", "scope"));
            return;
        }
        if (!containsExplicitTime(query)) {
            executionContext.updateRetrievalAssessment(RetrievalGapType.MISSING_TIME, true, List.of("time", "scope"));
            return;
        }
        if (resultCount <= 2) {
            executionContext.updateRetrievalAssessment(RetrievalGapType.AMBIGUOUS_SUBJECT, true, List.of("subject", "scope"));
            return;
        }
        executionContext.updateRetrievalAssessment(RetrievalGapType.MISSING_SCOPE, true, List.of("scope", "procedure"));
    }

    private boolean containsProcedureKeyword(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return PROCEDURE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean containsExplicitTime(String query) {
        return query != null && TIME_PATTERN.matcher(query).find();
    }

    private FollowupOptionsResult normalizeFollowupOptions(FollowupOptionsResult raw) {
        if (raw == null || !"ok".equals(raw.status())) {
            return FollowupOptionsResult.toolError("INVALID_FOLLOWUP_OPTIONS", "followup options response is invalid");
        }

        List<String> normalizedOptions = normalizeQuestions(raw.options());
        List<String> normalizedFocusTypes = normalizeFocusTypes(raw.focusTypes());
        if (normalizedOptions.size() != 2 || normalizedFocusTypes.size() != 2) {
            return FollowupOptionsResult.toolError("INVALID_FOLLOWUP_OPTIONS", "followup options must contain exactly 2 items");
        }
        if (normalizedFocusTypes.get(0).equals(normalizedFocusTypes.get(1))) {
            return FollowupOptionsResult.toolError("INVALID_FOCUS_TYPES", "followup focus types must cover two different dimensions");
        }
        return FollowupOptionsResult.ok(normalizedOptions, normalizedFocusTypes, sanitizeRationale(raw.rationale()));
    }

    private List<String> normalizeQuestions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String option : options) {
            if (option == null) {
                continue;
            }
            String trimmed = option.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("请补充")) {
                continue;
            }
            if (!trimmed.endsWith("？") && !trimmed.endsWith("?")) {
                trimmed = trimmed + "？";
            }
            if (trimmed.length() < 8 || !unique.add(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeFocusTypes(List<String> focusTypes) {
        if (focusTypes == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String focusType : focusTypes) {
            if (focusType == null || focusType.isBlank()) {
                continue;
            }
            normalized.add(focusType.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String sanitizeRationale(String rationale) {
        if (rationale == null || rationale.isBlank()) {
            return null;
        }
        return rationale.length() > 300 ? rationale.substring(0, 300) : rationale;
    }

    private String buildFollowupToolPrompt() {
        return """
                你是知识库问答系统中的二次检索候选问题生成器。
                你的任务是：基于当前用户原问题、检索缺口类型、已检索证据和检索历史，生成两个可直接用于下一轮检索的完整问题句。

                必须遵守：
                1. 你必须且只能输出一个合法 JSON 对象。
                2. 不要输出任何分析、解释、推理过程、Markdown 代码块。
                3. 输出字段只允许：status、options、focusTypes、rationale、errorCode、errorMessage。
                4. status 只能是 ok 或 tool_error。
                5. 当 status=ok 时，options 必须恰好 2 条，focusTypes 也必须恰好 2 条，且一一对应。
                6. 两条问题必须覆盖不同澄清维度，并且都能直接作为检索问题使用。
                7. 不允许输出“请补充更多背景”“请提供更多信息”这类开放式提示。
                8. 不允许引入当前上下文中不存在的新事实、新制度名称、新地域、新主体。
                9. 只能基于当前上下文，把原问题细化成更可检索的问题。

                当前检索缺口类型：{retrievalGapType}
                允许的澄清维度：{allowedFocusTypes}
                当前已选标签：{selectedTags}

                检索历史：
                {retrievalHistory}

                已检索证据：
                {retrievedEvidence}
                """;
    }

    private java.util.Map<String, Object> buildFollowupToolPromptParams() {
        return java.util.Map.of(
                "retrievalGapType", executionContext.retrievalGapType().name(),
                "allowedFocusTypes", executionContext.allowedFocusTypes().isEmpty()
                        ? "无"
                        : String.join(", ", executionContext.allowedFocusTypes()),
                "selectedTags", executionContext.selectedTags().isEmpty()
                        ? "无"
                        : String.join(", ", executionContext.selectedTags()),
                "retrievalHistory", summarizeRetrievalHistory(),
                "retrievedEvidence", summarizeRetrievedEvidence());
    }

    private String summarizeRetrievalHistory() {
        if (executionContext.retrievalHistory().isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (RetrievalHistoryEntry entry : executionContext.retrievalHistory()) {
            builder.append(index++)
                    .append(". query=")
                    .append(entry.query())
                    .append("; status=")
                    .append(entry.status())
                    .append("; resultCount=")
                    .append(entry.resultCount())
                    .append("; tags=")
                    .append(entry.tags())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private String summarizeRetrievedEvidence() {
        if (executionContext.retrievedEvidence().isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (EvidenceSnapshot snapshot : executionContext.retrievedEvidence()) {
            builder.append(index++)
                    .append(". id=")
                    .append(snapshot.id())
                    .append("; metadata=")
                    .append(snapshot.metadataSnapshot())
                    .append("; text=")
                    .append(abbreviate(snapshot.text(), 220))
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String sanitizeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private String normalizeQueryKey(KnowledgeSearchRequest request) {
        String query = request == null || request.query() == null ? "" : request.query().trim();
        List<String> tags = normalizeTags(request == null ? null : request.tagsAnyOf());
        int topK = request == null || request.topK() == null ? 0 : request.topK();
        return query + "|" + String.join(",", tags) + "|" + topK;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .sorted()
                .toList();
    }
}
