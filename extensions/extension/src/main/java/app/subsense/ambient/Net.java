package app.subsense.ambient;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** The page server's pure parts (who may ask, and the form it posts), kept apart so the unit tests run on a plain JVM. */
final class Net {
    private Net() {}

    /** Private, link-local, loopback, unique-local IPv6 and 100.64/10 (carrier-grade NAT, also Tailscale). */
    static boolean isHomeNetwork(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            if ((b[0] & 0xfe) == 0xfc) return true; // fc00::/7 unique local
            if (((Inet6Address) a).isIPv4CompatibleAddress()) return false;
        }
        if (a instanceof Inet4Address) {
            byte[] b = a.getAddress();
            return (b[0] & 255) == 100 && (b[1] & 0xc0) == 64; // 100.64/10 (carrier-grade NAT, also Tailscale)
        }
        return false;
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : body.split("&")) {
            if (part.isEmpty()) continue;
            int e = part.indexOf('=');
            try {
                String k = URLDecoder.decode(e < 0 ? part : part.substring(0, e), "UTF-8");
                String v = e < 0 ? "" : URLDecoder.decode(part.substring(e + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return m;
    }


    /**
     * The Host header of a request to the page: an IP address, localhost, or a local name (no dot, or .local / .lan /
     * .home.arpa), with or without a port. Anything else is a public name someone may have pointed at the TV.
     */
    static boolean isLocalHostHeader(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 1 && h.substring(1, end).matches("[0-9a-f:.%a-z]+") && h.substring(1, end).contains(":");
        }
        int colon = h.lastIndexOf(':');
        if (colon >= 0) {
            if (!h.substring(colon + 1).matches("[0-9]{1,5}")) return false;
            h = h.substring(0, colon);
        }
        if (h.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return true;
        if (!h.matches("[a-z0-9.-]+")) return false;
        return h.equals("localhost") || !h.contains(".") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa");
    }
}
