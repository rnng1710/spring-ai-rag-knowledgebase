package net.topikachu.rag.agent;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ContextFormatter;
import net.topikachu.rag.service.chat.GroundedTurnModule;
import net.topikachu.rag.service.chat.ParentContextBlock;
import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.RetrievalPipeline;
import net.topikachu.rag.service.chat.RetrievalResult;
import net.topikachu.rag.service.chat.StructuredResponseException;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptiveEvidenceWorkflowTest {

	private static final String QUESTION = "学校奖学金完整申请资格条件";
	private static final String CONVERSATION_ID = "conversation-1";
	private static final String FULL_CONTEXT = "完整父块原文，不是截断摘要。";
	private static final CurrentUserContext USER = new CurrentUserContext(
			"user-1", "tester", "USER", "dept-1", "Dept", "space-1", false);
	private static final SearchScope SCOPE = new SearchScope(List.of("space-1"), List.of("scholarship"));

	@Mock
	private RetrievalPipeline retrievalPipeline;
	@Mock
	private EvidenceGate evidenceGate;
	@Mock
	private ReactiveChatGateway reactiveChatGateway;
	@Mock
	private ChatModelStrategyFactory strategyFactory;
	@Mock
	private AgentHistorySnapshotBuilder historySnapshotBuilder;
	@Mock
	private GroundedTurnModule groundedTurnModule;
	@Mock
	private ContextFormatter contextFormatter;
	@Mock
	private TracingSupport tracingSupport;
	@Mock
	private ChatModelStrategy strategy;
	@Mock
	private ChatClient chatClient;

	private AdaptiveEvidenceWorkflow workflow;

	@BeforeEach
	void setUp() {
		workflow = new AdaptiveEvidenceWorkflow(
				retrievalPipeline,
				evidenceGate,
				reactiveChatGateway,
				strategyFactory,
				historySnapshotBuilder,
				groundedTurnModule,
				contextFormatter,
				tracingSupport,
				20,
				12,
				2_000,
				false);
		when(historySnapshotBuilder.build(CONVERSATION_ID)).thenReturn(List.of());
		when(tracingSupport.traceMono(anyString(), anyMap(), any()))
				.thenAnswer(invocation -> invocation.getArgument(2));
	}

	@Test
	void sufficientReviewComposesAfterOneRetrieval() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubGate(EvidenceGate.Verdict.REVIEW);
		stubReview(AdaptiveEvidenceWorkflow.ReviewVerdict.SUFFICIENT, List.of(), List.of());
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result()));

		AdaptiveEvidenceWorkflow.Answer answer = assertInstanceOf(
				AdaptiveEvidenceWorkflow.Answer.class,
				workflow.execute(request()).block());

		assertEquals("answer", answer.result().answer());
		GroundedTurnModule.Command command = capturedCommand();
		assertEquals(GroundedTurnModule.AnswerPolicy.REVIEWED_GROUNDED, command.answerPolicy());
		assertEquals("candidate answer", command.reviewedCandidateAnswer());
		assertEquals(List.of("ev-1"), command.reviewedEvidenceIds());
		assertEquals(List.of("ev-1"), command.candidateEvidence().stream()
				.map(document -> document.getMetadata().get("evidence_id").toString())
				.toList());
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
		verifyReviewContext();
	}

	@Test
	void insufficientReviewRefusesWithoutAnotherRetrieval() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubGate(EvidenceGate.Verdict.REVIEW);
		stubReview(AdaptiveEvidenceWorkflow.ReviewVerdict.INSUFFICIENT, List.of(), List.of());
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(refusalResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Refusal.class, workflow.execute(request()).block());

		assertEquals(GroundedTurnModule.AnswerPolicy.KNOWLEDGE_REFUSAL, capturedCommand().answerPolicy());
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void partialReviewUsesItsQueriesAndThenComposes() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubGate(EvidenceGate.Verdict.REVIEW, EvidenceGate.Verdict.REVIEW);
		stubReviews(
				new AdaptiveEvidenceWorkflow.EvidenceReview(
						AdaptiveEvidenceWorkflow.ReviewVerdict.PARTIAL,
						"partial candidate",
						List.of("ev-1"),
						List.of("缺少材料要求"),
						List.of("奖学金材料要求", "奖学金成绩要求")),
				new AdaptiveEvidenceWorkflow.EvidenceReview(
						AdaptiveEvidenceWorkflow.ReviewVerdict.SUFFICIENT,
						"complete candidate", List.of("ev-2"), List.of(), List.of()));
		when(retrievalPipeline.refineWithQueries(
				eq(QUESTION),
				eq(List.of("奖学金材料要求", "奖学金成绩要求")),
				anyList(), same(USER), same(SCOPE), eq(20), eq(12)))
				.thenReturn(Mono.just(retrievalResult("ev-1", "ev-2")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Answer.class, workflow.execute(request()).block());
		GroundedTurnModule.Command command = capturedCommand();
		assertEquals(List.of("ev-2"), command.reviewedEvidenceIds());
		assertEquals(List.of("ev-2"), command.candidateEvidence().stream()
				.map(document -> document.getMetadata().get("evidence_id").toString())
				.toList());
		assertEquals(List.of("ev-2"), command.parentContexts().stream()
				.flatMap(parent -> parent.evidenceIds().stream())
				.toList());

		verify(retrievalPipeline).refineWithQueries(
				eq(QUESTION),
				eq(List.of("奖学金材料要求", "奖学金成绩要求")),
				anyList(), same(USER), same(SCOPE), eq(20), eq(12));
		verify(evidenceGate, times(2)).assess(any());
	}

	@Test
	void qualityRepairThenPartialRepairUsesThreeRoundsAndFourQueriesTotal() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubGate(EvidenceGate.Verdict.RETRY, EvidenceGate.Verdict.REVIEW, EvidenceGate.Verdict.REVIEW);
		stubSearchPlan(List.of("奖学金资格条件改写", "不应使用的第二条"));
		stubReviews(
				new AdaptiveEvidenceWorkflow.EvidenceReview(
						AdaptiveEvidenceWorkflow.ReviewVerdict.PARTIAL,
						"partial candidate",
						List.of("ev-1"),
						List.of("对象", "成绩", "材料", "时间"),
						List.of("奖学金适用对象", "奖学金成绩要求", "奖学金材料要求", "奖学金申请时间")),
				new AdaptiveEvidenceWorkflow.EvidenceReview(
						AdaptiveEvidenceWorkflow.ReviewVerdict.SUFFICIENT,
						"complete candidate", List.of("ev-3"), List.of(), List.of()));
		when(retrievalPipeline.refineWithQueries(
				eq(QUESTION), eq(List.of("奖学金资格条件改写")), anyList(),
				same(USER), same(SCOPE), eq(20), eq(12)))
				.thenReturn(Mono.just(retrievalResult("ev-1", "ev-2")));
		when(retrievalPipeline.refineWithQueries(
				eq(QUESTION),
				eq(List.of("奖学金适用对象", "奖学金成绩要求", "奖学金材料要求")),
				anyList(), same(USER), same(SCOPE), eq(20), eq(12)))
				.thenReturn(Mono.just(retrievalResult("ev-1", "ev-2", "ev-3")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Answer.class, workflow.execute(request()).block());

		verify(retrievalPipeline, times(2)).refineWithQueries(
				eq(QUESTION), anyList(), anyList(), same(USER), same(SCOPE), eq(20), eq(12));
	}

	@Test
	void finalPartialReviewRefusesWithoutFourthRound() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubGate(EvidenceGate.Verdict.REVIEW, EvidenceGate.Verdict.REVIEW);
		stubReviews(
				new AdaptiveEvidenceWorkflow.EvidenceReview(
						AdaptiveEvidenceWorkflow.ReviewVerdict.PARTIAL,
						"partial candidate",
						List.of("ev-1"),
						List.of("缺少材料"), List.of("奖学金材料要求")),
				new AdaptiveEvidenceWorkflow.EvidenceReview(
						AdaptiveEvidenceWorkflow.ReviewVerdict.PARTIAL,
						"still partial candidate",
						List.of("ev-2"),
						List.of("仍缺材料"), List.of()));
		when(retrievalPipeline.refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt()))
				.thenReturn(Mono.just(retrievalResult("ev-1", "ev-2")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(refusalResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Refusal.class, workflow.execute(request()).block());

		verify(retrievalPipeline, times(1)).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
		assertEquals(GroundedTurnModule.AnswerPolicy.KNOWLEDGE_REFUSAL, capturedCommand().answerPolicy());
	}

	@Test
	void partialReviewWithoutQueriesFailsInsteadOfGeneratingAnAnswer() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubGate(EvidenceGate.Verdict.REVIEW);
		stubReview(
				AdaptiveEvidenceWorkflow.ReviewVerdict.PARTIAL,
				List.of("缺少材料"),
				List.of());

		assertThrows(StructuredResponseException.class, () -> workflow.execute(request()).block());
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
		verify(groundedTurnModule, never()).execute(any());
	}

	private void stubInitialRetrieval(RetrievalResult result) {
		when(retrievalPipeline.retrieveWithParentContexts(
				eq(QUESTION), same(USER), same(SCOPE), eq(20), eq(12), anyMap()))
				.thenReturn(Mono.just(result));
	}

	private void stubGate(EvidenceGate.Verdict... verdicts) {
		EvidenceGate.Assessment[] assessments = java.util.Arrays.stream(verdicts)
				.map(verdict -> new EvidenceGate.Assessment(verdict, verdict.name().toLowerCase()))
				.toArray(EvidenceGate.Assessment[]::new);
		when(evidenceGate.assess(any())).thenReturn(assessments[0],
				java.util.Arrays.copyOfRange(assessments, 1, assessments.length));
	}

	private void stubReview(AdaptiveEvidenceWorkflow.ReviewVerdict verdict,
							List<String> missingPoints,
							List<String> queries) {
		String candidateAnswer = verdict == AdaptiveEvidenceWorkflow.ReviewVerdict.INSUFFICIENT
				? ""
				: "candidate answer";
		List<String> supportingEvidenceIds = verdict == AdaptiveEvidenceWorkflow.ReviewVerdict.INSUFFICIENT
				? List.of()
				: List.of("ev-1");
		stubReviews(new AdaptiveEvidenceWorkflow.EvidenceReview(
				verdict, candidateAnswer, supportingEvidenceIds, missingPoints, queries));
	}

	private void stubReviews(AdaptiveEvidenceWorkflow.EvidenceReview... reviews) {
		when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
		when(strategy.getChatClient()).thenReturn(chatClient);
		when(contextFormatter.formatParentContexts(anyList())).thenReturn(FULL_CONTEXT);
		when(reactiveChatGateway.callStructured(
				same(chatClient), anyString(), anyMap(), anyList(), eq(QUESTION), eq(CONVERSATION_ID),
				eq(AdaptiveEvidenceWorkflow.EvidenceReview.class)))
				.thenReturn(Mono.just(reviews[0]),
						java.util.Arrays.stream(reviews).skip(1).map(Mono::just).toArray(Mono[]::new));
	}

	private void stubSearchPlan(List<String> queries) {
		when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
		when(strategy.getChatClient()).thenReturn(chatClient);
		when(reactiveChatGateway.callStructured(
				same(chatClient), anyString(), anyMap(), anyList(), eq(QUESTION), eq(CONVERSATION_ID),
				eq(AdaptiveEvidenceWorkflow.SearchPlan.class)))
				.thenReturn(Mono.just(new AdaptiveEvidenceWorkflow.SearchPlan(queries)));
	}

	@SuppressWarnings("unchecked")
	private void verifyReviewContext() {
		ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
		verify(reactiveChatGateway).callStructured(
				same(chatClient), anyString(), params.capture(), anyList(), eq(QUESTION), eq(CONVERSATION_ID),
				eq(AdaptiveEvidenceWorkflow.EvidenceReview.class));
		assertEquals(FULL_CONTEXT, params.getValue().get("evidenceContext"));
	}

	private GroundedTurnModule.Command capturedCommand() {
		ArgumentCaptor<GroundedTurnModule.Command> command =
				ArgumentCaptor.forClass(GroundedTurnModule.Command.class);
		verify(groundedTurnModule).execute(command.capture());
		return command.getValue();
	}

	private AdaptiveEvidenceWorkflow.AgentRequest request() {
		return new AdaptiveEvidenceWorkflow.AgentRequest(
				QUESTION, CONVERSATION_ID, "message-1", USER, SCOPE, "model-1", "trace-1", note -> {});
	}

	private GroundedTurnModule.Result result() {
		return new GroundedTurnModule.Result("answer", "factual", List.of(), 0);
	}

	private GroundedTurnModule.Result refusalResult() {
		return new GroundedTurnModule.Result("refusal", "refusal", List.of(), 0);
	}

	private RetrievalResult retrievalResult(String... evidenceIds) {
		List<Document> documents = java.util.Arrays.stream(evidenceIds).map(this::document).toList();
		List<ParentContextBlock> parents = java.util.Arrays.stream(evidenceIds).map(this::parentContext).toList();
		return new RetrievalResult(documents, parents);
	}

	private Document document(String evidenceId) {
		String suffix = evidenceId.substring("ev-".length());
		return new Document("evidence " + suffix, Map.of(
				"evidence_id", evidenceId,
				"parent_block_id", "parent-" + suffix,
				"doc_uuid", "doc-" + suffix,
				"rerank_score", 0.8d));
	}

	private ParentContextBlock parentContext(String evidenceId) {
		String suffix = evidenceId.substring("ev-".length());
		return new ParentContextBlock(
				"parent-" + suffix, "doc-" + suffix, "handbook.pdf", "parent context " + suffix,
				1, 1, 1, List.of(evidenceId), 1);
	}
}
