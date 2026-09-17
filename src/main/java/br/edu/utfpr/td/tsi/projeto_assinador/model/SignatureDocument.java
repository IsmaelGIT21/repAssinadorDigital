package br.edu.utfpr.td.tsi.projeto_assinador.model;

import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.DocumentStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SigningOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "signature_documents")
public class SignatureDocument {

    @Id
    private String id;

    private String title;

    @Indexed
    private String ownerId;

    private String ownerEmail;
    private String ownerName;

    private String originalFileId;
    private String currentFileId;

    @Builder.Default
    private DocumentStatus status = DocumentStatus.DRAFT;

    @Builder.Default
    private SigningOrder signingOrder = SigningOrder.PARALLEL;

    private int currentSigningStep;
    private int totalSigners;

    private LocalDateTime expiresAt;

    private LocalDateTime filesDeleteAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Version
    private Long version;

    public void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }
}
