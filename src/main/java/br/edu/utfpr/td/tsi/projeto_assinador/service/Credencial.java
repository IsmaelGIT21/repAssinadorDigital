package br.edu.utfpr.td.tsi.projeto_assinador.service;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

public record Credencial(PrivateKey chave, List<X509Certificate> cadeia) {

    public Credencial {
        if (chave == null || cadeia == null || cadeia.isEmpty()) {
            throw new IllegalArgumentException("A credencial exige chave privada e ao menos um certificado.");
        }
        cadeia = List.copyOf(cadeia);
    }

    public X509Certificate certificado() {
        return cadeia.get(0);
    }

    public String algoritmoAssinatura() {
        return "SHA256with" + ("EC".equals(chave.getAlgorithm()) ? "ECDSA" : "RSA");
    }
}
