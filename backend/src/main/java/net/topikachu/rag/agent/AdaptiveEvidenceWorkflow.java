package net.topikachu.rag.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.GroundedTurnModule;
import net.topikachu.rag.service.chat.ParentContextBlock;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.RetrievalPipeline;
import net.topikachu.rag.service.chat.RetrievalResult;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
public final class AdaptiveEvidenceWorkflow {

	private static final int MAX_QUERY_CHARS = 500;
	private static final int MAX_EVIDENCE_COUNT = 12;
	private static final String FOLLOWUP_PROMPT = "现有证据不足，请选择一个更具体的问题继续检索。";

	private final RetrievalPipeline retrievalPipeline;
	private final EvidenceGate evidenceGate;
	private final ReactiveChatGateway reactiveChatGateway;
	private final ChatModelStrategyFactory strategyFactory;
	private final AgentHistorySnapshotBuilder historySnapshotBuilder;
	private final GroundedTurnModule groundedTurnModule;
	private final TracingSupport tracingSupport;
	private final int hybridTopK;
	private final int maxEvidenceCount;
	private final Duration operationTimeout;

	public AdaptiveEvidenceWorkflow(
			RetrievalPipeline retrievalPipeline,
			EvidenceGate evidenceGate,
			ReactiveChatGateway reactiveChatGateway,
			ChatModelStrategyFactory strategyFactory,
			AgentHistorySnapshotBuilder historySnapshotBuilder,
			GroundedTurnModule groundedTurnModule,
			TracingSupport tracingSupport,
			@Value("${rag.retrieval.hybrid-topk:120}") int hybridTopK,
			@Value("${rag.agent.max-evidence-count:12}") int maxEvidenceCount,
			@Value("${rag.agent.timeout-ms:12000}") long timeoutMs) {
		this.retrievalPipeline = retrievalPipeline;
		this.evidenceGate = evidenceGate;
		this.reactiveChatGateway = reactiveChatGateway;
		this.strategyFactory = strategyFactory;
		this.historySnapshotBuilder = historySnapshotBuilder;
		this.groundedTurnModule = groundedTurnModule;
		this.tracingSupport = tracingSupport;
		this.hybridTopK = hybridTopK;
		if (maxEvidenceCount <= 0 || maxEvidenceCount > MAX_EVIDENCE_COUNT) {
			throw new IllegalArgumentException("Agent evidence cap must be between 1 and 12");
		}
		this.maxEvidenceCount = maxEvidenceCount;
		this.operationTimeout = Duration.ofMillis(timeoutMs);
	}

	public Mono<AgentOutcome> execute(AgentRequest request) {
		return Mono.defer(() -> executeCalibrated(request));
	}

	private Mono<AgentOutcome> executeCalibrated(AgentRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		evidenceGate.requireCalibration();
		String runId = UUID.randomUUID().toString();
		Mono<AgentOutcome> pipeline = Mono.fromCallable(() -> historySnapshotBuilder.build(request.conversationId()))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(history -> {
					AgentRunState initial = AgentRunState.start(
							runId,
							request.conversationId(),
							request.msgId(),
							request.userInput(),
							request.currentUser(),
							request.searchScope(),
							request.modelId(),
							AgentRunState.Budget.standard(),
							!history.isEmpty())
							.transition(
									AgentRunState.Stage.PLAN,
									"decision",
									"已建立固定检索预算，使用原问题进行首次检索。");
					return retrieveInitial(initial).flatMap(state -> assessAndRoute(state, history, request));
				});

		return tracingSupport.traceMono(
				"agent.adaptive_evidence_workflow",
				Map.of(
						"agent.run_id", runId,
						"chat.mode", "agent",
						"chat.conversation_id", request.conversationId(),
						"chat.msg_id", request.msgId()),
				pipeline);
	}

	private Mono<AgentRunState> retrieveInitial(AgentRunState state) {
		AgentRunState retrieving = state.transition(
				AgentRunState.Stage.RETRIEVE,
				"retrieval",
				"正在使用原问题检索知识库。");
		long startedAt = System.currentTimeMillis();
		return retrievalPipeline.retrieveWithParentContexts(
					retrieving.originalQuestion(),
					retrieving.currentUser(),
					retrieving.searchScope(),
					hybridTopK,
					maxEvidenceCount,
					Map.of("chat.mode", "agent", "agent.search.round", 1))
				.timeout(operationTimeout)
				.map(result -> withRetrievalResult(
						retrieving,
						1,
						List.of(retrieving.originalQuestion()),
						result,
						Set.of(),
						System.currentTimeMillis() - startedAt));
	}

	private Mono<AgentOutcome> assessAndRoute(AgentRunState state,
											 List<Message> history,
											 AgentRequest request) {
		AgentRunState assessing = state.transition(
				AgentRunState.Stage.ASSESS,
				"assessment",
				"正在评估累计证据是否足以回答。");
		EvidenceGate.Assessment assessment = evidenceGate.assess(assessing);
		AgentRunState assessed = assessing.withAssessment(assessment)
				.addNote(AgentStage.REVIEWING, "assessment", "证据评估结果：" + assessment.verdict().name() + "。");

		return switch (assessment.verdict()) {
			case SUFFICIENT -> compose(assessed, request);
			case WEAK -> planRepair(assessed, history, request);
			case AMBIGUOUS -> Mono.just(clarify(assessed));
			case EMPTY, NOT_IMPROVABLE -> refuse(assessed, request);
		};
	}

	private Mono<AgentOutcome> planRepair(AgentRunState state,
										 List<Message> history,
										 AgentRequest request) {
		AgentRunState planning = state.transition(
				AgentRunState.Stage.PLAN,
				AgentStage.QUERY_REWRITING,
				"planning",
				"证据较弱，正在规划一次有界查询修复。");
		ChatModelStrategy strategy = strategyFactory.getStrategy(planning.modelId());
		return reactiveChatGateway.callStructured(
						strategy.getChatClient(),
						searchPlanPrompt(),
						Map.of(
								"assessmentReason", planning.assessment().reason(),
								"selectedSpaces", summarizeValues(planning.searchScope().requestedSpaceCodes()),
								"selectedTags", summarizeValues(planning.searchScope().requestedTags()),
								"evidenceSummary", summarizeEvidence(planning)),
						history,
						planning.originalQuestion(),
						planning.conversationId(),
						SearchPlan.class)
				.timeout(operationTimeout)
				.switchIfEmpty(Mono.error(new IllegalStateException("Search planner returned no result")))
				.map(plan -> normalizeQueries(plan, planning))
				.flatMap(queries -> queries.isEmpty()
						? refuse(planning.withAssessment(new EvidenceGate.Assessment(
								EvidenceGate.Verdict.NOT_IMPROVABLE,
								"planner produced no novel query",
								RetrievalGapType.NOT_IMPROVABLE)), request)
						: retrieveRepair(planning, queries, history, request));
	}

	private Mono<AgentOutcome> retrieveRepair(AgentRunState state,
										  List<String> queries,
										  List<Message> history,
										  AgentRequest request) {
		AgentRunState retrieving = state.transition(
				AgentRunState.Stage.RETRIEVE,
				"retrieval",
				"正在并发执行 " + queries.size() + " 个修复查询。");
		Set<String> existingIds = retrieving.evidence().stream()
				.map(EvidenceSnapshot::id)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		List<Document> existingCandidates = toDocuments(retrieving.evidence());
		long startedAt = System.currentTimeMillis();
		return retrievalPipeline.refineWithQueries(
					retrieving.originalQuestion(),
					queries,
					existingCandidates,
					retrieving.currentUser(),
				retrieving.searchScope(),
				hybridTopK,
				maxEvidenceCount)
				.timeout(operationTimeout)
				.map(result -> withRetrievalResult(
						retrieving,
						2,
						queries,
						result,
						existingIds,
						System.currentTimeMillis() - startedAt))
				.flatMap(next -> assessAndRoute(next, history, request));
	}

	private AgentRunState withRetrievalResult(AgentRunState state,
										  int round,
										  List<String> queries,
										  RetrievalResult result,
										  Set<String> existingIds,
										  long latencyMs) {
		List<EvidenceSnapshot> evidence = result == null || result.childCandidates() == null
				? List.of()
				: result.childCandidates().stream()
						.limit(maxEvidenceCount)
						.map(EvidenceSnapshot::fromDocument)
						.toList();
		Set<String> availableIds = evidence.stream()
				.map(EvidenceSnapshot::id)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		List<String> newEvidenceIds = availableIds.stream()
				.filter(id -> !existingIds.contains(id))
				.toList();
		List<ParentContextBlock> parents = result == null || result.parentContexts() == null
				? List.of()
				: AgentEvidenceSelector.filterParentContexts(result.parentContexts(), availableIds);
		String status = evidence.isEmpty() || parents.isEmpty() ? "no_result" : "ok";
		List<AgentRunState.SearchAttempt> attempts = queries.stream()
				.map(query -> new AgentRunState.SearchAttempt(
						round,
						query,
						status,
						newEvidenceIds,
						latencyMs))
				.toList();
		return state.withRetrieval(round, attempts, evidence, parents);
	}

	private Mono<AgentOutcome> compose(AgentRunState state, AgentRequest request) {
		AgentRunState composing = state.transition(
				AgentRunState.Stage.COMPOSE,
				"generation",
				"证据已满足门槛，正在生成结构化答案。");
		return groundedTurnModule.execute(command(
					request,
					toDocuments(composing.evidence()),
					composing.parentContexts(),
					GroundedTurnModule.AnswerPolicy.GROUNDED,
					composing.budget().maxAnswerRepairs()))
				.map(result -> {
					AgentRunState verified = composing;
					if (result.repairCount() > 0) {
						verified = verified.addNote(
								AgentStage.REVISING,
								"repair",
								"首次结构化答案未通过来源校验，已完成一次修复。");
					}
					verified = verified.transition(
							AgentRunState.Stage.VERIFY,
							"validation",
							"结构化答案及来源已通过校验。");
					AgentRunState completed = verified.transition(
							AgentRunState.Stage.COMPLETE,
							"decision",
							"答案已提交，Agent 运行完成。");
					return (AgentOutcome) new Answer(result, completed.notes());
				});
	}

	private Mono<AgentOutcome> refuse(AgentRunState state, AgentRequest request) {
		AgentRunState refusing = state.transition(
				AgentRunState.Stage.REFUSE,
				"decision",
				"当前证据为空或无法继续改善，返回知识拒答。");
		return groundedTurnModule.execute(command(
					request,
					List.of(),
					List.of(),
					GroundedTurnModule.AnswerPolicy.KNOWLEDGE_REFUSAL,
					0))
				.map(result -> {
					AgentRunState completed = refusing.transition(
							AgentRunState.Stage.COMPLETE,
							"decision",
							"知识拒答已提交，Agent 运行完成。");
					return (AgentOutcome) new Refusal(result, completed.notes());
				});
	}

	private Clarify clarify(AgentRunState state) {
		AgentRunState clarified = state.transition(
				AgentRunState.Stage.CLARIFY,
				"decision",
				"问题缺少可由用户补充的限定，转为点击式追问。");
		return new Clarify(
				FOLLOWUP_PROMPT,
				buildClarificationOptions(clarified.originalQuestion(), clarified.assessment().gapType()),
				clarified.notes());
	}

	private GroundedTurnModule.Command command(AgentRequest request,
											 List<Document> evidence,
											 List<ParentContextBlock> parents,
											 GroundedTurnModule.AnswerPolicy answerPolicy,
											 int maxRepairs) {
		return new GroundedTurnModule.Command(
				request.userInput(),
				request.conversationId(),
				request.currentUser().userId(),
				request.modelId(),
				"agent",
				request.msgId(),
				request.traceId(),
				evidence,
				parents,
				answerPolicy,
				maxRepairs);
	}

	private List<String> normalizeQueries(SearchPlan plan, AgentRunState state) {
		if (plan == null || plan.queries() == null) {
			return List.of();
		}
		Set<String> seen = new LinkedHashSet<>();
		seen.add(normalizeQueryKey(state.originalQuestion()));
		state.attempts().stream()
				.map(AgentRunState.SearchAttempt::query)
				.map(this::normalizeQueryKey)
				.forEach(seen::add);

		List<String> normalized = new ArrayList<>();
		for (String query : plan.queries()) {
			if (query == null) {
				continue;
			}
			String candidate = query.strip();
			if (candidate.length() > MAX_QUERY_CHARS) {
				candidate = candidate.substring(0, MAX_QUERY_CHARS).strip();
			}
			String key = normalizeQueryKey(candidate);
			if (candidate.isEmpty() || !seen.add(key)) {
				continue;
			}
			normalized.add(candidate);
			if (normalized.size() == state.budget().maxSubqueries()) {
				break;
			}
		}
		return List.copyOf(normalized);
	}

	private String normalizeQueryKey(String query) {
		return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
	}

	private List<Document> toDocuments(List<EvidenceSnapshot> evidence) {
		return evidence.stream()
				.map(snapshot -> Document.builder()
						.id(snapshot.id())
						.text(snapshot.text())
						.metadata(new LinkedHashMap<>(snapshot.metadataSnapshot()))
						.build())
				.toList();
	}

	private String summarizeEvidence(AgentRunState state) {
		if (state.parentContexts().isEmpty()) {
			return "无";
		}
		StringBuilder summary = new StringBuilder();
		state.parentContexts().stream().limit(8).forEach(context -> summary
				.append("- file=")
				.append(context.fileName())
				.append("; evidenceIds=")
				.append(context.evidenceIds())
				.append("; text=")
				.append(abbreviate(context.content(), 220))
				.append(System.lineSeparator()));
		return summary.toString().strip();
	}

	private String summarizeValues(List<String> values) {
		return values == null || values.isEmpty() ? "无" : String.join(", ", values);
	}

	private String abbreviate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength) + "...";
	}

	private List<String> buildClarificationOptions(String originalQuestion, RetrievalGapType gapType) {
		List<String> focusTypes = switch (gapType == null ? RetrievalGapType.MISSING_SCOPE : gapType) {
			case MISSING_TIME -> List.of("time", "scope");
			case MISSING_PROCEDURE_BRANCH -> List.of("procedure", "scope");
			case AMBIGUOUS_SUBJECT -> List.of("subject", "scope");
			case MISSING_SCOPE, NOT_IMPROVABLE -> List.of("scope", "procedure");
		};
		String question = abbreviateQuestion(originalQuestion);
		return focusTypes.stream().map(focusType -> switch (focusType) {
			case "time" -> "针对“" + question + "”，如果进一步限定适用时间或发生阶段，知识库中有哪些明确规则？";
			case "procedure" -> "针对“" + question + "”，如果进一步限定处分程序、审批条件或复审环节，知识库中有哪些明确规则？";
			case "subject" -> "针对“" + question + "”，如果进一步限定对象、角色或学生身份，知识库中有哪些明确规则？";
			default -> "针对“" + question + "”，如果进一步限定适用对象或适用范围，知识库中有哪些明确规则？";
		}).toList();
	}

	private String abbreviateQuestion(String originalQuestion) {
		if (originalQuestion == null || originalQuestion.isBlank()) {
			return "当前问题";
		}
		String trimmed = originalQuestion.strip();
		return trimmed.length() > 90 ? trimmed.substring(0, 90) + "..." : trimmed;
	}

	private String searchPlanPrompt() {
		return """
				你是校园知识库的查询规划器。当前首轮证据较弱，只允许提出一次检索修复计划。
				必须遵守：
				1. 只输出一个合法 JSON 对象，字段只允许 queries。
				2. queries 必须是字符串数组；简单改写输出 1 条，分解检索输出 2 至 4 条。
				3. 每条 query 必须是可独立检索的完整自然语言问题，不得输出关键词堆砌。
				4. 保留原问题中的主体、时间、范围和限制，不得引入新事实、制度名、标签或空间。
				5. 不得输出原问题原文，不得输出重复 query。
				6. 不得输出 user、role、dept、space、tag、topK、timeout、并发、预算或任何控制字段。
				7. 不输出分析、解释、Markdown 或 JSON 之外的文字。

				证据评估原因：{assessmentReason}
				只读空间范围：{selectedSpaces}
				只读标签过滤：{selectedTags}
				首轮证据摘要：
				{evidenceSummary}
				""";
	}

	public record AgentRequest(
			String userInput,
			String conversationId,
			String msgId,
			CurrentUserContext currentUser,
			SearchScope searchScope,
			String modelId,
			String traceId) {

		public AgentRequest {
			if (userInput == null || userInput.isBlank()) {
				throw new IllegalArgumentException("userInput must not be blank");
			}
			Objects.requireNonNull(conversationId, "conversationId must not be null");
			Objects.requireNonNull(msgId, "msgId must not be null");
			Objects.requireNonNull(currentUser, "currentUser must not be null");
			searchScope = searchScope == null ? SearchScope.empty() : searchScope;
			Objects.requireNonNull(modelId, "modelId must not be null");
			traceId = traceId == null ? "" : traceId;
		}
	}

	public sealed interface AgentOutcome permits Answer, Clarify, Refusal {
		List<AgentNote> notes();
	}

	public record Answer(GroundedTurnModule.Result result, List<AgentNote> notes) implements AgentOutcome {
		public Answer {
			Objects.requireNonNull(result, "result must not be null");
			notes = notes == null ? List.of() : List.copyOf(notes);
		}
	}

	public record Clarify(String prompt, List<String> options, List<AgentNote> notes) implements AgentOutcome {
		public Clarify {
			Objects.requireNonNull(prompt, "prompt must not be null");
			options = options == null ? List.of() : List.copyOf(options);
			notes = notes == null ? List.of() : List.copyOf(notes);
		}
	}

	public record Refusal(GroundedTurnModule.Result result, List<AgentNote> notes) implements AgentOutcome {
		public Refusal {
			Objects.requireNonNull(result, "result must not be null");
			notes = notes == null ? List.of() : List.copyOf(notes);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SearchPlan(List<String> queries) {
		SearchPlan {
			queries = queries == null ? List.of() : queries.stream().filter(Objects::nonNull).toList();
		}
	}
}
