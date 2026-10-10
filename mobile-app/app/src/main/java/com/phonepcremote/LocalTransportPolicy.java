package com.phonepcremote;

/** Pure policy: never use a cellular/VPN bearer or silently escape USB-only mode. */
final class LocalTransportPolicy {
    static boolean allows(String mode, boolean wifi, boolean ethernet, boolean usb,
                          boolean cellular, boolean vpn) {
        if (vpn || cellular) return false;
        if ("usb".equals(mode)) return usb;
        if ("auto".equals(mode)) return wifi || ethernet || usb;
        return false;
    }
}
