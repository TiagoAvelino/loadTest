package org.acme.mqtt;

import java.nio.charset.StandardCharsets;

final class DebugUtil {
    private DebugUtil() {
    }

    static String bytesPreview(byte[] b, int max) {
        if (b == null)
            return "<null>";
        int n = Math.min(max, b.length);
        String s = new String(b, 0, n, StandardCharsets.UTF_8);
        return (b.length > n) ? s + "...(" + b.length + " bytes)" : s + " (" + b.length + " bytes)";
    }
}
