package br.edu.utfpr.td.tsi.projeto_assinador.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PosicaoEtiquetaTest {

    @Test
    void aceitaEtiquetaDentroDaPagina() {
        assertTrue(new PosicaoEtiqueta(1, 0, 0, 0.5, 0.1).isDentroDaPagina());
        assertTrue(new PosicaoEtiqueta(1, 0.5, 0.9, 0.5, 0.1).isDentroDaPagina());
    }

    @Test
    void recusaEtiquetaQueSaiDaPagina() {
        assertFalse(new PosicaoEtiqueta(1, 0.6, 0, 0.5, 0.1).isDentroDaPagina());
        assertFalse(new PosicaoEtiqueta(1, 0, 0.95, 0.5, 0.1).isDentroDaPagina());
        assertFalse(new PosicaoEtiqueta(1, -0.1, 0, 0.5, 0.1).isDentroDaPagina());
    }

    @Test
    void recusaTamanhoNuloOuValoresNaoNumericos() {
        assertFalse(new PosicaoEtiqueta(1, 0, 0, 0, 0.1).isDentroDaPagina());
        assertFalse(new PosicaoEtiqueta(1, Double.NaN, 0, 0.5, 0.1).isDentroDaPagina());
        assertFalse(new PosicaoEtiqueta(1, 0, 0, Double.POSITIVE_INFINITY, 0.1).isDentroDaPagina());
    }
}
