package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for validating 2FA codes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TwoFactorValidationDTO {
    
    @NotBlank(message = "Código é obrigatório")
    @Pattern(regexp = "\\d{6}", message = "Código deve ter 6 dígitos")
    private String code;
}
