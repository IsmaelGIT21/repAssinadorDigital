package br.edu.utfpr.td.tsi.projeto_assinador.util;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import br.edu.utfpr.td.tsi.projeto_assinador.model.DimensaoPagina;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;

public final class ConversorCoordenadas {

    private ConversorCoordenadas() {
    }

    public static int rotacao(PDPage pagina) {
        return Math.floorMod(pagina.getRotation(), 360);
    }

    public static DimensaoPagina dimensaoVisivel(PDPage pagina) {
        PDRectangle area = pagina.getCropBox();
        return estaDeitada(rotacao(pagina))
                ? new DimensaoPagina(area.getHeight(), area.getWidth())
                : new DimensaoPagina(area.getWidth(), area.getHeight());
    }


    public static PDRectangle paraRetanguloPdf(PDRectangle area, int rotacao, PosicaoEtiqueta posicao) {
        float larguraVisivel = estaDeitada(rotacao) ? area.getHeight() : area.getWidth();
        float alturaVisivel = estaDeitada(rotacao) ? area.getWidth() : area.getHeight();

        float x = (float) posicao.x() * larguraVisivel;
        float y = (float) posicao.y() * alturaVisivel;
        float largura = (float) posicao.largura() * larguraVisivel;
        float altura = (float) posicao.altura() * alturaVisivel;

        float esquerda = area.getLowerLeftX();
        float base = area.getLowerLeftY();

        return switch (rotacao) {
            case 0 -> new PDRectangle(esquerda + x, base + area.getHeight() - y - altura, largura, altura);
            case 90 -> new PDRectangle(esquerda + y, base + x, altura, largura);
            case 180 -> new PDRectangle(esquerda + area.getWidth() - x - largura, base + y, largura, altura);
            case 270 -> new PDRectangle(esquerda + area.getWidth() - y - altura, base + area.getHeight() - x - largura, altura, largura);
            default -> throw new IllegalArgumentException("Rotação de página não suportada: " + rotacao);
        };
    }

    private static boolean estaDeitada(int rotacao) {
        return rotacao == 90 || rotacao == 270;
    }
}
