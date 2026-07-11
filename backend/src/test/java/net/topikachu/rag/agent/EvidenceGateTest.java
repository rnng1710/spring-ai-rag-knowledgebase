package net.topikachu.rag.agent;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.service.chat.ParentContextBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceGateTest {

	private static final EvidenceGate GATE = new EvidenceGate(0.5, 2, 0.2);

	@ParameterizedTest(name = "{0}")
	@MethodSource("gateCases")
	void appliesEvidenceGateInFixedOrder(GateCase testCase) {
		EvidenceGate.Assessment assessment = GATE.assess(state(testCase));

		assertEquals(testCase.expectedVerdict(), assessment.verdict());
		assertEquals(testCase.expectedGap(), assessment.gapType());
	}

	@Test
	void checksNewEvidenceAcrossEveryAttemptInLatestRound() {
		AgentRunState state = start("学校奖学金完整申请资格条件", false, AgentRunState.Budget.standard())
				.withRetrieval(2,
						List.of(
								new AgentRunState.SearchAttempt(2, "query one", "ok", List.of(), 1),
								new AgentRunState.SearchAttempt(2, "query two", "ok", List.of("e2"), 1)),
						evidence(List.of(0.4, 0.3)),
						parents());

		EvidenceGate.Assessment assessment = GATE.assess(state);
		assertEquals(EvidenceGate.Verdict.NOT_IMPROVABLE, assessment.verdict());
		assertEquals("retrieval budget exhausted", assessment.reason());
	}

	@Test
	void keepsRunStateCollectionsAndTransitionsImmutable() {
		List<AgentRunState.SearchAttempt> attempts = new ArrayList<>();
		attempts.add(new AgentRunState.SearchAttempt(1, "query", "ok", List.of("e1"), 1));
		List<EvidenceSnapshot> evidence = new ArrayList<>(evidence(List.of(0.8)));
		List<ParentContextBlock> parents = new ArrayList<>(parents());

		AgentRunState retrieved = start("学校奖学金完整申请资格条件", false, AgentRunState.Budget.standard())
				.withRetrieval(1, attempts, evidence, parents);
		attempts.clear();
		evidence.clear();
		parents.clear();
		AgentRunState transitioned = retrieved
				.transition(AgentRunState.Stage.RETRIEVE, AgentStage.QUERY_REWRITING, "plan", "rewrite")
				.addNote(AgentStage.REVISING, "repair", "repairing");

		assertEquals(1, retrieved.attempts().size());
		assertEquals(1, retrieved.evidence().size());
		assertEquals(1, retrieved.parentContexts().size());
		assertEquals(List.of(1L, 2L), transitioned.notes().stream().map(AgentNote::sequence).toList());
		assertEquals(AgentStage.QUERY_REWRITING, transitioned.notes().get(0).stage());
		assertEquals(AgentStage.REVISING, transitioned.notes().get(1).stage());
		assertThrows(UnsupportedOperationException.class, () -> transitioned.notes().clear());
	}

	@Test
	void failsClosedUntilAllCalibrationValuesAreConfigured() {
		EvidenceGate uncalibrated = new EvidenceGate(null, null, null);

		assertThrows(IllegalStateException.class, uncalibrated::requireCalibration);
		assertThrows(IllegalArgumentException.class, () -> new EvidenceGate(0.5, null, 0.2));
		assertThrows(IllegalArgumentException.class, () -> new AgentRunState.Budget(3, 4, 1));
	}

	@Test
	void rerankerFallbackOverridesMixedStaleScoresWhenSecondRoundAddedEvidence() {
		List<EvidenceSnapshot> mixedEvidence = List.of(
				new EvidenceSnapshot("e1", "old", Map.of("rerank_score", 0.1)),
				new EvidenceSnapshot("e2", "new", Map.of("rerank_fallback", true)));
		AgentRunState state = start(
				"学校奖学金完整申请资格条件", false, AgentRunState.Budget.standard())
				.withRetrieval(
						2,
						List.of(new AgentRunState.SearchAttempt(2, "rewrite", "ok", List.of("e2"), 1)),
						mixedEvidence,
						parents());

		assertEquals(EvidenceGate.Verdict.SUFFICIENT, GATE.assess(state).verdict());
	}

	private static Stream<GateCase> gateCases() {
		return Stream.of(
				new GateCase("empty evidence", "完整奖学金申请资格条件", false, 1, null, true, true,
						EvidenceGate.Verdict.EMPTY, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("empty parents", "完整奖学金申请资格条件", false, 1, List.of(0.8), false, true,
						EvidenceGate.Verdict.EMPTY, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("round two added nothing", "完整奖学金申请资格条件", false, 2, List.of(0.4), true, false,
						EvidenceGate.Verdict.NOT_IMPROVABLE, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("reranker fallback", "完整奖学金申请资格条件", false, 1, List.of(), true, true,
						EvidenceGate.Verdict.SUFFICIENT, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("support threshold", "完整奖学金申请资格条件", false, 1, List.of(0.8, 0.7), true, true,
						EvidenceGate.Verdict.SUFFICIENT, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("score gap", "完整奖学金申请资格条件", false, 1, List.of(0.8, 0.4), true, true,
						EvidenceGate.Verdict.SUFFICIENT, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("budget exhausted", "完整奖学金申请资格条件", false, 2, List.of(0.4, 0.3), true, true,
						EvidenceGate.Verdict.NOT_IMPROVABLE, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("unresolved subject", "上述规定是否适用本校研究生", false, 1, List.of(0.4), true, true,
						EvidenceGate.Verdict.AMBIGUOUS, RetrievalGapType.AMBIGUOUS_SUBJECT),
				new GateCase("short subject", "条件？", false, 1, List.of(0.4), true, true,
						EvidenceGate.Verdict.AMBIGUOUS, RetrievalGapType.AMBIGUOUS_SUBJECT),
				new GateCase("relative time", "目前奖学金完整申请资格条件", true, 1, List.of(0.4), true, true,
						EvidenceGate.Verdict.AMBIGUOUS, RetrievalGapType.MISSING_TIME),
				new GateCase("explicit date", "2026年目前奖学金完整申请资格条件", true, 1, List.of(0.4), true, true,
						EvidenceGate.Verdict.WEAK, RetrievalGapType.NOT_IMPROVABLE),
				new GateCase("procedure branch", "申请材料不全下一步怎么办", true, 1, List.of(0.4), true, true,
						EvidenceGate.Verdict.AMBIGUOUS, RetrievalGapType.MISSING_PROCEDURE_BRANCH),
				new GateCase("ordinary weak evidence", "学校奖学金完整申请资格条件", false, 1, List.of(0.4, 0.3), true, true,
						EvidenceGate.Verdict.WEAK, RetrievalGapType.NOT_IMPROVABLE));
	}

	private static AgentRunState state(GateCase testCase) {
		List<EvidenceSnapshot> evidence = testCase.scores() == null
				? List.of()
				: testCase.scores().isEmpty() ? fallbackEvidence() : evidence(testCase.scores());
		List<String> newEvidenceIds = testCase.latestRoundAddedEvidence()
				? evidence.stream().map(EvidenceSnapshot::id).toList()
				: List.of();
		return start(testCase.question(), testCase.hasHistory(), AgentRunState.Budget.standard())
				.withRetrieval(
						testCase.round(),
						List.of(new AgentRunState.SearchAttempt(
								testCase.round(), testCase.question(), "ok", newEvidenceIds, 1)),
						evidence,
						testCase.hasParents() ? parents() : List.of());
	}

	private static AgentRunState start(String question, boolean hasHistory, AgentRunState.Budget budget) {
		return AgentRunState.start(
				"run-1",
				"conversation-1",
				"message-1",
				question,
				new CurrentUserContext("user-1", "tester", "USER", "dept-1", "Dept", "space-1", false),
				SearchScope.empty(),
				"model-1",
				budget,
				hasHistory);
	}

	private static List<EvidenceSnapshot> fallbackEvidence() {
		return List.of(new EvidenceSnapshot("e1", "fallback evidence", Map.of("rerank_fallback", true)));
	}

	private static List<EvidenceSnapshot> evidence(List<Double> scores) {
		List<EvidenceSnapshot> snapshots = new ArrayList<>();
		for (int i = 0; i < scores.size(); i++) {
			snapshots.add(new EvidenceSnapshot(
					"e" + (i + 1),
					"evidence " + (i + 1),
					Map.of("rerank_score", scores.get(i))));
		}
		return snapshots;
	}

	private static List<ParentContextBlock> parents() {
		return List.of(new ParentContextBlock(
				"parent-1", "doc-1", "policy.md", "parent content", 1, 1, 1, List.of("e1"), 1));
	}

	private record GateCase(
			String name,
			String question,
			boolean hasHistory,
			int round,
			List<Double> scores,
			boolean hasParents,
			boolean latestRoundAddedEvidence,
			EvidenceGate.Verdict expectedVerdict,
			RetrievalGapType expectedGap
	) {
		@Override
		public String toString() {
			return name;
		}
	}
}
