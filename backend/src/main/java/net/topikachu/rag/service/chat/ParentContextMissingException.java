package net.topikachu.rag.service.chat;

public class ParentContextMissingException extends RetrievalException {

    public ParentContextMissingException(String message) {
        super(message, null);
    }
}
