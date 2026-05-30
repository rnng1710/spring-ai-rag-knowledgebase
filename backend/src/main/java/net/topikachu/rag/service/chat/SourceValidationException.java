package net.topikachu.rag.service.chat;

public class SourceValidationException extends RuntimeException {

    private final String userMessage;
    private final String reason;

    public SourceValidationException(String userMessage) {
        this(userMessage, "unknown");
    }

    public SourceValidationException(String userMessage, String reason) {
        super(userMessage);
        this.userMessage = userMessage;
        this.reason = reason;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getReason() {
        return reason;
    }
}
