package br.edu.utfpr.td.tsi.projeto_assinador.exception;

/**
 * Exception thrown when two-factor authentication is required.
 */
public class TwoFactorRequiredException extends RuntimeException {
    public TwoFactorRequiredException(String message) {
        super(message);
    }
}
