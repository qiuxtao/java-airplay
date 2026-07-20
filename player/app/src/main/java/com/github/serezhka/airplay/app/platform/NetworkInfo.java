package com.github.serezhka.airplay.app.platform;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;
import java.util.Locale;

public final class NetworkInfo {

    private NetworkInfo() {
    }

    public static List<String> localAddresses() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(NetworkInfo::usable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> address instanceof Inet4Address
                            && !address.isAnyLocalAddress()
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()
                            && !isBenchmarkAddress((Inet4Address) address))
                    .map(address -> address.getHostAddress())
                    .distinct()
                    .sorted()
                    .toList();
        } catch (SocketException error) {
            return List.of();
        }
    }

    private static boolean usable(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp()
                    && !networkInterface.isLoopback()
                    && !networkInterface.isPointToPoint()
                    && !networkInterface.isVirtual()
                    && networkInterface.supportsMulticast()
                    && !looksLikeVirtualAdapter(networkInterface);
        } catch (SocketException ignored) {
            return false;
        }
    }

    private static boolean looksLikeVirtualAdapter(NetworkInterface networkInterface) {
        String description = (networkInterface.getName() + " " + networkInterface.getDisplayName())
                .toLowerCase(Locale.ROOT);
        return List.of("virtual", "vmware", "hyper-v", "vethernet", "wsl", "docker", "npcap",
                        "zerotier", "tailscale", "tunnel", "vpn")
                .stream()
                .anyMatch(description::contains);
    }

    private static boolean isBenchmarkAddress(Inet4Address address) {
        byte[] bytes = address.getAddress();
        return Byte.toUnsignedInt(bytes[0]) == 198
                && (Byte.toUnsignedInt(bytes[1]) == 18 || Byte.toUnsignedInt(bytes[1]) == 19);
    }
}
