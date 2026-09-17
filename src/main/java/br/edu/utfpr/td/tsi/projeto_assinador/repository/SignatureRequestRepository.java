package br.edu.utfpr.td.tsi.projeto_assinador.repository;

import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureRequest;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SignatureRequestRepository extends MongoRepository<SignatureRequest, String> {

    List<SignatureRequest> findByDocumentIdOrderBySigningStepAsc(String documentId);

    Optional<SignatureRequest> findByToken(String token);

    List<SignatureRequest> findBySignerEmail(String signerEmail);

    List<SignatureRequest> findBySignerEmailAndStatus(String signerEmail, SignatureRequestStatus status);

    List<SignatureRequest> findByDocumentIdAndStatus(String documentId, SignatureRequestStatus status);

    long countByDocumentIdAndStatus(String documentId, SignatureRequestStatus status);
}
