package net.topikachu.rag.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class EvidenceGate {

	private static final String RERANK_SCORE = "rerank_score";
	private static final String RERANK_FALLBACK = "rerank_fallback";
	private static final Pattern UNRESOLVED_REFERENCE = Pattern.compile(
			"^(?:它|这个|那个|上述(?:内容|规定)?|前者|后者|该(?:规定|情况|事项)?)(?:是|有|能|会|该|应|怎么|如何|为什么|是否|可以|呢|吗|\\?|？)");
	private static final Pattern RELATIVE_TIME = Pattern.compile(
			"今年|去年|明年|目前|现在|当前|近期|最近|当时|届时|本学期|上学期|下学期");
	private static final Pattern EXPLICIT_DATE = Pattern.compile(
			"(?:19|20)\\d{2}(?:年)?|\\d{4}[-/.]\\d{1,2}|\\d{1,2}月(?:\\d{1,2}日)?");
	private static final Pattern VAGUE_PROCEDURE = Pattern.compile(
			"怎么办|怎么处理|如何处理|下一步(?:怎么办|做什么|怎么走)?|后续(?:怎么办|怎么处理|流程是什么)|走(?:哪|什么)个?流程|按什么程序|程序怎么走");

	private final Double minRerankScore;
	private final Integer minSupportingEvidence;
	private final Double minTopScoreGap;

	public EvidenceGate(
			@Value("${rag.agent.evidence.min-rerank-score:#{null}}") Double minRerankScore,
			@Value("${rag.agent.evidence.min-supporting-evidence:#{null}}") Integer minSupportingEvidence,
			@Value("${rag.agent.evidence.min-top-score-gap:#{null}}") Double minTopScoreGap) {
		boolean noneConfigured = minRerankScore == null && minSupportingEvidence == null && minTopScoreGap == null;
		boolean allConfigured = minRerankScore != null && minSupportingEvidence != null && minTopScoreGap != null;
		if (!noneConfigured && !allConfigured) {
			throw new IllegalArgumentException("Evidence Gate calibration requires T, N and G together");
		}
		if (allConfigured && (minSupportingEvidence <= 0 || minSupportingEvidence > 3)) {
			throw new IllegalArgumentException("minSupportingEvidence must be between 1 and 3");
		}
		if (allConfigured && (!Double.isFinite(minRerankScore)
				|| Double.isNaN(minTopScoreGap)
				|| minTopScoreGap < 0)) {
			throw new IllegalArgumentException("Evidence Gate score and gap calibration are invalid");
		}
		this.minRerankScore = minRerankScore;
		this.minSupportingEvidence = minSupportingEvidence;
		this.minTopScoreGap = minTopScoreGap;
	}

	public void requireCalibration() {
		if (minRerankScore == null) {
			throw new IllegalStateException(
					"Agent Evidence Gate is not calibrated; run EvidenceGateCalibrationRunner and configure T/N/G");
		}
	}

	public Assessment assess(AgentRunState state) {
		requireCalibration();
		Objects.requireNonNull(state, "state must not be null");
		if (state.evidence().isEmpty() || state.parentContexts().isEmpty()) {
			return assessment(Verdict.EMPTY, "no evidence or parent context");
		}
		if (state.retrievalRound() >= 2 && latestRoundAddedNoEvidence(state)) {
			return assessment(Verdict.NOT_IMPROVABLE, "latest retrieval round added no evidence");
		}
		if (usedRerankerFallback(state.evidence())) {
			return assessment(Verdict.SUFFICIENT, "reranker fallback");
		}

		List<Double> scores = state.evidence().stream()
				.map(this::rerankScore)
				.flatMap(Optional::stream)
				.sorted(Comparator.reverseOrder())
				.toList();
		if (!scores.isEmpty() && meetsEvidenceThreshold(scores)) {
			return assessment(Verdict.SUFFICIENT, "evidence threshold met");
		}
		if (state.retrievalRound() >= state.budget().maxRetrievalRounds()) {
			return assessment(Verdict.NOT_IMPROVABLE, "retrieval budget exhausted");
		}

		RetrievalGapType ambiguity = ambiguity(state.originalQuestion(), state.hasConversationContext());
		if (ambiguity != null) {
			return new Assessment(Verdict.AMBIGUOUS, ambiguityReason(ambiguity), ambiguity);
		}
		return assessment(Verdict.WEAK, "evidence below threshold");
	}

	private boolean usedRerankerFallback(List<EvidenceSnapshot> evidence) {
		return evidence.stream()
				.map(EvidenceSnapshot::metadataSnapshot)
				.map(metadata -> metadata.get(RERANK_FALLBACK))
				.anyMatch(value -> Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)));
	}

	private boolean latestRoundAddedNoEvidence(AgentRunState state) {
		return state.attempts().stream()
				.filter(attempt -> attempt.round() == state.retrievalRound())
				.flatMap(attempt -> attempt.newEvidenceIds().stream())
				.noneMatch(id -> id != null && !id.isBlank());
	}

	private boolean meetsEvidenceThreshold(List<Double> scores) {
		double top = scores.get(0);
		if (top < minRerankScore) {
			return false;
		}
		long supportCount = scores.stream().filter(score -> score >= minRerankScore).count();
		double gap = scores.size() == 1 ? Double.POSITIVE_INFINITY : top - scores.get(1);
		return supportCount >= minSupportingEvidence
				|| (Double.isFinite(minTopScoreGap) && gap >= minTopScoreGap);
	}

	private Optional<Double> rerankScore(EvidenceSnapshot snapshot) {
		Object value = snapshot.metadataSnapshot().get(RERANK_SCORE);
		if (value instanceof Number number) {
			return Optional.of(number.doubleValue());
		}
		if (value instanceof String text) {
			try {
				return Optional.of(Double.parseDouble(text));
			}
			catch (NumberFormatException ignored) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	private RetrievalGapType ambiguity(String question, boolean hasConversationContext) {
		String normalized = question == null ? "" : question.strip().toLowerCase(Locale.ROOT);
		String meaningful = normalized.replaceAll("[\\p{P}\\p{S}\\s]", "");
		if (!hasConversationContext
				&& (meaningful.codePointCount(0, meaningful.length()) < 6
				|| UNRESOLVED_REFERENCE.matcher(normalized).find())) {
			return RetrievalGapType.AMBIGUOUS_SUBJECT;
		}
		if (RELATIVE_TIME.matcher(normalized).find() && !EXPLICIT_DATE.matcher(normalized).find()) {
			return RetrievalGapType.MISSING_TIME;
		}
		if (VAGUE_PROCEDURE.matcher(normalized).find()) {
			return RetrievalGapType.MISSING_PROCEDURE_BRANCH;
		}
		return null;
	}

	private String ambiguityReason(RetrievalGapType gapType) {
		return switch (gapType) {
			case AMBIGUOUS_SUBJECT -> "ambiguous subject";
			case MISSING_TIME -> "missing explicit time";
			case MISSING_PROCEDURE_BRANCH -> "missing procedure branch";
			default -> "ambiguous question";
		};
	}

	private Assessment assessment(Verdict verdict, String reason) {
		return new Assessment(verdict, reason, RetrievalGapType.NOT_IMPROVABLE);
	}

	public enum Verdict {
		SUFFICIENT,
		WEAK,
		AMBIGUOUS,
		EMPTY,
		NOT_IMPROVABLE
	}

	public record Assessment(Verdict verdict, String reason, RetrievalGapType gapType) {

		public Assessment {
			Objects.requireNonNull(verdict, "verdict must not be null");
			Objects.requireNonNull(reason, "reason must not be null");
			gapType = gapType == null ? RetrievalGapType.NOT_IMPROVABLE : gapType;
		}
	}
}
