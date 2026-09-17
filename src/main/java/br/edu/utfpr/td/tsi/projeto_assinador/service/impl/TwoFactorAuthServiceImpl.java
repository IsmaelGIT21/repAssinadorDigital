package br.edu.utfpr.td.tsi.projeto_assinador.service.impl;

import br.edu.utfpr.td.tsi.projeto_assinador.service.TwoFactorAuthService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Implementation of TwoFactorAuthService using Google Authenticator.
 * Handles TOTP-based two-factor authentication.
 */
@Slf4j
@Service
public class TwoFactorAuthServiceImpl implements TwoFactorAuthService {

    private final GoogleAuthenticator googleAuthenticator;

    @Value("${app.name:Assinador Digital}")
    private String appName;

    public TwoFactorAuthServiceImpl() {
        this.googleAuthenticator = new GoogleAuthenticator();
    }

    @Override
    public String generateSecretKey() {
        GoogleAuthenticatorKey key = googleAuthenticator.createCredentials();
        String secret = key.getKey();
        log.info("Novo secret 2FA gerado");
        return secret;
    }

    @Override
    public String generateQRCodeImageBase64(String userEmail, String secret) {
        try {
            String issuer = URLEncoder.encode(appName, StandardCharsets.UTF_8).replace("+", "%20");
            String account = URLEncoder.encode(userEmail, StandardCharsets.UTF_8);
            
            // Format: otpauth://totp/Issuer:Account?secret=SECRET&issuer=Issuer
            String qrCodeData = String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s",
                issuer, account, secret, issuer
            );

            // Generate QR code
            BitMatrix matrix = new MultiFormatWriter().encode(
                qrCodeData,
                BarcodeFormat.QR_CODE,
                300,
                300
            );

            // Convert to image
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            byte[] qrCodeBytes = outputStream.toByteArray();

            // Encode to Base64
            String base64Image = Base64.getEncoder().encodeToString(qrCodeBytes);
            log.info("QR Code gerado com sucesso para: {}", userEmail);
            
            return base64Image;
        } catch (Exception e) {
            log.error("Erro ao gerar QR Code para {}: {}", userEmail, e.getMessage());
            throw new RuntimeException("Erro ao gerar QR Code", e);
        }
    }

    @Override
    public boolean validateCode(String secret, int code) {
        try {
            boolean isValid = googleAuthenticator.authorize(secret, code);
            log.debug("Validação de código 2FA: {}", isValid ? "sucesso" : "falhou");
            return isValid;
        } catch (Exception e) {
            log.error("Erro ao validar código 2FA: {}", e.getMessage());
            return false;
        }
    }
}
