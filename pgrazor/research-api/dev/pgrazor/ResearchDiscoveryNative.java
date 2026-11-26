package dev.pgrazor;

final class ResearchDiscoveryNative {
    private ResearchDiscoveryNative() {}

    static native void setProgressionLease(long lease, long deadline);

    static native void setExportRequest(long request, long lease, long deadline);

    static native String query(String operation, String assembly, String namespace, String name,
            String memberKind, String filter, int offset, int limit);
}
