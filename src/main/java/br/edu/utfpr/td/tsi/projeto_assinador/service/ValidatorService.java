package br.edu.utfpr.td.tsi.projeto_assinador.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.ValidationResultDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.ValidationResultDTO.SignatureInfoDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.util.CpfValidator;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
public class ValidatorService {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Value("${certificate.path}")
    private String certificatePath;

    @Value("${certificate.password}")
    private String certificatePassword;

    public ValidationResultDTO validateDocument(byte[] bytes) {
        boolean signedByUs = false;
        List<SignatureInfoDTO> signatureInfos = new ArrayList<>();

        try (PDDocument document = PDDocument.load(bytes)) {
            List<PDSignature> signatureDictionaries = document.getSignatureDictionaries();

            if (signatureDictionaries.isEmpty()) {
                return new ValidationResultDTO(false);
            }

            X509Certificate caCertificate = loadCertificate();

            for (PDSignature signature : signatureDictionaries) {
                SignatureInfoDTO info = extractSignatureInfo(bytes, caCertificate, signature);
                if (info != null) {
                    signatureInfos.add(info);
                    if (info.isValid()) {
                        signedByUs = true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ValidationResultDTO(false);
        }

        return ValidationResultDTO.builder()
                .signedByUs(signedByUs)
                .signatures(signatureInfos)
                .build();
    }

    private X509Certificate loadCertificate() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream certStream = new FileInputStream(certificatePath)) {
            keyStore.load(certStream, certificatePassword.toCharArray());
        }

        String alias = keyStore.aliases().nextElement();
        return (X509Certificate) keyStore.getCertificate(alias);
    }

    private SignatureInfoDTO extractSignatureInfo(byte[] pdfBytes, X509Certificate caCert, PDSignature pdSignature) {
        try {
            byte[] cmsData;
            try (InputStream s = new ByteArrayInputStream(pdfBytes)) {
                cmsData = pdSignature.getContents(s);
            }
            byte[] signedContent;
            try (InputStream s = new ByteArrayInputStream(pdfBytes)) {
                signedContent = pdSignature.getSignedContent(s);
            }

            CMSSignedData signedData = new CMSSignedData(new CMSProcessableByteArray(signedContent), cmsData);
            Store<X509CertificateHolder> certificatesStore = signedData.getCertificates();
            SignerInformationStore signers = signedData.getSignerInfos();
            Collection<SignerInformation> c = signers.getSigners();

            for (SignerInformation signer : c) {
                @SuppressWarnings("unchecked")
                Collection<X509CertificateHolder> certCollection = (Collection<X509CertificateHolder>) certificatesStore
                        .getMatches(signer.getSID());

                for (X509CertificateHolder certHolder : certCollection) {
                    X509Certificate cert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                            .setProvider("BC")
                            .getCertificate(certHolder);

                    boolean valid = false;
                    try {
                        var verifier = new JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(cert);
                        if (!signer.verify(verifier)) throw new Exception();

                        boolean[] ku = cert.getKeyUsage();
                        if (ku == null || !ku[1]) throw new Exception();

                        Date signingTime = pdSignature.getSignDate() != null
                                ? pdSignature.getSignDate().getTime() : new Date();
                        PKIXParameters pkix = new PKIXParameters(
                                Collections.singleton(new TrustAnchor(caCert, null)));
                        pkix.setRevocationEnabled(false);
                        pkix.setDate(signingTime);
                        CertPathValidator.getInstance("PKIX").validate(
                                CertificateFactory.getInstance("X.509")
                                        .generateCertPath(Collections.singletonList(cert)),
                                pkix);
                        valid = true;
                    } catch (Exception validationError) {
                        log.warn("Falha ao validar assinatura do certificado {}: {}",
                                cert.getSubjectX500Principal().getName(), validationError.getMessage());
                        log.debug("Detalhes da falha de validação", validationError);
                    }

                    X500Name subject = certHolder.getSubject();
                    String signerName = extractRdn(subject, BCStyle.CN);
                    String cpfRaw = extractRdn(subject, BCStyle.SERIALNUMBER);
                    String email = extractRdn(subject, BCStyle.E);

                    String signedAt = null;
                    if (pdSignature.getSignDate() != null) {
                        signedAt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss")
                                .format(pdSignature.getSignDate().getTime());
                    }

                    return SignatureInfoDTO.builder()
                            .signerName(signerName)
                            .signerCpf(cpfRaw != null ? CpfValidator.mask(cpfRaw) : null)
                            .signerEmail(email)
                            .signedAt(signedAt)
                            .signatureReason(pdSignature.getReason())
                            .certificateIssuer(certHolder.getIssuer().toString())
                            .certificateSubjectDN(subject.toString())
                            .valid(valid)
                            .build();
                }
            }
        } catch (Exception e) {
            System.err.println("Erro ao processar bloco CMS: " + e.getMessage());
        }
        return null;
    }

    private String extractRdn(X500Name name, ASN1ObjectIdentifier oid) {
        RDN[] rdns = name.getRDNs(oid);
        if (rdns.length == 0) return null;
        return IETFUtils.valueToString(rdns[0].getFirst().getValue());
    }
}
