package net.topikachu.rag.agent;

public record AgentNote(AgentStage stage, String kind, String text, long timestamp) {
}
