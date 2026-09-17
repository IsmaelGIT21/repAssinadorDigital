package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignerDTO {

    @NotBlank(message = "Email do assinante é obrigatório")
    @Email(message = "Email do assinante deve ser válido")
    private String email;

    @NotBlank(message = "Nome do assinante é obrigatório")
    private String name;

    private int step;
}
