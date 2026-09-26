package br.edu.utfpr.td.tsi.projeto_assinador.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.edu.utfpr.td.tsi.projeto_assinador.model.ConteudoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Signatario;
import br.edu.utfpr.td.tsi.projeto_assinador.util.ConversorCoordenadas;

class AssinaturaDigitalServiceTest {

    private static final String SENHA = "senha-teste";
    private static final ConteudoEtiqueta CONTEUDO = new ConteudoEtiqueta(
            new Signatario("Maria da Silva – Ω", "123.456.789-00", "maria@utfpr.edu.br"),
            "26/09/2026 15:43:59", "0123456789abcdef");

    @TempDir
    Path pasta;

    private X509Certificate certificado;
    private AssinaturaDigitalService servico;

    @BeforeEach
    void criarCertificado() throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        KeyPair chaves = gerador.generateKeyPair();

        X500Name nome = new X500Name("CN=Autoridade de Teste");
        Instant agora = Instant.now();
        certificado = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                nome, BigInteger.ONE, Date.from(agora.minus(Duration.ofDays(1))),
                Date.from(agora.plus(Duration.ofDays(1))), nome, chaves.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(chaves.getPrivate())));

        KeyStore repositorio = KeyStore.getInstance("PKCS12");
        repositorio.load(null, null);
        repositorio.setKeyEntry("teste", chaves.getPrivate(), SENHA.toCharArray(), new Certificate[] { certificado });
        Path arquivo = pasta.resolve("certificado.p12");
        try (OutputStream saida = Files.newOutputStream(arquivo)) {
            repositorio.store(saida, SENHA.toCharArray());
        }

        servico = new AssinaturaDigitalService(new EtiquetaAssinatura(), arquivo.toString(), SENHA, "BR");
    }

    @Test
    void assinaNaPaginaEPosicaoEscolhidasComAssinaturaCmsValida() throws Exception {
        byte[] original = pdfComDuasPaginas(0, 0);
        PosicaoEtiqueta posicao = new PosicaoEtiqueta(2, 0.1, 0.2, 0.4, 0.1);

        byte[] assinado = servico.assinar(original, posicao, CONTEUDO, new GregorianCalendar());

        assertArrayEquals(original, Arrays.copyOf(assinado, original.length));

        try (PDDocument documento = PDDocument.load(assinado)) {
            PDSignature assinatura = documento.getSignatureDictionaries().get(0);
            assertEquals("Maria da Silva – Ω", assinatura.getName());
            assertCmsValido(assinatura, assinado);

            PDPage pagina = documento.getPage(1);
            PDAnnotationWidget widget = widgetDaAssinatura(documento);
            assertTrue(pagina.getAnnotations().stream()
                    .anyMatch(anotacao -> anotacao.getCOSObject() == widget.getCOSObject()));
            assertTrue(documento.getPage(0).getAnnotations().isEmpty());
            assertMesmoRetangulo(ConversorCoordenadas.paraRetanguloPdf(pagina.getCropBox(), 0, posicao),
                    widget.getRectangle());

            assertTrue(widget.getAppearance().getNormalAppearance().getAppearanceStream()
                    .getResources().getXObjectNames().iterator().hasNext());
        }
    }

    @Test
    void assinaPaginaGirada() throws Exception {
        byte[] original = pdfComDuasPaginas(0, 90);
        PosicaoEtiqueta posicao = new PosicaoEtiqueta(2, 0.5, 0.5, 0.3, 0.1);

        byte[] assinado = servico.assinar(original, posicao, CONTEUDO, new GregorianCalendar());

        try (PDDocument documento = PDDocument.load(assinado)) {
            PDPage pagina = documento.getPage(1);
            PDRectangle esperado = ConversorCoordenadas.paraRetanguloPdf(pagina.getCropBox(), 90, posicao);
            PDRectangle atual = widgetDaAssinatura(documento).getRectangle();
            assertMesmoRetangulo(esperado, atual);
            assertFalse(atual.getWidth() > atual.getHeight());
            assertCmsValido(documento.getSignatureDictionaries().get(0), assinado);
        }
    }

    private void assertCmsValido(PDSignature assinatura, byte[] pdf) throws Exception {
        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(assinatura.getSignedContent(pdf)),
                assinatura.getContents(pdf));
        SignerInformation assinante = cms.getSignerInfos().getSigners().iterator().next();
        assertTrue(assinante.verify(new JcaSimpleSignerInfoVerifierBuilder().build(certificado)));
    }

    private static PDAnnotationWidget widgetDaAssinatura(PDDocument documento) throws IOException {
        PDSignatureField campo = documento.getSignatureFields().get(0);
        return campo.getWidgets().get(0);
    }

    private static void assertMesmoRetangulo(PDRectangle esperado, PDRectangle atual) {
        float delta = 0.01f;
        assertEquals(esperado.getLowerLeftX(), atual.getLowerLeftX(), delta);
        assertEquals(esperado.getLowerLeftY(), atual.getLowerLeftY(), delta);
        assertEquals(esperado.getWidth(), atual.getWidth(), delta);
        assertEquals(esperado.getHeight(), atual.getHeight(), delta);
    }

    private static byte[] pdfComDuasPaginas(int rotacaoPrimeira, int rotacaoSegunda) throws IOException {
        try (PDDocument documento = new PDDocument()) {
            PDPage primeira = new PDPage(PDRectangle.A4);
            primeira.setRotation(rotacaoPrimeira);
            PDPage segunda = new PDPage(PDRectangle.LETTER);
            segunda.setRotation(rotacaoSegunda);
            documento.addPage(primeira);
            documento.addPage(segunda);
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            documento.save(saida);
            return saida.toByteArray();
        }
    }
}
