package fr.freebox.remote;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Messages du protocole Android TV : longueur en varint puis protobuf brut. */
final class Frames {

    private static final int MAX_MESSAGE = 1 << 20;

    private Frames() {
    }

    static void write(OutputStream out, byte[] message) throws IOException {
        writeVarint(out, message.length);
        out.write(message);
        out.flush();
    }

    static byte[] read(InputStream in) throws IOException {
        int length = readVarint(in);
        if (length < 0 || length > MAX_MESSAGE) {
            throw new IOException("Taille de message invalide : " + length);
        }
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Connexion fermée par le Player");
            }
            offset += read;
        }
        return data;
    }

    private static void writeVarint(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarint(InputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Connexion fermée par le Player");
            }
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("Varint trop long");
            }
        }
    }
}
