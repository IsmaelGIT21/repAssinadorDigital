package br.edu.utfpr.td.tsi.projeto_assinador.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import br.edu.utfpr.td.tsi.projeto_assinador.model.Pdf;

public interface PdfRepository extends MongoRepository<Pdf, String> {

    Optional<Pdf> findByIdAndProprietario(String id, String proprietario);
}
