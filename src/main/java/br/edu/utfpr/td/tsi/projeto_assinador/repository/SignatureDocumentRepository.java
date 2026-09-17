package br.edu.utfpr.td.tsi.projeto_assinador.repository;

import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureDocument;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.DocumentStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SignatureDocumentRepository extends MongoRepository<SignatureDocument, String> {

    List<SignatureDocument> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<SignatureDocument> findByOwnerIdAndStatus(String ownerId, DocumentStatus status);

    List<SignatureDocument> findByStatusAndExpiresAtBefore(DocumentStatus status, LocalDateTime dateTime);

    List<SignatureDocument> findByFilesDeleteAtBeforeAndOriginalFileIdNotNull(LocalDateTime dateTime);
}
