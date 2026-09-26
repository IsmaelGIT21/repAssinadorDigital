package br.edu.utfpr.td.tsi.projeto_assinador.service;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import br.edu.utfpr.td.tsi.projeto_assinador.model.ConteudoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Signatario;

@Component
public class EtiquetaAssinatura {

    public static final float LARGURA = 240;
    public static final float ALTURA = 72;

    static final String CAMINHO_LOGO = "static/images/utfpr_logo.png";

    private static final float MARGEM = 8;
    private static final float RAIO_BORDA = 6;
    private static final float LOGO_LARGURA_MAXIMA = 64;
    private static final float LOGO_ALTURA_MAXIMA = 38;
    private static final float LOGO_BASE = 26;
    private static final float TEXTO_X = MARGEM + LOGO_LARGURA_MAXIMA + 8;
    private static final float TEXTO_LARGURA_MAXIMA = LARGURA - TEXTO_X - MARGEM;

    private static final Color DOURADO_BORDA = new Color(0xE5, 0xA1, 0x00);
    private static final Color DOURADO_NOME = new Color(0xB0, 0x7A, 0x00);
    private static final Color TEXTO = new Color(0x33, 0x33, 0x33);
    private static final Color TEXTO_SECUNDARIO = new Color(0x66, 0x66, 0x66);

    private static final PDFont FONTE = PDType1Font.HELVETICA;
    private static final PDFont FONTE_NEGRITO = PDType1Font.HELVETICA_BOLD;

    private final byte[] logo;

    public EtiquetaAssinatura() {
        this.logo = carregarLogo();
    }

    public void desenhar(PDDocument documento, PDPageContentStream conteudo, ConteudoEtiqueta etiqueta) throws IOException {
        desenharFundoEBorda(conteudo);
        desenharLogo(documento, conteudo);
        desenharSignatario(conteudo, etiqueta.signatario());

        String hash = etiqueta.hash() == null ? "" : etiqueta.hash();
        escrever(conteudo, FONTE, 5.5f, TEXTO_SECUNDARIO, MARGEM, 8, LARGURA - 2 * MARGEM, etiqueta.dataHora() + " | Hash: " + hash);
    }

    private void desenharFundoEBorda(PDPageContentStream conteudo) throws IOException {
        float meiaLinha = 0.5f;
        conteudo.saveGraphicsState();
        conteudo.setNonStrokingColor(Color.WHITE);
        conteudo.setStrokingColor(DOURADO_BORDA);
        conteudo.setLineWidth(2 * meiaLinha);
        conteudo.setLineCapStyle(1);
        conteudo.setLineDashPattern(new float[] { 0, 2.5f }, 0);
        retanguloArredondado(conteudo, meiaLinha, meiaLinha, LARGURA - 2 * meiaLinha, ALTURA - 2 * meiaLinha);
        conteudo.fillAndStroke();
        conteudo.restoreGraphicsState();
    }

    private void desenharLogo(PDDocument documento, PDPageContentStream conteudo) throws IOException {
        PDImageXObject imagem = PDImageXObject.createFromByteArray(documento, logo, "utfpr_logo.png");
        float escala = Math.min(LOGO_LARGURA_MAXIMA / imagem.getWidth(), LOGO_ALTURA_MAXIMA / imagem.getHeight());
        float largura = imagem.getWidth() * escala;
        float altura = imagem.getHeight() * escala;
        conteudo.drawImage(imagem, MARGEM, LOGO_BASE + (LOGO_ALTURA_MAXIMA - altura) / 2, largura, altura);
    }

    private void desenharSignatario(PDPageContentStream conteudo, Signatario signatario) throws IOException {
        escrever(conteudo, FONTE_NEGRITO, 8.5f, DOURADO_NOME, TEXTO_X, 54, TEXTO_LARGURA_MAXIMA, signatario.nome());

        List<String> linhas = new ArrayList<>();

        if (temTexto(signatario.cpf())) {
            linhas.add("CPF: " + signatario.cpf());
        }

        if (temTexto(signatario.email())) {
            linhas.add(signatario.email());
        }

        float base = 43;

        for (String linha : linhas) {
            escrever(conteudo, FONTE, 6.5f, TEXTO, TEXTO_X, base, TEXTO_LARGURA_MAXIMA, linha);
            base -= 9;
        }
    }

    private static void escrever(PDPageContentStream conteudo, PDFont fonte, float tamanho, Color cor, float x, float y, float larguraMaxima, String texto) throws IOException {
        conteudo.beginText();
        conteudo.setFont(fonte, tamanho);
        conteudo.setNonStrokingColor(cor);
        conteudo.newLineAtOffset(x, y);
        conteudo.showText(ajustarLargura(texto, fonte, tamanho, larguraMaxima));
        conteudo.endText();
    }

    static String ajustarLargura(String texto, PDFont fonte, float tamanho, float larguraMaxima) throws IOException {
        StringBuilder suportado = new StringBuilder();
        texto.codePoints().forEach(codigo -> suportado.append(caractereSuportado(codigo, fonte)));

        String resultado = suportado.toString();

        if (largura(resultado, fonte, tamanho) <= larguraMaxima) {
            return resultado;
        }

        String reticencias = "...";

        while (!resultado.isEmpty() && largura(resultado + reticencias, fonte, tamanho) > larguraMaxima) {
            resultado = resultado.substring(0, resultado.length() - 1);
        }
        return resultado + reticencias;
    }

    private static String caractereSuportado(int codigo, PDFont fonte) {
        if (Character.isISOControl(codigo)) {
            return " ";
        }

        String caractere = new String(Character.toChars(codigo));

        try {
            fonte.encode(caractere);
            return caractere;
        } catch (IOException | IllegalArgumentException e) {
            return "?";
        }
    }

    private static float largura(String texto, PDFont fonte, float tamanho) throws IOException {
        return fonte.getStringWidth(texto) / 1000 * tamanho;
    }

    private static void retanguloArredondado(PDPageContentStream conteudo, float x, float y, float largura, float altura) throws IOException {
        float r = RAIO_BORDA;
        float k = 0.5523f * r; 
        conteudo.moveTo(x + r, y);
        conteudo.lineTo(x + largura - r, y);
        conteudo.curveTo(x + largura - r + k, y, x + largura, y + r - k, x + largura, y + r);
        conteudo.lineTo(x + largura, y + altura - r);
        conteudo.curveTo(x + largura, y + altura - r + k, x + largura - r + k, y + altura, x + largura - r, y + altura);
        conteudo.lineTo(x + r, y + altura);
        conteudo.curveTo(x + r - k, y + altura, x, y + altura - r + k, x, y + altura - r);
        conteudo.lineTo(x, y + r);
        conteudo.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        conteudo.closePath();
    }

    private static boolean temTexto(String valor) {
        return valor != null && !valor.isBlank();
    }

    private static byte[] carregarLogo() {
        ClassPathResource recurso = new ClassPathResource(CAMINHO_LOGO);

        if (!recurso.exists()) {
            throw new IllegalStateException("Logo da etiqueta não encontrado no classpath: " + CAMINHO_LOGO);
        }
        
        try (InputStream entrada = recurso.getInputStream()) {
            return entrada.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o logo da etiqueta: " + CAMINHO_LOGO, e);
        }
    }
}
