package app.subsense.ambient;

/** The few JSON helpers the page's answers need (no org.json, so the unit tests run on a plain JVM). */
final class Json {
    private Json() {}

    static void str(StringBuilder sb, String s) {
        sb.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    static String str(String s) {
        StringBuilder sb = new StringBuilder();
        str(sb, s);
        return sb.toString();
    }

    /** ["rrggbb", …] */
    static void colours(StringBuilder sb, int[] c) {
        sb.append('[');
        for (int i = 0; c != null && i < c.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(String.format("%06x", c[i] & 0xffffff)).append('"');
        }
        sb.append(']');
    }

    static void floats(StringBuilder sb, float[] f) {
        sb.append('[');
        for (int i = 0; f != null && i < f.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Math.round(f[i] * 10000) / 10000.0);
        }
        sb.append(']');
    }
}
