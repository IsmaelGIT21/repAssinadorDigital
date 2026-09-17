package br.edu.utfpr.td.tsi.projeto_assinador.service;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerIdentityDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureRequest;

public interface SignatureRequestService {

    SignatureRequest resolveToken(String token);

    SignatureRequest resolveTokenForSigner(String token, String signerEmail);

    void signDocument(String requestId, SignerIdentityDTO signerIdentity,
                      SignatureService.SignaturePlacement placement,
                      String userId, String ipAddress);

    void rejectSignature(String requestId, String signerEmail, String reason);

    void sendReminder(String requestId, String ownerId);

    byte[] getCurrentPdfForRequest(String requestId, String signerEmail);
}
