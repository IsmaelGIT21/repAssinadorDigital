package br.edu.utfpr.td.tsi.projeto_assinador.service.impl;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.DocumentResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignatureRequestResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureDocument;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureRequest;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.DocumentStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SigningOrder;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureDocumentRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureRequestRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.service.DocumentService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.EmailService;
import com.mongodb.client.gridfs.model.GridFSFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final SignatureDocumentRepository documentRepository;
    private final SignatureRequestRepository requestRepository;
    private final GridFsTemplate gridFsTemplate;
    private final EmailService emailService;

    @Override
    public DocumentResponseDTO createDocument(String ownerId, String ownerEmail, String ownerName,
                                               MultipartFile pdfFile, List<SignerDTO> signers,
                                               int expirationHours) {
        try {
            // Store PDF in GridFS
            ObjectId fileId = gridFsTemplate.store(
                    pdfFile.getInputStream(),
                    pdfFile.getOriginalFilename(),
                    "application/pdf"
            );

            // Create document - always parallel
            SignatureDocument document = SignatureDocument.builder()
                    .title(pdfFile.getOriginalFilename())
                    .ownerId(ownerId)
                    .ownerEmail(ownerEmail)
                    .ownerName(ownerName)
                    .originalFileId(fileId.toString())
                    .currentFileId(fileId.toString())
                    .status(DocumentStatus.PENDING)
                    .signingOrder(SigningOrder.PARALLEL)
                    .totalSigners(signers.size())
                    .expiresAt(LocalDateTime.now().plusHours(expirationHours))
                    .build();

            document = documentRepository.save(document);
            log.info("Documento criado: {} com {} assinantes", document.getId(), signers.size());

            // Create signature requests - all PENDING (parallel)
            for (SignerDTO signer : signers) {
                SignatureRequest request = SignatureRequest.builder()
                        .documentId(document.getId())
                        .signerEmail(signer.getEmail())
                        .signerName(signer.getName())
                        .signingStep(0)
                        .token(RandomStringUtils.randomAlphanumeric(64))
                        .status(SignatureRequestStatus.PENDING)
                        .expiresAt(document.getExpiresAt())
                        .build();

                requestRepository.save(request);
            }

            // Send invitations to all signers
            sendInvitations(document.getId(), ownerId);

            return toResponseDTO(document);

        } catch (Exception e) {
            log.error("Erro ao criar documento: {}", e.getMessage());
            throw new RuntimeException("Erro ao criar documento para assinatura", e);
        }
    }

    @Override
    public void sendInvitations(String documentId, String ownerId) {
        SignatureDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));

        List<SignatureRequest> pendingRequests = requestRepository
                .findByDocumentIdAndStatus(documentId, SignatureRequestStatus.PENDING);

        for (SignatureRequest request : pendingRequests) {
            emailService.sendSignatureInvitation(
                    request.getSignerEmail(),
                    request.getSignerName(),
                    document.getTitle(),
                    document.getOwnerName(),
                    request.getToken()
            );
        }

        log.info("Convites enviados para {} assinantes do documento {}", pendingRequests.size(), documentId);
    }

    @Override
    public DocumentResponseDTO getDocument(String documentId, String userId, String userEmail) {
        SignatureDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));

        assertAccess(document, userId, userEmail);

        DocumentResponseDTO dto = toResponseDTO(document);
        List<SignatureRequest> requests = requestRepository.findByDocumentIdOrderBySigningStepAsc(documentId);
        dto.setRequests(requests.stream().map(this::toRequestResponseDTO).collect(Collectors.toList()));
        return dto;
    }

    @Override
    public List<DocumentResponseDTO> getDocumentsByOwner(String ownerId) {
        return documentRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream()
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<SignatureRequestResponseDTO> getPendingRequestsForUser(String userEmail) {
        List<SignatureRequest> requests = requestRepository
                .findBySignerEmailAndStatus(userEmail, SignatureRequestStatus.PENDING);

        List<SignatureRequestResponseDTO> result = new ArrayList<>();
        for (SignatureRequest req : requests) {
            SignatureRequestResponseDTO dto = toRequestResponseDTO(req);
            documentRepository.findById(req.getDocumentId()).ifPresent(doc -> {
                dto.setDocumentTitle(doc.getTitle());
                dto.setOwnerName(doc.getOwnerName());
            });
            result.add(dto);
        }
        return result;
    }

    @Override
    public List<DocumentResponseDTO> getDocumentsForUser(String userId, String userEmail, String filter) {
        String normalized = filter == null ? "all" : filter.toLowerCase();

        Map<String, SignatureDocument> docs = new LinkedHashMap<>();
        Map<String, SignatureRequest> userRequestByDocId = new LinkedHashMap<>();

        // Owned documents
        for (SignatureDocument doc : documentRepository.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            docs.put(doc.getId(), doc);
        }

        // Documents where user is a signer
        for (SignatureRequest req : requestRepository.findBySignerEmail(userEmail)) {
            userRequestByDocId.putIfAbsent(req.getDocumentId(), req);
            if (!docs.containsKey(req.getDocumentId())) {
                documentRepository.findById(req.getDocumentId()).ifPresent(d -> docs.put(d.getId(), d));
            }
        }

        Set<String> ownerIds = new HashSet<>();
        ownerIds.add(userId);

        List<DocumentResponseDTO> result = new ArrayList<>();
        for (SignatureDocument doc : docs.values()) {
            DocumentResponseDTO dto = toResponseDTO(doc);
            boolean isOwner = ownerIds.contains(doc.getOwnerId());
            SignatureRequest userReq = userRequestByDocId.get(doc.getId());
            dto.setUserIsOwner(isOwner);
            dto.setUserIsSigner(userReq != null);
            if (userReq != null) {
                dto.setUserRequestStatus(userReq.getStatus());
                if (userReq.getStatus() == SignatureRequestStatus.PENDING) {
                    dto.setUserPendingToken(userReq.getToken());
                }
            }

            if (matchesFilter(dto, normalized)) {
                result.add(dto);
            }
        }

        result.sort(Comparator.comparing(DocumentResponseDTO::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private boolean matchesFilter(DocumentResponseDTO dto, String filter) {
        switch (filter) {
            case "sent":
                return dto.isUserIsOwner();
            case "received":
                return dto.isUserIsSigner();
            case "pending":
                return dto.getUserPendingToken() != null;
            case "signed":
                return dto.getUserRequestStatus() == SignatureRequestStatus.SIGNED
                        || (dto.isUserIsOwner() && dto.getStatus() == DocumentStatus.COMPLETED);
            case "all":
            default:
                return true;
        }
    }

    @Override
    public void cancelDocument(String documentId, String ownerId) {
        SignatureDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));

        if (!document.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Sem permissão para cancelar este documento");
        }

        document.setStatus(DocumentStatus.CANCELLED);
        document.setFilesDeleteAt(LocalDateTime.now().plusDays(30));
        document.updateTimestamp();
        documentRepository.save(document);

        // Cancel all pending requests
        List<SignatureRequest> requests = requestRepository.findByDocumentIdOrderBySigningStepAsc(documentId);
        for (SignatureRequest req : requests) {
            if (req.getStatus() == SignatureRequestStatus.PENDING) {
                req.setStatus(SignatureRequestStatus.EXPIRED);
                req.updateTimestamp();
                requestRepository.save(req);
            }
        }

        log.info("Documento {} cancelado pelo owner {}", documentId, ownerId);
    }

    @Override
    public byte[] downloadCurrentPdf(String documentId, String userId, String userEmail) {
        SignatureDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));
        assertAccess(document, userId, userEmail);
        if (document.getCurrentFileId() == null) {
            throw new RuntimeException("Os arquivos deste documento foram removidos após o período de retenção");
        }
        return loadFromGridFs(document.getCurrentFileId());
    }

    @Override
    public byte[] downloadOriginalPdf(String documentId, String userId, String userEmail) {
        SignatureDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Documento não encontrado"));
        assertAccess(document, userId, userEmail);
        if (document.getOriginalFileId() == null) {
            throw new RuntimeException("Os arquivos deste documento foram removidos após o período de retenção");
        }
        return loadFromGridFs(document.getOriginalFileId());
    }

    byte[] loadFromGridFs(String fileId) {
        try {
            GridFSFile file = gridFsTemplate.findOne(
                    new Query(Criteria.where("_id").is(new ObjectId(fileId))));
            if (file == null) {
                throw new RuntimeException("Arquivo não encontrado no GridFS");
            }
            GridFsResource resource = gridFsTemplate.getResource(file);
            try (InputStream is = resource.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                is.transferTo(baos);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            throw new RuntimeException("Erro ao carregar arquivo do GridFS", e);
        }
    }

    private void assertAccess(SignatureDocument document, String userId, String userEmail) {
        if (document.getOwnerId().equals(userId)) return;
        // Check if user is a signer
        List<SignatureRequest> requests = requestRepository.findByDocumentIdOrderBySigningStepAsc(document.getId());
        boolean isSigner = requests.stream().anyMatch(r -> r.getSignerEmail().equals(userEmail));
        if (!isSigner) {
            throw new RuntimeException("Sem permissão para acessar este documento");
        }
    }

    private DocumentResponseDTO toResponseDTO(SignatureDocument doc) {
        long signedCount = requestRepository.countByDocumentIdAndStatus(doc.getId(), SignatureRequestStatus.SIGNED);
        return DocumentResponseDTO.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .ownerName(doc.getOwnerName())
                .status(doc.getStatus())
                .totalSigners(doc.getTotalSigners())
                .signedCount((int) signedCount)
                .expiresAt(doc.getExpiresAt())
                .filesDeleteAt(doc.getFilesDeleteAt())
                .filesRemoved(doc.getOriginalFileId() == null)
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private SignatureRequestResponseDTO toRequestResponseDTO(SignatureRequest req) {
        return SignatureRequestResponseDTO.builder()
                .id(req.getId())
                .signerEmail(req.getSignerEmail())
                .signerName(req.getSignerName())
                .signingStep(req.getSigningStep())
                .status(req.getStatus())
                .rejectionReason(req.getRejectionReason())
                .signedAt(req.getSignedAt())
                .documentId(req.getDocumentId())
                .token(req.getToken())
                .build();
    }
}
