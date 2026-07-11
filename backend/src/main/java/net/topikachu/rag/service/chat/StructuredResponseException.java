package net.topikachu.rag.service.chat;

public final class StructuredResponseException extends IllegalArgumentException {

	public StructuredResponseException(String message) {
		super(message);
	}

	public StructuredResponseException(String message, Throwable cause) {
		super(message, cause);
	}
}
