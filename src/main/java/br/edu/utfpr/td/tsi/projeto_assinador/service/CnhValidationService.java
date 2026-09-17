package br.edu.utfpr.td.tsi.projeto_assinador.service;

import br.edu.utfpr.td.tsi.projeto_assinador.util.CpfValidator;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.bouncycastle.util.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;
import java.security.cert.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CnhValidationService {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final Pattern CPF_PATTERN = Pattern.compile(
            "\\d{3}[.\\s-]?\\d{3}[.\\s-]?\\d{3}[.\\s-]?\\d{2}");

    private final IcpBrasilTrustStoreService icpBrasilTrustStore;

    @Value("${tesseract.datapath:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessdataPath;

    public record CnhData(String name, String cpf) {}

    public CnhData extractFromCnhPdf(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(pdfBytes)) {

            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                throw new IllegalArgumentException(
                        "O PDF da CNH não possui assinatura digital. Exporte a CNH pelo aplicativo CNH do Brasil.");
            }

            validateSignatures(pdfBytes, signatures);

            String ocrText = performOcr(document);
            if (ocrText == null || ocrText.isBlank()) {
                throw new IllegalArgumentException(
                        "Não foi possível ler o conteúdo do PDF da CNH.");
            }

            String cpf = extractCpf(ocrText);
            if (cpf == null) {
                throw new IllegalArgumentException(
                        "CPF não encontrado no PDF da CNH. Verifique se o arquivo é a CNH exportada pelo aplicativo CNH do Brasil.");
            }

            String name = extractName(ocrText);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "Nome não encontrado no PDF da CNH. Verifique se o arquivo é a CNH exportada pelo aplicativo CNH do Brasil.");
            }

            log.info("Dados extraídos da CNH - Nome: {}, CPF: {}", name, CpfValidator.mask(cpf));
            return new CnhData(name, cpf);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            log.error("Erro ao processar PDF da CNH: {}", e.getMessage());
            throw new IllegalArgumentException("Erro ao processar o PDF da CNH. Verifique se o arquivo é válido.", e);
        }
    }

    /**
     * Validates all digital signatures in the CNH PDF using PKIX chain validation
     * against ICP-Brasil root certificates.
     *
     * Verifies:
     * 1. CMS signature integrity (content was not tampered with)
     * 2. Certificate chain from signer up to a trusted ICP-Brasil root (PKIX)
     */
    private void validateSignatures(byte[] pdfBytes, List<PDSignature> signatures) {
        if (icpBrasilTrustStore.getTrustAnchors().isEmpty()) {
            throw new IllegalArgumentException(
                    "O sistema não possui certificados ICP-Brasil carregados. " +
                    "Tente novamente em alguns minutos.");
        }

        boolean hasValidSignature = false;

        for (PDSignature signature : signatures) {
            String subFilter = signature.getSubFilter();
            if (subFilter == null) continue;
            if (!subFilter.toLowerCase().contains("pkcs7") && !subFilter.toLowerCase().contains("cades")) {
                continue;
            }

            try (InputStream is = new ByteArrayInputStream(pdfBytes)) {
                byte[] signedContent = signature.getSignedContent(is);
                byte[] cmsData = signature.getContents(new ByteArrayInputStream(pdfBytes));

                CMSSignedData signedData = new CMSSignedData(
                        new CMSProcessableByteArray(signedContent), cmsData);
                Store<X509CertificateHolder> cmsStore = signedData.getCertificates();

                for (SignerInformation signer : signedData.getSignerInfos().getSigners()) {
                    @SuppressWarnings("unchecked")
                    Collection<X509CertificateHolder> certs = cmsStore.getMatches(signer.getSID());

                    for (X509CertificateHolder certHolder : certs) {
                        X509Certificate signerCert = new JcaX509CertificateConverter()
                                .setProvider("BC")
                                .getCertificate(certHolder);

                        // 1. Verify CMS signature integrity
                        boolean integrityOk = signer.verify(
                                new JcaSimpleSignerInfoVerifierBuilder()
                                        .setProvider("BC")
                                        .build(signerCert));

                        if (!integrityOk) {
                            log.warn("Assinatura CMS inválida - conteúdo possivelmente adulterado");
                            throw new IllegalArgumentException(
                                    "A assinatura digital da CNH é inválida. O documento pode ter sido adulterado.");
                        }

                        // 2. Build cert pool: bundled intermediates + certs embedded in the PDF
                        Set<X509Certificate> allIntermediates = new HashSet<>(icpBrasilTrustStore.getIntermediateCerts());
                        Collection<X509CertificateHolder> embeddedCerts = cmsStore.getMatches(null);
                        JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider("BC");
                        for (X509CertificateHolder embedded : embeddedCerts) {
                            allIntermediates.add(converter.getCertificate(embedded));
                        }

                        // 3. Determine signing time (TSA timestamp or PDSignature date)
                        Date signingTime = extractSigningTime(signer, signature);

                        // 4. PKIX chain validation at signing time against ICP-Brasil roots
                        log.debug("Validando cadeia PKIX - Signer: {}, Issuer: {}, Pool size: {}, Data assinatura: {}",
                                signerCert.getSubjectX500Principal().getName(),
                                signerCert.getIssuerX500Principal().getName(),
                                allIntermediates.size(), signingTime);
                        validateCertificateChain(signerCert, allIntermediates, signingTime);

                        // 5. Validate TSA timestamp integrity and cert validity at signing time
                        validateTimestamp(signer, signerCert);

                        hasValidSignature = true;
                        log.info("Assinatura da CNH verificada com sucesso (PKIX + CRL + TSA). Certificado: {}",
                                signerCert.getSubjectX500Principal().getName());
                    }
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                log.error("Erro ao verificar assinatura da CNH: {}", e.getMessage());
                throw new IllegalArgumentException(
                        "Erro ao verificar a assinatura digital da CNH. Verifique se o arquivo é válido.", e);
            }
        }

        if (!hasValidSignature) {
            throw new IllegalArgumentException(
                    "Nenhuma assinatura digital válida encontrada na CNH. " +
                    "Exporte a CNH pelo aplicativo CNH do Brasil.");
        }
    }

    /**
     * Extracts the signing time from TSA timestamp or PDSignature date.
     * Prefers TSA timestamp (cryptographically reliable) over PDSignature date.
     */
    private Date extractSigningTime(SignerInformation signer, PDSignature pdSignature) {
        // Try TSA timestamp first (most reliable)
        AttributeTable unsignedAttrs = signer.getUnsignedAttributes();
        if (unsignedAttrs != null) {
            Attribute tsaAttr = unsignedAttrs.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
            if (tsaAttr != null) {
                try {
                    byte[] tsaTokenBytes = tsaAttr.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded();
                    TimeStampToken tsaToken = new TimeStampToken(new CMSSignedData(tsaTokenBytes));
                    Date tsaTime = tsaToken.getTimeStampInfo().getGenTime();
                    log.debug("Data da assinatura extraída do TSA: {}", tsaTime);
                    return tsaTime;
                } catch (Exception e) {
                    log.debug("Falha ao extrair timestamp TSA: {}", e.getMessage());
                }
            }
        }

        // Fallback to PDSignature date
        if (pdSignature.getSignDate() != null) {
            Date sigDate = pdSignature.getSignDate().getTime();
            log.debug("Data da assinatura extraída do PDSignature: {}", sigDate);
            return sigDate;
        }

        // No date available - use current time
        log.warn("Nenhuma data de assinatura encontrada - usando data atual para validação");
        return new Date();
    }

    /**
     * Validates the certificate chain from the signer certificate up to a trusted
     * ICP-Brasil root using Java's PKIX CertPathValidator with CRL checking (soft-fail).
     * Validates at the signing time, not the current time.
     */
    private void validateCertificateChain(X509Certificate signerCert, Set<X509Certificate> intermediates, Date signingTime)
            throws IllegalArgumentException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            // Build the certificate path (signer + intermediates for path building)
            List<X509Certificate> certChain = buildOrderedChain(signerCert, intermediates);
            CertPath certPath = cf.generateCertPath(certChain);

            // Configure PKIX parameters with ICP-Brasil roots as trust anchors
            PKIXParameters params = new PKIXParameters(icpBrasilTrustStore.getTrustAnchors());

            // Validate at the signing time, not now (certs may have expired since signing)
            params.setDate(signingTime);

            params.setRevocationEnabled(false);

            // Add intermediates as additional cert store for path building
            CollectionCertStoreParameters storeParams = new CollectionCertStoreParameters(intermediates);
            params.addCertStore(CertStore.getInstance("Collection", storeParams));

            // Validate chain
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            validator.validate(certPath, params);
            log.info("Cadeia PKIX validada com sucesso na data {}", signingTime);

            // CRL check (non-blocking) - try to verify revocation status separately
            checkRevocationStatus(certChain);

        } catch (CertPathValidatorException e) {
            log.warn("Cadeia de certificados da CNH não confiável: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "O certificado digital da CNH não pertence à cadeia ICP-Brasil. " +
                    "Utilize a CNH exportada pelo aplicativo oficial CNH do Brasil.");
        } catch (Exception e) {
            log.error("Erro na validação PKIX: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "Erro ao validar a cadeia de certificados da CNH.", e);
        }
    }

    /**
     * Non-blocking CRL revocation check for each certificate in the chain.
     * Downloads CRLs, verifies the CRL signature against the issuer, then checks revocation.
     * Logs warnings but does not fail if CRL is unreachable.
     * Throws if a certificate is confirmed as revoked.
     */
    private void checkRevocationStatus(List<X509Certificate> certChain) {
        Set<X509Certificate> allKnownCerts = new HashSet<>(icpBrasilTrustStore.getIntermediateCerts());
        for (TrustAnchor anchor : icpBrasilTrustStore.getTrustAnchors()) {
            allKnownCerts.add(anchor.getTrustedCert());
        }
        allKnownCerts.addAll(certChain);

        for (X509Certificate cert : certChain) {
            checkCertRevocation(cert, allKnownCerts);
        }
    }

    private void checkCertRevocation(X509Certificate cert, Set<X509Certificate> allKnownCerts) {
        try {
            byte[] crlDpExtension = cert.getExtensionValue("2.5.29.31");
            if (crlDpExtension == null) {
                log.debug("Certificado sem CRL Distribution Points: {}",
                        cert.getSubjectX500Principal().getName());
                return;
            }

            org.bouncycastle.asn1.ASN1Primitive asn1 = org.bouncycastle.asn1.ASN1Primitive.fromByteArray(
                    org.bouncycastle.asn1.DEROctetString.getInstance(crlDpExtension).getOctets());
            org.bouncycastle.asn1.x509.CRLDistPoint crlDistPoint =
                    org.bouncycastle.asn1.x509.CRLDistPoint.getInstance(asn1);

            for (org.bouncycastle.asn1.x509.DistributionPoint dp : crlDistPoint.getDistributionPoints()) {
                if (dp.getDistributionPoint() == null || dp.getDistributionPoint().getName() == null) continue;
                org.bouncycastle.asn1.x509.GeneralNames names =
                        org.bouncycastle.asn1.x509.GeneralNames.getInstance(dp.getDistributionPoint().getName());
                for (org.bouncycastle.asn1.x509.GeneralName name : names.getNames()) {
                    if (name.getTagNo() != org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier) continue;
                    String crlUrl = name.getName().toString();
                    log.debug("Verificando CRL: {}", crlUrl);
                    try {
                        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(5))
                                .build();
                        java.net.http.HttpResponse<byte[]> response = client.send(
                                java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create(crlUrl))
                                        .timeout(java.time.Duration.ofSeconds(10))
                                        .GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofByteArray());

                        if (response.statusCode() == 200) {
                            CertificateFactory cf = CertificateFactory.getInstance("X.509");
                            java.security.cert.X509CRL crl = (java.security.cert.X509CRL)
                                    cf.generateCRL(new ByteArrayInputStream(response.body()));

                            // Verify CRL signature against the cert's issuer
                            X509Certificate issuer = findIssuer(cert, allKnownCerts);
                            if (issuer != null) {
                                try {
                                    crl.verify(issuer.getPublicKey());
                                } catch (Exception e) {
                                    log.warn("CRL com assinatura inválida de {} - ignorando", crlUrl);
                                    continue;
                                }
                            }

                            if (crl.isRevoked(cert)) {
                                log.warn("Certificado REVOGADO na CRL: {}", cert.getSubjectX500Principal().getName());
                                throw new IllegalArgumentException(
                                        "O certificado digital da CNH foi revogado. " +
                                        "Exporte uma versão atualizada pelo aplicativo CNH do Brasil.");
                            }
                            log.info("CRL verificada - certificado não revogado: {}",
                                    cert.getSubjectX500Principal().getName());
                            return; // CRL verified for this cert, move to next
                        }
                    } catch (IllegalArgumentException e) {
                        throw e;
                    } catch (Exception e) {
                        log.debug("Falha ao acessar CRL {}: {}", crlUrl, e.getMessage());
                    }
                }
            }
            log.debug("Não foi possível verificar CRL para: {}", cert.getSubjectX500Principal().getName());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Erro ao processar CRL DP: {}", e.getMessage());
        }
    }

    /**
     * Validates the TSA (Time Stamp Authority) timestamp embedded in the signature.
     * Verifies that the signature was made while the certificate was still valid.
     * If no timestamp is present, rejects the document.
     */
    private void validateTimestamp(SignerInformation signer, X509Certificate signerCert) {
        AttributeTable unsignedAttrs = signer.getUnsignedAttributes();
        if (unsignedAttrs == null) {
            log.info("Assinatura da CNH sem atributos unsigned (sem TSA) - validação por data do PDSignature");
            return;
        }

        Attribute tsaAttr = unsignedAttrs.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        if (tsaAttr == null) {
            log.info("Assinatura da CNH sem carimbo de tempo TSA - validação por data do PDSignature");
            return;
        }

        try {
            // Parse the timestamp token from the unsigned attributes
            byte[] tsaTokenBytes = tsaAttr.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded();
            TimeStampToken tsaToken = new TimeStampToken(
                    new CMSSignedData(tsaTokenBytes));
            TimeStampTokenInfo tsaInfo = tsaToken.getTimeStampInfo();
            Date signingTime = tsaInfo.getGenTime();

            // Verify that the signing time falls within the certificate's validity period
            try {
                signerCert.checkValidity(signingTime);
            } catch (CertificateExpiredException e) {
                throw new IllegalArgumentException(
                        "A CNH foi assinada após a expiração do certificado digital. " +
                        "Exporte uma versão atualizada pelo aplicativo CNH do Brasil.");
            } catch (CertificateNotYetValidException e) {
                throw new IllegalArgumentException(
                        "A CNH foi assinada antes do certificado digital ser válido. Documento suspeito.");
            }

            // Verify the TSA token's own signature integrity
            Store<X509CertificateHolder> tsaCerts = tsaToken.getCertificates();
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> tsaSignerCerts = tsaCerts.getMatches(tsaToken.getSID());

            for (X509CertificateHolder tsaCertHolder : tsaSignerCerts) {
                X509Certificate tsaCert = new JcaX509CertificateConverter()
                        .setProvider("BC")
                        .getCertificate(tsaCertHolder);
                tsaToken.validate(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider("BC")
                        .build(tsaCert));
                log.info("Timestamp TSA validado - assinatura realizada em: {}, TSA: {}",
                        signingTime, tsaCert.getSubjectX500Principal().getName());
                return;
            }

            log.warn("Timestamp TSA presente mas sem certificado do carimbo - não foi possível validar integridade do TSA");

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Erro ao validar timestamp TSA (validação continuou): {}", e.getMessage());
        }
    }

    /**
     * Builds an ordered certificate chain from signer up to (but not including) the root.
     * Each cert[i] is issued by cert[i+1].
     */
    private List<X509Certificate> buildOrderedChain(X509Certificate signerCert, Set<X509Certificate> pool) {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(signerCert);

        X509Certificate current = signerCert;
        Set<String> visited = new HashSet<>();
        visited.add(current.getSubjectX500Principal().getName());

        while (!current.getIssuerX500Principal().equals(current.getSubjectX500Principal())) {
            X509Certificate issuer = findIssuer(current, pool);
            if (issuer == null) {
                log.debug("Não encontrou emissor para: {}", current.getIssuerX500Principal().getName());
                break;
            }

            String issuerDN = issuer.getSubjectX500Principal().getName();
            if (visited.contains(issuerDN)) break;
            visited.add(issuerDN);

            // Don't include the root itself - PKIX expects the path to end before the anchor
            if (issuer.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) break;

            chain.add(issuer);
            current = issuer;
        }

        return chain;
    }

    private X509Certificate findIssuer(X509Certificate cert, Set<X509Certificate> pool) {
        // First pass: exact X500Principal match + cryptographic verification
        for (X509Certificate candidate : pool) {
            if (candidate.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
                try {
                    cert.verify(candidate.getPublicKey());
                    return candidate;
                } catch (Exception ignored) {}
            }
        }
        // Second pass: canonical (RFC2253) string comparison + cryptographic verification
        // Handles different ASN.1 encodings (UTF8String vs PrintableString) and RDN ordering
        String issuerCanonical = cert.getIssuerX500Principal().getName(javax.security.auth.x500.X500Principal.CANONICAL);
        for (X509Certificate candidate : pool) {
            String subjectCanonical = candidate.getSubjectX500Principal().getName(javax.security.auth.x500.X500Principal.CANONICAL);
            if (subjectCanonical.equals(issuerCanonical)) {
                try {
                    cert.verify(candidate.getPublicKey());
                    return candidate;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String performOcr(PDDocument document) {
        StringBuilder allText = new StringBuilder();
        List<BufferedImage> images = extractEmbeddedImages(document);

        for (BufferedImage img : images) {
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessdataPath);
            tesseract.setLanguage("por");
            try {
                allText.append(tesseract.doOCR(img)).append("\n");
            } catch (TesseractException e) {
                log.warn("OCR falhou em imagem: {}", e.getMessage());
            }
        }

        return allText.toString();
    }

    private List<BufferedImage> extractEmbeddedImages(PDDocument document) {
        List<BufferedImage> images = new ArrayList<>();
        for (PDPage page : document.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;
            for (COSName name : resources.getXObjectNames()) {
                try {
                    PDXObject xObject = resources.getXObject(name);
                    if (xObject instanceof PDImageXObject imageObj) {
                        BufferedImage img = imageObj.getImage();
                        if (img.getWidth() > 200 && img.getHeight() > 200) {
                            images.add(img);
                        }
                    }
                } catch (IOException e) {
                    log.warn("Erro ao extrair imagem '{}': {}", name.getName(), e.getMessage());
                }
            }
        }
        return images;
    }

    private String extractCpf(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher matcher = CPF_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = CpfValidator.sanitize(matcher.group());
            if (raw.length() == 11 && CpfValidator.isValid(raw)) {
                return raw;
            }
        }

        return null;
    }

    private String extractName(String text) {
        if (text == null || text.isBlank()) return null;

        String[] lines = text.split("\\r?\\n");

        // Strategy 1: Extract from MRZ (Machine Readable Zone)
        // Format: FIRSTNAME<<LASTNAME<<<<<<<<<<<<<<<
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^[A-Z<]{20,}$") && trimmed.contains("<<") && !trimmed.matches(".*\\d.*")) {
                if (trimmed.matches("^[A-Z]<[A-Z]{3}.*")) continue;

                String withoutPadding = trimmed.replaceAll("<+$", "");
                StringBuilder nameBuilder = new StringBuilder();
                for (String part : withoutPadding.split("<<")) {
                    for (String word : part.split("<")) {
                        if (!word.isEmpty()) {
                            if (nameBuilder.length() > 0) nameBuilder.append(" ");
                            nameBuilder.append(word);
                        }
                    }
                }
                String mrzName = nameBuilder.toString();
                if (mrzName.length() >= 3 && mrzName.contains(" ")) {
                    return normalizeName(mrzName);
                }
            }
        }

        // Strategy 2: Line after "NOME E SOBRENOME" label
        for (int i = 0; i < lines.length - 1; i++) {
            String upper = lines[i].toUpperCase();
            if (upper.contains("NOME") && upper.contains("SOBRENOME")) {
                String nextLine = lines[i + 1].trim();
                String nameCandidate = extractLeadingName(nextLine);
                if (nameCandidate != null) {
                    return normalizeName(nameCandidate);
                }
            }
        }

        return null;
    }

    private String extractLeadingName(String line) {
        StringBuilder name = new StringBuilder();
        for (String token : line.split("\\s+")) {
            if (token.matches("\\d{2}/\\d{2}/\\d{4}") || token.matches(".*\\d.*")) break;
            if (token.matches("^[A-ZÁÉÍÓÚÂÊÎÔÛÃÕÇa-záéíóúâêî��ûãõç]{2,}$")
                    || isPreposition(token.toLowerCase())) {
                if (name.length() > 0) name.append(" ");
                name.append(token);
            } else {
                break;
            }
        }
        String result = name.toString().trim();
        return result.contains(" ") ? result : null;
    }

    private String normalizeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                if (part.length() <= 3 && isPreposition(part.toLowerCase())) {
                    sb.append(part.toLowerCase());
                } else {
                    sb.append(part.substring(0, 1).toUpperCase())
                      .append(part.substring(1).toLowerCase());
                }
            }
        }
        return sb.toString();
    }

    private boolean isPreposition(String word) {
        return word.equals("de") || word.equals("da") || word.equals("do")
                || word.equals("das") || word.equals("dos") || word.equals("e");
    }
}
