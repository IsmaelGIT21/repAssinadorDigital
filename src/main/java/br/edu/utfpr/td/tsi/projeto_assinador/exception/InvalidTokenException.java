package br.edu.utfpr.td.tsi.projeto_assinador.exception;

/**
 * Exception thrown when an invalid or expired token is provided.
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
