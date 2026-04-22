package net.topikachu.rag.agent;

public record AgentNote(long sequence, AgentStage stage, String kind, String text, long timestamp) {
}
