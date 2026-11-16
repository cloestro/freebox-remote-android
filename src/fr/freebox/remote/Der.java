package fr.freebox.remote;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Petits utilitaires d'encodage DER pour fabriquer un certificat X.509 auto-signé. */
final class Der {

    private Der() {
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        if (value.length < 0x80) {
            out.write(value.length);
        } else {
            int size = 0;
            for (int n = value.length; n > 0; n >>>= 8) {
                size++;
            }
            out.write(0x80 | size);
            for (int i = size - 1; i >= 0; i--) {
                out.write((value.length >>> (8 * i)) & 0xFF);
            }
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    static byte[] bool(boolean value) {
        return tlv(0x01, new byte[]{(byte) (value ? 0xFF : 0x00)});
    }

    static byte[] nullValue() {
        return new byte[]{0x05, 0x00};
    }

    static byte[] octet(byte[] value) {
        return tlv(0x04, value);
    }

    static byte[] utf8(String value) {
        return tlv(0x0C, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static byte[] ia5(String value) {
        return tlv(0x16, value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** GeneralName dNSName : contexte [2] implicite, contenu IA5 brut. */
    static byte[] dnsName(String value) {
        return tlv(0x82, value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static byte[] utc(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(date).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static byte[] bitString(byte[] value) {
        byte[] withUnusedBits = new byte[value.length + 1];
        System.arraycopy(value, 0, withUnusedBits, 1, value.length);
        return tlv(0x03, withUnusedBits);
    }

    static byte[] context(int number, boolean constructed, byte[] value) {
        return tlv((constructed ? 0xA0 : 0x80) | number, value);
    }

    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long first = Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]);
        writeBase128(out, first);
        for (int i = 2; i < parts.length; i++) {
            writeBase128(out, Long.parseLong(parts[i]));
        }
        return tlv(0x06, out.toByteArray());
    }

    static byte[] algorithm(String oid) {
        return seq(oid(oid), nullValue());
    }

    static byte[] rsaPublicKey(RSAPublicKey key) {
        return seq(integer(key.getModulus()), integer(key.getPublicExponent()));
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        int size = 1;
        for (long n = value; (n >>>= 7) != 0; ) {
            size++;
        }
        for (int i = size - 1; i >= 0; i--) {
            int b = (int) ((value >>> (7 * i)) & 0x7F);
            out.write(i == 0 ? b : b | 0x80);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
