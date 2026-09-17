package br.edu.utfpr.td.tsi.projeto_assinador.model;

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
@Document(collection = "signature_audit_logs")
public class SignatureAuditLog {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String signerName;
    private String signerCpfHash;
    private String signerEmail;

    @Indexed
    private String documentHashSha256;
    private String signedDocumentHashSha256;

    private String certificateSerialNumber;
    private String certificateSubjectDN;

    private LocalDateTime signedAt;
    private String ipAddress;
    private String signatureReason;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
