package com.phonepcremote;

public final class LocalTransportPolicyTest {
    public static void main(String[] args) {
        int cases = 0;
        for (String mode : new String[]{"auto", "usb", "unknown"}) {
            for (int bits = 0; bits < 32; bits++) {
                boolean wifi = (bits & 1) != 0, ethernet = (bits & 2) != 0;
                boolean usb = (bits & 4) != 0, cellular = (bits & 8) != 0, vpn = (bits & 16) != 0;
                boolean actual = LocalTransportPolicy.allows(mode, wifi, ethernet, usb, cellular, vpn);
                boolean expected = !cellular && !vpn && ("usb".equals(mode) ? usb
                        : "auto".equals(mode) && (wifi || ethernet || usb));
                if (actual != expected) throw new AssertionError(mode + ":" + bits);
                cases++;
            }
        }
        System.out.println("PASS " + cases + " local transport policy combinations");
    }
}
