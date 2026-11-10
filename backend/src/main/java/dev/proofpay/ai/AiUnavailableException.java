package dev.proofpay.ai;

/** The LLM provider could not be reached or is not configured. Nothing was changed; safe to retry. */
public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException(String message) { super(message); }
    public AiUnavailableException(String message, Throwable cause) { super(message, cause); }
}
