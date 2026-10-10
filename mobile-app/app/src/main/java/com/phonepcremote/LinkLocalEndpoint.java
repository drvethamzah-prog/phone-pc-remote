package com.phonepcremote;

import java.net.Inet6Address;
import java.net.InetAddress;

/** Store only an IPv6 address; the receiving phone supplies its own interface scope. */
final class LinkLocalEndpoint {
    static String normalize(String value) {
        if (value == null || value.length() > 100 || !value.contains(":")) return "";
        String literal = value.split("%", 2)[0];
        if (!literal.matches("[0-9a-fA-F:]+")) return "";
        try {
            InetAddress address = InetAddress.getByName(literal);
            return address instanceof Inet6Address && address.isLinkLocalAddress() ? address.getHostAddress() : "";
        } catch (Exception ignored) { return ""; }
    }
}
