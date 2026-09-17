package br.edu.utfpr.td.tsi.projeto_assinador.repository;

import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureAuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SignatureAuditLogRepository extends MongoRepository<SignatureAuditLog, String> {

    List<SignatureAuditLog> findByUserId(String userId);

    List<SignatureAuditLog> findByDocumentHashSha256(String hash);
}
