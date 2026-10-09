package net.topikachu.rag.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceAssessmentCalibrationCorpusTest {

	private static final Path CORPUS = Path.of(
			"src/test/resources/evidence-assessment/calibration-cases.jsonl");

	@Test
	void containsEightHumanLabeledCasesForEachRating() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		List<CalibrationCase> cases;
		try (var lines = Files.lines(CORPUS)) {
			cases = lines.filter(line -> !line.isBlank())
					.map(line -> readCase(objectMapper, line))
					.toList();
		}

		assertEquals(32, cases.size());
		Map<String, Long> counts = cases.stream().collect(Collectors.groupingBy(
				CalibrationCase::expectedRating,
				Collectors.counting()));
		for (AdaptiveEvidenceWorkflow.AnswerabilityRating rating
				: AdaptiveEvidenceWorkflow.AnswerabilityRating.values()) {
			assertEquals(8L, counts.getOrDefault(rating.name(), 0L));
		}
		for (CalibrationCase item : cases) {
			assertFalse(item.id().isBlank());
			assertFalse(item.question().isBlank());
			assertFalse(item.evidenceContext().isBlank());
			assertFalse(item.notes().isBlank());
		}
	}

	private CalibrationCase readCase(ObjectMapper objectMapper, String json) {
		try {
			return objectMapper.readValue(json, CalibrationCase.class);
		}
		catch (Exception error) {
			throw new IllegalArgumentException("Invalid calibration case: " + json, error);
		}
	}

	private record CalibrationCase(
			String id,
			String question,
			String evidenceContext,
			String expectedRating,
			String notes) {
	}
}
