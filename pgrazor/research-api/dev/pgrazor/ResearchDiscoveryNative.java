package dev.pgrazor;

final class ResearchDiscoveryNative {
    private ResearchDiscoveryNative() {}

    static native String query(String operation, String assembly, String namespace, String name,
            String memberKind, String filter, int offset, int limit);
}
