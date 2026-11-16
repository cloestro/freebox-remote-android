package fr.freebox.remote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

/** Découverte des Players Android TV sur le réseau local (scan du /24). */
final class Discovery {

    static final class Device {
        final String host;
        final String name;
        final String mac;

        Device(String host, String name, String mac) {
            this.host = host;
            this.name = name;
            this.mac = mac;
        }

        String label() {
            return name == null || name.isEmpty() ? host : name;
        }
    }

    interface Listener {
        void onFound(Device device);
    }

    private static final int THREADS = 48;
    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int TLS_TIMEOUT_MS = 1800;

    private Discovery() {
    }

    static List<Device> scan(ClientCert certificates, Listener listener) {
        String prefix = localPrefix();
        List<Device> found = Collections.synchronizedList(new ArrayList<>());
        if (prefix == null) {
            return found;
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CompletionService<Device> completion = new ExecutorCompletionService<>(pool);
        String ownAddress = localAddress();
        int submitted = 0;
        for (int i = 1; i < 255; i++) {
            String host = prefix + i;
            if (host.equals(ownAddress)) {
                continue;
            }
            completion.submit(() -> probe(host, certificates));
            submitted++;
        }

        long deadline = System.currentTimeMillis() + 15_000;
        try {
            for (int i = 0; i < submitted; i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Future<Device> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) {
                    break;
                }
                Device device = future.get();
                if (device != null) {
                    found.add(device);
                    if (listener != null) {
                        listener.onFound(device);
                    }
                }
            }
        } catch (Exception ignored) {
            // scan interrompu : on renvoie ce qu'on a trouvé
        } finally {
            pool.shutdownNow();
        }

        Collections.sort(found, new Comparator<Device>() {
            @Override
            public int compare(Device first, Device second) {
                boolean firstUnnamed = first.name == null;
                boolean secondUnnamed = second.name == null;
                if (firstUnnamed != secondUnnamed) {
                    return firstUnnamed ? 1 : -1;
                }
                return first.label().compareToIgnoreCase(second.label());
            }
        });
        return found;
    }

    private static Device probe(String host, ClientCert certificates) {
        if (!tcpOpen(host, 6467) && !tcpOpen(host, 6466)) {
            return null;
        }
        String name = null;
        String mac = null;
        SSLSocket socket = null;
        try {
            socket = certificates.connect(host, 6467, TLS_TIMEOUT_MS);
            Certificate[] chain = socket.getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate) {
                CertInfo info = CertInfo.parse((X509Certificate) chain[0]);
                name = info.name;
                mac = info.mac;
            }
        } catch (Exception ignored) {
            // L'appareil répond mais le certificat n'est pas lisible : on garde l'IP.
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // sans conséquence
                }
            }
        }
        return new Device(host, name, mac);
    }

    private static boolean tcpOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String localAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address
                            && address.isSiteLocalAddress()
                            && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // pas de réseau disponible
        }
        return null;
    }

    private static String localPrefix() {
        String address = localAddress();
        if (address == null) {
            return null;
        }
        int dot = address.lastIndexOf('.');
        return dot < 0 ? null : address.substring(0, dot + 1);
    }
}
