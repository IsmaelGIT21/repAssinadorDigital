package br.edu.utfpr.td.tsi.projeto_assinador.service.impl;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerIdentityDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureAuditLog;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureDocument;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureRequest;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.DocumentStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureAuditLogRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureDocumentRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureRequestRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.service.EmailService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.SignatureRequestService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.SignatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureRequestServiceImpl implements SignatureRequestService {

    private final SignatureRequestRepository requestRepository;
    private final SignatureDocumentRepository documentRepository;
    private final SignatureAuditLogRepository auditLogRepository;
    private final SignatureService signatureService;
    private final EmailService emailService;
    private final GridFsTemplate gridFsTemplate;
    private final DocumentServiceImpl documentServiceImpl;

    private static final int MAX_RETRIES = 3;
    private static final int FILE_RETENTION_DAYS = 30;

    @Override
    public SignatureRequest resolveToken(String token) {
        SignatureRequest request = requestRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Token de assinatura inválido ou expirado"));
        assertNotExpired(request);
        return request;
    }

    @Override
    public SignatureRequest resolveTokenForSigner(String token, String signerEmail) {
        SignatureRequest request = resolveToken(token);
        assertSigner(request, signerEmail);
        assertPending(request);
        return request;
    }

    @Override
    public void signDocument(String requestId, SignerIdentityDTO signerIdentity,
                             SignatureService.SignaturePlacement placement,
                             String userId, String ipAddress) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                doSign(requestId, signerIdentity, placement, userId, ipAddress);
                return;
            } catch (OptimisticLockingFailureException e) {
                log.warn("Conflito de concorrência (tentativa {}/{}) ao assinar documento", attempt, MAX_RETRIES);
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("O documento está sendo assinado por outra pessoa. Tente novamente em instantes.", e);
                }
            }
        }
    }

    private void doSign(String requestId, SignerIdentityDTO signerIdentity,
                        SignatureService.SignaturePlacement placement,
                        String userId, String ipAddress) {
        SignatureRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Solicitação de assinatura não encontrada"));

        assertSigner(request, signerIdentity.getEmail());
        assertNotExpired(request);

        assertPending(request);

        SignatureDocument document = documentRepository.findById(request.getDocumentId())
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));

        if (document.getStatus() != DocumentStatus.PENDING) {
            throw new RuntimeException("Este documento não está mais disponível para assinatura");
        }

        ObjectId newFileId = null;
        boolean documentCommitted = false;
        try {
            // Load current PDF from GridFS
            byte[] currentPdf = documentServiceImpl.loadFromGridFs(document.getCurrentFileId());

            // Sign the PDF
            ByteArrayOutputStream signedOutput = new ByteArrayOutputStream();
            SignatureService.SignatureResult signatureResult =
                    signatureService.signPDF(currentPdf, signedOutput, signerIdentity, placement);
            byte[] signedPdf = signedOutput.toByteArray();

            // Store signed PDF in GridFS
            newFileId = gridFsTemplate.store(
                    new ByteArrayInputStream(signedPdf),
                    document.getTitle() + "_signed",
                    "application/pdf"
            );

            String prevFileId = document.getCurrentFileId();
            document.setCurrentFileId(newFileId.toString());
            document.updateTimestamp();

            long signedCount = requestRepository
                    .countByDocumentIdAndStatus(document.getId(), SignatureRequestStatus.SIGNED) + 1;
            if (signedCount >= document.getTotalSigners()) {
                document.setStatus(DocumentStatus.COMPLETED);
                document.setFilesDeleteAt(LocalDateTime.now().plusDays(FILE_RETENTION_DAYS));
            }

            document = documentRepository.save(document);
            documentCommitted = true;

            request.setStatus(SignatureRequestStatus.SIGNED);
            request.setSignerUserId(userId);
            request.setSignedAt(signatureResult.signedAt());
            request.updateTimestamp();
            requestRepository.save(request);

            deleteIntermediateFile(prevFileId, document.getOriginalFileId());
            saveAuditLog(userId, signerIdentity, currentPdf, signedPdf,
                    signatureResult, ipAddress);

            if (document.getStatus() == DocumentStatus.COMPLETED) {
                notifyCompletionSafely(document);
            } else {
                notifyProgressSafely(document, signedCount);
            }

            log.info("Documento {} assinado por {}", document.getId(), signerIdentity.getEmail());

        } catch (OptimisticLockingFailureException e) {
            if (!documentCommitted) deleteGridFsFile(newFileId);
            throw e;
        } catch (RuntimeException e) {
            if (!documentCommitted) deleteGridFsFile(newFileId);
            throw e;
        } catch (Exception e) {
            if (!documentCommitted) deleteGridFsFile(newFileId);
            log.error("Erro ao assinar documento: {}", e.getMessage());
            throw new RuntimeException("Erro ao processar a assinatura", e);
        }
    }

    @Override
    public void rejectSignature(String requestId, String signerEmail, String reason) {
        SignatureRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Solicitação não encontrada"));

        assertSigner(request, signerEmail);
        assertNotExpired(request);

        assertPending(request);

        request.setStatus(SignatureRequestStatus.REJECTED);
        request.setRejectionReason(reason);
        request.updateTimestamp();
        requestRepository.save(request);

        // Notify owner
        SignatureDocument document = documentRepository.findById(request.getDocumentId()).orElse(null);
        if (document != null) {
            if (document.getStatus() == DocumentStatus.PENDING) {
                document.setStatus(DocumentStatus.CANCELLED);
                document.setFilesDeleteAt(LocalDateTime.now().plusDays(FILE_RETENTION_DAYS));
                document.updateTimestamp();
                documentRepository.save(document);
                expirePendingRequests(document.getId());
            }
            try {
                emailService.sendSignatureRejected(
                        document.getOwnerEmail(),
                        document.getOwnerName(),
                        document.getTitle(),
                        request.getSignerName(),
                        reason
                );
            } catch (Exception e) {
                log.warn("Recusa registrada, mas o proprietário não foi notificado: {}", e.getMessage());
            }
        }

        log.info("Assinatura rejeitada para request {} por motivo: {}", requestId, reason);
    }

    @Override
    public void sendReminder(String requestId, String ownerId) {
        SignatureRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Solicitação não encontrada"));

        SignatureDocument document = documentRepository.findById(request.getDocumentId())
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));

        if (!document.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Sem permissão para enviar lembrete");
        }

        if (request.getStatus() != SignatureRequestStatus.PENDING) {
            throw new RuntimeException("Só é possível enviar lembrete para solicitações pendentes");
        }
        assertNotExpired(request);

        emailService.sendSignatureReminder(
                request.getSignerEmail(),
                request.getSignerName(),
                document.getTitle(),
                document.getOwnerName(),
                request.getToken()
        );

        log.info("Lembrete enviado para {} sobre documento {}", request.getSignerEmail(), document.getId());
    }

    @Override
    public byte[] getCurrentPdfForRequest(String requestId, String signerEmail) {
        SignatureRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Solicitação não encontrada"));

        assertSigner(request, signerEmail);
        assertNotExpired(request);
        assertPending(request);

        SignatureDocument document = documentRepository.findById(request.getDocumentId())
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));

        return documentServiceImpl.loadFromGridFs(document.getCurrentFileId());
    }

    private void assertSigner(SignatureRequest request, String signerEmail) {
        if (signerEmail == null || !signerEmail.equalsIgnoreCase(request.getSignerEmail())) {
            throw new RuntimeException("Este convite pertence a outro usuário");
        }
    }

    private void assertNotExpired(SignatureRequest request) {
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new RuntimeException("Token de assinatura inválido ou expirado");
        }
    }

    private void assertPending(SignatureRequest request) {
        if (request.getStatus() != SignatureRequestStatus.PENDING) {
            throw new RuntimeException("Esta solicitação de assinatura não está pendente");
        }
    }

    private void expirePendingRequests(String documentId) {
        List<SignatureRequest> requests = requestRepository
                .findByDocumentIdAndStatus(documentId, SignatureRequestStatus.PENDING);
        for (SignatureRequest pendingRequest : requests) {
            pendingRequest.setStatus(SignatureRequestStatus.EXPIRED);
            pendingRequest.updateTimestamp();
            requestRepository.save(pendingRequest);
        }
    }

    private void saveAuditLog(String userId, SignerIdentityDTO signerIdentity,
                              byte[] originalPdf, byte[] signedPdf,
                              SignatureService.SignatureResult result, String ipAddress) {
        try {
            auditLogRepository.save(SignatureAuditLog.builder()
                    .userId(userId)
                    .signerName(signerIdentity.getFullName())
                    .signerCpfHash(sha256(signerIdentity.getCpf().getBytes(StandardCharsets.UTF_8)))
                    .signerEmail(signerIdentity.getEmail())
                    .documentHashSha256(sha256(originalPdf))
                    .signedDocumentHashSha256(sha256(signedPdf))
                    .certificateSerialNumber(result.certificateSerialNumber())
                    .certificateSubjectDN(result.certificateSubjectDN())
                    .signedAt(result.signedAt())
                    .ipAddress(ipAddress)
                    .signatureReason(result.reason())
                    .build());
        } catch (Exception e) {
            log.error("Assinatura concluída, mas não foi possível registrar a auditoria: {}", e.getMessage());
        }
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private void deleteIntermediateFile(String fileId, String originalFileId) {
        if (fileId != null && !fileId.equals(originalFileId)) {
            deleteGridFsFile(new ObjectId(fileId));
        }
    }

    private void deleteGridFsFile(ObjectId fileId) {
        if (fileId == null) return;
        try {
            gridFsTemplate.delete(new Query(Criteria.where("_id").is(fileId)));
        } catch (Exception e) {
            log.warn("Não foi possível remover o arquivo intermediário {}: {}", fileId, e.getMessage());
        }
    }

    private void notifyProgressSafely(SignatureDocument document, long signedCount) {
        try {
            emailService.sendSignatureProgress(
                    document.getOwnerEmail(), document.getOwnerName(), document.getTitle(),
                    (int) signedCount, document.getTotalSigners());
        } catch (Exception e) {
            log.warn("Assinatura concluída, mas o aviso de progresso não foi enviado: {}", e.getMessage());
        }
    }

    private void notifyCompletionSafely(SignatureDocument document) {
        try {
            notifyCompletion(document);
        } catch (Exception e) {
            log.warn("Documento concluído, mas nem todos os avisos foram enviados: {}", e.getMessage());
        }
    }

    private void notifyCompletion(SignatureDocument document) {
        emailService.sendDocumentCompleted(
                document.getOwnerEmail(),
                document.getOwnerName(),
                document.getTitle(),
                document.getId()
        );

        List<SignatureRequest> requests = requestRepository
                .findByDocumentIdOrderBySigningStepAsc(document.getId());
        for (SignatureRequest req : requests) {
            if (req.getStatus() == SignatureRequestStatus.SIGNED) {
                emailService.sendDocumentCompleted(
                        req.getSignerEmail(),
                        req.getSignerName(),
                        document.getTitle(),
                        document.getId()
                );
            }
        }
    }
}
