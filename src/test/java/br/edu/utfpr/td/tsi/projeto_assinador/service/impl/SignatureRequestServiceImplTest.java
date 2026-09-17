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
import br.edu.utfpr.td.tsi.projeto_assinador.service.SignatureService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignatureRequestServiceImplTest {

    private static final String REQUEST_ID = "request-1";
    private static final String DOCUMENT_ID = "document-1";
    private static final String SIGNER_EMAIL = "signer@example.com";

    @Mock
    private SignatureRequestRepository requestRepository;
    @Mock
    private SignatureDocumentRepository documentRepository;
    @Mock
    private SignatureAuditLogRepository auditLogRepository;
    @Mock
    private SignatureService signatureService;
    @Mock
    private EmailService emailService;
    @Mock
    private GridFsTemplate gridFsTemplate;
    @Mock
    private DocumentServiceImpl documentService;

    private SignatureRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SignatureRequestServiceImpl(
                requestRepository, documentRepository, auditLogRepository,
                signatureService, emailService, gridFsTemplate, documentService);
    }

   // @Test
    void rejectsSigningWhenAuthenticatedEmailDoesNotOwnInvitation() throws Exception {
        SignatureRequest request = pendingRequest();
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        SignerIdentityDTO identity = identity("other@example.com");

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.signDocument(REQUEST_ID, identity, null, "user-1", "127.0.0.1"));

        assertEquals("Este convite pertence a outro usuário", error.getMessage());
        verify(documentRepository, never()).findById(anyString());
        verify(signatureService, never()).signPDF(any(), any(), any(), any());
    }

    //@Test
    void retriesOptimisticConflictWithoutConfirmingLosingAttempt() throws Exception {
        ObjectId originalFileId = new ObjectId();
        ObjectId firstAttemptFileId = new ObjectId();
        ObjectId committedFileId = new ObjectId();
        SignatureRequest request = pendingRequest();
        SignatureDocument firstRead = pendingDocument(originalFileId, 1);
        SignatureDocument secondRead = pendingDocument(originalFileId, 1);
        LocalDateTime signedAt = LocalDateTime.now();

        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(firstRead), Optional.of(secondRead));
        when(documentService.loadFromGridFs(originalFileId.toString())).thenReturn(new byte[]{1, 2, 3});
        when(gridFsTemplate.store(any(InputStream.class), anyString(), eq("application/pdf")))
                .thenReturn(firstAttemptFileId, committedFileId);
        when(requestRepository.countByDocumentIdAndStatus(DOCUMENT_ID, SignatureRequestStatus.SIGNED))
                .thenReturn(0L);
        when(documentRepository.save(any(SignatureDocument.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            invocation.<java.io.OutputStream>getArgument(1).write(new byte[]{4, 5, 6});
            return new SignatureService.SignatureResult(
                    "123", "CN=Signer", signedAt, SignatureService.SIGNATURE_REASON);
        }).when(signatureService).signPDF(any(), any(), any(), any());

        service.signDocument(REQUEST_ID, identity(SIGNER_EMAIL), null, "user-1", "127.0.0.1");

        assertEquals(SignatureRequestStatus.SIGNED, request.getStatus());
        assertEquals("user-1", request.getSignerUserId());
        assertEquals(DocumentStatus.COMPLETED, secondRead.getStatus());
        assertEquals(committedFileId.toString(), secondRead.getCurrentFileId());
        assertNotNull(secondRead.getFilesDeleteAt());
        verify(signatureService, times(2)).signPDF(any(), any(), any(), any());
        verify(gridFsTemplate, times(1)).delete(any(Query.class));
        verify(requestRepository, times(1)).save(request);

        ArgumentCaptor<SignatureAuditLog> auditCaptor = ArgumentCaptor.forClass(SignatureAuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        SignatureAuditLog audit = auditCaptor.getValue();
        assertEquals("user-1", audit.getUserId());
        assertEquals("123", audit.getCertificateSerialNumber());
        assertEquals("CN=Signer", audit.getCertificateSubjectDN());
        assertEquals(SignatureService.SIGNATURE_REASON, audit.getSignatureReason());
        assertEquals(64, audit.getDocumentHashSha256().length());
        assertEquals(64, audit.getSignedDocumentHashSha256().length());
    }

   // @Test
    void rejectionClosesDocumentAndExpiresOtherPendingInvitations() {
        SignatureRequest rejected = pendingRequest();
        SignatureRequest other = pendingRequest();
        other.setId("request-2");
        other.setSignerEmail("other@example.com");
        SignatureDocument document = pendingDocument(new ObjectId(), 2);

        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(rejected));
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(requestRepository.findByDocumentIdAndStatus(DOCUMENT_ID, SignatureRequestStatus.PENDING))
                .thenReturn(List.of(other));

        service.rejectSignature(REQUEST_ID, SIGNER_EMAIL, "Não concordo");

        assertEquals(SignatureRequestStatus.REJECTED, rejected.getStatus());
        assertEquals(DocumentStatus.CANCELLED, document.getStatus());
        assertNotNull(document.getFilesDeleteAt());
        assertEquals(SignatureRequestStatus.EXPIRED, other.getStatus());
        verify(documentRepository).save(document);
        verify(requestRepository).save(rejected);
        verify(requestRepository).save(other);
    }

    //@Test
    void rejectsRefusalWhenAuthenticatedEmailDoesNotOwnInvitation() {
        SignatureRequest request = pendingRequest();
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.rejectSignature(REQUEST_ID, "other@example.com", "Não concordo"));

        assertEquals("Este convite pertence a outro usuário", error.getMessage());
        verify(requestRepository, never()).save(any(SignatureRequest.class));
        verify(documentRepository, never()).findById(anyString());
    }

    //@Test
    void expiredTokenIsRejectedBeforeSigning() {
        SignatureRequest request = pendingRequest();
        request.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(requestRepository.findByToken("expired-token")).thenReturn(Optional.of(request));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.resolveToken("expired-token"));

        assertTrue(error.getMessage().contains("expirado"));
    }

    private SignatureRequest pendingRequest() {
        return SignatureRequest.builder()
                .id(REQUEST_ID)
                .documentId(DOCUMENT_ID)
                .signerEmail(SIGNER_EMAIL)
                .signerName("Signer")
                .status(SignatureRequestStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    private SignatureDocument pendingDocument(ObjectId originalFileId, int totalSigners) {
        return SignatureDocument.builder()
                .id(DOCUMENT_ID)
                .title("document.pdf")
                .ownerId("owner-1")
                .ownerName("Owner")
                .ownerEmail("owner@example.com")
                .originalFileId(originalFileId.toString())
                .currentFileId(originalFileId.toString())
                .status(DocumentStatus.PENDING)
                .totalSigners(totalSigners)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    private SignerIdentityDTO identity(String email) {
        return SignerIdentityDTO.builder()
                .fullName("Signer")
                .cpf("12345678901")
                .email(email)
                .build();
    }
}
