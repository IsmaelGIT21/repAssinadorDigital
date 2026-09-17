package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileUpdateDTO {

    @Size(max = 100, message = "Cidade deve ter no máximo 100 caracteres")
    private String city;

    @Size(min = 2, max = 2, message = "UF deve ter 2 caracteres")
    private String state;
}
