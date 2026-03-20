package net.topikachu.rag.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class AgentExecutor {

    private final AgentTools tools;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${rag.agent.timeout-ms:12000}")
    private long timeoutMs;

    public AgentExecutor(AgentTools tools,
            ChatModelStrategyFactory strategyFactory,
            ReactiveChatGateway reactiveChatGateway) {
        this.tools = tools;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
    }

    public Mono<AgentExecutionResult> execute(String userInput, List<String> tags, String modelId) {
        ChatModelStrategy strategy = strategyFactory.getStrategy(modelId);
        List<AgentNote> notes = new ArrayList<>();
        List<Document> initialEvidence = new ArrayList<>();

        addNote(notes, AgentStage.PLANNING, "decision", "正在规划检索与回答步骤。");

        return decideNext(strategy, userInput, initialEvidence, tags)
                .defaultIfEmpty(new AgentDecision("retrieve", userInput, tags, null))
                .flatMap(decision -> {
                    String action = normalizeAction(decision.action());
                    if ("followup".equals(action)) {
                        addNote(notes, AgentStage.FOLLOWUP, "decision", "问题关键信息不足，先向用户追问。");
                        return Mono.just(new AgentExecutionResult(
                                List.of(),
                                notes,
                                defaultFollowup(decision.followup()),
                                null,
                                null,
                                false));
                    }

                    List<String> effectiveTags = (decision.tags() != null && !decision.tags().isEmpty())
                            ? decision.tags()
                            : tags;
                    String planningQuery = (decision.query() != null && !decision.query().isBlank())
                            ? decision.query()
                            : userInput;

                    addNote(notes, AgentStage.PLANNING, "decision", "已识别原始问题，准备生成检索改写。");
                    addNote(notes, AgentStage.QUERY_REWRITING, "info", "正在将原始问题整理为更适合检索的表达。");

                    return rewriteQuery(strategy, userInput, planningQuery, effectiveTags)
                            .defaultIfEmpty(new AgentRewriteResult(userInput, "已完成检索改写。"))
                            .flatMap(rewriteResult -> {
                                String query = normalizeRewriteQuery(userInput, rewriteResult == null ? null : rewriteResult.query());
                                log.info("Agent rewritten query for userInput='{}': {}", userInput, query);
                                addNote(notes, AgentStage.QUERY_REWRITING, "info",
                                        safeText(rewriteResult == null ? null : rewriteResult.note(), "已完成检索改写。"));
                                addNote(notes, AgentStage.RETRIEVING, "retrieval", "正在检索与问题相关的知识库内容。");

                                return retrieveEvidence(query, effectiveTags, initialEvidence)
                                        .flatMap(evidence -> buildResultAfterEvidence(
                                                strategy,
                                                userInput,
                                                effectiveTags,
                                                notes,
                                                evidence));
                            });
                });
    }

    private Mono<AgentExecutionResult> buildResultAfterEvidence(ChatModelStrategy strategy,
            String userInput,
            List<String> effectiveTags,
            List<AgentNote> notes,
            List<Document> evidence) {
        if (evidence.isEmpty()) {
            addNote(notes, AgentStage.FOLLOWUP, "decision", "未找到足够证据，转为追问以补齐条件。");
            return Mono.just(new AgentExecutionResult(
                    List.of(),
                    notes,
                    "未检索到足够证据，请提供更具体的描述或关键词。",
                    null,
                    null,
                    false));
        }

        addNote(notes, AgentStage.DRAFTING, "info", "正在基于检索证据生成回答草稿。");
        String context = buildContext(evidence);

        return generateDraft(strategy, userInput, context)
                .flatMap(draft -> {
                    log.info("Agent first-pass answer draft for userInput='{}': {}", userInput, draft);
                    addNote(notes, AgentStage.DRAFTING, "info", "已生成草稿，准备进行证据一致性审查。");
                    addNote(notes, AgentStage.REVIEWING, "critique", "正在核查结论、引用与证据是否一致。");

                    return reviewDraft(strategy, userInput, context, draft, effectiveTags)
                            .defaultIfEmpty(new AgentReviewDecision("pass", null, null, null))
                            .flatMap(reviewDecision -> finalizeReview(
                                    strategy,
                                    userInput,
                                    effectiveTags,
                                    notes,
                                    evidence,
                                    draft,
                                    reviewDecision));
                });
    }

    private Mono<AgentExecutionResult> finalizeReview(ChatModelStrategy strategy,
            String userInput,
            List<String> effectiveTags,
            List<AgentNote> notes,
            List<Document> evidence,
            String draft,
            AgentReviewDecision reviewDecision) {
        String verdict = normalizeVerdict(reviewDecision == null ? null : reviewDecision.verdict());

        if ("followup".equals(verdict)) {
            addNote(notes, AgentStage.FOLLOWUP, "critique",
                    safeText(reviewDecision == null ? null : reviewDecision.critique(),
                            "现有证据不足以直接回答，先向用户确认关键条件。"));
            return Mono.just(new AgentExecutionResult(
                    List.of(),
                    notes,
                    defaultFollowup(reviewDecision == null ? null : reviewDecision.followup()),
                    draft,
                    null,
                    false));
        }

        if (!"revise".equals(verdict)) {
            addNote(notes, AgentStage.GENERATING_FINAL, "info", "正在生成最终答案。");
            return Mono.just(new AgentExecutionResult(evidence, notes, null, draft, null, false));
        }

        addNote(notes, AgentStage.REVIEWING, "critique",
                safeText(reviewDecision == null ? null : reviewDecision.critique(),
                        "已识别到需要补强的证据或表述问题，准备修订答案。"));

        Mono<List<Document>> evidenceMono;
        if (reviewDecision != null && reviewDecision.query() != null && !reviewDecision.query().isBlank()) {
            addNote(notes, AgentStage.RETRIEVING, "retrieval", "根据审查结果补充检索证据。");
            evidenceMono = retrieveEvidence(reviewDecision.query(), effectiveTags, evidence);
        } else {
            evidenceMono = Mono.just(evidence);
        }

        return evidenceMono.flatMap(updatedEvidence -> {
            if (updatedEvidence.isEmpty()) {
                addNote(notes, AgentStage.FOLLOWUP, "decision", "补充检索后仍缺少关键证据，转为追问。");
                return Mono.just(new AgentExecutionResult(
                        List.of(),
                        notes,
                        "当前仍缺少回答所需的关键信息，请补充时间、对象或具体场景。",
                        draft,
                        null,
                        true));
            }

            addNote(notes, AgentStage.REVISING, "info", "正在结合审查意见重写最终答案。");
            addNote(notes, AgentStage.GENERATING_FINAL, "info", "正在生成最终答案。");
            return Mono.just(new AgentExecutionResult(
                    updatedEvidence,
                    notes,
                    null,
                    draft,
                    safeText(reviewDecision.critique(), "请基于证据修正答案，并确保引用准确。"),
                    true));
        });
    }

    private Mono<List<Document>> retrieveEvidence(String query, List<String> tags, List<Document> existingEvidence) {
        return tools.retrieve(query, tags, null)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(docs -> mergeEvidence(existingEvidence, docs))
                .onErrorResume(e -> {
                    log.warn("Agent retrieve failed for query='{}': {}", query, e.getMessage());
                    return Mono.just(mergeEvidence(existingEvidence, List.of()));
                });
    }

    private List<Document> mergeEvidence(List<Document> existing, List<Document> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        }

        Map<String, Document> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (Document doc : existing) {
                merged.put(evidenceKey(doc), doc);
            }
        }
        for (Document doc : incoming) {
            merged.putIfAbsent(evidenceKey(doc), doc);
        }
        return new ArrayList<>(merged.values());
    }

    private String evidenceKey(Document doc) {
        if (doc == null) {
            return "null";
        }
        if (doc.getId() != null && !doc.getId().isBlank()) {
            return doc.getId();
        }
        return Integer.toHexString((doc.getText() == null ? "" : doc.getText()).hashCode());
    }

    private void addNote(List<AgentNote> notes, AgentStage stage, String kind, String text) {
        notes.add(new AgentNote(stage, kind, text, System.currentTimeMillis()));
    }

    private String defaultFollowup(String followup) {
        return (followup != null && !followup.isBlank())
                ? followup
                : "为了准确回答，请补充更具体的信息。";
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

    private Mono<String> generateDraft(ChatModelStrategy strategy, String userInput, String context) {
        String prompt = """
                你是校园知识库问答助手。请基于提供的【知识库上下文】先生成一版回答草稿。

                要求：
                1) 只能使用上下文内容，不得引入外部知识。
                2) 只有在上下文提供了真实页码时，才能附页码引用，例如【第18页】【第19页】；如果上下文只有“片段N”或根本没有页码，只能写“根据已知文档”或直接陈述依据，禁止编造页码。
                3) 若证据不足，明确指出无法确定，但仍尽量给出基于证据的最保守回答。
                4) 不要解释你的推理过程。

                ================ 知识库上下文 ================
                {context}
                ============================================
                """;

        return reactiveChatGateway.call(
                strategy.getChatClient(),
                prompt,
                Map.of("context", context),
                userInput);
    }

    private Mono<AgentRewriteResult> rewriteQuery(ChatModelStrategy strategy,
            String userInput,
            String planningQuery,
            List<String> tags) {
        String tagSummary = (tags == null || tags.isEmpty()) ? "无" : String.join(", ", tags);
        String prompt = """
                你是知识库检索查询改写器。你必须输出纯 JSON，不要包含任何其它文本。
                任务是把用户原始提问改写成更适合制度、规则、FAQ 文档检索的查询语句。

                JSON 结构如下：
                {
                  "query": "改写后的检索问题",
                  "note": "已完成检索改写"
                }

                规则：
                1) 只做“检索友好化改写”,不要回答问题本身。
                2) 不要输出推理过程，不要 markdown。
                3) 保留用户原意，不新增事实，不猜测政策，不编造时间、地点、流程。
                4) 如果用户问题依赖上下文，要结合最近对话，把代词、简称、省略信息补全为“独立可检索问题”。
                5) 优先保留并显式写出这些检索锚点：
                    业务主题：奖学金、助学金、转专业、选课、补考、重修、毕业论文、宿舍、医保、校园卡、图书馆、成绩单、学籍、请假、实习、就业协议、党团关系等
                    组织与对象：本科生、研究生、国际学生、新生、毕业生、学院、教务处、学工处、财务处、图书馆
                    约束词：申请条件、申请时间、截止时间、办理流程、所需材料、办理地点、是否可以、收费标准、联系方式。
                6) 如果原问题已经足够清晰，直接原样返回。
                7) 不要把具体名词改得更抽象；宁可更明确，不要更文学化。
                8) 无论改成了几个查询，修改后的检索语句必须是完整的表达.
                9) 对于含多个意图的问题，输出 1 个主查询和最多 2 个子查询
                """;

        String userPrompt = """
                用户原始问题：
                %s

                规划阶段建议检索问题：
                %s

                已用标签：
                %s
                """.formatted(userInput, planningQuery, tagSummary);

        return reactiveChatGateway.call(strategy.getChatClient(), prompt, Map.of(), userPrompt)
                .flatMap(raw -> Mono.justOrEmpty(parseRewriteResult(raw)));
    }

    private Mono<AgentReviewDecision> reviewDraft(ChatModelStrategy strategy,
            String userInput,
            String context,
            String draft,
            List<String> tags) {
        String tagSummary = (tags == null || tags.isEmpty()) ? "无" : String.join(", ", tags);
        String prompt = """
                你是回答质量审查器。你必须输出纯 JSON，不要包含任何其它文本。
                JSON 结构如下：
                {
                  "verdict": "pass|revise|followup",
                  "critique": "面向用户可展示的简短审查摘要",
                  "query": "如果需要补充检索，给出新的检索查询，否则为空",
                  "followup": "如果信息缺失到无法继续，给出明确追问，否则为空"
                }

                审查标准：
                1) 结论是否有足够证据支撑。
                2) 引用是否和上下文中的真实页码匹配；“片段N”只是内部定位，不属于可输出页码。
                3) 是否有明显越界推断。
                4) 是否需要补检索或追问。
                5) 若当前草稿可以直接作为最终答案，verdict=pass。
                6) 若能通过补充检索后修正，verdict=revise。
                7) 若缺少关键条件且无法通过补检索解决，verdict=followup。
                8) 如果当前证据没有真实页码，不应要求输出【第X页】；此时允许使用“根据已知文档”这类无页码表述。
                """;

        String userPrompt = """
                用户问题：
                %s

                已用标签：
                %s

                当前证据：
                %s

                草稿答案：
                %s
                """.formatted(userInput, tagSummary, context, draft);

        return reactiveChatGateway.call(strategy.getChatClient(), prompt, Map.of(), userPrompt)
                .flatMap(raw -> Mono.justOrEmpty(parseReviewDecision(raw)));
    }

    private Mono<AgentDecision> decideNext(ChatModelStrategy strategy, String userInput, List<Document> evidence, List<String> tags) {
        String evidenceSummary = summarizeEvidence(evidence);
        String tagSummary = (tags == null || tags.isEmpty()) ? "无" : String.join(", ", tags);

        String systemPrompt = """
                你是一个检索增强问答的“决策代理”。你必须输出纯 JSON，不能包含任何其它文本。
                仅允许以下 action: retrieve, answer, followup。
                JSON 结构如下：
                {
                  "action": "retrieve|answer|followup",
                  "query": "用于检索的查询（仅在 retrieve 时必填）",
                  "tags": ["可选标签"],
                  "followup": "需要追问的具体问题（仅在 followup 时必填）"
                }
                规则：
                1) 若证据为空或不足以支撑明确回答，优先 action=retrieve。
                2) 若无法通过检索补齐关键条件，action=followup 并提出明确追问。
                3) 若证据充足，action=answer。
                4) 输出必须是严格 JSON，不要 markdown、不要解释。
                """;

        String userPrompt = """
                用户问题：
                %s

                当前证据摘要：
                %s

                可用标签：
                %s
                """.formatted(userInput, evidenceSummary, tagSummary);

        return reactiveChatGateway.call(strategy.getChatClient(), systemPrompt, Map.of(), userPrompt)
                .flatMap(raw -> Mono.justOrEmpty(parseDecision(raw)))
                .map(decision -> decision == null
                        ? new AgentDecision("retrieve", userInput, tags, null)
                        : decision);
    }

    private AgentReviewDecision parseReviewDecision(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = unwrapJsonFence(raw);
        try {
            return objectMapper.readValue(trimmed, AgentReviewDecision.class);
        } catch (Exception e) {
            log.warn("Failed to parse review decision JSON: {}", trimmed);
            return null;
        }
    }

    private AgentRewriteResult parseRewriteResult(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = unwrapJsonFence(raw);
        try {
            return objectMapper.readValue(trimmed, AgentRewriteResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse rewrite result JSON: {}", trimmed);
            return null;
        }
    }

    private AgentDecision parseDecision(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = unwrapJsonFence(raw);
        try {
            return objectMapper.readValue(trimmed, AgentDecision.class);
        } catch (Exception e) {
            log.warn("Failed to parse agent decision JSON: {}", trimmed);
            return null;
        }
    }

    private String unwrapJsonFence(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first >= 0 && last > first) {
                trimmed = trimmed.substring(first + 1, last).trim();
            }
        }
        return trimmed;
    }

    private String normalizeAction(String action) {
        if (action == null) {
            return "retrieve";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "retrieve", "answer", "followup" -> a;
            default -> "retrieve";
        };
    }

    private String normalizeVerdict(String verdict) {
        if (verdict == null) {
            return "pass";
        }
        String value = verdict.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "pass", "revise", "followup" -> value;
            default -> "pass";
        };
    }

    private String safeText(String raw, String fallback) {
        return (raw == null || raw.isBlank()) ? fallback : raw;
    }

    private String normalizeRewriteQuery(String userInput, String rewrittenQuery) {
        if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
            return userInput;
        }
        String normalized = rewrittenQuery.trim();
        if (normalized.length() < 2) {
            return userInput;
        }
        return normalized;
    }

    private String summarizeEvidence(List<Document> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, evidence.size());
        for (int i = 0; i < limit; i++) {
            Document doc = evidence.get(i);
            String file = (String) doc.getMetadata().getOrDefault("file_name", "Unknown");
            String text = doc.getText();
            if (text == null) {
                text = "";
            }
            String snippet = text.length() > 120 ? text.substring(0, 120) + "..." : text;
            sb.append("[").append(i + 1).append("] ").append(file).append("：").append(snippet).append("\n");
        }
        return sb.toString();
    }
}
