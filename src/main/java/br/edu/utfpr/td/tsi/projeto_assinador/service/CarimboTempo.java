package br.edu.utfpr.td.tsi.projeto_assinador.service;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CarimboTempo {

    private static final Duration TEMPO_LIMITE = Duration.ofSeconds(15);
    private static final int BITS_NONCE = 64;

    private final URI servidor;
    private final HttpClient http;
    private final SecureRandom aleatorio = new SecureRandom();

    public CarimboTempo(@Value("${certificate.tsa_url:}") String endereco) {
        this.servidor = endereco == null || endereco.isBlank() ? null : validar(endereco.strip());
        this.http = HttpClient.newBuilder().connectTimeout(TEMPO_LIMITE).build();
    }

    public boolean habilitado() {
        return servidor != null;
    }

    public CMSSignedData aplicar(CMSSignedData cms) throws IOException {
        if (!habilitado()) {
            return cms;
        }

        List<SignerInformation> carimbados = new ArrayList<>();

        for (SignerInformation assinante : cms.getSignerInfos().getSigners()) {
            carimbados.add(carimbar(assinante));
        }

        return CMSSignedData.replaceSigners(cms, new SignerInformationStore(carimbados));
    }

    private SignerInformation carimbar(SignerInformation assinante) throws IOException {
        TimeStampToken carimbo = solicitar(assinante.getSignature());

        AttributeTable atributos = assinante.getUnsignedAttributes() != null
                ? assinante.getUnsignedAttributes()
                : new AttributeTable(new ASN1EncodableVector());

        return SignerInformation.replaceUnsignedAttributes(assinante, atributos.add(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken, carimbo.toCMSSignedData().toASN1Structure()));
    }

    private TimeStampToken solicitar(byte[] assinatura) throws IOException {
        TimeStampRequestGenerator gerador = new TimeStampRequestGenerator();
        gerador.setCertReq(true);
        TimeStampRequest pedido = gerador.generate(TSPAlgorithms.SHA256, sha256(assinatura), new BigInteger(BITS_NONCE, aleatorio));

        HttpRequest requisicao = HttpRequest.newBuilder(servidor)
                .timeout(TEMPO_LIMITE)
                .header("Content-Type", "application/timestamp-query")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pedido.getEncoded()))
                .build();

        try {
            HttpResponse<byte[]> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofByteArray());
            if (resposta.statusCode() != 200) {
                throw new IOException("A TSA " + servidor + " respondeu HTTP " + resposta.statusCode() + ".");
            }

            TimeStampResponse carimbo = new TimeStampResponse(resposta.body());
            carimbo.validate(pedido);
            if (carimbo.getTimeStampToken() == null) {
                throw new IOException("A TSA recusou o pedido: " + carimbo.getStatusString());
            }
            return carimbo.getTimeStampToken();
        } catch (TSPException e) {
            throw new IOException("Resposta inválida da TSA " + servidor + ".", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Pedido de carimbo de tempo interrompido.", e);
        }
    }

    private static URI validar(String endereco) {
        URI uri = URI.create(endereco);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("certificate.tsa_url deve ser um endereço http(s).");
        }
        return uri;
    }

    private static byte[] sha256(byte[] dados) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(dados);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM.", e);
        }
    }
}
