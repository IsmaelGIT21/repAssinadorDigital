package br.edu.utfpr.td.tsi.projeto_assinador.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class IcpBrasilTrustStoreService {

    private static final String ICP_BRASIL_BUNDLE_URL =
            "https://acraiz.icpbrasil.gov.br/credenciadas/CertificadosAC-ICP-Brasil/ACcompactado.zip";

    @Value("${icp-brasil.cache-dir:${user.home}/.cache/icp-brasil}")
    private String cacheDir;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Set<TrustAnchor> trustAnchors = new HashSet<>();
    private Set<X509Certificate> intermediateCerts = new HashSet<>();

    public Set<TrustAnchor> getTrustAnchors() {
        lock.readLock().lock();
        try {
            return new HashSet<>(trustAnchors);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<X509Certificate> getIntermediateCerts() {
        lock.readLock().lock();
        try {
            return new HashSet<>(intermediateCerts);
        } finally {
            lock.readLock().unlock();
        }
    }

    @PostConstruct
    public void init() {
        Path cachePath = Path.of(cacheDir);
        try {
            Files.createDirectories(cachePath);
        } catch (IOException e) {
            log.error("Não foi possível criar diretório de cache: {}", cacheDir, e);
        }

        // Try: download fresh -> fallback to cache
        if (!tryDownloadAndLoad()) {
            if (!tryLoadFromCache()) {
                log.error("Nenhum certificado ICP-Brasil disponível. " +
                        "Validação de CNH não funcionará até que o download seja bem-sucedido.");
            }
        }
    }

    @Scheduled(cron = "0 0 3 * * *") // Daily at 3 AM
    public void refreshCertificates() {
        log.info("Atualizando certificados ICP-Brasil...");
        if (tryDownloadAndLoad()) {
            log.info("Certificados ICP-Brasil atualizados com sucesso");
        } else {
            log.warn("Falha ao atualizar certificados ICP-Brasil - mantendo versão em cache");
        }
    }

    private boolean tryDownloadAndLoad() {
        try {
            log.info("Baixando certificados ICP-Brasil de {}...", ICP_BRASIL_BUNDLE_URL);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ICP_BRASIL_BUNDLE_URL))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            // Try default Java trust store first, fallback to custom with bundled intermediate
            HttpResponse<byte[]> response;
            try {
                HttpClient defaultClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                response = defaultClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception sslEx) {
                log.debug("Download com trust store padrão falhou ({}), tentando com intermediário bundled...",
                        sslEx.getMessage());
                HttpClient fallbackClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .sslContext(buildSslContext())
                        .build();
                response = fallbackClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            }

            if (response.statusCode() != 200) {
                log.warn("Download falhou com status HTTP {}", response.statusCode());
                return false;
            }

            byte[] zipBytes = response.body();
            Set<X509Certificate> allCerts = extractCertsFromZip(zipBytes);

            if (allCerts.isEmpty()) {
                log.warn("ZIP baixado não contém certificados válidos");
                return false;
            }

            // Save to cache
            Path zipCache = Path.of(cacheDir, "ACcompactado.zip");
            Files.write(zipCache, zipBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            applyLoadedCerts(allCerts);
            log.info("Certificados ICP-Brasil baixados e carregados: {} certificados", allCerts.size());
            return true;

        } catch (Exception e) {
            log.warn("Falha ao baixar certificados ICP-Brasil: {}", e.getMessage());
            return false;
        }
    }

    private boolean tryLoadFromCache() {
        Path zipCache = Path.of(cacheDir, "ACcompactado.zip");
        if (!Files.exists(zipCache)) {
            log.info("Nenhum cache de certificados ICP-Brasil encontrado em {}", zipCache);
            return false;
        }

        try {
            byte[] zipBytes = Files.readAllBytes(zipCache);
            Set<X509Certificate> allCerts = extractCertsFromZip(zipBytes);

            if (allCerts.isEmpty()) {
                log.warn("Cache de certificados vazio ou corrompido");
                return false;
            }

            applyLoadedCerts(allCerts);
            log.info("Certificados ICP-Brasil carregados do cache: {} certificados", allCerts.size());
            return true;

        } catch (Exception e) {
            log.warn("Falha ao carregar certificados do cache: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Builds an SSLContext that trusts Java's default CAs plus the Let's Encrypt E7
     * intermediate, which the ICP-Brasil server doesn't include in its TLS handshake.
     */
    private SSLContext buildSslContext() throws Exception {
        // Build custom keystore: Java cacerts + Let's Encrypt E7 intermediate
        KeyStore customKs = KeyStore.getInstance(KeyStore.getDefaultType());
        customKs.load(null, null);

        // Add all default trusted certs from Java's cacerts
        KeyStore cacerts = getCacertsKeyStore();
        var aliases = cacerts.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            customKs.setCertificateEntry(alias, cacerts.getCertificate(alias));
        }

        // Add Let's Encrypt E7 intermediate (bundled because ICP-Brasil server omits it in TLS handshake)
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream e7Stream = getClass().getClassLoader().getResourceAsStream("ssl/letsencrypt-e7.pem")) {
            if (e7Stream != null) {
                X509Certificate e7Cert = (X509Certificate) cf.generateCertificate(e7Stream);
                customKs.setCertificateEntry("letsencrypt-e7", e7Cert);
            }
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(customKs);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext;
    }

    private KeyStore getCacertsKeyStore() throws Exception {
        String javaHome = System.getProperty("java.home");
        Path cacertsPath = Path.of(javaHome, "lib", "security", "cacerts");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream is = Files.newInputStream(cacertsPath)) {
            ks.load(is, "changeit".toCharArray());
        }
        return ks;
    }

    private void applyLoadedCerts(Set<X509Certificate> allCerts) {
        Set<TrustAnchor> roots = new HashSet<>();
        Set<X509Certificate> intermediates = new HashSet<>();

        for (X509Certificate cert : allCerts) {
            if (isSelfSigned(cert)) {
                roots.add(new TrustAnchor(cert, null));
            } else {
                intermediates.add(cert);
            }
        }

        lock.writeLock().lock();
        try {
            this.trustAnchors = roots;
            this.intermediateCerts = intermediates;
        } finally {
            lock.writeLock().unlock();
        }

        log.info("Trust store atualizado: {} raízes, {} intermediários", roots.size(), intermediates.size());
    }

    private boolean isSelfSigned(X509Certificate cert) {
        try {
            cert.verify(cert.getPublicKey());
            return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
        } catch (Exception e) {
            return false;
        }
    }

    private Set<X509Certificate> extractCertsFromZip(byte[] zipBytes) throws Exception {
        Set<X509Certificate> certs = new HashSet<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".crt")) {
                    continue;
                }
                try {
                    byte[] certBytes = zis.readAllBytes();
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(
                            new ByteArrayInputStream(certBytes));
                    certs.add(cert);
                } catch (Exception e) {
                    log.debug("Erro ao parsear certificado {}: {}", entry.getName(), e.getMessage());
                }
            }
        }

        return certs;
    }
}
