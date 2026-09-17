package br.edu.utfpr.td.tsi.projeto_assinador.service;

/**
 * Service interface for Two-Factor Authentication operations.
 * Handles TOTP generation, QR code creation, and code validation.
 */
public interface TwoFactorAuthService {

    /**
     * Generate a new secret key for TOTP 2FA.
     * 
     * @return the generated secret key
     */
    String generateSecretKey();

    /**
     * Generate QR code image as Base64 string.
     * 
     * @param userEmail the user's email
     * @param secret the TOTP secret key
     * @return Base64 encoded QR code image
     */
    String generateQRCodeImageBase64(String userEmail, String secret);

    /**
     * Validate a TOTP code against a secret.
     * 
     * @param secret the TOTP secret key
     * @param code the 6-digit code to validate
     * @return true if code is valid, false otherwise
     */
    boolean validateCode(String secret, int code);
}
