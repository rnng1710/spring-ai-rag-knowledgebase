package net.topikachu.rag.agent;

public enum AgentStage {
    IDLE("idle"),
    PLANNING("planning"),
    QUERY_REWRITING("query_rewriting"),
    RETRIEVING("retrieving"),
    DRAFTING("drafting"),
    REVIEWING("reviewing"),
    REVISING("revising"),
    GENERATING_FINAL("generating_final"),
    FOLLOWUP("followup"),
    DONE("done"),
    ERROR("error");

    private final String wireValue;

    AgentStage(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
