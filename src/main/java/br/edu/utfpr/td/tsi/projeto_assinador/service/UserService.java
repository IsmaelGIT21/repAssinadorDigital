package br.edu.utfpr.td.tsi.projeto_assinador.service;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserLoginDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserRegistrationDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserResponseDTO;

/**
 * Service interface for user management operations.
 * Handles user registration, authentication, and CRUD operations.
 */
public interface UserService {

    /**
     * Register a new user in the system.
     * 
     * @param registrationDTO the registration data
     * @return the created user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserAlreadyExistsException if email or CPF already exists
     */
    UserResponseDTO registerUser(UserRegistrationDTO registrationDTO);

    /**
     * Authenticate a user with email and password.
     * 
     * @param loginDTO the login credentials
     * @return the authenticated user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.InvalidCredentialsException if credentials are invalid
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     */
    UserResponseDTO authenticateUser(UserLoginDTO loginDTO);

    /**
     * Verify user email with token.
     * 
     * @param token the verification token
     * @return the updated user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.InvalidTokenException if token is invalid or expired
     */
    UserResponseDTO verifyEmailToken(String token);

    /**
     * Set password during email verification and complete the verification.
     * 
     * @param token the verification token
     * @param password the password to set
     * @return the updated user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.InvalidTokenException if token is invalid or expired
     */
    UserResponseDTO setPasswordAndVerifyEmail(String token, String password);

    /**
     * Complete email verification with CNH identity data and password.
     * Sets name, CPF, and password on the user, then marks email as verified.
     *
     * @param token the verification token
     * @param name the user's full name (extracted from CNH)
     * @param cpf the user's CPF (extracted from CNH)
     * @param password the password to set
     * @return the updated user as a response DTO
     */
    UserResponseDTO completeVerificationWithCnh(String token, String name, String cpf, String password);

    /**
     * Generate 2FA secret for a user.
     * 
     * @param userId the user ID
     * @return the 2FA setup information (secret and QR code)
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     */
    br.edu.utfpr.td.tsi.projeto_assinador.dto.TwoFactorSetupDTO generate2FASecret(String userId);

    /**
     * Enable 2FA for a user after validating the code.
     * 
     * @param userId the user ID
     * @param code the 6-digit TOTP code
     * @return the updated user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.Invalid2FACodeException if code is invalid
     */
    UserResponseDTO enable2FA(String userId, int code);

    /**
     * Validate 2FA code for a user.
     * 
     * @param userId the user ID
     * @param code the 6-digit TOTP code
     * @return true if code is valid, false otherwise
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     */
    boolean validate2FA(String userId, int code);

    /**
     * Update user profile name.
     *
     * @param userId the user ID
     * @param name the new name
     * @return the updated user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     */
    UserResponseDTO updateUserName(String userId, String name);

    /**
     * Update user profile (name, organization, job title, city, state).
     * CPF is immutable and cannot be changed through this method.
     *
     * @param userId the user ID
     * @param profileUpdateDTO the profile data to update
     * @return the updated user as a response DTO
     */
    UserResponseDTO updateProfile(String userId, br.edu.utfpr.td.tsi.projeto_assinador.dto.ProfileUpdateDTO profileUpdateDTO);

    /**
     * Disable 2FA for a user.
     * 
     * @param userId the user ID
     * @return the updated user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     */
    UserResponseDTO disable2FA(String userId);

    /**
     * Set the preference to hide the 2FA modal.
     * 
     * @param userId the user ID
     * @param hide whether to hide the modal
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     */
    void setHideTwoFactorModal(String userId, boolean hide);

    /**
     * Get user by ID.
     * 
     * @param userId the user ID
     * @return the user as a response DTO
     * @throws br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException if user is not found
     */
    UserResponseDTO getUserById(String userId);

    void requestPasswordReset(String email);

    void resetPassword(String token, String newPassword);

    /**
     * Check if an email belongs to an existing verified user.
     *
     * @param email the email to check
     * @return true if a verified user exists for this email
     */
    boolean isEmailVerified(String email);

    /**
     * Get or create a verification token for a signer email. Used when a signer
     * receives a signing link but does not yet have a verified account — since
     * the signer has access to the signing email, we skip a separate email
     * verification step and let them set password + CNH directly.
     *
     * @param email the signer email
     * @return an active verification token, or empty if the user already has a verified account
     */
    java.util.Optional<String> getOrCreateSignerVerificationToken(String email);
}
