package fr.freebox.remote;

import java.net.Socket;

/**
 * Fermeture de sockets hors du thread principal. Fermer un {@code SSLSocket} écrit un
 * message {@code close_notify} : Android refuse cette écriture réseau sur l'UI
 * ({@code NetworkOnMainThreadException}) et l'application plante.
 */
final class Sockets {

    private Sockets() {
    }

    /** Ferme la socket sans bloquer ni déclencher StrictMode si on est sur l'UI. */
    static void closeAsync(final Socket socket) {
        if (socket == null) {
            return;
        }
        Thread thread = new Thread(() -> closeNow(socket), "freebox-close");
        thread.setDaemon(true);
        thread.start();
    }

    static void closeNow(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
            // sans conséquence : la session est terminée de toute façon
        }
    }
}
