package com.github.serezhka.airplay.lib;

import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Registers airplay/airtunes service mdns
 */
@Slf4j
public class AirPlayBonjour {

    private static final String AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "._raop._tcp.local";
    private static final String ALL_FEATURES = "0x5A7FFFF7,0x1E";
    private static final String MIRRORING_FEATURES = "0x5A7FFF80,0x1E";

    private final String serverName;
    private final boolean mirrorOnly;

    private final Object lifecycleLock = new Object();
    private final List<JmDNS> jmDNSList = new ArrayList<>();
    private final AtomicLong generation = new AtomicLong();

    private ExecutorService registrationExecutor;

    public AirPlayBonjour(String serverName) {
        this(serverName, false);
    }

    public AirPlayBonjour(String serverName, boolean mirrorOnly) {
        this.serverName = serverName;
        this.mirrorOnly = mirrorOnly;
    }

    public void start(int airTunesPort) throws Exception {
        stop();
        List<InetAddress> addresses = NetworkInterface.networkInterfaces()
                .filter(networkInterfaceFilter())
                .flatMap(NetworkInterface::inetAddresses)
                .filter(inetAddressFilter())
                .toList();
        if (addresses.isEmpty()) {
            throw new IOException("No active multicast-capable IPv4 network is available");
        }

        long currentGeneration = generation.incrementAndGet();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        synchronized (lifecycleLock) {
            registrationExecutor = executor;
        }
        CompletableFuture<Void> firstAvailable = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(addresses.size());
        AtomicReference<Exception> lastFailure = new AtomicReference<>();
        addresses.forEach(address -> executor.submit(() -> {
            try {
                register(address, airTunesPort, currentGeneration, executor, firstAvailable);
            } catch (IOException | RuntimeException error) {
                lastFailure.set(error);
            } finally {
                if (remaining.decrementAndGet() == 0) {
                    if (!firstAvailable.isDone()) {
                        IOException error = new IOException(
                                "Bonjour registration failed on every available network adapter");
                        if (lastFailure.get() != null) {
                            error.addSuppressed(lastFailure.get());
                        }
                        firstAvailable.completeExceptionally(error);
                    }
                    executor.shutdown();
                }
            }
        }));

        try {
            firstAvailable.get();
        } catch (ExecutionException error) {
            executor.shutdownNow();
            if (error.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(error.getCause());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw error;
        }
    }

    public void stop() {
        generation.incrementAndGet();
        ExecutorService executor;
        List<JmDNS> registrations;
        synchronized (lifecycleLock) {
            executor = registrationExecutor;
            registrationExecutor = null;
            registrations = List.copyOf(jmDNSList);
            jmDNSList.clear();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        closeRegistrations(registrations);
    }

    private void register(InetAddress inetAddress,
                          int airTunesPort,
                          long expectedGeneration,
                          ExecutorService executor,
                          CompletableFuture<Void> firstAvailable) throws IOException {
        JmDNS jmDNS = null;
        try {
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(inetAddress);
            byte[] hardwareAddress = networkInterface == null ? null : networkInterface.getHardwareAddress();
            String mac = hardwareAddressBytesToString(hardwareAddress);

            jmDNS = JmDNS.create(inetAddress);
            jmDNS.registerService(ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE,
                    serverName, airTunesPort, 0, 0, airPlayMDNSProps(mac)));
            log.info("{} service is registered on address {}, port {}", serverName + AIRPLAY_SERVICE_TYPE,
                    inetAddress.getHostAddress(), airTunesPort);

            if (!acceptRegistration(expectedGeneration, executor, jmDNS)) {
                closeRegistration(jmDNS);
                return;
            }
            firstAvailable.complete(null);

            String airTunesServerName = mac.replaceAll(":", "") + "@" + serverName;
            try {
                jmDNS.registerService(ServiceInfo.create(airTunesServerName + AIRTUNES_SERVICE_TYPE,
                        airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps()));
                log.info("{} service is registered on address {}, port {}",
                        airTunesServerName + AIRTUNES_SERVICE_TYPE,
                        inetAddress.getHostAddress(), airTunesPort);
            } catch (IOException | RuntimeException error) {
                // The AirPlay service is already usable for mirroring. Keep it alive even if the
                // optional standalone RAOP advertisement cannot be registered on this adapter.
                log.warn("Unable to register the RAOP service on {}", inetAddress, error);
            }
        } catch (IOException | RuntimeException error) {
            log.warn("Unable to register Bonjour service on {}", inetAddress, error);
            closeRegistration(jmDNS);
            throw error;
        }
    }

    private boolean acceptRegistration(long expectedGeneration, ExecutorService executor, JmDNS registration) {
        synchronized (lifecycleLock) {
            if (generation.get() == expectedGeneration && registrationExecutor == executor) {
                jmDNSList.add(registration);
                return true;
            }
        }
        return false;
    }

    private void closeRegistrations(Collection<JmDNS> registrations) {
        if (registrations.isEmpty()) {
            return;
        }
        ExecutorService closer = Executors.newVirtualThreadPerTaskExecutor();
        try {
            closer.invokeAll(registrations.stream()
                    .<java.util.concurrent.Callable<Void>>map(registration -> () -> {
                        closeRegistration(registration);
                        return null;
                    })
                    .toList(), 2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            closer.shutdownNow();
        }
    }

    private void closeRegistration(JmDNS jmDNS) {
        if (jmDNS == null) {
            return;
        }
        try {
            jmDNS.close();
        } catch (IOException error) {
            log.warn("Unable to close Bonjour service", error);
        }
    }

    private Map<String, String> airPlayMDNSProps(String deviceId) {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        airPlayMDNSProps.put("features", advertisedFeatures());
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x44");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV3,2C");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        //airPlayMDNSProps.put("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        airPlayMDNSProps.put("rmodel", "PC1.0");
        airPlayMDNSProps.put("rrv", "1.01");
        airPlayMDNSProps.put("rsv", "1.00");
        airPlayMDNSProps.put("pcversion", "1715");
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "1,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("ek", "1");
        //airTunesMDNSProps.put("vv", "2");
        airTunesMDNSProps.put("ft", advertisedFeatures());
        airTunesMDNSProps.put("am", "AppleTV3,2C");
        airTunesMDNSProps.put("md", "0,1,2");
        //airTunesMDNSProps.put("rhd", "5.6.0.0");
        //airTunesMDNSProps.put("pw", "false");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("sm", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x44");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        return airTunesMDNSProps;
    }

    private Predicate<NetworkInterface> networkInterfaceFilter() {
        return networkInterface -> {
            try {
                return !networkInterface.isLoopback()
                        && !networkInterface.isPointToPoint()
                        && !networkInterface.isVirtual()
                        && networkInterface.isUp()
                        && networkInterface.supportsMulticast()
                        && !looksLikeVirtualAdapter(networkInterface);
            } catch (SocketException e) {
                return false;
            }
        };
    }

    private Predicate<InetAddress> inetAddressFilter() {
        return inetAddress -> inetAddress instanceof Inet4Address
                && !inetAddress.isAnyLocalAddress()
                && !inetAddress.isLoopbackAddress()
                && !inetAddress.isLinkLocalAddress()
                && !isBenchmarkAddress(inetAddress);
    }

    private boolean looksLikeVirtualAdapter(NetworkInterface networkInterface) {
        String description = (networkInterface.getName() + " " + networkInterface.getDisplayName())
                .toLowerCase(Locale.ROOT);
        return List.of("virtual", "vmware", "hyper-v", "vethernet", "wsl", "docker", "npcap",
                        "zerotier", "tailscale", "tunnel", "vpn")
                .stream()
                .anyMatch(description::contains);
    }

    private boolean isBenchmarkAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && Byte.toUnsignedInt(bytes[0]) == 198
                && (Byte.toUnsignedInt(bytes[1]) == 18 || Byte.toUnsignedInt(bytes[1]) == 19);
    }

    private String hardwareAddressBytesToString(byte[] mac) {
        if (mac == null) {
            return "00:00:00:00:00:00"; // loopback
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
        }
        return sb.toString().toUpperCase();
    }

    private String advertisedFeatures() {
        return mirrorOnly ? MIRRORING_FEATURES : ALL_FEATURES;
    }
}
