package fr.freebox.remote;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Certificat client auto-signé, stocké en PKCS#12, et contexte TLS.
 * Le Player s'appuie sur ce certificat pour se souvenir de l'appairage.
 */
final class ClientCert {

    private static final String ALIAS = "client";
    private static final char[] PASSWORD = "freebox-remote".toCharArray();
    private static final String COMMON_NAME = "Freebox Remote";

    private final File file;
    private final Object lock = new Object();
    private SSLContext cachedContext;

    ClientCert(File filesDir) {
        this.file = new File(filesDir, "client.p12");
    }

    /** Génère le certificat au premier lancement (bloquant : appeler hors du thread UI). */
    void ensure() throws Exception {
        synchronized (lock) {
            if (file.isFile()) {
                return;
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            X509Certificate certificate = selfSign(pair);

            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD, new Certificate[]{certificate});

            File temporary = new File(file.getParentFile(), "client.p12.tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                store.store(out, PASSWORD);
            }
            if (temporary.isFile() && !temporary.renameTo(file)) {
                throw new IllegalStateException("Impossible d'enregistrer le certificat client.");
            }
            // Le fichier contient la clé privée : lecture réservée à l'application.
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, true);
            cachedContext = null;
        }
    }

    X509Certificate certificate() throws Exception {
        synchronized (lock) {
            return (X509Certificate) store().getCertificate(ALIAS);
        }
    }

    PrivateKey privateKey() throws Exception {
        synchronized (lock) {
            return (PrivateKey) store().getKey(ALIAS, PASSWORD);
        }
    }

    SSLContext sslContext() throws Exception {
        synchronized (lock) {
            if (cachedContext != null) {
                return cachedContext;
            }
            KeyStore store = store();
            KeyManagerFactory managers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, PASSWORD);

            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), trustAll, new SecureRandom());
            cachedContext = context;
            return context;
        }
    }

    /** Ouvre une connexion TLS vers le Player (certificat client présenté). */
    SSLSocket connect(String host, int port, int timeoutMillis) throws Exception {
        SSLSocket socket = (SSLSocket) sslContext().getSocketFactory().createSocket();
        socket.connect(new java.net.InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        socket.setTcpNoDelay(true);
        socket.startHandshake();
        return socket;
    }

    private KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(file)) {
            store.load(in, PASSWORD);
        }
        return store;
    }

    private static X509Certificate selfSign(KeyPair pair) throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        Date notBefore = new Date(System.currentTimeMillis() - 86_400_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L);

        byte[] signatureAlgorithm = Der.algorithm("1.2.840.113549.1.1.11"); // sha256WithRSAEncryption
        byte[] name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(COMMON_NAME))));
        byte[] subjectPublicKeyInfo = Der.seq(
                Der.algorithm("1.2.840.113549.1.1.1"), // rsaEncryption
                Der.bitString(Der.rsaPublicKey(publicKey)));
        byte[] extensions = Der.context(3, true, Der.seq(
                // BasicConstraints : CA=true, pathLen=0 (comme la librairie de référence)
                Der.seq(
                        Der.oid("2.5.29.19"),
                        Der.octet(Der.seq(Der.bool(true), Der.integer(BigInteger.ZERO)))),
                // SubjectAltName : dNSName = nom du client
                Der.seq(
                        Der.oid("2.5.29.17"),
                        Der.octet(Der.seq(Der.dnsName(COMMON_NAME))))));

        byte[] tbsCertificate = Der.seq(
                Der.context(0, true, Der.integer(2)), // version v3
                Der.integer(1000),
                signatureAlgorithm,
                name,
                Der.seq(Der.utc(notBefore), Der.utc(notAfter)),
                name,
                subjectPublicKeyInfo,
                extensions);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbsCertificate);
        byte[] signature = signer.sign();

        byte[] encoded = Der.seq(tbsCertificate, signatureAlgorithm, Der.bitString(signature));
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(encoded));
    }
}
