package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.DocumentStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponseDTO {
    private String id;
    private String title;
    private String ownerName;
    private DocumentStatus status;
    private int totalSigners;
    private int signedCount;
    private LocalDateTime expiresAt;
    private LocalDateTime filesDeleteAt;
    private boolean filesRemoved;
    private LocalDateTime createdAt;
    private List<SignatureRequestResponseDTO> requests;

    // User-specific fields for list views
    private boolean userIsOwner;
    private boolean userIsSigner;
    private String userPendingToken;
    private SignatureRequestStatus userRequestStatus;
}
