package br.edu.utfpr.td.tsi.projeto_assinador.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
@ResponseStatus(HttpStatus.NOT_FOUND)
public class DocumentoNaoEncontradoException extends RuntimeException {

    public DocumentoNaoEncontradoException() {
        super("Documento não encontrado.");
    }
}
