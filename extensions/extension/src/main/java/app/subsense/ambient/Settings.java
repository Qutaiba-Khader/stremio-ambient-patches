package app.subsense.ambient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every setting of the ambient light, with its default and its allowed range. Values are kept as strings that are always
 * valid: {@link #set} refuses anything else, so the capture code never has to check them again. Pure Java (no Android),
 * so the unit tests run on a plain JVM; storing them is {@link Ambient}'s job.
 */
final class Settings {
    static final int BOOL = 0, INT = 1, HOST = 2, CHOICE = 3;

    static final class Def {
        final String key;
        final int type;
        final String def;
        final int min, max;
        final String[] choices;

        Def(String key, int type, String def, int min, int max, String... choices) {
            this.key = key;
            this.type = type;
            this.def = def;
            this.min = min;
            this.max = max;
            this.choices = choices;
        }
    }

    private static Def b(String k, boolean d) { return new Def(k, BOOL, d ? "1" : "0", 0, 1); }
    private static Def i(String k, int d, int min, int max) { return new Def(k, INT, Integer.toString(d), min, max); }

    /** The colour adjustments of one set (SDR or HDR). */
    static final String[] SET_KEYS = {"bright", "black", "gamma", "sat", "temp"};

    static final Def[] DEFS = {
            b("enabled", true),
            // The owner types it on the TV's page; never a default address (see the plan: no address in the code).
            new Def("host", HOST, "", 0, 0),
            i("priority", 90, 50, 250),         // HyperHDR: the lower number wins; it takes 50-250 from a flatbuffer client
            i("rate", 11, 1, 50),               // updates per second (11: the owner's best, 2026-11-12)
            i("top", 6, 1, 64), i("bottom", 6, 1, 64), i("left", 3, 1, 64), i("right", 3, 1, 64), // the owner's choice
            i("inset", 0, 0, 30),               // distance from the picture's edge, % of the picture's height
            i("depth", 10, 1, 50),              // how far each area reaches into the picture, % of the height
            i("spread", 100, 10, 100),          // how much of its share of the edge each area covers, %
            b("bars", true),                    // skip black bars (letterbox / pillarbox)
            // a row or column is a bar when no pixel in it is brighter: 2 = true black only (a video's own black arrives as 0-1
            // after the GPU's YUV-to-RGB rounding, measured 01,00,01); a dark picture (5 and up) is never a bar
            i("barLevel", 2, 0, 32),
            new Def("hdrMode", CHOICE, "auto", 0, 0, "auto", "sdr", "hdr"),
            b("tunnel", false),                 // tunneled playback: the picture then never reaches the app (no LED)
            i("port", 8095, 1024, 65535),       // this page's port (from the next start of the app)
            i("sdr.bright", 100, 0, 300), i("sdr.black", 0, 0, 50), i("sdr.gamma", 100, 30, 300),
            i("sdr.sat", 100, 0, 300), i("sdr.temp", 0, -100, 100),
            i("hdr.bright", 100, 0, 300), i("hdr.black", 0, 0, 50), i("hdr.gamma", 100, 30, 300),
            i("hdr.sat", 100, 0, 300), i("hdr.temp", 0, -100, 100),
            // HDR light (see Hdr): how the copy is read, the white level, the highlights' share of the LEDs, the top
            new Def("hdr.read", CHOICE, "auto", 0, 0, "auto", "android", "pq", "sdr"),
            b("hdr.autoWhite", true),           // the white level follows the film (Hdr.AutoWhite)
            i("hdr.lift", 100, 25, 400),        // automatic white: more = brighter lights
            i("hdr.white", 203, 5, 1000),       // manual white level, nits (BT.2408 HDR reference white: 203)
            i("hdr.headroom", 25, 0, 90),       // % of the LED range kept for light above white
            b("hdr.peakTv", true),              // the brightest highlight = the TV's own peak (EDID, via Android)
            i("hdr.peak", 1000, 100, 4000),     // manual brightest highlight, nits
    };

    private final Map<String, String> values = new LinkedHashMap<>();

    Settings() {
        for (Def d : DEFS) values.put(d.key, d.def);
    }

    static Def def(String key) {
        for (Def d : DEFS) if (d.key.equals(key)) return d;
        return null;
    }

    synchronized String get(String key) { return values.get(key); }

    synchronized int i(String key) { return Integer.parseInt(values.get(key)); }

    synchronized boolean b(String key) { return "1".equals(values.get(key)); }

    synchronized Map<String, String> all() { return new LinkedHashMap<>(values); }

    /** Sets one value. Returns null when it was accepted, else why not (nothing is changed then). */
    synchronized String set(String key, String raw) {
        Def d = def(key);
        if (d == null) return "unknown setting " + key;
        String v = raw == null ? "" : raw.trim();
        switch (d.type) {
            case BOOL:
                if (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on")) v = "1";
                else if (v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")) v = "0";
                else return key + ": on or off";
                break;
            case INT:
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    return key + ": a whole number";
                }
                if (n < d.min || n > d.max) return key + ": from " + d.min + " to " + d.max;
                v = Integer.toString(n);
                break;
            case HOST:
                String err = checkHost(v);
                if (err != null) return err;
                break;
            case CHOICE:
                boolean ok = false;
                for (String c : d.choices) ok |= c.equals(v);
                if (!ok) return key + ": one of " + String.join(", ", d.choices);
                break;
            default:
                return "unknown setting " + key;
        }
        values.put(key, v);
        return null;
    }

    /** Loads stored values; a stored value that is no longer valid keeps the default. */
    synchronized void load(Map<String, ?> stored) {
        for (Def d : DEFS) {
            Object v = stored.get(d.key);
            if (v != null) set(d.key, v.toString());
        }
    }

    /** Puts back the defaults of the keys that start with prefix ("" = all, except the HyperHDR address and the port;
     * "points" = the Points card). */
    /** The Points card's settings (reset together). */
    static final java.util.List<String> POINT_KEYS = java.util.Arrays.asList("top", "bottom", "left", "right", "inset", "depth", "spread", "bars", "barLevel");

    synchronized void reset(String prefix) {
        for (Def d : DEFS) {
            if (prefix.equals("points") ? !POINT_KEYS.contains(d.key) : !d.key.startsWith(prefix)) continue;
            if (prefix.isEmpty() && (d.key.equals("host") || d.key.equals("port"))) continue;
            values.put(d.key, d.def);
        }
    }

    /** "host" or "host:port" (port 19400 when left out); empty = not set. Returns null when valid. */
    static String checkHost(String v) {
        if (v.isEmpty()) return null;
        String host = v;
        int colon = v.lastIndexOf(':');
        if (colon >= 0) {
            host = v.substring(0, colon);
            try {
                int p = Integer.parseInt(v.substring(colon + 1));
                if (p < 1 || p > 65535) return "port: from 1 to 65535";
            } catch (NumberFormatException e) {
                return "HyperHDR address: host or host:port";
            }
        }
        if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9.-]+")) return "HyperHDR address: host or host:port";
        return null;
    }

    static String hostOf(String v) {
        int colon = v.lastIndexOf(':');
        return colon >= 0 ? v.substring(0, colon) : v;
    }

    static int portOf(String v) {
        int colon = v.lastIndexOf(':');
        return colon >= 0 ? Integer.parseInt(v.substring(colon + 1)) : 19400;
    }

    /** The settings as a JSON object (numbers and booleans typed, strings escaped). */
    synchronized String toJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Def d : DEFS) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(d.key).append("\":");
            String v = values.get(d.key);
            if (d.type == BOOL) sb.append("1".equals(v) ? "true" : "false");
            else if (d.type == INT) sb.append(v);
            else Json.str(sb, v);
        }
        return sb.append('}').toString();
    }

    /** The ranges, for the page (so the page and the TV can never disagree). */
    static String defsJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Def d : DEFS) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(d.key).append("\":{\"min\":").append(d.min).append(",\"max\":").append(d.max).append(",\"def\":");
            if (d.type == BOOL) sb.append("1".equals(d.def) ? "true" : "false");
            else if (d.type == INT) sb.append(d.def);
            else Json.str(sb, d.def);
            sb.append('}');
        }
        return sb.append('}').toString();
    }
}
