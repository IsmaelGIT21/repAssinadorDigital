package br.edu.utfpr.td.tsi.projeto_assinador.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import br.edu.utfpr.td.tsi.projeto_assinador.model.Signatario;

@Component
public class EmissorCertificado {

    private static final int TAMANHO_CHAVE_RSA = 2048;
    private static final int BITS_NUMERO_SERIE = 127;
    private static final int USO_ASSINATURA_DIGITAL = 0;
    private static final int USO_NAO_REPUDIO = 1;
    private static final int USO_ASSINAR_CERTIFICADOS = 5;
    private static final Duration TOLERANCIA_RELOGIO = Duration.ofMinutes(5);

    private final Path caminho;
    private final char[] senha;
    private final Duration validade;
    private final SecureRandom aleatorio = new SecureRandom();
    private volatile Credencial emissor;

    public EmissorCertificado(@Value("${certificate.path}") String caminho,
            @Value("${certificate.password}") String senha,
            @Value("${certificate.validity_time:1440}") long validadeEmMinutos) {

        if (validadeEmMinutos <= 0) {
            throw new IllegalArgumentException("certificate.validity_time deve ser maior que zero.");
        }
        this.caminho = Path.of(caminho);
        this.senha = senha.toCharArray();
        this.validade = Duration.ofMinutes(validadeEmMinutos);
    }

    public Credencial credencialPara(Signatario signatario, Instant momento) {
        Credencial credencialEmissor = carregarEmissor();
        X509Certificate certificado = credencialEmissor.certificado();

        if (momento.isBefore(certificado.getNotBefore().toInstant())
                || momento.isAfter(certificado.getNotAfter().toInstant())) {
            throw new IllegalStateException("O certificado " + caminho + " não está dentro do prazo de validade.");
        }

        if (!ehAutoridadeCertificadora(certificado)) {
            exigirUso(certificado, "assinatura digital", USO_ASSINATURA_DIGITAL, USO_NAO_REPUDIO);
            return credencialEmissor;
        }

        exigirUso(certificado, "emissão de certificados", USO_ASSINAR_CERTIFICADOS);
        return emitir(credencialEmissor, signatario, momento);
    }

    private Credencial emitir(Credencial autoridade, Signatario signatario, Instant momento) {
        if (signatario == null || signatario.nome() == null || signatario.nome().isBlank()) {
            throw new IllegalArgumentException("O signatário precisa ter um nome para receber um certificado.");
        }

        try {
            KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
            gerador.initialize(TAMANHO_CHAVE_RSA, aleatorio);
            KeyPair chaves = gerador.generateKeyPair();

            X509Certificate ac = autoridade.certificado();
            Instant fimPedido = momento.plus(validade);
            Instant fimAc = ac.getNotAfter().toInstant();

            JcaX509v3CertificateBuilder construtor = new JcaX509v3CertificateBuilder(ac,
                    new BigInteger(BITS_NUMERO_SERIE, aleatorio).add(BigInteger.ONE),
                    Date.from(momento.minus(TOLERANCIA_RELOGIO)),
                    Date.from(fimPedido.isBefore(fimAc) ? fimPedido : fimAc),
                    nomeDistinto(signatario), chaves.getPublic());

            JcaX509ExtensionUtils extensoes = new JcaX509ExtensionUtils();
            construtor.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            construtor.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            construtor.addExtension(Extension.subjectKeyIdentifier, false,
                    extensoes.createSubjectKeyIdentifier(chaves.getPublic()));
            construtor.addExtension(Extension.authorityKeyIdentifier, false,
                    extensoes.createAuthorityKeyIdentifier(ac));
            adicionarEmail(construtor, signatario.email());

            X509Certificate certificado = new JcaX509CertificateConverter().getCertificate(construtor.build(
                    new JcaContentSignerBuilder(autoridade.algoritmoAssinatura()).build(autoridade.chave())));
            certificado.verify(ac.getPublicKey());

            List<X509Certificate> cadeia = new ArrayList<>();
            cadeia.add(certificado);
            cadeia.addAll(autoridade.cadeia());
            return new Credencial(chaves.getPrivate(), cadeia);
        } catch (GeneralSecurityException | OperatorCreationException | CertIOException e) {
            throw new IllegalStateException("Falha ao emitir o certificado do signatário.", e);
        }
    }

    private static X500Name nomeDistinto(Signatario signatario) {
        X500NameBuilder nome = new X500NameBuilder(BCStyle.INSTANCE);
        nome.addRDN(BCStyle.CN, signatario.nome().strip());
        return nome.build();
    }

    private static void adicionarEmail(JcaX509v3CertificateBuilder construtor, String email) throws CertIOException {
        if (email != null && !email.isBlank()) {
            construtor.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.rfc822Name, email.strip())));
        }
    }

    private static boolean ehAutoridadeCertificadora(X509Certificate certificado) {
        return certificado.getBasicConstraints() >= 0;
    }

    private void exigirUso(X509Certificate certificado, String finalidade, int... usosAceitos) {
        boolean[] usos = certificado.getKeyUsage();
        if (usos == null) {
            return;
        }
        for (int uso : usosAceitos) {
            if (usos[uso]) {
                return;
            }
        }
        throw new IllegalStateException("O certificado " + caminho + " não permite " + finalidade + ".");
    }

    private Credencial carregarEmissor() {
        Credencial atual = emissor;
        if (atual == null) {
            synchronized (this) {
                if (emissor == null) {
                    emissor = lerPkcs12();
                }
                atual = emissor;
            }
        }
        return atual;
    }

    private Credencial lerPkcs12() {
        try (InputStream entrada = Files.newInputStream(caminho)) {
            KeyStore repositorio = KeyStore.getInstance("PKCS12");
            repositorio.load(entrada, senha);
            for (String alias : Collections.list(repositorio.aliases())) {
                if (repositorio.isKeyEntry(alias)) {
                    PrivateKey chave = (PrivateKey) repositorio.getKey(alias, senha);
                    return new Credencial(chave, paraX509(repositorio.getCertificateChain(alias)));
                }
            }
            throw new IllegalStateException("O certificado " + caminho + " não contém chave privada.");
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível abrir o certificado de assinatura "
                    + caminho + ". Verifique certificate.path e certificate.password.", e);
        }
    }

    private static List<X509Certificate> paraX509(Certificate[] cadeia) {
        List<X509Certificate> certificados = new ArrayList<>();
        for (Certificate certificado : cadeia) {
            certificados.add((X509Certificate) certificado);
        }
        return certificados;
    }
}
