package br.edu.utfpr.td.tsi.projeto_assinador.scheduler;

import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureDocument;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureRequest;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.DocumentStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureDocumentRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExpirationScheduler {

    private static final int FILE_RETENTION_DAYS = 30;

    private final SignatureDocumentRepository documentRepository;
    private final SignatureRequestRepository requestRepository;
    private final GridFsTemplate gridFsTemplate;

    @Scheduled(fixedRate = 3600000) // Every hour
    public void expireDocuments() {
        LocalDateTime now = LocalDateTime.now();
        List<SignatureDocument> expiredDocs = documentRepository
                .findByStatusAndExpiresAtBefore(DocumentStatus.PENDING, now);

        for (SignatureDocument doc : expiredDocs) {
            doc.setStatus(DocumentStatus.EXPIRED);
            doc.setFilesDeleteAt(now.plusDays(FILE_RETENTION_DAYS));
            doc.updateTimestamp();
            documentRepository.save(doc);

            List<SignatureRequest> requests = requestRepository
                    .findByDocumentIdOrderBySigningStepAsc(doc.getId());
            for (SignatureRequest req : requests) {
                if (req.getStatus() == SignatureRequestStatus.PENDING) {
                    req.setStatus(SignatureRequestStatus.EXPIRED);
                    req.updateTimestamp();
                    requestRepository.save(req);
                }
            }

            log.info("Documento {} expirado. Arquivos serão removidos em {}", doc.getId(), doc.getFilesDeleteAt());
        }

        if (!expiredDocs.isEmpty()) {
            log.info("{} documento(s) expirado(s)", expiredDocs.size());
        }
    }

    @Scheduled(fixedRate = 86400000) // Every 24 hours
    public void cleanupExpiredFiles() {
        LocalDateTime now = LocalDateTime.now();
        List<SignatureDocument> docs = documentRepository
                .findByFilesDeleteAtBeforeAndOriginalFileIdNotNull(now);

        int filesDeleted = 0;

        for (SignatureDocument doc : docs) {
            Set<String> fileIds = new HashSet<>();
            if (doc.getOriginalFileId() != null) fileIds.add(doc.getOriginalFileId());
            if (doc.getCurrentFileId() != null) fileIds.add(doc.getCurrentFileId());

            for (String fileId : fileIds) {
                try {
                    gridFsTemplate.delete(new Query(Criteria.where("_id").is(new ObjectId(fileId))));
                    filesDeleted++;
                } catch (Exception e) {
                    log.warn("Erro ao remover arquivo {} do GridFS: {}", fileId, e.getMessage());
                }
            }

            doc.setOriginalFileId(null);
            doc.setCurrentFileId(null);
            doc.updateTimestamp();
            documentRepository.save(doc);

            log.info("Arquivos do documento {} removidos do GridFS", doc.getId());
        }

        if (filesDeleted > 0) {
            log.info("{} arquivo(s) removido(s) do GridFS de {} documento(s)", filesDeleted, docs.size());
        }
    }
}
