package br.edu.utfpr.td.tsi.projeto_assinador.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import br.edu.utfpr.td.tsi.projeto_assinador.model.DimensaoPagina;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;

class ConversorCoordenadasTest {

    private static final float DELTA = 0.01f;

    private static final PDRectangle AREA = new PDRectangle(200, 400);
    private static final PosicaoEtiqueta POSICAO = new PosicaoEtiqueta(1, 0.1, 0.2, 0.5, 0.1);

    @Test
    void semRotacaoInverteOEixoY() {
        assertRetangulo(20, 280, 100, 40, ConversorCoordenadas.paraRetanguloPdf(AREA, 0, POSICAO));
    }

    @Test
    void rotacao90() {
        assertRetangulo(40, 40, 20, 200, ConversorCoordenadas.paraRetanguloPdf(AREA, 90, POSICAO));
    }

    @Test
    void rotacao180() {
        assertRetangulo(80, 80, 100, 40, ConversorCoordenadas.paraRetanguloPdf(AREA, 180, POSICAO));
    }

    @Test
    void rotacao270() {
        assertRetangulo(140, 160, 20, 200, ConversorCoordenadas.paraRetanguloPdf(AREA, 270, POSICAO));
    }

    @Test
    void consideraCropBoxDeslocada() {
        PDRectangle area = new PDRectangle(50, 30, 200, 400);
        assertRetangulo(70, 310, 100, 40, ConversorCoordenadas.paraRetanguloPdf(area, 0, POSICAO));
    }

    @Test
    void etiquetaNoCantoInferiorDireitoEncostaNasBordas() {
        PosicaoEtiqueta canto = new PosicaoEtiqueta(1, 0.5, 0.9, 0.5, 0.1);
        assertRetangulo(100, 0, 100, 40, ConversorCoordenadas.paraRetanguloPdf(AREA, 0, canto));
    }

    @Test
    void tamanhosDePaginaDiferentesUsamAsProporcoes() {
        PDRectangle a4 = PDRectangle.A4;
        PDRectangle retangulo = ConversorCoordenadas.paraRetanguloPdf(a4, 0, POSICAO);
        assertEquals(a4.getWidth() * 0.5f, retangulo.getWidth(), DELTA);
        assertEquals(a4.getHeight() * 0.1f, retangulo.getHeight(), DELTA);
    }

    @Test
    void rotacaoInvalidaEhRejeitada() {
        assertThrows(IllegalArgumentException.class, () -> ConversorCoordenadas.paraRetanguloPdf(AREA, 45, POSICAO));
    }

    @Test
    void dimensaoVisivelTrocaLarguraEAlturaEmPaginasDeitadas() {
        PDPage pagina = new PDPage(AREA);
        pagina.setRotation(-90);
        assertEquals(270, ConversorCoordenadas.rotacao(pagina));
        assertEquals(new DimensaoPagina(400, 200), ConversorCoordenadas.dimensaoVisivel(pagina));

        pagina.setRotation(180);
        assertEquals(new DimensaoPagina(200, 400), ConversorCoordenadas.dimensaoVisivel(pagina));
    }

    private static void assertRetangulo(float x, float y, float largura, float altura, PDRectangle atual) {
        assertEquals(x, atual.getLowerLeftX(), DELTA, "x");
        assertEquals(y, atual.getLowerLeftY(), DELTA, "y");
        assertEquals(largura, atual.getWidth(), DELTA, "largura");
        assertEquals(altura, atual.getHeight(), DELTA, "altura");
    }
}
