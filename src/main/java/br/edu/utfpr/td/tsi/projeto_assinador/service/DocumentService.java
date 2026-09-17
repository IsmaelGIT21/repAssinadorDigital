package br.edu.utfpr.td.tsi.projeto_assinador.service;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.DocumentResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignatureRequestResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    DocumentResponseDTO createDocument(String ownerId, String ownerEmail, String ownerName,
                                        MultipartFile pdfFile, List<SignerDTO> signers,
                                        int expirationHours);

    void sendInvitations(String documentId, String ownerId);

    DocumentResponseDTO getDocument(String documentId, String userId, String userEmail);

    List<DocumentResponseDTO> getDocumentsByOwner(String ownerId);

    List<SignatureRequestResponseDTO> getPendingRequestsForUser(String userEmail);

    /**
     * Returns all documents the user is involved with (as owner or signer),
     * each annotated with user-specific role, pending signing token, and request status.
     * Filter can be: "all", "sent", "received", "pending", "signed".
     */
    List<DocumentResponseDTO> getDocumentsForUser(String userId, String userEmail, String filter);

    void cancelDocument(String documentId, String ownerId);

    byte[] downloadCurrentPdf(String documentId, String userId, String userEmail);

    byte[] downloadOriginalPdf(String documentId, String userId, String userEmail);
}
