package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for email verification response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationResponseDTO {
    
    private boolean success;
    private String message;
    private String userEmail;
}
