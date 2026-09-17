package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignatureRequestResponseDTO {
    private String id;
    private String signerEmail;
    private String signerName;
    private int signingStep;
    private SignatureRequestStatus status;
    private String rejectionReason;
    private LocalDateTime signedAt;
    private String documentTitle;
    private String documentId;
    private String ownerName;
    private String token;
}
