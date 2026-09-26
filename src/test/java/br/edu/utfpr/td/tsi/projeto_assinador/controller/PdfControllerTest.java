package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.edu.utfpr.td.tsi.projeto_assinador.exception.DocumentoNaoEncontradoException;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.OperacaoInvalidaException;
import br.edu.utfpr.td.tsi.projeto_assinador.model.ConteudoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.DimensaoPagina;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Pdf;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Signatario;
import br.edu.utfpr.td.tsi.projeto_assinador.service.PdfService;


@WebMvcTest(controllers = PdfController.class, properties = {
        "spring.security.user.name=" + PdfControllerTest.USUARIO,
        "spring.security.user.password=" + PdfControllerTest.SENHA })
class PdfControllerTest {

    static final String USUARIO = "maria@utfpr.edu.br";
    static final String SENHA = "senha-teste";
    private static final Pattern TOKEN_CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final Signatario SIGNATARIO = new Signatario(USUARIO, null, USUARIO);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PdfService pdfService;

    private MockHttpSession sessao;

    @BeforeEach
    void iniciarSessao() {
        sessao = new MockHttpSession();
    }

    @Test
    void paginaInicialLevaParaAssinarPdf() throws Exception {
        mvc.perform(autenticado(get("/")))
                .andExpect(redirectedUrl("/sign-pdf"));
    }

    @Test
    void envioSemTokenCsrfEhRecusado() throws Exception {
        mvc.perform(autenticado(multipart("/sign-pdf").file(arquivoPdf())))
                .andExpect(status().isForbidden());

        verify(pdfService, never()).criar(any(), any());
    }

    @Test
    void envioValidoCriaDocumentoEAbreTelaDeAssinatura() throws Exception {
        when(pdfService.criar(any(), eq(USUARIO))).thenReturn(pdf("abc123", false));

        mvc.perform(comCsrf(multipart("/sign-pdf").file(arquivoPdf())))
                .andExpect(redirectedUrl("/documents/abc123/sign"));
    }

    @Test
    void envioInvalidoVoltaParaTela1ComMensagem() throws Exception {
        when(pdfService.criar(any(), eq(USUARIO)))
                .thenThrow(new OperacaoInvalidaException("O arquivo enviado não é um PDF válido."));

        mvc.perform(comCsrf(multipart("/sign-pdf").file(arquivoPdf())))
                .andExpect(redirectedUrl("/sign-pdf"))
                .andExpect(flash().attribute("erro", "O arquivo enviado não é um PDF válido."));
    }

    @Test
    void telaDeAssinaturaExibeEtiquetaComLogoEFormularioProtegido() throws Exception {
        when(pdfService.buscar("abc123", USUARIO)).thenReturn(pdf("abc123", false));
        when(pdfService.previaEtiqueta(SIGNATARIO)).thenReturn(
                new ConteudoEtiqueta(SIGNATARIO, "26/09/2026 15:43:59", ""));

        mvc.perform(autenticado(get("/documents/abc123/sign")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Assinar Documento")))
                .andExpect(content().string(containsString("/images/utfpr_logo.png")))
                .andExpect(content().string(containsString("26/09/2026 15:43:59 | Hash: ")))
                .andExpect(content().string(not(containsString("Validar em"))))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void documentoAssinadoExibeUmaMensagemComDataEOfereceDownload() throws Exception {
        Pdf assinado = pdf("abc123", true);
        when(pdfService.buscar("abc123", USUARIO)).thenReturn(assinado);
        when(pdfService.formatarDataHora(assinado)).thenReturn("26/09/2026 16:43:05");
        when(pdfService.previaEtiqueta(SIGNATARIO)).thenReturn(
                new ConteudoEtiqueta(SIGNATARIO, "26/09/2026 15:43:59", ""));

        mvc.perform(autenticado(get("/documents/abc123/sign")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Documento assinado com sucesso em 26/09/2026 16:43:05.")))
                .andExpect(content().string(not(containsString("ainda não possui assinaturas"))))
                .andExpect(content().string(containsString("/documents/abc123/download")))
                .andExpect(content().string(not(containsString("id=\"etiqueta\""))));
    }

    @Test
    void documentoDeOutroUsuarioResponde404() throws Exception {
        when(pdfService.buscar("de-outro", USUARIO)).thenThrow(new DocumentoNaoEncontradoException());

        mvc.perform(autenticado(get("/documents/de-outro/sign")))
                .andExpect(status().isNotFound());
    }

    @Test
    void assinarComPosicaoValidaChamaOServico() throws Exception {
        mvc.perform(comCsrf(posicao("2", "0.1", "0.2", "0.3", "0.1")))
                .andExpect(redirectedUrl("/documents/abc123/sign"))
                .andExpect(flash().attributeCount(1))
                .andExpect(flash().attribute("pagina", 2));

        verify(pdfService).assinar("abc123", USUARIO, new PosicaoEtiqueta(2, 0.1, 0.2, 0.3, 0.1), SIGNATARIO);
    }

    @Test
    void assinarComEtiquetaForaDaPaginaNaoChamaOServico() throws Exception {
        mvc.perform(comCsrf(posicao("1", "0.8", "0.2", "0.5", "0.1")))
                .andExpect(redirectedUrl("/documents/abc123/sign"))
                .andExpect(flash().attribute("erro", "A etiqueta precisa ficar inteira dentro da página."));

        verify(pdfService, never()).assinar(any(), any(), any(), any());
    }

    @Test
    void assinarComValorMalFormadoNaoChamaOServico() throws Exception {
        mvc.perform(comCsrf(posicao("1", "abc", "0.2", "0.5", "0.1")))
                .andExpect(redirectedUrl("/documents/abc123/sign"))
                .andExpect(flash().attribute("erro", "Posição da etiqueta inválida."));

        verify(pdfService, never()).assinar(any(), any(), any(), any());
    }

    @Test
    void erroDeNegocioAoAssinarViraMensagem() throws Exception {
        when(pdfService.assinar(any(), any(), any(), any()))
                .thenThrow(new OperacaoInvalidaException("A página escolhida não existe neste documento."));

        mvc.perform(comCsrf(posicao("9", "0.1", "0.2", "0.3", "0.1")))
                .andExpect(flash().attribute("erro", "A página escolhida não existe neste documento."));
    }

    private MockHttpServletRequestBuilder posicao(String pagina, String x, String y, String largura,
            String altura) {
        return post("/documents/abc123/sign")
                .param("pagina", pagina).param("x", x).param("y", y)
                .param("largura", largura).param("altura", altura);
    }

    private MockHttpServletRequestBuilder comCsrf(MockHttpServletRequestBuilder requisicao) throws Exception {
        String html = mvc.perform(autenticado(get("/sign-pdf"))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        Matcher token = TOKEN_CSRF.matcher(html);
        if (!token.find()) {
            throw new AssertionError("Formulário sem token CSRF");
        }
        return autenticado(requisicao).param("_csrf", token.group(1));
    }

    private MockHttpServletRequestBuilder autenticado(MockHttpServletRequestBuilder requisicao) {
        String credenciais = Base64.getEncoder()
                .encodeToString((USUARIO + ":" + SENHA).getBytes(StandardCharsets.UTF_8));
        return requisicao.session(sessao).header(HttpHeaders.AUTHORIZATION, "Basic " + credenciais);
    }

    private static MockMultipartFile arquivoPdf() {
        return new MockMultipartFile("pdf-file", "contrato.pdf", "application/pdf", "%PDF-1.7".getBytes());
    }

    private static Pdf pdf(String id, boolean assinado) {
        Pdf pdf = new Pdf("contrato.pdf", USUARIO, "arquivo", "0".repeat(64),
                List.of(new DimensaoPagina(595, 842), new DimensaoPagina(842, 595)));
        pdf.setId(id);
        pdf.setVersao(0L);
        if (assinado) {
            pdf.setArquivoAssinadoId("assinado");
        }
        return pdf;
    }
}
