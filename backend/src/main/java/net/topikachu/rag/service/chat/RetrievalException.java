package net.topikachu.rag.service.chat;

public class RetrievalException extends RuntimeException {

    private final String userMessage;

    public RetrievalException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
