package com.phonepcremote;

import java.net.URI;
import java.net.URLDecoder;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

final class QrEnrollment {
    final String host, fingerprint, code;
    final int port;

    private QrEnrollment(String host, int port, String fingerprint, String code) {
        this.host = host;
        this.port = port;
        this.fingerprint = fingerprint;
        this.code = code;
    }

    static QrEnrollment parse(String content, long nowSeconds) throws Exception {
        if (content == null || content.length() > 2048) throw new IllegalArgumentException("Invalid QR");
        URI uri = new URI(content);
        if (!"qareeb".equals(uri.getScheme()) || !"pair".equals(uri.getRawAuthority())
                || !(uri.getPath() == null || uri.getPath().isEmpty()) || uri.getFragment() != null
                || uri.getRawQuery() == null) throw new IllegalArgumentException("Invalid QR");
        Map<String, String> fields = new HashMap<>();
        for (String item : uri.getRawQuery().split("&", -1)) {
            String[] pair = item.split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("Invalid QR");
            String key = URLDecoder.decode(pair[0], "UTF-8");
            if (fields.put(key, URLDecoder.decode(pair[1], "UTF-8")) != null) throw new IllegalArgumentException("Duplicate field");
        }
        if (fields.size() != 6 || !"1".equals(fields.get("v"))) throw new IllegalArgumentException("Unsupported QR");
        String host = fields.get("host");
        String linkLocal = LinkLocalEndpoint.normalize(host == null ? "" : host);
        if (linkLocal.isEmpty()) {
            if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Local IP required");
            String[] octets = host.split("\\.");
            for (String octet : octets) {
                if (Integer.parseInt(octet) > 255 || (octet.length() > 1 && octet.startsWith("0"))) throw new IllegalArgumentException("Invalid IP");
            }
            InetAddress address = InetAddress.getByName(host);
            if (!LocalSubnet.isLocal(address) || address.isLoopbackAddress()) throw new IllegalArgumentException("Local IP required");
        } else {
            if (host.contains("%")) throw new IllegalArgumentException("Foreign scope forbidden");
            host = linkLocal;
        }
        int port = Integer.parseInt(fields.get("port"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        long expires = Long.parseLong(fields.get("expires"));
        if (expires <= nowSeconds || expires - nowSeconds > 300) throw new IllegalArgumentException("Expired QR or incorrect clock");
        String code = fields.get("code");
        if (code == null || !code.matches("[0-9A-F]{32}")) throw new IllegalArgumentException("Invalid ticket");
        return new QrEnrollment(host, port, PinnedTls.normalize(fields.get("fp")), code);
    }
}
