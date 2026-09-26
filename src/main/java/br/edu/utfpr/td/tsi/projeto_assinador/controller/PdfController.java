package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.edu.utfpr.td.tsi.projeto_assinador.exception.OperacaoInvalidaException;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Pdf;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Signatario;
import br.edu.utfpr.td.tsi.projeto_assinador.service.EtiquetaAssinatura;
import br.edu.utfpr.td.tsi.projeto_assinador.service.PdfService;
import jakarta.validation.Valid;

@Controller
public class PdfController {

    private static final String TELA_ASSINATURA = "redirect:/documents/{id}/sign";

    private final PdfService pdfService;
    private final DataSize tamanhoMaximo;

    public PdfController(PdfService pdfService,
            @Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize tamanhoMaximo) {
        this.pdfService = pdfService;
        this.tamanhoMaximo = tamanhoMaximo;
    }

    @GetMapping("/")
    public String inicio() {
        return "redirect:/sign-pdf";
    }

    @GetMapping({ "/pdf", "/sign-pdf" })
    public String pdf(Model model) {
        model.addAttribute("tamanhoMaximoBytes", tamanhoMaximo.toBytes());
        model.addAttribute("tamanhoMaximoMb", tamanhoMaximo.toMegabytes());
        return "pdf-upload";
    }

    @PostMapping("/sign-pdf")
    public String enviar(@RequestParam(name = "pdf-file", required = false) MultipartFile arquivo,
            Principal usuario, RedirectAttributes redirecionamento) {
        try {
            Pdf pdf = pdfService.criar(arquivo, usuario.getName());
            redirecionamento.addAttribute("id", pdf.getId());
            return TELA_ASSINATURA;
        } catch (OperacaoInvalidaException e) {
            redirecionamento.addFlashAttribute("erro", e.getMessage());
            return "redirect:/sign-pdf";
        }
    }

    @GetMapping("/documents/{id}/sign")
    public String telaAssinatura(@PathVariable String id, Principal usuario, Model model) {
        Pdf pdf = pdfService.buscar(id, usuario.getName());
        model.addAttribute("pdf", pdf);
        model.addAttribute("assinadoEm", pdfService.formatarDataHora(pdf));
        model.addAttribute("etiqueta", pdfService.previaEtiqueta(signatario(usuario)));
        model.addAttribute("etiquetaLargura", EtiquetaAssinatura.LARGURA);
        model.addAttribute("etiquetaAltura", EtiquetaAssinatura.ALTURA);
        return "pdf-sign";
    }


    @GetMapping(value = "/documents/{id}/pages/{numero}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> pagina(@PathVariable String id, @PathVariable int numero, Principal usuario) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(pdfService.renderizarPagina(id, usuario.getName(), numero));
    }

    @PostMapping("/documents/{id}/sign")
    public String assinar(@PathVariable String id, @Valid PosicaoEtiqueta posicao, BindingResult validacao,
            Principal usuario, RedirectAttributes redirecionamento) {
        if (validacao.hasErrors()) {
            redirecionamento.addFlashAttribute("erro", mensagemDe(validacao));
            return TELA_ASSINATURA;
        }
        try {
            pdfService.assinar(id, usuario.getName(), posicao, signatario(usuario));
        } catch (OperacaoInvalidaException e) {
            redirecionamento.addFlashAttribute("erro", e.getMessage());
        }
        redirecionamento.addFlashAttribute("pagina", posicao.pagina());
        return TELA_ASSINATURA;
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> baixar(@PathVariable String id, Principal usuario) {
        Pdf pdf = pdfService.buscar(id, usuario.getName());
        String nome = pdf.isAssinado() ? pdf.getNome().replaceFirst("(?i)\\.pdf$", "") + "-assinado.pdf" : pdf.getNome();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nome, StandardCharsets.UTF_8).build().toString())
                .body(pdfService.conteudoAtual(pdf));
    }

    private static Signatario signatario(Principal usuario) {
        String login = usuario.getName();
        return new Signatario(login, null, login.contains("@") ? login : null);
    }

    private static String mensagemDe(BindingResult validacao) {
        ObjectError erro = validacao.getAllErrors().get(0);
        boolean valorMalFormado = erro instanceof FieldError campo && campo.isBindingFailure();
        return valorMalFormado ? "Posição da etiqueta inválida." : erro.getDefaultMessage();
    }
}
