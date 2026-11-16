package fr.freebox.remote;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encodeur/décodeur protobuf minimal (varint + champs length-delimited).
 * Évite toute dépendance externe : l'APK reste autonome.
 */
final class Proto {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Proto varint(int field, long value) {
        writeTag(field, 0);
        writeVarint(value);
        return this;
    }

    Proto bool(int field, boolean value) {
        return varint(field, value ? 1 : 0);
    }

    Proto bytes(int field, byte[] value) {
        writeTag(field, 2);
        writeVarint(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    Proto string(int field, String value) {
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    Proto message(int field, Proto value) {
        return bytes(field, value.toByteArray());
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    private void writeTag(int field, int wire) {
        writeVarint(((long) field << 3) | wire);
    }

    private void writeVarint(long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /** Lecteur de champs protobuf. Les champs inconnus sont ignorés par l'appelant. */
    static final class Reader {
        private final byte[] data;
        private int pos;
        int field;
        int wire;
        long varint;
        byte[] bytes;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean next() {
            if (pos >= data.length) {
                return false;
            }
            long tag = readVarint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            switch (wire) {
                case 0:
                    varint = readVarint();
                    break;
                case 1:
                    skip(8);
                    break;
                case 2: {
                    int length = (int) readVarint();
                    if (length < 0 || pos + length > data.length) {
                        throw new IllegalArgumentException("message protobuf tronqué");
                    }
                    bytes = new byte[length];
                    System.arraycopy(data, pos, bytes, 0, length);
                    pos += length;
                    break;
                }
                case 5:
                    skip(4);
                    break;
                default:
                    throw new IllegalArgumentException("type de champ inconnu : " + wire);
            }
            return true;
        }

        String string() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private void skip(int count) {
            if (pos + count > data.length) {
                throw new IllegalArgumentException("message protobuf tronqué");
            }
            pos += count;
        }

        private long readVarint() {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IllegalArgumentException("varint tronqué");
                }
                int b = data[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("varint trop long");
                }
            }
        }
    }
}
