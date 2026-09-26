package br.edu.utfpr.td.tsi.projeto_assinador.service;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.util.Matrix;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.edu.utfpr.td.tsi.projeto_assinador.model.ConteudoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.util.ConversorCoordenadas;

@Service
public class AssinaturaDigitalService {

    private final EtiquetaAssinatura etiqueta;
    private final Path caminhoCertificado;
    private final char[] senhaCertificado;
    private final String local;

    public AssinaturaDigitalService(EtiquetaAssinatura etiqueta,
            @Value("${certificate.path}") String caminhoCertificado,
            @Value("${certificate.password}") String senhaCertificado,
            @Value("${certificate.location:}") String local) {

        this.etiqueta = etiqueta;
        this.caminhoCertificado = Path.of(caminhoCertificado);
        this.senhaCertificado = senhaCertificado.toCharArray();
        this.local = local;
    }

    public byte[] assinar(byte[] pdf, PosicaoEtiqueta posicao, ConteudoEtiqueta conteudo, Calendar momento) throws IOException {
        Credencial credencial = carregarCredencial();

        try (PDDocument documento = PDDocument.load(pdf);
                SignatureOptions opcoes = new SignatureOptions()) {

            int indicePagina = posicao.pagina() - 1;
            PDPage pagina = documento.getPage(indicePagina);
            int rotacao = ConversorCoordenadas.rotacao(pagina);
            PDRectangle retangulo = ConversorCoordenadas.paraRetanguloPdf(pagina.getCropBox(), rotacao, posicao);

            PDSignature assinatura = new PDSignature();
            assinatura.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            assinatura.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            assinatura.setName(conteudo.signatario().nome());
            assinatura.setLocation(local);
            assinatura.setReason("Assinatura eletrônica de " + conteudo.signatario().nome());
            assinatura.setSignDate(momento);

            opcoes.setVisualSignature(modeloVisual(pagina, retangulo, rotacao, conteudo));
            opcoes.setPage(indicePagina);
            opcoes.setPreferredSignatureSize(SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2);

            documento.addSignature(assinatura, dados -> gerarCms(dados, credencial), opcoes);

            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            documento.saveIncremental(saida);
            return saida.toByteArray();
        }
    }


    private InputStream modeloVisual(PDPage paginaOriginal, PDRectangle retangulo, int rotacao, ConteudoEtiqueta conteudo) throws IOException {
        try (PDDocument modelo = new PDDocument()) {
            PDPage pagina = new PDPage(paginaOriginal.getMediaBox());
            modelo.addPage(pagina);

            PDAcroForm formulario = new PDAcroForm(modelo);
            modelo.getDocumentCatalog().setAcroForm(formulario);
            formulario.setSignaturesExist(true);
            formulario.setAppendOnly(true);
            formulario.getCOSObject().setDirect(true);

            PDSignatureField campo = new PDSignatureField(formulario);
            formulario.getFields().add(campo);
            PDAnnotationWidget widget = campo.getWidgets().get(0);
            widget.setRectangle(retangulo);

            boolean deitada = rotacao == 90 || rotacao == 270;
            float largura = deitada ? retangulo.getHeight() : retangulo.getWidth();
            float altura = deitada ? retangulo.getWidth() : retangulo.getHeight();

            PDFormXObject aparencia = new PDFormXObject(new PDStream(modelo));
            aparencia.setResources(new PDResources());
            aparencia.setFormType(1);
            aparencia.setBBox(new PDRectangle(largura, altura));
            aparencia.setMatrix(AffineTransform.getQuadrantRotateInstance(rotacao / 90));

            PDAppearanceStream fluxo = new PDAppearanceStream(aparencia.getCOSObject());
            PDAppearanceDictionary dicionario = new PDAppearanceDictionary();
            dicionario.getCOSObject().setDirect(true);
            dicionario.setNormalAppearance(fluxo);
            widget.setAppearance(dicionario);

            try (PDPageContentStream desenho = new PDPageContentStream(modelo, fluxo)) {
                float escala = Math.min(largura / EtiquetaAssinatura.LARGURA, altura / EtiquetaAssinatura.ALTURA);
                desenho.transform(Matrix.getScaleInstance(escala, escala));
                etiqueta.desenhar(modelo, desenho, conteudo);
            }

            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            modelo.save(saida);
            return new ByteArrayInputStream(saida.toByteArray());
        }
    }

    private static byte[] gerarCms(InputStream dados, Credencial credencial) throws IOException {
        try {
            X509Certificate certificado = (X509Certificate) credencial.cadeia()[0];
            String algoritmo = "SHA256with" + ("EC".equals(credencial.chave().getAlgorithm()) ? "ECDSA" : "RSA");
            ContentSigner assinador = new JcaContentSignerBuilder(algoritmo).build(credencial.chave());

            CMSSignedDataGenerator gerador = new CMSSignedDataGenerator();
            gerador.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().build()).build(assinador, certificado));
            gerador.addCertificates(new JcaCertStore(Arrays.asList(credencial.cadeia())));
            return gerador.generate(new CMSProcessableByteArray(dados.readAllBytes()), false).getEncoded();
        } catch (GeneralSecurityException | OperatorCreationException | CMSException e) {
            throw new IOException("Falha ao gerar a assinatura CMS.", e);
        }
    }

    private Credencial carregarCredencial() {
        try (InputStream entrada = Files.newInputStream(caminhoCertificado)) {
            KeyStore repositorio = KeyStore.getInstance("PKCS12");
            repositorio.load(entrada, senhaCertificado);
            for (String alias : Collections.list(repositorio.aliases())) {
                if (repositorio.isKeyEntry(alias)) {
                    PrivateKey chave = (PrivateKey) repositorio.getKey(alias, senhaCertificado);
                    return new Credencial(chave, repositorio.getCertificateChain(alias));
                }
            }
            throw new IllegalStateException("O certificado " + caminhoCertificado + " não contém chave privada.");
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível abrir o certificado de assinatura "
                    + caminhoCertificado + ". Verifique certificate.path e certificate.password.", e);
        }
    }

    private record Credencial(PrivateKey chave, Certificate[] cadeia) {
    }
}
