package br.edu.utfpr.td.tsi.projeto_assinador.service;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Calendar;

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
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.edu.utfpr.td.tsi.projeto_assinador.model.ConteudoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Signatario;
import br.edu.utfpr.td.tsi.projeto_assinador.util.ConversorCoordenadas;

@Service
public class AssinaturaDigitalService {

    private static final int TAMANHO_RESERVADO_ASSINATURA = SignatureOptions.DEFAULT_SIGNATURE_SIZE * 4;

    private final EtiquetaAssinatura etiqueta;
    private final EmissorCertificado emissor;
    private final CarimboTempo carimboTempo;
    private final String local;

    public AssinaturaDigitalService(EtiquetaAssinatura etiqueta, EmissorCertificado emissor, CarimboTempo carimboTempo, @Value("${certificate.location:}") String local) {
        this.etiqueta = etiqueta;
        this.emissor = emissor;
        this.carimboTempo = carimboTempo;
        this.local = local;
    }

    public byte[] assinar(byte[] pdf, PosicaoEtiqueta posicao, ConteudoEtiqueta conteudo, Calendar momento) throws IOException {
        Signatario signatario = conteudo.signatario();
        Credencial credencial = emissor.credencialPara(signatario, momento.toInstant());

        try (PDDocument documento = PDDocument.load(pdf);
                SignatureOptions opcoes = new SignatureOptions()) {

            int indicePagina = posicao.pagina() - 1;
            PDPage pagina = documento.getPage(indicePagina);
            int rotacao = ConversorCoordenadas.rotacao(pagina);
            PDRectangle retangulo = ConversorCoordenadas.paraRetanguloPdf(pagina.getCropBox(), rotacao, posicao);

            PDSignature assinatura = new PDSignature();
            assinatura.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            assinatura.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            assinatura.setName(signatario.nome());
            assinatura.setLocation(local);
            assinatura.setReason("Assinatura eletrônica de " + signatario.nome());
            assinatura.setSignDate(momento);

            opcoes.setVisualSignature(modeloVisual(pagina, retangulo, rotacao, conteudo));
            opcoes.setPage(indicePagina);
            opcoes.setPreferredSignatureSize(TAMANHO_RESERVADO_ASSINATURA);

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

    private byte[] gerarCms(InputStream dados, Credencial credencial) throws IOException {
        try {
            CMSSignedDataGenerator gerador = new CMSSignedDataGenerator();
            gerador.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().build())
                    .build(new JcaContentSignerBuilder(credencial.algoritmoAssinatura()).build(credencial.chave()),
                            credencial.certificado()));
            gerador.addCertificates(new JcaCertStore(credencial.cadeia()));

            CMSSignedData cms = gerador.generate(new CMSProcessableByteArray(dados.readAllBytes()), false);
            return carimboTempo.aplicar(cms).getEncoded();
        } catch (GeneralSecurityException | OperatorCreationException | CMSException e) {
            throw new IOException("Falha ao gerar a assinatura CMS.", e);
        }
    }
}
