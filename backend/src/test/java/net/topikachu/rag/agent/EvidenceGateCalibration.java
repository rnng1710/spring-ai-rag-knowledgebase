package net.topikachu.rag.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

final class EvidenceGateCalibration {

	private static final double MIN_PRECISION = 0.95;
	private static final double GAP_DISABLED = Double.POSITIVE_INFINITY;

	private EvidenceGateCalibration() {
	}

	static Optional<Thresholds> select(List<Sample> samples) {
		List<Sample> usable = samples == null ? List.of() : samples.stream()
				.filter(sample -> sample != null && sample.topScore() != null && sample.contextRecall() != null)
				.toList();
		long positives = usable.stream().filter(Sample::sufficient).count();
		if (usable.isEmpty() || positives == 0) {
			return Optional.empty();
		}

		List<Double> scoreCandidates = usable.stream()
				.flatMap(sample -> sample.scores().stream())
				.filter(Double::isFinite)
				.distinct()
				.toList();
		List<Double> gapCandidates = Stream.concat(
				Stream.of(0.0, GAP_DISABLED),
				usable.stream().map(Sample::topScoreGap).filter(Double::isFinite))
				.distinct()
				.toList();

		java.util.ArrayList<Thresholds> candidates = new java.util.ArrayList<>();
		for (double minScore : scoreCandidates) {
			for (double minGap : gapCandidates) {
				for (int minSupportingEvidence = 1; minSupportingEvidence <= 3; minSupportingEvidence++) {
					Thresholds thresholds = evaluate(
							usable,
							positives,
							minScore,
							minSupportingEvidence,
							minGap);
					if (thresholds.predictedSufficient() > 0 && thresholds.precision() >= MIN_PRECISION) {
						candidates.add(thresholds);
					}
				}
			}
		}

		return candidates.stream()
				.max(Comparator.comparingDouble(Thresholds::recall)
						.thenComparingDouble(Thresholds::precision)
						.thenComparingDouble(Thresholds::minTopScore)
						.thenComparingInt(Thresholds::minSupportingEvidence)
						.thenComparingDouble(Thresholds::minTopScoreGap));
	}

	private static Thresholds evaluate(List<Sample> samples,
									   long positives,
									   double minScore,
									   int minSupportingEvidence,
									   double minGap) {
		long predicted = 0;
		long truePositives = 0;
		for (Sample sample : samples) {
			long supporting = sample.scores().stream().filter(score -> score >= minScore).count();
			boolean gapSatisfied = Double.isFinite(minGap) && sample.topScoreGap() >= minGap;
			boolean accepted = sample.topScore() >= minScore
					&& (supporting >= minSupportingEvidence || gapSatisfied);
			if (accepted) {
				predicted++;
				if (sample.sufficient()) {
					truePositives++;
				}
			}
		}
		double precision = predicted == 0 ? 0.0 : (double) truePositives / predicted;
		double recall = (double) truePositives / positives;
		return new Thresholds(
				minScore,
				minSupportingEvidence,
				minGap,
				precision,
				recall,
				predicted,
				truePositives);
	}

	record Sample(String id,
				  List<Double> scores,
				  Double contextRecall,
				  int childCount,
				  int parentCount,
				  int documentCount) {

		Sample {
			scores = scores == null ? List.of() : scores.stream()
					.filter(Objects::nonNull)
					.sorted(Comparator.reverseOrder())
					.toList();
		}

		Double topScore() {
			return scores.isEmpty() ? null : scores.get(0);
		}

		double topScoreGap() {
			return scores.size() < 2 ? Double.POSITIVE_INFINITY : scores.get(0) - scores.get(1);
		}

		boolean sufficient() {
			return contextRecall != null && contextRecall >= 0.9;
		}
	}

	record Thresholds(double minTopScore,
					  int minSupportingEvidence,
					  double minTopScoreGap,
					  double precision,
					  double recall,
					  long predictedSufficient,
					  long truePositives) {
	}
}
