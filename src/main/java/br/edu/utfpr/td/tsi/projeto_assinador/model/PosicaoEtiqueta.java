package br.edu.utfpr.td.tsi.projeto_assinador.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

public record PosicaoEtiqueta(
        @Min(value = 1, message = "Página inválida.") int pagina,
        double x,
        double y,
        double largura,
        double altura) {

    private static final double TOLERANCIA = 1e-6;

    @AssertTrue(message = "A etiqueta precisa ficar inteira dentro da página.")
    public boolean isDentroDaPagina() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(largura) && Double.isFinite(altura)
                && x >= 0 && y >= 0 && largura > 0 && altura > 0
                && x + largura <= 1 + TOLERANCIA
                && y + altura <= 1 + TOLERANCIA;
    }
}
