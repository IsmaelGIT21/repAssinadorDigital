package br.edu.utfpr.td.tsi.projeto_assinador.service;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerIdentityDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.ValidationResultDTO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureValidationIntegrationTest {

    private static final String CERTIFICATE_PASSWORD = "test-password";

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path tempDir;

    private SignatureService signatureService;
    private ValidatorService validatorService;

    @BeforeEach
    void setUp() throws Exception {
        Path certificatePath = tempDir.resolve("test-ca.p12");
        createTestCa(certificatePath);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        signatureService = new SignatureService(templateEngine, new DefaultResourceLoader());
        ReflectionTestUtils.setField(signatureService, "certificatePath", certificatePath.toString());
        ReflectionTestUtils.setField(signatureService, "certificatePassword", CERTIFICATE_PASSWORD);
        ReflectionTestUtils.setField(signatureService, "certificateValidityTime", 1440);
        ReflectionTestUtils.setField(signatureService, "certificateLocation", "BR");
        ReflectionTestUtils.setField(signatureService, "appBaseURL", "http://localhost:8080");

        validatorService = new ValidatorService();
        ReflectionTestUtils.setField(validatorService, "certificatePath", certificatePath.toString());
        ReflectionTestUtils.setField(validatorService, "certificatePassword", CERTIFICATE_PASSWORD);
    }

    //@Test
    void validatesSingleAndSuccessiveSignatures() throws Exception {
        byte[] original = createPdf();
        byte[] firstSigned = sign(original, "Primeiro Signatario", "11144477735",
                "primeiro@example.com", 40, 40);

        ValidationResultDTO firstResult = validatorService.validateDocument(firstSigned);
        assertThat(firstResult.isSignedByUs()).isTrue();
        assertThat(firstResult.getSignatures()).hasSize(1).allMatch(
                ValidationResultDTO.SignatureInfoDTO::isValid);

        byte[] secondSigned = sign(firstSigned, "Segundo Signatario", "52998224725",
                "segundo@example.com", 40, 120);

        ValidationResultDTO secondResult = validatorService.validateDocument(secondSigned);
        assertThat(secondResult.isSignedByUs()).isTrue();
        assertThat(secondResult.getSignatures()).hasSize(2).allMatch(
                ValidationResultDTO.SignatureInfoDTO::isValid);
    }

    private byte[] createPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] sign(byte[] input, String name, String cpf, String email,
                        double x, double y) throws Exception {
        SignerIdentityDTO signer = SignerIdentityDTO.builder()
                .fullName(name)
                .cpf(cpf)
                .email(email)
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        signatureService.signPDF(input, output, signer,
                new SignatureService.SignaturePlacement(0, x, y));
        return output.toByteArray();
    }

    private void createTestCa(Path certificatePath) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        X500Name subject = new X500Name("CN=Signature Validation Test CA,O=Test,C=BR");
        Date notBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
        Date notAfter = Date.from(Instant.now().plus(3650, ChronoUnit.DAYS));
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, notBefore, notAfter, subject, keyPair.getPublic());
        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                extensionUtils.createAuthorityKeyIdentifier(keyPair.getPublic()));

        X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider("BC").build(keyPair.getPrivate())));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("test-ca", keyPair.getPrivate(), CERTIFICATE_PASSWORD.toCharArray(),
                new java.security.cert.Certificate[]{certificate});
        try (OutputStream output = Files.newOutputStream(certificatePath)) {
            keyStore.store(output, CERTIFICATE_PASSWORD.toCharArray());
        }
    }
}
