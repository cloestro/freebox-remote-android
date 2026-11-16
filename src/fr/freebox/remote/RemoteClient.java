package fr.freebox.remote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Session de télécommande (port 6466) : poignée de main RemoteMessage,
 * réponse aux pings, envoi des touches et des liens d'application.
 */
final class RemoteClient {

    static final int STATUS_CONNECTING = 0;
    static final int STATUS_CONNECTED = 1;
    static final int STATUS_OFFLINE = 2;
    static final int STATUS_NEEDS_PAIRING = 3;

    static final int DIRECTION_START_LONG = 1;
    static final int DIRECTION_END_LONG = 2;
    static final int DIRECTION_SHORT = 3;

    // PING | KEY | POWER | VOLUME | APP_LINK (pas d'IME : évite le clavier sur la TV)
    private static final int FEATURES = 1 | 2 | 32 | 64 | 512;

    interface Listener {
        void onStatus(int status, String detail);

        void onPower(boolean on);

        void onVolume(int level, int max, boolean muted);
    }

    private final String host;
    private final ClientCert certificates;
    private final Listener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object sendLock = new Object();
    // Les touches partent du thread d'interface : on les met en file pour ne jamais
    // écrire sur la socket depuis l'UI (StrictMode interdit le réseau sur le main thread).
    private final LinkedBlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();

    private volatile boolean connected;
    private volatile SSLSocket socket;
    private volatile DataOutputStream output;
    private volatile Boolean powerOn;
    private Thread thread;
    private Thread writer;

    RemoteClient(String host, ClientCert certificates, Listener listener) {
        this.host = host;
        this.certificates = certificates;
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::run, "freebox-remote");
        thread.setDaemon(true);
        thread.start();
        writer = new Thread(this::writeLoop, "freebox-remote-writer");
        writer.setDaemon(true);
        writer.start();
    }

    void stop() {
        running.set(false);
        connected = false;
        outgoing.clear();
        closeSocket();
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            thread = null;
        }
        Thread currentWriter = writer;
        if (currentWriter != null) {
            currentWriter.interrupt();
            writer = null;
        }
    }

    boolean isConnected() {
        return connected;
    }

    Boolean isPowerOn() {
        return powerOn;
    }

    boolean sendKey(int keyCode, int direction) {
        if (!connected) {
            return false;
        }
        enqueue(new Proto()
                .message(10, new Proto()
                        .varint(1, keyCode)
                        .varint(2, direction))
                .toByteArray());
        return true;
    }

    boolean launch(String link) {
        if (!connected) {
            return false;
        }
        enqueue(new Proto().message(90, new Proto().string(1, link)).toByteArray());
        return true;
    }

    private void enqueue(byte[] frame) {
        if (!outgoing.offer(frame)) {
            dropConnection();
        }
    }

    /** Envoie les touches en attente, hors du thread d'interface. */
    private void writeLoop() {
        while (running.get()) {
            byte[] frame;
            try {
                frame = outgoing.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == null) {
                continue;
            }
            DataOutputStream stream = output;
            if (!connected || stream == null) {
                continue;
            }
            try {
                synchronized (sendLock) {
                    Frames.write(stream, frame);
                }
            } catch (IOException error) {
                dropConnection();
            }
        }
    }

    private void run() {
        long delay = 1000;
        while (running.get()) {
            try {
                listener.onStatus(STATUS_CONNECTING, null);
                connectAndRead();
                if (!running.get()) {
                    return;
                }
                // connectAndRead ne rend la main qu'en cas d'arrêt demandé.
            } catch (SSLException error) {
                if (!running.get()) {
                    return;
                }
                listener.onStatus(STATUS_NEEDS_PAIRING, "Ce Player doit être appairé.");
                return;
            } catch (Exception error) {
                if (!running.get()) {
                    return;
                }
                listener.onStatus(STATUS_OFFLINE, PairingClient.safeMessage(error));
                sleep(delay);
                delay = Math.min(delay * 2, 10_000);
            }
        }
    }

    private void connectAndRead() throws Exception {
        SSLSocket newSocket = certificates.connect(host, 6466, 8000);
        // Le Player envoie des pings réguliers ; sans réponse il ferme la session.
        // Un silence prolongé signifie une connexion morte : on la recycle.
        newSocket.setSoTimeout(120_000);
        DataInputStream input = new DataInputStream(newSocket.getInputStream());
        DataOutputStream newOutput = new DataOutputStream(newSocket.getOutputStream());

        socket = newSocket;
        output = newOutput;

        try {
            Certificate[] chain = newSocket.getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate) {
                CertInfo.parse((X509Certificate) chain[0]);
            }
            while (running.get()) {
                byte[] raw;
                try {
                    raw = Frames.read(input);
                } catch (SocketTimeoutException error) {
                    throw new IOException("Le Player ne répond plus.");
                }
                handle(raw);
            }
        } finally {
            connected = false;
            output = null;
            closeSocket();
        }
    }

    private void handle(byte[] raw) throws IOException {
        Proto.Reader reader = new Proto.Reader(raw);
        boolean ping = false;
        long pingValue = 0;
        while (reader.next()) {
            switch (reader.field) {
                case 1: // remote_configure
                    sendConfigure();
                    markConnected();
                    break;
                case 2: // remote_set_active
                    send(new Proto().message(2, new Proto().varint(1, FEATURES)));
                    break;
                case 8: { // remote_ping_request
                    Proto.Reader pingReader = new Proto.Reader(reader.bytes);
                    while (pingReader.next()) {
                        if (pingReader.field == 1 && pingReader.wire == 0) {
                            ping = true;
                            pingValue = pingReader.varint;
                        }
                    }
                    break;
                }
                case 40: { // remote_start
                    Proto.Reader startReader = new Proto.Reader(reader.bytes);
                    while (startReader.next()) {
                        if (startReader.field == 1 && startReader.wire == 0) {
                            powerOn = startReader.varint != 0;
                            listener.onPower(powerOn);
                        }
                    }
                    break;
                }
                case 50: // remote_set_volume_level
                    handleVolume(reader.bytes);
                    break;
                case 3: // remote_error : rien à faire, le Player se débrouille
                default:
                    break;
            }
        }
        if (ping) {
            send(new Proto().message(9, new Proto().varint(1, pingValue)));
        }
    }

    private void handleVolume(byte[] raw) {
        Proto.Reader reader = new Proto.Reader(raw);
        int max = 0;
        int level = 0;
        boolean muted = false;
        while (reader.next()) {
            if (reader.field == 6 && reader.wire == 0) {
                max = (int) reader.varint;
            } else if (reader.field == 7 && reader.wire == 0) {
                level = (int) reader.varint;
            } else if (reader.field == 8 && reader.wire == 0) {
                muted = reader.varint != 0;
            }
        }
        listener.onVolume(level, max, muted);
    }

    private void sendConfigure() throws IOException {
        Proto device = new Proto()
                .varint(3, 1)
                .string(4, "1")
                .string(5, "atvremote")
                .string(6, "1.0.0");
        send(new Proto().message(1, new Proto()
                .varint(1, FEATURES)
                .message(2, device)));
    }

    private void markConnected() {
        if (!connected) {
            connected = true;
            listener.onStatus(STATUS_CONNECTED, null);
        }
    }

    private synchronized void send(Proto message) throws IOException {
        DataOutputStream stream = output;
        synchronized (sendLock) {
            if (stream == null) {
                throw new IOException("Connexion fermée");
            }
            Frames.write(stream, message.toByteArray());
        }
    }

    private void dropConnection() {
        connected = false;
        closeSocket();
    }

    private void closeSocket() {
        SSLSocket current = socket;
        socket = null;
        Sockets.closeAsync(current);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
