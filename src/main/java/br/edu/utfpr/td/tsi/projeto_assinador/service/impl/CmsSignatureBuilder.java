package br.edu.utfpr.td.tsi.projeto_assinador.service.impl;

import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Arrays;

public class CmsSignatureBuilder implements SignatureInterface {

    private final PrivateKey privateKey;
    private final Certificate[] chain;

    public CmsSignatureBuilder(PrivateKey privateKey, Certificate[] chain) {
        this.privateKey = privateKey;
        this.chain = chain;
    }

    @Override
    public byte[] sign(InputStream content) {
        try {
            byte[] contentBytes = content.readAllBytes();
            CMSProcessableByteArray contentData = new CMSProcessableByteArray(contentBytes);

            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            var builder = new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().build());

            generator.addSignerInfoGenerator(builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(privateKey),
                    (java.security.cert.X509Certificate) chain[0]));

            generator.addCertificates(new JcaCertStore(Arrays.asList(chain)));

            return generator.generate(contentData, false).getEncoded();

        } catch (Exception e) {
            throw new RuntimeException("Erro ao assinar o PDF", e);
        }
    }
}
