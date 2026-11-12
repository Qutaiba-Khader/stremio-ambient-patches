package app.subsense.ambient;

import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the playing video's Media3 Format, whatever decodes it (a hardware MediaCodec or Stremio's FFmpeg renderer): a
 * short walk over the fields of the objects behind the video view (the player view, its player, the renderers…) to the
 * first Format whose log line says "mimeType=video/". Media3's Format class is given by the patch (found by its log
 * line's strings, so a shrunk app is no problem); its own log line (Format.toLogString) then tells the transfer
 * ("ST2084 PQ", "HLG") and Dolby Vision.
 *
 * Bounded: framework classes are not entered, at most 4000 objects and 6 steps, arrays up to 64 items.
 */
final class FormatProbe {
    private FormatProbe() {}

    /** Replaced by the patch with the app's Format class (const-class). Null when not patched: the probe stays off. */
    static Class<?> formatType() { return null; }

    private static Method logString;
    private static boolean looked;
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();

    /** The playing video's format line, or null. */
    static String find(View video) {
        Class<?> format = formatType();
        if (format == null || video == null) return null;
        Method log = logMethod(format);
        ArrayDeque<Object[]> queue = new ArrayDeque<>(); // {object, depth}
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ViewParent p = video.getParent();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) queue.add(new Object[]{p, 0});
        int visited = 0;
        while (!queue.isEmpty() && visited < 4000) {
            Object[] item = queue.poll();
            Object o = item[0];
            int depth = (Integer) item[1];
            if (o == null || seen.put(o, Boolean.TRUE) != null) continue;
            visited++;
            Class<?> c = o.getClass();
            if (format.isInstance(o)) {
                String line = describe(log, o);
                if (line != null && line.contains("mimeType=video/")) return line;
                continue;
            }
            if (depth >= 6) continue;
            if (c.isArray()) {
                if (c.getComponentType().isPrimitive()) continue;
                int n = Math.min(Array.getLength(o), 64);
                for (int i = 0; i < n; i++) queue.add(new Object[]{Array.get(o, i), depth + 1});
                continue;
            }
            if (framework(c)) continue;
            for (Field f : fields(c)) {
                try {
                    queue.add(new Object[]{f.get(o), depth + 1});
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean framework(Class<?> c) {
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.") || n.startsWith("kotlin.")
                || n.startsWith("kotlinx.") || n.startsWith("dalvik.") || n.startsWith("sun.") || n.startsWith("com.android.")
                || n.startsWith("libcore.") || n.startsWith("okhttp3.") || n.startsWith("okio.") || n.startsWith("app.subsense.");
    }

    /** The object fields of a class and of its app superclasses (framework ones are skipped), made readable once. */
    private static synchronized Field[] fields(Class<?> c) {
        Field[] cached = FIELDS.get(c);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && !framework(k); k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) {
                }
            }
        }
        cached = out.toArray(new Field[0]);
        FIELDS.put(c, cached);
        return cached;
    }

    /** Format.toLogString(Format): the static method taking a Format and giving a String. */
    private static synchronized Method logMethod(Class<?> format) {
        if (!looked) {
            looked = true;
            for (Method m : format.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == format) {
                    m.setAccessible(true);
                    logString = m;
                }
            }
        }
        return logString;
    }

    private static String describe(Method log, Object format) {
        try {
            return log != null ? (String) log.invoke(null, format) : String.valueOf(format);
        } catch (Throwable t) {
            return null;
        }
    }
}
