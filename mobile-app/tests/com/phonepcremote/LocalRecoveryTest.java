package com.phonepcremote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

public final class LocalRecoveryTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static LocalSubnet subnet(String ip, int prefix) throws Exception {
        return new LocalSubnet((Inet4Address) InetAddress.getByName(ip), prefix);
    }
    public static void main(String[] args) throws Exception {
        check("10.10.11.255".equals(subnet("10.10.11.9", 23).broadcast()), "/23 broadcast");
        check("10.10.15.255".equals(subnet("10.10.11.9", 20).broadcast()), "/20 broadcast");
        List<String> small = subnet("192.168.1.70", 26).scanCandidates();
        check(small.size() == 61 && small.contains("192.168.1.65") && small.contains("192.168.1.126")
                && !small.contains("192.168.1.70") && !small.contains("192.168.1.63"), "scan escaped subnet");
        check(subnet("10.10.11.9", 16).scanCandidates().size() <= 255, "unbounded large-subnet scan");
        check(subnet("10.10.11.9", 32).scanCandidates().isEmpty(), "/32 must not scan neighbors");
        check(subnet("10.10.11.9", 31).broadcast().isEmpty(), "/31 has no broadcast");
        check(subnet("10.10.11.9", 0).broadcast().equals("255.255.255.255"), "/0 mask");
        check(LocalSubnet.isLocal(InetAddress.getByName("10.10.11.9")), "private IPv4 rejected");
        check(!LocalSubnet.isLocal(InetAddress.getByName("8.8.8.8")), "public IPv4 accepted");
        check(!LocalSubnet.isLocal(InetAddress.getByName("224.0.0.1")), "multicast accepted");
        ReconnectPolicy retry = new ReconnectPolicy();
        check(retry.nextDelay(-5) == 600, "first delay");
        check(retry.nextDelay(999) == 1750, "jitter cap");
        check(retry.nextDelay(0) == 3500 && retry.nextDelay(0) == 7000 && retry.nextDelay(0) == -1,
                "retries not bounded");
        retry.reset();
        check(retry.nextDelay(0) == 600, "new network must reset retry budget");
        String nonce = "12345678-1234-1234-1234-123456789012";
        String proof = DiscoveryAuthentication.proof("test-secret", nonce, 8765);
        check(DiscoveryAuthentication.verifies("test-secret", nonce, 8765, proof), "proof failed");
        check(!DiscoveryAuthentication.verifies("wrong-secret", nonce, 8765, proof), "wrong server accepted");
        check(!DiscoveryAuthentication.verifies("test-secret", nonce + "x", 8765, proof), "replayed proof accepted");
        check(!DiscoveryAuthentication.verifies("test-secret", nonce, 8766, proof), "modified port accepted");
        check(!DiscoveryAuthentication.verifies("test-secret", nonce, 8765, "bad"), "malformed proof accepted");
        System.out.println("PASS local subnet, bounded recovery, and authenticated discovery");
        check(!LinkLocalEndpoint.normalize("fe80::1234%13").isEmpty(), "scoped IPv6 rejected");
        check(LinkLocalEndpoint.normalize("fe80::1234%13").equals(LinkLocalEndpoint.normalize("fe80::1234%wlan0")), "foreign interface scope persisted");
        for (String invalid : new String[]{"2001:db8::1", "::1", "::ffff:10.0.0.1", "10.0.0.1", "example.com", "fe80::invalid"}) {
            check(LinkLocalEndpoint.normalize(invalid).isEmpty(), "non-link-local endpoint accepted: " + invalid);
        }
        System.out.println("PASS IPv6 fallback endpoint filtering and scope removal");
        System.out.println("VECTOR " + proof);
    }
}
