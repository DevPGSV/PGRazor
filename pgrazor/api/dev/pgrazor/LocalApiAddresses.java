package dev.pgrazor;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

final class LocalApiAddresses {
    static List<String> candidates(LocalApiConfig binding, Iterable<InetAddress> lanAddresses) {
        TreeSet<String> urls = new TreeSet<>();
        for (InetAddress address : lanAddresses) {
            if (!(address instanceof Inet4Address) || address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()) continue;
            String ip = address.getHostAddress();
            if (binding.host.equals("0.0.0.0") || binding.host.equals(ip)) {
                urls.add("http://" + ip + ":" + binding.port + "/api/v1");
            }
        }
        return new ArrayList<>(urls);
    }
}
