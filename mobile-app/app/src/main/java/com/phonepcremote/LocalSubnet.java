package com.phonepcremote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

final class LocalSubnet {
    private final long address;
    private final long first;
    private final long last;
    private final int prefix;

    LocalSubnet(Inet4Address ip, int prefix) {
        if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("Invalid prefix");
        this.prefix = prefix;
        byte[] bytes = ip.getAddress();
        long value = 0;
        for (byte b : bytes) value = (value << 8) | (b & 255);
        address = value;
        long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        first = address & mask;
        last = first | (0xffffffffL ^ mask);
    }

    String broadcast() { return prefix >= 31 ? "" : format(last); }

    List<String> scanCandidates() {
        List<String> result = new ArrayList<>();
        // Broadcast covers large subnets. The TCP fallback stays bounded to a local /24 window.
        long start = Math.max(prefix < 31 ? first + 1 : first, address & 0xffffff00L);
        long end = Math.min(prefix < 31 ? last - 1 : last, (address & 0xffffff00L) + 255);
        for (long value = start; value <= end; value++) {
            if (value != address) result.add(format(value));
        }
        return result;
    }

    static boolean isLocal(InetAddress address) {
        return !address.isAnyLocalAddress() && !address.isMulticastAddress()
                && (address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress());
    }

    private static String format(long value) {
        return ((value >> 24) & 255) + "." + ((value >> 16) & 255) + "." + ((value >> 8) & 255) + "." + (value & 255);
    }
}
