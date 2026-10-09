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
		when(contextFormatter.formatParentContexts(anyList())).thenReturn(FULL_CONTEXT);
		when(tracingSupport.traceMono(anyString(), anyMap(), any()))
				.thenAnswer(invocation -> invocation.getArgument(2));
	}

	@Test
	void fullyAnswerableUsesOneReviewAndOneAnswerCall() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubAssessments(assessment(
				AdaptiveEvidenceWorkflow.AnswerabilityRating.FULLY_ANSWERABLE,
				List.of("申请资格条件"), List.of("ev-1"), List.of(), List.of()));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(answerResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Answer.class, workflow.execute(request()).block());

		GroundedTurnModule.Command command = capturedCommand();
		assertEquals(0, command.maxAnswerRepairs());
		assertEquals(List.of("申请资格条件"), command.supportedAspects());
		assertTrue(command.missingAspects().isEmpty());
		assertEquals(List.of("ev-1"), evidenceIds(command));
		verifyReviewCount(1);
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
		verifyPromptContract();
	}

	@Test
	void limitedAnswerableGeneratesBoundedAnswerWithoutRetrieval() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubAssessments(assessment(
				AdaptiveEvidenceWorkflow.AnswerabilityRating.LIMITED_ANSWERABLE,
				List.of("已知申请对象"), List.of("ev-1"), List.of("材料要求未覆盖"), List.of()));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(answerResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Answer.class, workflow.execute(request()).block());

		GroundedTurnModule.Command command = capturedCommand();
		assertEquals(GroundedTurnModule.AnswerPolicy.REVIEWED_GROUNDED, command.answerPolicy());
		assertEquals(List.of("材料要求未覆盖"), command.missingAspects());
		verifyReviewCount(1);
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void criticalGapRetrievesOnceThenGeneratesLimitedAnswer() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubAssessments(
				assessment(
						AdaptiveEvidenceWorkflow.AnswerabilityRating.CRITICAL_RETRIEVAL_GAP,
						List.of(), List.of(), List.of("缺少材料要求"),
						List.of("奖学金申请材料要求是什么？", "奖学金成绩要求是什么？")),
				assessment(
						AdaptiveEvidenceWorkflow.AnswerabilityRating.LIMITED_ANSWERABLE,
						List.of("材料要求"), List.of("ev-2"), List.of("成绩要求未覆盖"), List.of()));
		when(retrievalPipeline.refineWithQueries(
				eq(QUESTION),
				eq(List.of("奖学金申请材料要求是什么？", "奖学金成绩要求是什么？")),
				anyList(), same(USER), same(SCOPE), eq(20), eq(12)))
				.thenReturn(Mono.just(retrievalResult("ev-1", "ev-2")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(answerResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Answer.class, workflow.execute(request()).block());

		GroundedTurnModule.Command command = capturedCommand();
		assertEquals(List.of("ev-2"), evidenceIds(command));
		assertEquals(List.of("成绩要求未覆盖"), command.missingAspects());
		verifyReviewCount(2);
		verify(retrievalPipeline, times(1)).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void secondCriticalGapRefusesWithoutThirdRetrieval() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubAssessments(
				criticalAssessment("缺少材料", "奖学金申请材料要求是什么？"),
				criticalAssessment("仍缺材料", "奖学金申请材料完整清单是什么？"));
		when(retrievalPipeline.refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt()))
				.thenReturn(Mono.just(retrievalResult("ev-1")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(refusalResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Refusal.class, workflow.execute(request()).block());

		assertEquals(GroundedTurnModule.AnswerPolicy.KNOWLEDGE_REFUSAL, capturedCommand().answerPolicy());
		verifyReviewCount(2);
		verify(retrievalPipeline, times(1)).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void unanswerableRefusesWithoutSupplementalRetrieval() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubAssessments(assessment(
				AdaptiveEvidenceWorkflow.AnswerabilityRating.UNANSWERABLE,
				List.of(), List.of(), List.of("知识库没有相关规定"), List.of()));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(refusalResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Refusal.class, workflow.execute(request()).block());

		verifyReviewCount(1);
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void emptyInitialEvidenceUsesUnifiedAssessmentAndCanRecover() {
		stubInitialRetrieval(retrievalResult());
		stubAssessments(
				criticalAssessment("首次检索为空", "学校奖学金申请资格是什么？"),
				assessment(
						AdaptiveEvidenceWorkflow.AnswerabilityRating.FULLY_ANSWERABLE,
						List.of("申请资格"), List.of("ev-1"), List.of(), List.of()));
		when(retrievalPipeline.refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt()))
				.thenReturn(Mono.just(retrievalResult("ev-1")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(answerResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Answer.class, workflow.execute(request()).block());

		verifyReviewCount(2);
		verify(retrievalPipeline, times(1)).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void duplicateSupplementalQueryRefusesWithoutRetrieval() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubAssessments(criticalAssessment("缺少完整条件", QUESTION));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(refusalResult()));

		assertInstanceOf(AdaptiveEvidenceWorkflow.Refusal.class, workflow.execute(request()).block());

		verifyReviewCount(1);
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void rejectsLimitedAssessmentThatAlsoRequestsRetrieval() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		stubAssessments(assessment(
				AdaptiveEvidenceWorkflow.AnswerabilityRating.LIMITED_ANSWERABLE,
				List.of("申请对象"), List.of("ev-1"), List.of("缺少材料"), List.of("补查材料")));

		assertThrows(StructuredResponseException.class, () -> workflow.execute(request()).block());
		verify(groundedTurnModule, never()).execute(any());
	}

	private void stubInitialRetrieval(RetrievalResult result) {
		when(retrievalPipeline.retrieveWithParentContexts(
				eq(QUESTION), same(USER), same(SCOPE), eq(20), eq(12), anyMap()))
				.thenReturn(Mono.just(result));
	}

	private void stubAssessments(AdaptiveEvidenceWorkflow.EvidenceAssessment... assessments) {
		when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
		when(strategy.getChatClient()).thenReturn(chatClient);
		when(reactiveChatGateway.callStructured(
				same(chatClient), anyString(), anyMap(), anyList(), eq(QUESTION), eq(CONVERSATION_ID),
				eq(AdaptiveEvidenceWorkflow.EvidenceAssessment.class)))
				.thenReturn(Mono.just(assessments[0]),
						java.util.Arrays.stream(assessments).skip(1).map(Mono::just).toArray(Mono[]::new));
	}

	private AdaptiveEvidenceWorkflow.EvidenceAssessment assessment(
			AdaptiveEvidenceWorkflow.AnswerabilityRating rating,
			List<String> supportedAspects,
			List<String> supportingEvidenceIds,
			List<String> missingAspects,
			List<String> supplementalQueries) {
		return new AdaptiveEvidenceWorkflow.EvidenceAssessment(
				rating,
				"评审依据",
				supportedAspects,
				supportingEvidenceIds,
				missingAspects,
				supplementalQueries);
	}

	private AdaptiveEvidenceWorkflow.EvidenceAssessment criticalAssessment(String gap, String query) {
		return assessment(
				AdaptiveEvidenceWorkflow.AnswerabilityRating.CRITICAL_RETRIEVAL_GAP,
				List.of(), List.of(), List.of(gap), List.of(query));
	}

	@SuppressWarnings("unchecked")
	private void verifyPromptContract() {
		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(reactiveChatGateway).callStructured(
				same(chatClient), prompt.capture(), anyMap(), anyList(), eq(QUESTION), eq(CONVERSATION_ID),
				eq(AdaptiveEvidenceWorkflow.EvidenceAssessment.class));
		String value = prompt.getValue();
		assertOrdered(value,
				"# Instruction",
				"## Metric Definition",
				"## Criteria",
				"## Rating Rubric",
				"## Evaluation Steps",
				"## Output Schema");
		assertTrue(value.contains("FULLY_ANSWERABLE"));
		assertTrue(value.contains("LIMITED_ANSWERABLE"));
		assertTrue(value.contains("CRITICAL_RETRIEVAL_GAP"));
		assertTrue(value.contains("UNANSWERABLE"));
		assertTrue(!value.contains("canSearchAgain"));
		assertTrue(!value.contains("{maxQueries}"));
	}

	private void assertOrdered(String value, String... markers) {
		int previous = -1;
		for (String marker : markers) {
			int current = value.indexOf(marker);
			assertTrue(current > previous, () -> marker + " must appear in order");
			previous = current;
		}
	}

	private void verifyReviewCount(int count) {
		verify(reactiveChatGateway, times(count)).callStructured(
				same(chatClient), anyString(), anyMap(), anyList(), eq(QUESTION), eq(CONVERSATION_ID),
				eq(AdaptiveEvidenceWorkflow.EvidenceAssessment.class));
	}

	private GroundedTurnModule.Command capturedCommand() {
		ArgumentCaptor<GroundedTurnModule.Command> command =
				ArgumentCaptor.forClass(GroundedTurnModule.Command.class);
		verify(groundedTurnModule).execute(command.capture());
		return command.getValue();
	}

	private List<String> evidenceIds(GroundedTurnModule.Command command) {
		return command.candidateEvidence().stream()
				.map(document -> document.getMetadata().get("evidence_id").toString())
				.toList();
	}

	private AdaptiveEvidenceWorkflow.AgentRequest request() {
		return new AdaptiveEvidenceWorkflow.AgentRequest(
				QUESTION, CONVERSATION_ID, "message-1", USER, SCOPE, "model-1", "trace-1", note -> {});
	}

	private GroundedTurnModule.Result answerResult() {
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
