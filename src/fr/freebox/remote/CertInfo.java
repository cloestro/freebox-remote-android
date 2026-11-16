package fr.freebox.remote;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Extrait le nom et l'adresse MAC depuis le certificat présenté par le Player. */
final class CertInfo {

    final String name;
    final String mac;

    private CertInfo(String name, String mac) {
        this.name = name;
        this.mac = mac;
    }

    static CertInfo parse(X509Certificate certificate) {
        String dn = certificate.getSubjectX500Principal().getName();
        String commonName = null;
        String dnQualifier = null;
        for (String attribute : splitDn(dn)) {
            int equals = attribute.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = attribute.substring(0, equals).trim();
            String value = unescape(attribute.substring(equals + 1).trim());
            if ("CN".equalsIgnoreCase(key)) {
                commonName = value;
            } else if ("2.5.4.46".equals(key) || "dnQualifier".equalsIgnoreCase(key)) {
                dnQualifier = value;
            }
        }

        String name = null;
        String mac = null;
        String[] parts = commonName == null ? new String[0] : commonName.split("/");
        if (dnQualifier != null && !dnQualifier.isEmpty()) {
            String[] qualifierParts = dnQualifier.split("/");
            name = qualifierParts[qualifierParts.length - 1];
        } else if (parts.length > 1) {
            name = parts[parts.length - 2];
        } else if (commonName != null && !commonName.isEmpty()) {
            name = commonName;
        }
        if (parts.length > 0) {
            mac = parts[parts.length - 1];
        }
        if (name != null) {
            name = name.trim();
        }
        if (mac != null) {
            mac = mac.trim();
        }
        return new CertInfo(emptyToNull(name), emptyToNull(mac));
    }

    private static List<String> splitDn(String dn) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == ',') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
