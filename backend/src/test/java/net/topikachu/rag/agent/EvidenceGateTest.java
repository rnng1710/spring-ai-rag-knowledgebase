package net.topikachu.rag.agent;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.service.chat.ParentContextBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceGateTest {

	private static final EvidenceGate GATE = new EvidenceGate();

	@ParameterizedTest(name = "{0}")
	@MethodSource("gateCases")
	void appliesRetrievalQualityGateInFixedOrder(GateCase testCase) {
		assertEquals(testCase.expected(), GATE.assess(state(testCase)).verdict());
	}

	@Test
	void rejectsInvalidBudget() {
		assertThrows(IllegalArgumentException.class, () -> new AgentRunState.Budget(2, 4, 1));
	}

	private static Stream<GateCase> gateCases() {
		return Stream.of(
				new GateCase("empty first round retries", 1, List.of(), false, true, EvidenceGate.Verdict.RETRY),
				new GateCase("low scores reach semantic review", 1, List.of(0.4, 0.3), false, true, EvidenceGate.Verdict.REVIEW),
				new GateCase("threshold equality reviews", 1, List.of(0.5, 0.3), false, true, EvidenceGate.Verdict.REVIEW),
				new GateCase("rerank fallback reviews", 1, List.of(), true, true, EvidenceGate.Verdict.REVIEW),
				new GateCase("missing score reviews", 1, null, false, true, EvidenceGate.Verdict.REVIEW),
				new GateCase("second round without new evidence refuses", 2, List.of(0.8), false, false, EvidenceGate.Verdict.REFUSE),
				new GateCase("second round low scores still review", 2, List.of(0.4), false, true, EvidenceGate.Verdict.REVIEW),
				new GateCase("second round with new relevant evidence reviews", 2, List.of(0.8), false, true, EvidenceGate.Verdict.REVIEW));
	}

	private AgentRunState state(GateCase testCase) {
		List<EvidenceSnapshot> evidence;
		if (testCase.scores() == null) {
			evidence = List.of(new EvidenceSnapshot("e1", "evidence", Map.of()));
		}
		else if (testCase.fallback()) {
			evidence = List.of(new EvidenceSnapshot("e1", "evidence", Map.of("rerank_fallback", true)));
		}
		else {
			evidence = java.util.stream.IntStream.range(0, testCase.scores().size())
					.mapToObj(index -> new EvidenceSnapshot(
							"e" + (index + 1),
							"evidence " + (index + 1),
							Map.of("rerank_score", testCase.scores().get(index))))
					.toList();
		}
		List<ParentContextBlock> parents = evidence.isEmpty()
				? List.of()
				: List.of(new ParentContextBlock(
						"p1", "doc-1", "handbook.pdf", "parent", 1, 1, 1,
						evidence.stream().map(EvidenceSnapshot::id).toList(), 1));
		List<String> newIds = testCase.addedEvidence()
				? evidence.stream().map(EvidenceSnapshot::id).toList()
				: List.of();
		return AgentRunState.start(
					"run", "conversation", "message", "question",
					new CurrentUserContext("user", "tester", "USER", "dept", "Dept", "space", false),
					SearchScope.empty(), "model", AgentRunState.Budget.standard())
				.withRetrieval(
						testCase.round(),
						List.of(new AgentRunState.SearchAttempt(
								testCase.round(), "query", "ok", newIds, 1)),
						evidence,
						parents);
	}

	private record GateCase(
			String name,
			int round,
			List<Double> scores,
			boolean fallback,
			boolean addedEvidence,
			EvidenceGate.Verdict expected) {

		@Override
		public String toString() {
			return name;
		}
	}
}
