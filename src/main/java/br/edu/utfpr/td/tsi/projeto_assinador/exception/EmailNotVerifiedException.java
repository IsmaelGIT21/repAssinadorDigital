package br.edu.utfpr.td.tsi.projeto_assinador.exception;

/**
 * Exception thrown when a user tries to login without verifying their email.
 */
public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
