package dev.pgrazor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

final class LocalApiConfig {
    static final LocalApiConfig DEFAULT = new LocalApiConfig("0.0.0.0", 8765);
    final String host;
    final int port;
    private final InetAddress address;

    LocalApiConfig(String host, int port) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Port must be 1024–65535");
        if (host == null || host.length() > 15) throw new IllegalArgumentException("A numeric IPv4 address is required");
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("A numeric IPv4 address is required");
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
                throw new IllegalArgumentException("A numeric IPv4 address is required");
            }
            bytes[i] = (byte) Integer.parseInt(part);
        }
        try {
            address = InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
        if (address.isMulticastAddress() || (bytes[0] & 255) >= 240) {
            throw new IllegalArgumentException("A unicast or wildcard IPv4 address is required");
        }
        this.host = host;
        this.port = port;
    }

    InetSocketAddress socketAddress() {
        return new InetSocketAddress(address, port);
    }
}
