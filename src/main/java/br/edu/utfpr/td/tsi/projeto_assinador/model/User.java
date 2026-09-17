package br.edu.utfpr.td.tsi.projeto_assinador.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * User entity representing a user in the system.
 * Stores user information including credentials and personal data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    private String id;

    @NotBlank(message = "Nome é obrigatório")
    private String name;

    @NotBlank(message = "Email é obrigatório")
    @Email(message = "Email deve ser válido")
    @Indexed(unique = true)
    private String email;

    @Indexed(unique = true, sparse = true)
    private String cpf;

    private String passwordHash; // Initially null until set during verification

    @Builder.Default
    private boolean emailVerified = false;

    private String emailVerificationToken;

    private LocalDateTime emailVerificationExpiry;

    private String passwordResetToken;

    private LocalDateTime passwordResetExpiry;

    private String twoFactorSecret;

    @Builder.Default
    private boolean twoFactorEnabled = false;

    @Builder.Default
    private boolean hideTwoFactorModal = false;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Updates the timestamp when the user is modified
     */
    public void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }
}
