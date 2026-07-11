package net.topikachu.rag.agent;

import net.topikachu.rag.autoevaluation.dto.RagasEvaluationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceGateCalibrationTest {

	@Test
	void choosesHighestRecallRuleThatKeepsPrecisionAtLeastNinetyFivePercent() {
		List<EvidenceGateCalibration.Sample> samples = List.of(
				new EvidenceGateCalibration.Sample("strong-1", List.of(8.0, 7.5), 1.0, 2, 1, 1),
				new EvidenceGateCalibration.Sample("strong-2", List.of(7.0, 6.5), 0.95, 2, 1, 1),
				new EvidenceGateCalibration.Sample("weak", List.of(1.0, 0.5), 0.2, 2, 1, 1));

		EvidenceGateCalibration.Thresholds thresholds = EvidenceGateCalibration.select(samples).orElseThrow();

		assertEquals(1.0, thresholds.precision());
		assertEquals(1.0, thresholds.recall());
		assertTrue(thresholds.minTopScore() > 1.0);
	}

	@Test
	void refusesToInventThresholdsWithoutPositiveLabels() {
		assertTrue(EvidenceGateCalibration.select(List.of(
				new EvidenceGateCalibration.Sample("weak", List.of(1.0), 0.0, 1, 1, 1))).isEmpty());
	}

	@Test
	void usesLowerRankedScoreBoundariesAndCanDisableGapAcceptance() {
		List<EvidenceGateCalibration.Sample> samples = List.of(
				new EvidenceGateCalibration.Sample("strong-1", List.of(10.0, 6.0), 1.0, 2, 1, 1),
				new EvidenceGateCalibration.Sample("strong-2", List.of(9.0, 6.0), 0.95, 2, 1, 1),
				new EvidenceGateCalibration.Sample("weak-1", List.of(10.0, 5.0), 0.2, 2, 1, 1),
				new EvidenceGateCalibration.Sample("weak-2", List.of(9.0, 5.0), 0.1, 2, 1, 1));

		EvidenceGateCalibration.Thresholds thresholds = EvidenceGateCalibration.select(samples).orElseThrow();

		assertEquals(6.0, thresholds.minTopScore());
		assertEquals(2, thresholds.minSupportingEvidence());
		assertEquals(Double.POSITIVE_INFINITY, thresholds.minTopScoreGap());
		assertEquals(1.0, thresholds.precision());
		assertEquals(1.0, thresholds.recall());
	}

	@Test
	void rejectsIncompleteRagasCalibrationResults() {
		RagasEvaluationResponse response = new RagasEvaluationResponse(List.of(
				new RagasEvaluationResponse.RagasEvaluationResult(
						"q1", null, null, null, null, null, null, null)));

		assertThrows(AssertionError.class,
				() -> EvidenceGateCalibrationRunner.requireCompleteRecalls(Set.of("q1"), response));
	}
}
