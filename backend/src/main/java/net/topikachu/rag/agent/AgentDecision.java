package net.topikachu.rag.agent;

import java.util.List;

public record AgentDecision(String action, String query, List<String> tags, String followup) {
}
