package br.edu.utfpr.td.tsi.projeto_assinador.validation;

import br.edu.utfpr.td.tsi.projeto_assinador.util.CpfValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CpfConstraintValidator implements ConstraintValidator<CPF, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // Let @NotBlank handle null/blank
        }
        return CpfValidator.isValid(value);
    }
}
