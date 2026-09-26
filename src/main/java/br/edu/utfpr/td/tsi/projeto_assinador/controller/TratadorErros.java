package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.support.RequestContextUtils;

import jakarta.servlet.http.HttpServletRequest;
@ControllerAdvice
public class TratadorErros {

    private final DataSize tamanhoMaximo;

    public TratadorErros(@Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize tamanhoMaximo) {
        this.tamanhoMaximo = tamanhoMaximo;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String arquivoGrandeDemais(HttpServletRequest requisicao) {
        RequestContextUtils.getOutputFlashMap(requisicao).put("erro",
                "O arquivo excede o tamanho máximo de " + tamanhoMaximo.toMegabytes() + " MB.");
        return "redirect:/sign-pdf";
    }
}
