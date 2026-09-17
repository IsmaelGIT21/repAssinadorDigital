package br.edu.utfpr.td.tsi.projeto_assinador.model;

import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "signature_requests")
public class SignatureRequest {

    @Id
    private String id;

    @Indexed
    private String documentId;

    private String signerEmail;
    private String signerUserId;
    private String signerName;

    private int signingStep;

    @Indexed(unique = true)
    private String token;

    @Builder.Default
    private SignatureRequestStatus status = SignatureRequestStatus.PENDING;

    private String rejectionReason;

    private LocalDateTime signedAt;
    private LocalDateTime expiresAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    public void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }
}
