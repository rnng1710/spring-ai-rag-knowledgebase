package net.topikachu.rag.agent;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptiveEvidenceWorkflowTest {

	private static final String QUESTION = "学校奖学金完整申请资格条件";
	private static final String CONVERSATION_ID = "conversation-1";
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
				tracingSupport,
				20,
				12,
				2_000);
		when(historySnapshotBuilder.build(CONVERSATION_ID)).thenReturn(List.of());
		when(tracingSupport.traceMono(anyString(), anyMap(), any()))
				.thenAnswer(invocation -> invocation.getArgument(2));
	}

	@Test
	void sufficientEvidenceRetrievesOnceAndComposesGroundedAnswer() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		when(evidenceGate.assess(any())).thenReturn(assessment(EvidenceGate.Verdict.SUFFICIENT));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result(0)));

		AdaptiveEvidenceWorkflow.Answer answer = assertInstanceOf(
				AdaptiveEvidenceWorkflow.Answer.class,
				workflow.execute(request()).block());

		assertEquals("answer", answer.result().answer());
		ArgumentCaptor<GroundedTurnModule.Command> command = commandCaptor();
		assertEquals(GroundedTurnModule.AnswerPolicy.GROUNDED, command.getValue().answerPolicy());
		assertEquals(1, command.getValue().maxAnswerRepairs());
		assertEquals(List.of("ev-1"), evidenceIds(command.getValue().candidateEvidence()));
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
		verifyNoInteractions(strategyFactory, reactiveChatGateway);
	}

	@Test
	void weakEvidencePlansQueriesAndRefinesOnceWithTheOriginalAclContext() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		when(evidenceGate.assess(any())).thenReturn(
				assessment(EvidenceGate.Verdict.WEAK),
				assessment(EvidenceGate.Verdict.SUFFICIENT));
		stubSearchPlan(List.of("奖学金适用对象", "奖学金成绩要求", "奖学金材料要求"));
		when(retrievalPipeline.refineWithQueries(
				eq(QUESTION),
				eq(List.of("奖学金适用对象", "奖学金成绩要求", "奖学金材料要求")),
				anyList(),
				same(USER),
				same(SCOPE),
				eq(20),
				eq(12)))
				.thenReturn(Mono.just(retrievalResult("ev-1", "ev-2")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result(0)));

		AdaptiveEvidenceWorkflow.AgentOutcome outcome = workflow.execute(request()).block();

		assertInstanceOf(AdaptiveEvidenceWorkflow.Answer.class, outcome);
		verify(retrievalPipeline).retrieveWithParentContexts(
				eq(QUESTION), same(USER), same(SCOPE), eq(20), eq(12), anyMap());
		verify(retrievalPipeline, times(1)).refineWithQueries(
				eq(QUESTION),
				eq(List.of("奖学金适用对象", "奖学金成绩要求", "奖学金材料要求")),
				anyList(),
				same(USER),
				same(SCOPE),
				eq(20),
				eq(12));
		verify(evidenceGate, times(2)).assess(any());
	}

	@Test
	void plannerQueriesAreDeduplicatedTruncatedAndCappedAtFour() {
		String longQuery = "x".repeat(501);
		List<String> expected = List.of("Rewrite", longQuery.substring(0, 500), "second", "third");
		stubInitialRetrieval(retrievalResult("ev-1"));
		when(evidenceGate.assess(any())).thenReturn(
				assessment(EvidenceGate.Verdict.WEAK),
				assessment(EvidenceGate.Verdict.SUFFICIENT));
		stubSearchPlan(List.of(
				"Rewrite", " rewrite ", QUESTION, longQuery, "second", "third", "fourth", "fifth"));
		when(retrievalPipeline.refineWithQueries(
				eq(QUESTION), eq(expected), anyList(), same(USER), same(SCOPE), eq(20), eq(12)))
				.thenReturn(Mono.just(retrievalResult("ev-1", "ev-2")));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result(0)));

		workflow.execute(request()).block();

		verify(retrievalPipeline).refineWithQueries(
				eq(QUESTION), eq(expected), anyList(), same(USER), same(SCOPE), eq(20), eq(12));
	}

	@Test
	void ambiguousEvidenceReturnsExactlyTwoOptionsWithoutComposing() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		when(evidenceGate.assess(any())).thenReturn(new EvidenceGate.Assessment(
				EvidenceGate.Verdict.AMBIGUOUS,
				"missing explicit time",
				RetrievalGapType.MISSING_TIME));

		AdaptiveEvidenceWorkflow.Clarify clarify = assertInstanceOf(
				AdaptiveEvidenceWorkflow.Clarify.class,
				workflow.execute(request()).block());

		assertEquals(2, clarify.options().size());
		assertTrue(clarify.options().stream().allMatch(option -> !option.isBlank()));
		verifyNoInteractions(groundedTurnModule, strategyFactory, reactiveChatGateway);
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@ParameterizedTest
	@EnumSource(value = EvidenceGate.Verdict.class, names = {"EMPTY", "NOT_IMPROVABLE"})
	void emptyOrNotImprovableEvidenceUsesKnowledgeRefusal(EvidenceGate.Verdict verdict) {
		stubInitialRetrieval(retrievalResult("ev-1"));
		when(evidenceGate.assess(any())).thenReturn(assessment(verdict));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result(0)));

		AdaptiveEvidenceWorkflow.AgentOutcome outcome = workflow.execute(request()).block();

		assertInstanceOf(AdaptiveEvidenceWorkflow.Refusal.class, outcome);
		ArgumentCaptor<GroundedTurnModule.Command> command = commandCaptor();
		assertEquals(GroundedTurnModule.AnswerPolicy.KNOWLEDGE_REFUSAL, command.getValue().answerPolicy());
		assertEquals(0, command.getValue().maxAnswerRepairs());
		assertTrue(command.getValue().candidateEvidence().isEmpty());
		assertTrue(command.getValue().parentContexts().isEmpty());
		verifyNoInteractions(strategyFactory, reactiveChatGateway);
	}

	@Test
	void plannerQueriesThatOnlyRepeatTheOriginalQuestionBecomeRefusal() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		when(evidenceGate.assess(any())).thenReturn(assessment(EvidenceGate.Verdict.WEAK));
		stubSearchPlan(List.of(QUESTION, "  " + QUESTION + "  ", QUESTION));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result(0)));

		AdaptiveEvidenceWorkflow.AgentOutcome outcome = workflow.execute(request()).block();

		assertInstanceOf(AdaptiveEvidenceWorkflow.Refusal.class, outcome);
		ArgumentCaptor<GroundedTurnModule.Command> command = commandCaptor();
		assertEquals(GroundedTurnModule.AnswerPolicy.KNOWLEDGE_REFUSAL, command.getValue().answerPolicy());
		verify(retrievalPipeline, never()).refineWithQueries(
				anyString(), anyList(), anyList(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void repairedGroundedResultAddsRevisingNote() {
		stubInitialRetrieval(retrievalResult("ev-1"));
		when(evidenceGate.assess(any())).thenReturn(assessment(EvidenceGate.Verdict.SUFFICIENT));
		when(groundedTurnModule.execute(any())).thenReturn(Mono.just(result(1)));

		AdaptiveEvidenceWorkflow.Answer answer = assertInstanceOf(
				AdaptiveEvidenceWorkflow.Answer.class,
				workflow.execute(request()).block());

		assertTrue(answer.notes().stream().anyMatch(note -> note.stage() == AgentStage.REVISING));
	}

	private void stubInitialRetrieval(RetrievalResult result) {
		when(retrievalPipeline.retrieveWithParentContexts(
				eq(QUESTION), same(USER), same(SCOPE), eq(20), eq(12), anyMap()))
				.thenReturn(Mono.just(result));
	}

	private void stubSearchPlan(List<String> queries) {
		when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
		when(strategy.getChatClient()).thenReturn(chatClient);
		when(reactiveChatGateway.callStructured(
				same(chatClient),
				anyString(),
				anyMap(),
				anyList(),
				eq(QUESTION),
				eq(CONVERSATION_ID),
				eq(AdaptiveEvidenceWorkflow.SearchPlan.class)))
				.thenReturn(Mono.just(new AdaptiveEvidenceWorkflow.SearchPlan(queries)));
	}

	private ArgumentCaptor<GroundedTurnModule.Command> commandCaptor() {
		ArgumentCaptor<GroundedTurnModule.Command> command =
				ArgumentCaptor.forClass(GroundedTurnModule.Command.class);
		verify(groundedTurnModule).execute(command.capture());
		return command;
	}

	private AdaptiveEvidenceWorkflow.AgentRequest request() {
		return new AdaptiveEvidenceWorkflow.AgentRequest(
				QUESTION,
				CONVERSATION_ID,
				"message-1",
				USER,
				SCOPE,
				"model-1",
				"trace-1");
	}

	private EvidenceGate.Assessment assessment(EvidenceGate.Verdict verdict) {
		return new EvidenceGate.Assessment(verdict, verdict.name().toLowerCase(), RetrievalGapType.NOT_IMPROVABLE);
	}

	private GroundedTurnModule.Result result(int repairCount) {
		return new GroundedTurnModule.Result("answer", "factual", List.of(), repairCount);
	}

	private RetrievalResult retrievalResult(String... evidenceIds) {
		List<Document> documents = java.util.Arrays.stream(evidenceIds)
				.map(this::document)
				.toList();
		List<ParentContextBlock> parents = java.util.Arrays.stream(evidenceIds)
				.map(this::parentContext)
				.toList();
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
				"parent-" + suffix,
				"doc-" + suffix,
				"handbook.pdf",
				"parent context " + suffix,
				1,
				1,
				1,
				List.of(evidenceId),
				1);
	}

	private List<String> evidenceIds(List<Document> documents) {
		return documents.stream()
				.map(document -> String.valueOf(document.getMetadata().get("evidence_id")))
				.toList();
	}
}
