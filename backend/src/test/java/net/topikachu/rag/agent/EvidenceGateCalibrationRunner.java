package net.topikachu.rag.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.autoevaluation.dto.RagasEvaluationRequest;
import net.topikachu.rag.autoevaluation.dto.RagasEvaluationResponse;
import net.topikachu.rag.autoevaluation.service.RagasEvaluationClient;
import net.topikachu.rag.service.chat.ParentContextBlock;
import net.topikachu.rag.service.chat.RetrievalPipeline;
import net.topikachu.rag.service.chat.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("ollama-openai")
@EnabledIfSystemProperty(named = "rag.agent.calibration.enabled", matches = "true")
class EvidenceGateCalibrationRunner {

	private final RetrievalPipeline retrievalPipeline;
	private final RagasEvaluationClient ragasEvaluationClient;
	private final ObjectMapper objectMapper;

	@Value("${rag.agent.calibration.questions-file:classpath:evidence-gate/calibration-questions.csv}")
	private Resource questionsFile;

	@Value("${rag.agent.calibration.output-file:target/evidence-gate-calibration.json}")
	private String outputFile;

	@Value("${rag.retrieval.hybrid-topk:120}")
	private int hybridTopK;

	@Value("${rag.agent.max-evidence-count:12}")
	private int evidenceTopK;

	@Autowired
	EvidenceGateCalibrationRunner(RetrievalPipeline retrievalPipeline,
								  RagasEvaluationClient ragasEvaluationClient,
								  ObjectMapper objectMapper) {
		this.retrievalPipeline = retrievalPipeline;
		this.ragasEvaluationClient = ragasEvaluationClient;
		this.objectMapper = objectMapper;
	}

	@Test
	void calibrateEvidenceGate() throws Exception {
		List<Question> questions = readQuestions(questionsFile);
		requireExactly52UniqueQuestions(questions.stream().map(Question::question).toList());
		assertTrue(questions.stream().noneMatch(question -> question.reference().isBlank()),
				"Every calibration question must have a reference answer");

		List<Retrieved> retrieved = Flux.fromIterable(questions)
				.index()
				.concatMap(indexed -> retrievalPipeline.retrieveWithParentContexts(
							indexed.getT2().question(),
							null,
							SearchScope.empty(),
							hybridTopK,
							evidenceTopK,
							Map.of("chat.mode", "agent_calibration"))
						.map(result -> toRetrieved(indexed.getT1(), indexed.getT2(), result)))
				.collectList()
				.block(Duration.ofMinutes(20));
		assertNotNull(retrieved, "Retrieval did not complete");
		assertEquals(questions.size(), retrieved.size(), "Every calibration question must be retrieved");

		RagasEvaluationRequest request = new RagasEvaluationRequest(retrieved.stream()
				.map(sample -> new RagasEvaluationRequest.RagasEvaluationItem(
						sample.id(),
						sample.question(),
						sample.reference(),
						sample.contexts(),
						sample.reference()))
				.toList());
		RagasEvaluationResponse response = ragasEvaluationClient.evaluate(request)
				.block(Duration.ofMinutes(10));
		Map<String, Double> recalls = requireCompleteRecalls(
				retrieved.stream().map(Retrieved::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
				response);

		List<EvidenceGateCalibration.Sample> samples = retrieved.stream()
				.map(item -> new EvidenceGateCalibration.Sample(
						item.id(),
						item.scores(),
						recalls.get(item.id()),
						item.childCount(),
						item.parentCount(),
						item.documentCount()))
				.toList();
		EvidenceGateCalibration.Thresholds thresholds = EvidenceGateCalibration.select(samples)
				.orElseThrow(() -> new AssertionError("No Evidence Gate rule reached 95% SUFFICIENT precision"));
		Map<String, Retrieved> retrievedById = retrieved.stream().collect(java.util.stream.Collectors.toMap(
				Retrieved::id,
				item -> item,
				(left, right) -> left,
				LinkedHashMap::new));

		Path output = Path.of(outputFile);
		if (output.getParent() != null) {
			Files.createDirectories(output.getParent());
		}
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), new Report(
				thresholds,
				"rag.agent.evidence.min-rerank-score=" + thresholds.minTopScore(),
				"rag.agent.evidence.min-supporting-evidence=" + thresholds.minSupportingEvidence(),
				"rag.agent.evidence.min-top-score-gap=" + thresholds.minTopScoreGap(),
				samples.stream()
						.map(sample -> SampleReport.from(sample, retrievedById.get(sample.id()), thresholds))
						.toList()));
	}

	private Retrieved toRetrieved(long index, Question question, RetrievalResult result) {
		assertNotNull(result, "Missing retrieval result for question " + index);
		List<Document> children = result.childCandidates();
		List<ParentContextBlock> parents = result.parentContexts() == null
				? List.of()
				: result.parentContexts();
		List<Double> scores = requireValidRerankScores(Long.toString(index), children);
		int documentCount = (int) children.stream()
				.map(document -> document.getMetadata().get("doc_uuid"))
				.filter(java.util.Objects::nonNull)
				.map(Object::toString)
				.distinct()
				.count();
		return new Retrieved(
				Long.toString(index),
				question.question(),
				question.reference(),
				parents.stream().map(ParentContextBlock::content).toList(),
				scores,
				children.size(),
				parents.size(),
				documentCount);
	}

	static void requireExactly52UniqueQuestions(List<String> questions) {
		assertNotNull(questions, "Calibration questions are required");
		assertEquals(52, questions.size(), "Calibration must replay the committed 52-question dataset");
		assertTrue(questions.stream().noneMatch(question -> question == null || question.isBlank()),
				"Calibration questions must not be blank");
		assertEquals(52, questions.stream().map(String::strip).distinct().count(),
				"Calibration questions must be unique");
	}

	static List<Double> requireValidRerankScores(String id, List<Document> children) {
		assertNotNull(children, "Missing child candidates for question " + id);
		assertFalse(children.isEmpty(), "Question " + id + " produced no child candidates");
		return children.stream().map(document -> {
			assertNotNull(document, "Question " + id + " produced a null child candidate");
			Object rawScore = document.getMetadata().get("rerank_score");
			assertTrue(rawScore instanceof Number, "Question " + id + " has a missing/non-numeric rerank score");
			double score = ((Number) rawScore).doubleValue();
			assertTrue(Double.isFinite(score), "Question " + id + " has a non-finite rerank score");
			return score;
		}).toList();
	}

	static Map<String, Double> requireCompleteRecalls(Set<String> expectedIds, RagasEvaluationResponse response) {
		assertNotNull(response, "RAGAS evaluation did not complete");
		assertNotNull(response.items(), "RAGAS returned no items");
		assertEquals(expectedIds.size(), response.items().size(), "RAGAS must return exactly one item per question");
		Map<String, Double> recalls = new LinkedHashMap<>();
		for (RagasEvaluationResponse.RagasEvaluationResult item : response.items()) {
			assertNotNull(item, "RAGAS returned a null item");
			assertTrue(expectedIds.contains(item.evaluationId()), "RAGAS returned an unknown evaluation id");
			assertTrue(item.error() == null || item.error().isBlank(),
					"RAGAS item " + item.evaluationId() + " failed: " + item.error());
			assertNotNull(item.contextRecall(), "RAGAS item " + item.evaluationId() + " has no context recall");
			assertTrue(Double.isFinite(item.contextRecall()),
					"RAGAS item " + item.evaluationId() + " has non-finite context recall");
			assertTrue(recalls.putIfAbsent(item.evaluationId(), item.contextRecall()) == null,
					"RAGAS returned duplicate evaluation id " + item.evaluationId());
		}
		assertEquals(expectedIds, recalls.keySet(), "RAGAS omitted one or more calibration questions");
		return recalls;
	}

	private List<Question> readQuestions(Resource resource) throws Exception {
		List<Question> result = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			boolean header = true;
			while ((line = reader.readLine()) != null) {
				if (header) {
					header = false;
					continue;
				}
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				int separator = trimmed.indexOf("\",\"");
				if (separator < 0) {
					continue;
				}
				String question = unquote(trimmed.substring(0, separator + 1));
				String reference = unquote(trimmed.substring(separator + 2));
				result.add(new Question(question, reference));
			}
		}
		return result;
	}

	private String unquote(String value) {
		String trimmed = value.trim();
		return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
				? trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"")
				: trimmed;
	}

	private record Question(String question, String reference) {
	}

	private record Retrieved(String id,
						 String question,
						 String reference,
						 List<String> contexts,
						 List<Double> scores,
						 int childCount,
						 int parentCount,
						 int documentCount) {
	}

	private record Report(EvidenceGateCalibration.Thresholds thresholds,
						  String minScoreProperty,
						  String minSupportingEvidenceProperty,
						  String minGapProperty,
						  List<SampleReport> samples) {
	}

	private record SampleReport(String id,
							String question,
							String reference,
							List<Double> rerankScores,
							Double topScore,
							Double secondScore,
							double topSecondGap,
							long supportCount,
							double contextRecall,
							int childCount,
							int parentCount,
							int documentCount,
							boolean sufficient) {

		private static SampleReport from(EvidenceGateCalibration.Sample sample,
									 Retrieved retrieved,
									 EvidenceGateCalibration.Thresholds thresholds) {
			Double secondScore = sample.scores().size() < 2 ? null : sample.scores().get(1);
			long supportCount = sample.scores().stream()
					.filter(score -> score >= thresholds.minTopScore())
					.count();
			return new SampleReport(
					sample.id(),
					retrieved.question(),
					retrieved.reference(),
					sample.scores(),
					sample.topScore(),
					secondScore,
					sample.topScoreGap(),
					supportCount,
					sample.contextRecall(),
					sample.childCount(),
					sample.parentCount(),
					sample.documentCount(),
					sample.sufficient());
		}
	}
}
