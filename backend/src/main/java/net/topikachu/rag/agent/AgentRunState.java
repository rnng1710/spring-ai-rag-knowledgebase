package net.topikachu.rag.agent;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.service.chat.ParentContextBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AgentRunState(
		String runId,
		String conversationId,
		String msgId,
		String originalQuestion,
		CurrentUserContext currentUser,
		SearchScope searchScope,
		String modelId,
		Stage stage,
		Budget budget,
		int retrievalRound,
		List<SearchAttempt> attempts,
		List<EvidenceSnapshot> evidence,
		List<ParentContextBlock> parentContexts,
		EvidenceGate.Assessment assessment,
		List<AgentNote> notes,
		boolean hasConversationContext
) {

	public AgentRunState {
		Objects.requireNonNull(runId, "runId must not be null");
		Objects.requireNonNull(conversationId, "conversationId must not be null");
		Objects.requireNonNull(msgId, "msgId must not be null");
		Objects.requireNonNull(originalQuestion, "originalQuestion must not be null");
		Objects.requireNonNull(currentUser, "currentUser must not be null");
		searchScope = searchScope == null ? SearchScope.empty() : searchScope;
		Objects.requireNonNull(modelId, "modelId must not be null");
		Objects.requireNonNull(stage, "stage must not be null");
		Objects.requireNonNull(budget, "budget must not be null");
		if (retrievalRound < 0) {
			throw new IllegalArgumentException("retrievalRound must not be negative");
		}
		attempts = attempts == null ? List.of() : List.copyOf(attempts);
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
		parentContexts = parentContexts == null ? List.of() : List.copyOf(parentContexts);
		notes = notes == null ? List.of() : List.copyOf(notes);
	}

	public static AgentRunState start(String runId,
								  String conversationId,
								  String msgId,
								  String originalQuestion,
								  CurrentUserContext currentUser,
								  SearchScope searchScope,
								  String modelId,
								  Budget budget,
								  boolean hasConversationContext) {
		return new AgentRunState(
				runId,
				conversationId,
				msgId,
				originalQuestion,
				currentUser,
				searchScope,
				modelId,
				Stage.PLAN,
				budget,
				0,
				List.of(),
				List.of(),
				List.of(),
				null,
				List.of(),
				hasConversationContext);
	}

	public AgentRunState transition(Stage nextStage, String kind, String text) {
		return transition(nextStage, toWireStage(nextStage), kind, text);
	}

	public AgentRunState transition(Stage nextStage, AgentStage wireStage, String kind, String text) {
		Objects.requireNonNull(nextStage, "nextStage must not be null");
		Objects.requireNonNull(wireStage, "wireStage must not be null");
		List<AgentNote> nextNotes = new ArrayList<>(notes);
		nextNotes.add(new AgentNote(
				notes.size() + 1L,
				wireStage,
				kind,
				text,
				System.currentTimeMillis()));
		return copy(nextStage, retrievalRound, attempts, evidence, parentContexts, assessment, nextNotes);
	}

	public AgentRunState addNote(AgentStage wireStage, String kind, String text) {
		return transition(stage, wireStage, kind, text);
	}

	public AgentRunState withRetrieval(int round,
								   List<SearchAttempt> roundAttempts,
								   List<EvidenceSnapshot> nextEvidence,
								   List<ParentContextBlock> nextParentContexts) {
		if (round <= 0 || round > budget.maxRetrievalRounds()) {
			throw new IllegalArgumentException("round must be within retrieval budget");
		}
		List<SearchAttempt> additions = roundAttempts == null ? List.of() : List.copyOf(roundAttempts);
		if (additions.stream().anyMatch(attempt -> attempt.round() != round)) {
			throw new IllegalArgumentException("all search attempts must belong to the supplied round");
		}
		List<SearchAttempt> nextAttempts = new ArrayList<>(attempts);
		nextAttempts.addAll(additions);
		return copy(stage, round, nextAttempts, nextEvidence, nextParentContexts, assessment, notes);
	}

	public AgentRunState withAssessment(EvidenceGate.Assessment nextAssessment) {
		return copy(stage, retrievalRound, attempts, evidence, parentContexts,
				Objects.requireNonNull(nextAssessment, "assessment must not be null"), notes);
	}

	private AgentRunState copy(Stage nextStage,
							   int nextRound,
							   List<SearchAttempt> nextAttempts,
							   List<EvidenceSnapshot> nextEvidence,
							   List<ParentContextBlock> nextParentContexts,
							   EvidenceGate.Assessment nextAssessment,
							   List<AgentNote> nextNotes) {
		return new AgentRunState(
				runId,
				conversationId,
				msgId,
				originalQuestion,
				currentUser,
				searchScope,
				modelId,
				nextStage,
				budget,
				nextRound,
				nextAttempts,
				nextEvidence,
				nextParentContexts,
				nextAssessment,
				nextNotes,
				hasConversationContext);
	}

	private static AgentStage toWireStage(Stage stage) {
		return switch (stage) {
			case PLAN -> AgentStage.PLANNING;
			case RETRIEVE -> AgentStage.RETRIEVING;
			case ASSESS, VERIFY -> AgentStage.REVIEWING;
			case COMPOSE -> AgentStage.DRAFTING;
			case CLARIFY -> AgentStage.FOLLOWUP;
			case REFUSE -> AgentStage.GENERATING_FINAL;
			case COMPLETE -> AgentStage.DONE;
		};
	}

	public enum Stage {
		PLAN,
		RETRIEVE,
		ASSESS,
		COMPOSE,
		CLARIFY,
		REFUSE,
		VERIFY,
		COMPLETE
	}

	public record Budget(int maxRetrievalRounds, int maxSubqueries, int maxAnswerRepairs) {

		public Budget {
			if (maxRetrievalRounds != 2 || maxSubqueries != 4 || maxAnswerRepairs != 1) {
				throw new IllegalArgumentException("Agent budget is fixed at 2 retrieval rounds, 4 queries and 1 repair");
			}
		}

		public static Budget standard() {
			return new Budget(2, 4, 1);
		}
	}

	public record SearchAttempt(
			int round,
			String query,
			String status,
			List<String> newEvidenceIds,
			long latencyMs
	) {

		public SearchAttempt {
			if (round <= 0) {
				throw new IllegalArgumentException("round must be positive");
			}
			Objects.requireNonNull(query, "query must not be null");
			Objects.requireNonNull(status, "status must not be null");
			newEvidenceIds = newEvidenceIds == null ? List.of() : List.copyOf(newEvidenceIds);
			if (latencyMs < 0) {
				throw new IllegalArgumentException("latencyMs must not be negative");
			}
		}
	}
}
