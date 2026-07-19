package com.github.serezhka.airplay.app.platform;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;

public final class NetworkInfo {

    private NetworkInfo() {
    }

    public static List<String> localAddresses() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(NetworkInfo::usable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
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
                    && networkInterface.supportsMulticast();
        } catch (SocketException ignored) {
            return false;
        }
    }
}
