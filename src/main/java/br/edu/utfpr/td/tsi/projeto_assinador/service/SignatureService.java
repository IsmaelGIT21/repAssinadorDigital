package br.edu.utfpr.td.tsi.projeto_assinador.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerIdentityDTO;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import br.edu.utfpr.td.tsi.projeto_assinador.service.impl.CmsSignatureBuilder;
import javax.imageio.ImageIO;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class SignatureService {

    public static final String SIGNATURE_REASON = "Assinatura eletrônica de documento PDF";

    public record SignatureResult(
            String certificateSerialNumber,
            String certificateSubjectDN,
            LocalDateTime signedAt,
            String reason) {
    }

    @Value("${app.base-url:}")
    private String appBaseURL;

    private record StampRenderResult(PDImageXObject image, float width, float height) {
    }

    private static class DerivedCredential {
        final X509Certificate certificate;
        final PrivateKey privateKey;

        DerivedCredential(X509Certificate certificate, PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }
    }

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static final class SignaturePlacement {
        public static final float DEFAULT_WIDTH = 220f;
        public static final float DEFAULT_HEIGHT = 60f;

        private final int pageIndex;
        private final double x;
        private final double yFromBottom;

        public SignaturePlacement(int pageIndex, double x, double yFromBottom) {
            this.pageIndex = pageIndex;
            this.x = x;
            this.yFromBottom = yFromBottom;
        }

        static SignaturePlacement defaultPlacement() {
            return new SignaturePlacement(-1, 40, 40);
        }

        int getPageIndex() {
            return pageIndex;
        }

        double getX() {
            return x;
        }

        double getYFromBottom() {
            return yFromBottom;
        }
    }

    private final SpringTemplateEngine templateEngine;
    private final ResourceLoader resourceLoader;
    private String cachedLogoDataUri;

    public SignatureService(SpringTemplateEngine templateEngine, ResourceLoader resourceLoader) {
        this.templateEngine = templateEngine;
        this.resourceLoader = resourceLoader;
    }

    @Value("${certificate.path}")
    private String certificatePath;

    @Value("${certificate.password}")
    private String certificatePassword;

    @Value("${certificate.common_name}")
    private String certificateCommonName;

    @Value("${certificate.validity_time}")
    private int certificateValidityTime;

    @Value("${certificate.location}")
    private String certificateLocation;

    private DerivedCredential generateDerivedCertificate(SignerIdentityDTO signerIdentity) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream certStream = new FileInputStream(certificatePath)) {
            keyStore.load(certStream, certificatePassword.toCharArray());
        }

        String alias = keyStore.aliases().nextElement();
        X509Certificate caCert = (X509Certificate) keyStore.getCertificate(alias);

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair derivedKeyPair = keyGen.generateKeyPair();

        Date notBefore = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(notBefore);
        cal.add(Calendar.MINUTE, certificateValidityTime);
        Date notAfter = cal.getTime();

        X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());

        // Build X500Name with signer identity (ICP-Brasil compatible)
        X500NameBuilder subjectBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        subjectBuilder.addRDN(BCStyle.CN, signerIdentity.getFullName());
        subjectBuilder.addRDN(BCStyle.SERIALNUMBER, signerIdentity.getCpf());
        subjectBuilder.addRDN(BCStyle.E, signerIdentity.getEmail());
        subjectBuilder.addRDN(BCStyle.C, "BR");
        X500Name subject = subjectBuilder.build();

        BigInteger serial = new BigInteger(64, new SecureRandom());
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                serial,
                notBefore,
                notAfter,
                subject,
                derivedKeyPair.getPublic());

        // Add X.509 extensions for advanced electronic signature
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        certBuilder.addExtension(Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(derivedKeyPair.getPublic()));
        certBuilder.addExtension(Extension.authorityKeyIdentifier, false,
                extUtils.createAuthorityKeyIdentifier(caCert));
        certBuilder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        certBuilder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection));
        certBuilder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.rfc822Name, signerIdentity.getEmail())));

        PrivateKey caPrivateKey = (PrivateKey) keyStore.getKey(alias, certificatePassword.toCharArray());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caPrivateKey);

        X509CertificateHolder certHolder = certBuilder.build(signer);
        X509Certificate derivedCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder);
        derivedCert.verify(caCert.getPublicKey());

        return new DerivedCredential(derivedCert, derivedKeyPair.getPrivate());
    }

    public SignatureResult signPDF(byte[] originalPdfBytes, OutputStream output, SignerIdentityDTO signerIdentity,
            SignaturePlacement placement) throws Exception {
        SignaturePlacement effectivePlacement = placement != null ? placement : SignaturePlacement.defaultPlacement();

        // Compute document hash for the visual stamp
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(originalPdfBytes);
        StringBuilder hexHash = new StringBuilder();
        for (int i = 0; i < Math.min(8, hash.length); i++) {
            hexHash.append(String.format("%02x", hash[i]));
        }
        String documentHashPreview = hexHash.toString();

        DerivedCredential credential = generateDerivedCertificate(signerIdentity);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream certStream = new FileInputStream(certificatePath)) {
            ks.load(certStream, certificatePassword.toCharArray());
        }
        String alias = ks.aliases().nextElement();
        Certificate[] caChain = ks.getCertificateChain(alias);

        Certificate[] newChain = new Certificate[caChain.length + 1];
        newChain[0] = credential.certificate;
        System.arraycopy(caChain, 0, newChain, 1, caChain.length);

        byte[] stampPdfBytes = renderStampPdf(signerIdentity, documentHashPreview);

        ByteArrayOutputStream signedOutputBuffer = new ByteArrayOutputStream();
        LocalDateTime signedAt = LocalDateTime.now();

        try (PDDocument document = PDDocument.load(originalPdfBytes)) {
            if (document.isEncrypted()) {
                document.setAllSecurityToBeRemoved(true);
            }

            int numberOfPages = document.getNumberOfPages();
            if (numberOfPages == 0) {
                throw new IOException("Documento PDF sem páginas.");
            }
            int placementPage = effectivePlacement.getPageIndex();
            int pageIndex = placementPage >= 0 ? Math.min(placementPage, numberOfPages - 1)
                    : numberOfPages - 1;
            PDPage targetPage = document.getPage(pageIndex);

            float pageWidth = targetPage.getMediaBox().getWidth();
            float pageHeight = targetPage.getMediaBox().getHeight();

            float rectWidth = Math.min(SignaturePlacement.DEFAULT_WIDTH, pageWidth);
            float rectHeight = Math.min(SignaturePlacement.DEFAULT_HEIGHT, pageHeight);
            float safeX = Math.max(0, Math.min((float) effectivePlacement.getX(), pageWidth - rectWidth));
            float safeYFromBottom = Math.max(0,
                    Math.min((float) effectivePlacement.getYFromBottom(), pageHeight - rectHeight));

            PDRectangle widgetRect = new PDRectangle(safeX, safeYFromBottom, rectWidth, rectHeight);

            byte[] template = buildVisualSignatureTemplate(stampPdfBytes, widgetRect,
                    pageWidth, pageHeight);

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(signerIdentity.getFullName());
            signature.setContactInfo(signerIdentity.getEmail());
            signature.setLocation(certificateLocation);
            Calendar signatureDate = Calendar.getInstance();
            signatureDate.setTime(Date.from(signedAt.atZone(ZoneId.systemDefault()).toInstant()));
            signature.setReason(SIGNATURE_REASON);
            signature.setSignDate(signatureDate);

            CmsSignatureBuilder signatureImpl = new CmsSignatureBuilder(credential.privateKey, newChain);
            try (SignatureOptions signatureOptions = new SignatureOptions();
                    InputStream templateStream = new ByteArrayInputStream(template)) {
                signatureOptions.setVisualSignature(templateStream);
                signatureOptions.setPage(pageIndex);
                document.addSignature(signature, signatureImpl, signatureOptions);
                document.saveIncremental(signedOutputBuffer);
            }
        }

        output.write(signedOutputBuffer.toByteArray());
        output.flush();

        return new SignatureResult(
                credential.certificate.getSerialNumber().toString(),
                credential.certificate.getSubjectX500Principal().getName(),
                signedAt,
                SIGNATURE_REASON);
    }

    private byte[] buildVisualSignatureTemplate(byte[] stampPdfBytes, PDRectangle widgetRect,
            float pageWidth, float pageHeight) throws IOException {
        try (PDDocument template = new PDDocument();
                PDDocument stampSource = PDDocument.load(stampPdfBytes)) {

            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            template.addPage(page);

            PDFRenderer renderer = new PDFRenderer(stampSource);
            renderer.setSubsamplingAllowed(false);
            var bufferedImage = renderer.renderImageWithDPI(0, 180f, ImageType.ARGB);
            PDImageXObject stampImage = LosslessFactory.createFromImage(template, bufferedImage);

            PDAcroForm acroForm = new PDAcroForm(template);
            template.getDocumentCatalog().setAcroForm(acroForm);

            PDSignatureField signatureField = new PDSignatureField(acroForm);
            PDAnnotationWidget widget = signatureField.getWidgets().get(0);
            widget.setRectangle(widgetRect);
            widget.setPage(page);

            PDAppearanceStream apStream = new PDAppearanceStream(template);
            apStream.setBBox(new PDRectangle(0, 0, widgetRect.getWidth(), widgetRect.getHeight()));
            PDResources apResources = new PDResources();
            apStream.setResources(apResources);
            try (PDPageContentStream cs = new PDPageContentStream(template, apStream)) {
                cs.drawImage(stampImage, 0, 0, widgetRect.getWidth(), widgetRect.getHeight());
            }

            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.setNormalAppearance(apStream);
            widget.setAppearance(appearance);

            page.getAnnotations().add(widget);
            acroForm.setFields(java.util.Collections.singletonList(signatureField));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            template.save(out);
            return out.toByteArray();
        }
    }

    private void dumpDocumentSnapshot(byte[] documentBytes, String targetPath) {
        try {
            Path targetFile = Paths.get(targetPath);
            Path parent = targetFile.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(targetFile, documentBytes);
            System.out.println("[SignatureService] Snapshot salvo em " + targetFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Não foi possível salvar snapshot de depuração: " + e.getMessage());
        }
    }

    private void addVisualSignature(PDDocument document, SignerIdentityDTO signerIdentity,
            SignaturePlacement placement, String documentHashPreview) throws IOException {
        int numberOfPages = document.getNumberOfPages();
        if (numberOfPages == 0) {
            throw new IOException("Documento PDF sem páginas.");
        }

        int placementPage = placement.getPageIndex();
        int pageIndex = placementPage >= 0 ? Math.min(placementPage, numberOfPages - 1)
                : numberOfPages - 1;
        PDPage targetPage = document.getPage(pageIndex);

        float pageWidth = targetPage.getMediaBox().getWidth();
        float pageHeight = targetPage.getMediaBox().getHeight();

        StampRenderResult stampRenderResult = createStampForm(document, signerIdentity, documentHashPreview);
        if (stampRenderResult == null) {
            System.err.println("Não foi possível renderizar a estampa HTML; assinatura sem elemento visual.");
            return;
        }

        float rectWidth = Math.min(SignaturePlacement.DEFAULT_WIDTH, pageWidth);
        float rectHeight = Math.min(SignaturePlacement.DEFAULT_HEIGHT, pageHeight);

        float safeX = Math.max(0, Math.min((float) placement.getX(), pageWidth - rectWidth));
        float safeYFromBottom = Math.max(0, Math.min((float) placement.getYFromBottom(), pageHeight - rectHeight));

        try (PDPageContentStream contentStream = new PDPageContentStream(document, targetPage,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            contentStream.drawImage(stampRenderResult.image(), safeX, safeYFromBottom, rectWidth, rectHeight);
        } catch (Exception e) {
            System.err.println("Erro ao adicionar estampa renderizada: " + e.getMessage());
        }

        // Mark page tree as updated so saveIncremental includes the new content stream
        markForIncrementalSave(targetPage);
    }

    private void markForIncrementalSave(PDPage page) {
        org.apache.pdfbox.cos.COSBase contents = page.getCOSObject().getItem(org.apache.pdfbox.cos.COSName.CONTENTS);
        if (contents instanceof org.apache.pdfbox.cos.COSUpdateInfo cu) {
            cu.setNeedToBeUpdated(true);
        }
        if (contents instanceof org.apache.pdfbox.cos.COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                org.apache.pdfbox.cos.COSBase item = arr.getObject(i);
                if (item instanceof org.apache.pdfbox.cos.COSUpdateInfo itemCu) {
                    itemCu.setNeedToBeUpdated(true);
                }
            }
        }
        org.apache.pdfbox.pdmodel.PDResources resources = page.getResources();
        if (resources != null) {
            resources.getCOSObject().setNeedToBeUpdated(true);
        }
        page.getCOSObject().setNeedToBeUpdated(true);
        org.apache.pdfbox.cos.COSBase parent = page.getCOSObject().getItem(org.apache.pdfbox.cos.COSName.PARENT);
        while (parent instanceof org.apache.pdfbox.cos.COSUpdateInfo parentCu) {
            parentCu.setNeedToBeUpdated(true);
            if (parent instanceof org.apache.pdfbox.cos.COSDictionary parentDict) {
                parent = parentDict.getItem(org.apache.pdfbox.cos.COSName.PARENT);
            } else {
                break;
            }
        }
    }

    private StampRenderResult createStampForm(PDDocument targetDocument, SignerIdentityDTO signerIdentity,
            String documentHashPreview) throws IOException {
        byte[] stampPdfBytes;
        try {
            stampPdfBytes = renderStampPdf(signerIdentity, documentHashPreview);
        } catch (IOException e) {
            System.err.println("Falha ao gerar estampa HTML: " + e.getMessage());
            return null;
        }

        try (PDDocument stampDocument = PDDocument.load(stampPdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(stampDocument);
            renderer.setSubsamplingAllowed(false);
            float renderDpi = 180f;
            var bufferedImage = renderer.renderImageWithDPI(0, renderDpi, ImageType.ARGB);

            dumpStampDebugArtifacts(stampPdfBytes, bufferedImage);

            PDImageXObject imageObject = LosslessFactory.createFromImage(targetDocument, bufferedImage);

            return new StampRenderResult(imageObject,
                    SignaturePlacement.DEFAULT_WIDTH,
                    SignaturePlacement.DEFAULT_HEIGHT);
        }
    }

    private void dumpStampDebugArtifacts(byte[] stampPdfBytes, java.awt.image.BufferedImage image) {
        try {
            Path targetDir = Paths.get("target");
            if (Files.notExists(targetDir)) {
                Files.createDirectories(targetDir);
            }
            Files.write(targetDir.resolve("signature-stamp-debug.pdf"), stampPdfBytes);
            ImageIO.write(image, "png", targetDir.resolve("signature-stamp-debug.png").toFile());
            System.out.printf("[SignatureService] Estampa renderizada: %d x %d px%n", image.getWidth(),
                    image.getHeight());
        } catch (IOException e) {
            System.err.println("Não foi possível gravar artefatos de depuração da estampa: " + e.getMessage());
        }
    }

    private byte[] renderStampPdf(SignerIdentityDTO signerIdentity, String documentHashPreview) throws IOException {
        Context context = new Context();
        context.setVariable("elementId", null);
        context.setVariable("userName", shortenName(signerIdentity.getFullName()));
        context.setVariable("userEmail", signerIdentity.getEmail());
        context.setVariable("cpfMasked", maskCpf(signerIdentity.getCpf()));
        context.setVariable("signDateTime", java.time.format.DateTimeFormatter
                .ofPattern("dd/MM/yyyy HH:mm:ss").format(java.time.LocalDateTime.now()));
        context.setVariable("documentHash", documentHashPreview);
        String logoDataUri = loadLogoDataUri();
        String logoSrc = logoDataUri != null ? logoDataUri : resolveLogoFileUri();
        context.setVariable("logoSrc", logoSrc);
        context.setVariable("extraClasses", null);
        context.setVariable("stampWidth", SignaturePlacement.DEFAULT_WIDTH);
        context.setVariable("stampHeight", SignaturePlacement.DEFAULT_HEIGHT);
        context.setVariable("validatorURL", appBaseURL + "/validator");

        String html = templateEngine.process("pdf/signature-stamp", context);

        String baseUri = null;
        try {
            Resource staticResource = resourceLoader.getResource("classpath:/static/");
            baseUri = staticResource.getURL().toExternalForm();
        } catch (IOException ignored) {
            // Default to data URI only when base URI cannot be resolved.
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, baseUri);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (Exception e) {
            throw new IOException("Erro ao renderizar HTML da estampa", e);
        }
    }

    private String loadLogoDataUri() {
        if (cachedLogoDataUri != null) {
            return cachedLogoDataUri;
        }

        try {
            Resource logoResource = resourceLoader.getResource("classpath:/static/images/utfpr_logo.png");
            if (!logoResource.exists()) {
                logoResource = resourceLoader.getResource("classpath:/resources/static/images/utfpr_logo.png");
            }
            try (InputStream inputStream = logoResource.getInputStream()) {
                byte[] logoBytes = inputStream.readAllBytes();
                cachedLogoDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(logoBytes);
            }
        } catch (IOException e) {
            System.err.println("Não foi possível carregar o logo para a estampa: " + e.getMessage());
            cachedLogoDataUri = null;
        }

        return cachedLogoDataUri;
    }

    private String resolveLogoFileUri() {
        try {
            Resource logoResource = resourceLoader.getResource("classpath:/static/images/utfpr_logo.png");
            if (!logoResource.exists()) {
                logoResource = resourceLoader.getResource("classpath:/resources/static/images/utfpr_logo.png");
            }
            return logoResource.getURL().toExternalForm();
        } catch (IOException e) {
            System.err.println("Não foi possível localizar o logo como arquivo: " + e.getMessage());
            return "/images/utfpr_logo.png";
        }
    }

    public static String shortenName(String fullName) {
        if (fullName == null) return null;
        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) return trimmed;
        String[] parts = trimmed.split("\\s+");
        if (parts.length <= 2) return trimmed;
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(' ').append(Character.toUpperCase(parts[i].charAt(0))).append('.');
            }
        }
        sb.append(' ').append(parts[parts.length - 1]);
        return sb.toString();
    }

    private String maskCpf(String cpf) {
        if (cpf == null || cpf.length() != 11) return "***.***.***-**";
        return "***." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-**";
    }
}
