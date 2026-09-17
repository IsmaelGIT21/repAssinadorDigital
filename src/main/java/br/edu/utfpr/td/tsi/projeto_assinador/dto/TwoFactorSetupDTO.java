package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for 2FA setup response.
 * Contains secret key and QR code for authenticator app configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TwoFactorSetupDTO {
    
    private String secret;
    private String qrCodeBase64;
    private String manualEntryKey;
}
