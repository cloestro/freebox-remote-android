package fr.freebox.remote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Appairage « Google TV pairing » (port 6467).
 * Le Player affiche un code hexadécimal à 6 caractères ; on calcule un secret
 * SHA-256 à partir des clés RSA et du code, puis on l'envoie.
 */
final class PairingClient {

    interface Listener {
        /** La session est prête : l'utilisateur peut saisir le code affiché sur la TV. */
        void onReady(String serverName);

        void onPaired();

        void onError(String message);
    }

    private static final int PROTOCOL_VERSION = 2;
    private static final int STATUS_OK = 200;
    private static final int STATUS_BAD_SECRET = 402;

    private final String host;
    private final ClientCert certificates;
    private final Listener listener;

    private final AtomicBoolean finished = new AtomicBoolean(false);
    private SSLSocket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private X509Certificate peerCertificate;
    private volatile boolean ready;
    private volatile boolean codeSent;

    PairingClient(String host, ClientCert certificates, Listener listener) {
        this.host = host;
        this.certificates = certificates;
        this.listener = listener;
    }

    void start() {
        Thread thread = new Thread(this::run, "freebox-pairing");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            socket = certificates.connect(host, 6467, 8000);
            // L'utilisateur peut avoir besoin de temps pour lire le code sur la TV.
            socket.setSoTimeout(120_000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());

            Certificate[] chain = socket.getSession().getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                fail("Certificat du Player illisible.");
                return;
            }
            peerCertificate = (X509Certificate) chain[0];

            send(new Proto()
                    .varint(1, PROTOCOL_VERSION)
                    .varint(2, STATUS_OK)
                    .message(10, new Proto()
                            .string(1, "atvremote")
                            .string(2, "Freebox Remote")));

            while (!finished.get()) {
                byte[] raw = Frames.read(input);
                Proto.Reader reader = new Proto.Reader(raw);
                int status = STATUS_OK;
                int kind = 0;
                while (reader.next()) {
                    if (reader.field == 2 && reader.wire == 0) {
                        status = (int) reader.varint;
                    } else if (reader.field == 11 || reader.field == 20
                            || reader.field == 31 || reader.field == 41) {
                        kind = reader.field;
                    }
                }

                if (status == STATUS_BAD_SECRET) {
                    fail("Code incorrect ou expiré. Réessayez.");
                    return;
                }
                if (status != STATUS_OK) {
                    fail("Le Player a refusé l'appairage (statut " + status + ").");
                    return;
                }

                if (kind == 11) { // pairing_request_ack
                    send(new Proto()
                            .varint(1, PROTOCOL_VERSION)
                            .varint(2, STATUS_OK)
                            .message(20, new Proto()
                                    .message(1, new Proto().varint(1, 3).varint(2, 6))
                                    .varint(3, 1)));
                } else if (kind == 20) { // options
                    send(new Proto()
                            .varint(1, PROTOCOL_VERSION)
                            .varint(2, STATUS_OK)
                            .message(30, new Proto()
                                    .message(1, new Proto().varint(1, 3).varint(2, 6))
                                    .varint(2, 1)));
                } else if (kind == 31) { // configuration_ack
                    if (!ready) {
                        ready = true;
                        CertInfo info = CertInfo.parse(peerCertificate);
                        listener.onReady(info.name);
                    }
                } else if (kind == 41) { // secret_ack
                    if (finished.compareAndSet(false, true)) {
                        listener.onPaired();
                    }
                    close();
                    return;
                }
            }
        } catch (SocketTimeoutException error) {
            fail("Délai dépassé : le Player n'a pas répondu.");
        } catch (SSLException error) {
            fail("Connexion sécurisée impossible. Vérifiez que le Player est allumé.");
        } catch (IOException error) {
            fail("Appairage interrompu (" + safeMessage(error) + ").");
        } catch (Exception error) {
            fail("Appairage impossible (" + safeMessage(error) + ").");
        } finally {
            close();
        }
    }

    /** Envoie le code à 6 caractères hexadécimaux (majuscules ou minuscules). */
    void submitCode(String rawCode) {
        String code = normalize(rawCode);
        if (code.length() != 6 || !isHex(code)) {
            listener.onError("Le code doit contenir 6 caractères : 0-9 et A-F.");
            return;
        }
        if (!ready) {
            listener.onError("La session d'appairage n'est pas encore prête.");
            return;
        }
        if (codeSent) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                byte[] secret = computeSecret(code);
                if (secret == null) {
                    listener.onError("Code incorrect. Vérifiez les 6 caractères affichés sur la TV.");
                    return;
                }
                codeSent = true;
                send(new Proto()
                        .varint(1, PROTOCOL_VERSION)
                        .varint(2, STATUS_OK)
                        .message(40, new Proto().bytes(1, secret)));
            } catch (Exception error) {
                fail("Impossible de valider le code (" + safeMessage(error) + ").");
            }
        }, "freebox-pairing-code");
        thread.setDaemon(true);
        thread.start();
    }

    void cancel() {
        finished.set(true);
        close();
    }

    /** Vrai tant que la session peut encore recevoir un code. */
    boolean isAlive() {
        return !finished.get();
    }

    private byte[] computeSecret(String code) throws Exception {
        X509Certificate client = certificates.certificate();
        if (peerCertificate == null) {
            return null;
        }
        RSAPublicKey clientKey = (RSAPublicKey) client.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) peerCertificate.getPublicKey();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(unsigned(clientKey.getModulus()));
        digest.update(unsigned(clientKey.getPublicExponent()));
        digest.update(unsigned(serverKey.getModulus()));
        digest.update(unsigned(serverKey.getPublicExponent()));
        digest.update(hexToBytes(code.substring(2)));
        byte[] hash = digest.digest();

        int expectedFirstByte = Integer.parseInt(code.substring(0, 2), 16);
        if ((hash[0] & 0xFF) != expectedFirstByte) {
            return null;
        }
        return hash;
    }

    private synchronized void send(Proto message) throws IOException {
        if (output == null) {
            throw new IOException("connexion fermée");
        }
        Frames.write(output, message.toByteArray());
    }

    private void fail(String message) {
        if (finished.compareAndSet(false, true)) {
            listener.onError(message);
        }
        close();
    }

    private void close() {
        finished.set(true);
        Sockets.closeAsync(socket);
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int offset = 0;
        while (offset < bytes.length - 1 && bytes[offset] == 0) {
            offset++;
        }
        return Arrays.copyOfRange(bytes, offset, bytes.length);
    }

    private static String normalize(String code) {
        return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
    }

    private static boolean isHex(String code) {
        for (int i = 0; i < code.length(); i++) {
            if (Character.digit(code.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }
}
