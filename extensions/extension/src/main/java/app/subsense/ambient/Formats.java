package app.subsense.ambient;

/** Reading Media3's Format log line ("id=1, mimeType=video/hevc, …, color=BT2020/Limited range/ST2084 PQ/10/10, …"). Pure Java. */
final class Formats {
    private Formats() {}

    /** HDR from a Format log line: the PQ or HLG transfer in its colour part, or Dolby Vision (a label never counts). */
    static boolean isHdr(String line) {
        if (line == null) return false;
        String color = part(line, "color="), mime = part(line, "mimeType=");
        return "video/dolby-vision".equals(mime) || (color != null && (color.contains("ST2084 PQ") || color.contains("HLG")));
    }

    /** One "key=value" part of a log line (up to the next ", "). */
    static String part(String line, String key) {
        int i = line.indexOf(key);
        while (i > 0 && !(line.charAt(i - 1) == ' ' || line.charAt(i - 1) == ',')) i = line.indexOf(key, i + 1);
        if (i < 0) return null;
        int e = line.indexOf(", ", i);
        return line.substring(i + key.length(), e < 0 ? line.length() : e);
    }

    /** "video/hevc 1280x720, BT2020/Limited range/ST2084 PQ/…" from a Format log line. */
    static String summary(String line) {
        String mime = part(line, "mimeType="), res = part(line, "res="), color = part(line, "color=");
        return (mime != null ? mime : "video") + (res != null ? " " + res : "") + (color != null ? ", " + color : ", colour not given");
    }

}
