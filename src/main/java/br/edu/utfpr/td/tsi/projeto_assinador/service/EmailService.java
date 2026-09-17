package br.edu.utfpr.td.tsi.projeto_assinador.service;

/**
 * Service interface for email operations.
 * Handles sending verification emails and other email notifications.
 */
public interface EmailService {

    /**
     * Send an email verification message to the user.
     * 
     * @param toEmail the recipient email address
     * @param userName the name of the user
     * @param verificationToken the verification token
     */
    void sendVerificationEmail(String toEmail, String userName, String verificationToken);

    void sendSignatureInvitation(String toEmail, String signerName, String documentTitle,
                                 String ownerName, String signingToken);

    void sendSignatureReminder(String toEmail, String signerName, String documentTitle,
                                String ownerName, String signingToken);

    void sendDocumentCompleted(String toEmail, String recipientName, String documentTitle,
                                String documentId);

    void sendSignatureRejected(String ownerEmail, String ownerName, String documentTitle,
                                String signerName, String rejectionReason);

    void sendSignatureProgress(String ownerEmail, String ownerName, String documentTitle,
                                int signedCount, int totalCount);

    void sendAlreadyRegisteredNotice(String toEmail, String userName, String resetToken);

    void sendPasswordResetEmail(String toEmail, String userName, String resetToken);
}
