package net.topikachu.rag.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class EvidenceGate {

	private static final String RERANK_SCORE = "rerank_score";
	private static final String RERANK_FALLBACK = "rerank_fallback";

	public Assessment assess(AgentRunState state) {
		Objects.requireNonNull(state, "state must not be null");
		if (state.retrievalRound() >= 2 && latestRoundAddedNoEvidence(state)) {
			return assessment(Verdict.REFUSE, "latest retrieval round added no evidence");
		}
		if (state.evidence().isEmpty() || state.parentContexts().isEmpty()) {
			return assessment(state.retrievalRound() == 1 ? Verdict.RETRY : Verdict.REFUSE,
					"no evidence or parent context");
		}
		if (usedRerankerFallback(state.evidence()) || hasMissingScore(state.evidence())) {
			return assessment(Verdict.REVIEW, "rerank score unavailable");
		}
		return assessment(Verdict.REVIEW, "evidence ready for semantic review");
	}

	private boolean usedRerankerFallback(List<EvidenceSnapshot> evidence) {
		return evidence.stream()
				.map(EvidenceSnapshot::metadataSnapshot)
				.map(metadata -> metadata.get(RERANK_FALLBACK))
				.anyMatch(value -> Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)));
	}

	private boolean hasMissingScore(List<EvidenceSnapshot> evidence) {
		return evidence.stream().anyMatch(snapshot -> rerankScore(snapshot).isEmpty());
	}

	private boolean latestRoundAddedNoEvidence(AgentRunState state) {
		return state.attempts().stream()
				.filter(attempt -> attempt.round() == state.retrievalRound())
				.flatMap(attempt -> attempt.newEvidenceIds().stream())
				.noneMatch(id -> id != null && !id.isBlank());
	}

	private Optional<Double> rerankScore(EvidenceSnapshot snapshot) {
		Object value = snapshot.metadataSnapshot().get(RERANK_SCORE);
		if (value instanceof Number number) {
			double score = number.doubleValue();
			return Double.isFinite(score) ? Optional.of(score) : Optional.empty();
		}
		if (value instanceof String text) {
			try {
				double score = Double.parseDouble(text);
				return Double.isFinite(score) ? Optional.of(score) : Optional.empty();
			}
			catch (NumberFormatException ignored) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	private Assessment assessment(Verdict verdict, String reason) {
		return new Assessment(verdict, reason);
	}

	public enum Verdict {
		REVIEW,
		RETRY,
		REFUSE
	}

	public record Assessment(Verdict verdict, String reason) {

		public Assessment {
			Objects.requireNonNull(verdict, "verdict must not be null");
			Objects.requireNonNull(reason, "reason must not be null");
		}
	}
}
