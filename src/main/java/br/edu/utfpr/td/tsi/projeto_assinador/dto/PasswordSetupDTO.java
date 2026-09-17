package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for setting up password during email verification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordSetupDTO {
    
    @NotBlank(message = "Senha é obrigatória")
    @Size(min = 8, message = "Senha deve ter no mínimo 8 caracteres")
    private String password;
    
    @NotBlank(message = "Confirmação de senha é obrigatória")
    private String confirmPassword;
}
