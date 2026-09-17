package br.edu.utfpr.td.tsi.projeto_assinador.exception;

/**
 * Exception thrown when an invalid 2FA code is provided.
 */
public class Invalid2FACodeException extends RuntimeException {
    public Invalid2FACodeException(String message) {
        super(message);
    }
}
