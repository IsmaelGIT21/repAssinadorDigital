package br.edu.utfpr.td.tsi.projeto_assinador.service.impl;

import br.edu.utfpr.td.tsi.projeto_assinador.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Implementation of EmailService.
 * Handles sending emails for verification and notifications.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Override
    public void sendVerificationEmail(String toEmail, String userName, String verificationToken) {
        try {
            String verificationUrl = baseUrl + "/verify-email?token=" + verificationToken;
            
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("Verificação de Email - Assinador Digital");
            message.setText(String.format(
                "Olá %s,\n\n" +
                "Obrigado por se registrar no Assinador Digital!\n\n" +
                "Para completar seu cadastro, clique no link abaixo para verificar seu email e definir sua senha:\n\n" +
                "%s\n\n" +
                "Este link expirará em 24 horas.\n\n" +
                "Se você não se cadastrou no Assinador Digital, por favor ignore este email.\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                userName, verificationUrl
            ));

            mailSender.send(message);
            log.info("Email de verificação enviado para: {}", toEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar email de verificação para {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Erro ao enviar email de verificação", e);
        }
    }

    @Override
    public void sendSignatureInvitation(String toEmail, String signerName, String documentTitle,
                                         String ownerName, String signingToken) {
        try {
            String signingUrl = baseUrl + "/sign?token=" + signingToken;

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("Solicitação de Assinatura - " + documentTitle);
            message.setText(String.format(
                "Olá %s,\n\n" +
                "%s solicita sua assinatura no documento \"%s\".\n\n" +
                "Para assinar o documento, clique no link abaixo:\n\n" +
                "%s\n\n" +
                "Caso não possua uma conta, será necessário se cadastrar antes de assinar.\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                signerName, ownerName, documentTitle, signingUrl
            ));

            mailSender.send(message);
            log.info("Email de convite para assinatura enviado para: {}", toEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar convite de assinatura para {}: {}", toEmail, e.getMessage());
        }
    }

    @Override
    public void sendSignatureReminder(String toEmail, String signerName, String documentTitle,
                                       String ownerName, String signingToken) {
        try {
            String signingUrl = baseUrl + "/sign?token=" + signingToken;

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("Lembrete: Assinatura Pendente - " + documentTitle);
            message.setText(String.format(
                "Olá %s,\n\n" +
                "Este é um lembrete de que %s aguarda sua assinatura no documento \"%s\".\n\n" +
                "Para assinar o documento, clique no link abaixo:\n\n" +
                "%s\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                signerName, ownerName, documentTitle, signingUrl
            ));

            mailSender.send(message);
            log.info("Lembrete de assinatura enviado para: {}", toEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar lembrete para {}: {}", toEmail, e.getMessage());
        }
    }

    @Override
    public void sendDocumentCompleted(String toEmail, String recipientName, String documentTitle,
                                       String documentId) {
        try {
            String documentUrl = baseUrl + "/documents/" + documentId;

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("Documento Assinado - " + documentTitle);
            message.setText(String.format(
                "Olá %s,\n\n" +
                "Todas as assinaturas do documento \"%s\" foram coletadas com sucesso!\n\n" +
                "Para visualizar e baixar o documento assinado:\n\n" +
                "%s\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                recipientName, documentTitle, documentUrl
            ));

            mailSender.send(message);
            log.info("Email de conclusão enviado para: {}", toEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar notificação de conclusão para {}: {}", toEmail, e.getMessage());
        }
    }

    @Override
    public void sendSignatureRejected(String ownerEmail, String ownerName, String documentTitle,
                                       String signerName, String rejectionReason) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(ownerEmail);
            message.setSubject("Assinatura Recusada - " + documentTitle);
            message.setText(String.format(
                "Olá %s,\n\n" +
                "%s recusou assinar o documento \"%s\".\n\n" +
                "Motivo: %s\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                ownerName, signerName, documentTitle,
                rejectionReason != null ? rejectionReason : "Não informado"
            ));

            mailSender.send(message);
            log.info("Email de rejeição enviado para: {}", ownerEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar notificação de rejeição para {}: {}", ownerEmail, e.getMessage());
        }
    }

    @Override
    public void sendSignatureProgress(String ownerEmail, String ownerName, String documentTitle,
                                       int signedCount, int totalCount) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(ownerEmail);
            message.setSubject("Progresso de Assinatura - " + documentTitle);
            message.setText(String.format(
                "Olá %s,\n\n" +
                "O documento \"%s\" recebeu mais uma assinatura.\n\n" +
                "Progresso: %d de %d assinatura(s) coletada(s).\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                ownerName, documentTitle, signedCount, totalCount
            ));

            mailSender.send(message);
            log.info("Email de progresso enviado para: {}", ownerEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar progresso para {}: {}", ownerEmail, e.getMessage());
        }
    }

    @Override
    public void sendAlreadyRegisteredNotice(String toEmail, String userName, String resetToken) {
        try {
            String loginUrl = baseUrl + "/login";
            String resetUrl = baseUrl + "/reset-password?token=" + resetToken;

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("Tentativa de Cadastro - Assinador Digital");
            message.setText(String.format(
                "Olá %s,\n\n" +
                "Alguém tentou criar uma conta no Assinador Digital usando este email.\n\n" +
                "Você já possui uma conta cadastrada. Para acessar, utilize o login:\n\n" +
                "%s\n\n" +
                "Se você esqueceu sua senha, use o link abaixo para redefinir (válido por 1 hora):\n\n" +
                "%s\n\n" +
                "Se não foi você, ignore este email.\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                userName, loginUrl, resetUrl
            ));

            mailSender.send(message);
            log.info("Email de conta já existente enviado para: {}", toEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar aviso de conta existente para {}: {}", toEmail, e.getMessage());
        }
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String userName, String resetToken) {
        try {
            String resetUrl = baseUrl + "/reset-password?token=" + resetToken;

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("Redefinição de Senha - Assinador Digital");
            message.setText(String.format(
                "Olá %s,\n\n" +
                "Recebemos uma solicitação para redefinir sua senha no Assinador Digital.\n\n" +
                "Para redefinir sua senha, clique no link abaixo:\n\n" +
                "%s\n\n" +
                "Este link expirará em 1 hora.\n\n" +
                "Se você não solicitou a redefinição de senha, ignore este email.\n\n" +
                "Atenciosamente,\n" +
                "Equipe Assinador Digital",
                userName, resetUrl
            ));

            mailSender.send(message);
            log.info("Email de redefinição de senha enviado para: {}", toEmail);
        } catch (Exception e) {
            log.error("Erro ao enviar email de redefinição para {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Erro ao enviar email de redefinição de senha", e);
        }
    }
}
