package app.subsense.ambient;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Map;

/**
 * Ambient light for Stremio: copies a small picture of the playing video (PixelCopy of the player's SurfaceView, the
 * decoder's own output scaled by the GPU, so the hardware decoder stays on), averages areas along its edges, adjusts
 * the colours with the SDR or the HDR set and sends a small border image to HyperHDR's flatbuffer server.
 *
 * Entry points, all called from the patched app:
 *   onAppCreate      start of the Application's onCreate: settings, the settings page, the activity watcher
 *   tunneling        where Stremio turns tunneled playback on (it follows "Audio passthrough"): this app's own switch
 *   onCodecConfigure before every MediaCodec.configure: the video's format, for HDR detection
 * Everything is guarded: any failure here leaves Stremio playing as before.
 */
public final class Ambient implements Application.ActivityLifecycleCallbacks, Runnable {
    static final String TAG = "StremioAmbient";
    static final int W = 96, H = 54;               // the copy (16:9)
    private static final String PREFS = "stremio_ambient";

    private static volatile Ambient instance;

    final Settings settings = new Settings();
    private final SharedPreferences prefs;
    final String version;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler work, net;
    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final int[] px = new int[W * H];
    private final Sender sender;
    private WebServer server;
    private Activity current;
    private boolean toasted;
    private volatile boolean busy, sending;
    private volatile int generation;

    // what the page shows (written by the threads below, read by the page)
    volatile String capture = "no video on screen";
    volatile String video = "none yet";
    volatile boolean videoHdr, videoTunneled, videoHlg;
    /** When a video MediaCodec was last configured and when the current video view was first seen (elapsedRealtime). */
    volatile long codecAt = -1, surfaceAt;
    volatile String hdrReading = "";
    /** The video MediaCodec now set up (null when it was released) and its mime; the playing format's mime (FormatProbe). */
    private volatile WeakReference<Object> videoCodec;
    volatile String codecMime = "", probeMime;
    volatile long videoAt;
    volatile String activeSet = "sdr";
    volatile int[] rawColours, outColours, content;
    volatile float[] areas;
    volatile long pageSeenAt;
    volatile float copyMs, fps;
    volatile long copiesOk, copiesFailed;
    private long fpsT0, fpsN;
    private int[] lastContent;
    private final Edges.BarTracker barTracker = new Edges.BarTracker();
    private final Hdr.AutoWhite autoWhite = new Hdr.AutoWhite();
    private final Application app;
    volatile TvDisplay tv = new TvDisplay();
    /** What the HDR light uses now: the white level and the brightest highlight (nits), and the film's level (nits). */
    volatile double whiteNow = 203, peakNow = 1000, filmKey = Double.NaN;
    private WeakReference<SurfaceView> lastSurface;
    private long probeAt;
    private volatile String lastProbe;

    private Ambient(Application app) {
        this.app = app;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        settings.load(prefs.getAll());
        migrate();
        String v = "?";
        try {
            v = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        version = v;
        HandlerThread w = new HandlerThread("Ambient-copy");
        w.start();
        work = new Handler(w.getLooper());
        HandlerThread n = new HandlerThread("Ambient-net");
        n.start();
        net = new Handler(n.getLooper());
        sender = new Sender(net);
    }

    static Ambient get() { return instance; }

    /**
     * Stored settings from older versions: a value that was only ever the old default follows the new default.
     * 3: updates per second 20 -> 11, points per edge 8/8/4/4 -> 6/6/3/3 (the owner's choices).
     */
    private void migrate() {
        int v = prefs.getInt("settingsVersion", 0);
        if (v >= 3) return;
        SharedPreferences.Editor e = prefs.edit();
        String[][] moved = {{"rate", "20", "11"}, {"top", "8", "6"}, {"bottom", "8", "6"}, {"left", "4", "3"}, {"right", "4", "3"}};
        for (String[] m : moved) {
            if (m[1].equals(prefs.getString(m[0], null))) {
                settings.set(m[0], m[2]);
                e.putString(m[0], m[2]);
            }
        }
        e.putInt("settingsVersion", 3).commit();
    }

    // ---- entry points ----

    /** Start of the Application's onCreate. Only the app's main process gets the ambient light and the page. */
    public static void onAppCreate(Application app) {
        try {
            if (instance != null || !isMainProcess(app)) return;
            Ambient a = new Ambient(app);
            instance = a;
            app.registerActivityLifecycleCallbacks(a);
            a.server = new WebServer(a, a.settings.i("port"));
            a.server.start();
            Log.i(TAG, "ready; settings page on port " + a.settings.i("port"));
        } catch (Throwable t) {
            Log.e(TAG, "not started", t);
        }
    }

    /**
     * Where Stremio decides tunneled playback (it passes its "Audio passthrough" setting): tunneled video never reaches
     * the app, so the ambient light has its own switch for it, off unless turned on on the page. Audio passthrough
     * itself keeps working without tunneling.
     */
    public static boolean tunneling(boolean stremioWants) {
        try {
            Ambient a = instance;
            boolean on = a != null && a.settings.b("tunnel");
            Log.i(TAG, "tunneled playback: Stremio " + stremioWants + ", used " + on);
            return on;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Before every MediaCodec.configure: a video codec with a surface is the video being played. */
    public static void onCodecConfigure(Object codec, MediaFormat format, Surface surface) {
        try {
            Ambient a = instance;
            if (a == null || format == null || surface == null) return;
            String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : "";
            if (mime == null || !mime.startsWith("video/")) return;
            a.videoCodec = new WeakReference<>(codec);
            a.codecMime = mime;
            int transfer = intOf(format, "color-transfer");
            int profile = intOf(format, "profile");
            a.videoHdr = isHdr(mime, transfer, profile);
            a.videoHlg = transfer == 7;
            a.codecAt = SystemClock.elapsedRealtime();
            a.videoTunneled = intOf(format, "feature-tunneled-playback") == 1;
            a.videoAt = System.currentTimeMillis();
            a.video = mime + " " + intOf(format, "width") + "x" + intOf(format, "height") + ", "
                    + (transfer == 6 ? "PQ" : transfer == 7 ? "HLG" : transfer == 3 ? "SDR" : transfer < 0 ? "transfer not given" : "transfer " + transfer)
                    + (profile >= 0 ? ", profile 0x" + Integer.toHexString(profile) : "")
                    + (a.videoTunneled ? ", tunneled" : "");
            Log.i(TAG, "video: " + a.video + (a.videoHdr ? " -> HDR" : " -> SDR"));
        } catch (Throwable t) {
            Log.w(TAG, "codec format not read", t);
        }
    }

    /** Before every MediaCodec.release: the video's codec going away means the video is no longer hardware-decoded. */
    public static void onCodecRelease(Object codec) {
        try {
            Ambient a = instance;
            WeakReference<Object> v = a == null ? null : a.videoCodec;
            if (v != null && v.get() == codec) a.videoCodec = null;
        } catch (Throwable ignored) {
        }
    }

    /**
     * HDR: PQ or HLG transfer, Dolby Vision, or an HDR profile (HEVC Main10 HDR10/HDR10+, VP9 profile 2 HDR, AV1 Main10
     * HDR10/HDR10+), as MediaCodecInfo.CodecProfileLevel numbers them.
     */
    static boolean isHdr(String mime, int transfer, int profile) {
        if (transfer == 6 || transfer == 7) return true;
        if ("video/dolby-vision".equals(mime)) return true;
        if (profile < 0) return false;
        switch (mime) {
            case "video/hevc": return profile == 0x1000 || profile == 0x2000;
            case "video/x-vnd.on2.vp9": return profile == 0x1000 || profile == 0x2000 || profile == 0x4000 || profile == 0x8000;
            case "video/av01": return profile == 0x1000 || profile == 0x2000;
            default: return false;
        }
    }

    private static int intOf(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isMainProcess(Application app) {
        try {
            if (Build.VERSION.SDK_INT >= 28) return app.getPackageName().equals(Application.getProcessName());
            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            int pid = Process.myPid();
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.pid == pid) return app.getPackageName().equals(p.processName);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    // ---- settings (the page) ----

    /** Applies the page's changes at once and stores them. Returns null, or the first refused value's reason. */
    String change(Map<String, String> changes) {
        String err = null;
        SharedPreferences.Editor e = prefs.edit();
        synchronized (settings) {
            for (Map.Entry<String, String> c : changes.entrySet()) {
                String why = settings.set(c.getKey(), c.getValue());
                if (why != null) {
                    if (err == null) err = why;
                } else {
                    e.putString(c.getKey(), settings.get(c.getKey()));
                }
            }
        }
        e.commit(); // at once: the page thread may wait, and an app killed right after a change keeps it
        return err; // the loop reads the settings on every round: nothing to restart
    }

    void reset(String prefix) {
        SharedPreferences.Editor e = prefs.edit();
        synchronized (settings) {
            settings.reset(prefix);
            for (Map.Entry<String, String> v : settings.all().entrySet()) e.putString(v.getKey(), v.getValue());
        }
        e.commit();
    }

    String senderState() {
        if (settings.get("host").isEmpty()) return "HyperHDR address not set";
        if (!settings.b("enabled")) return "off";
        return sender.state;
    }

    long sent() { return sender.sent; }

    /** The TV as Android sees it, read again when older than 30 s (for the page). */
    TvDisplay tv() {
        TvDisplay t = tv;
        if (t.readAt == 0 || SystemClock.elapsedRealtime() - t.readAt > 30_000) tv = t = TvDisplay.read(app);
        return t;
    }

    // ---- activities ----

    @Override public void onActivityResumed(Activity a) {
        try {
            current = a;
            main.removeCallbacks(this);
            main.postDelayed(this, 200);
            if (!toasted && settings.get("host").isEmpty()) {
                toasted = true;
                String ip = lanAddress();
                Toast.makeText(a, "Ambient light: set the HyperHDR address on http://" + (ip != null ? ip : "<TV address>") + ":"
                        + settings.i("port"), Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "resume", t);
        }
    }

    @Override public void onActivityPaused(Activity a) {
        try {
            if (current != a) return;
            current = null;
            lastSurface = null;
            main.removeCallbacks(this);
            stopSending("no video on screen");
        } catch (Throwable t) {
            Log.w(TAG, "pause", t);
        }
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}

    // ---- the copy loop: looks on the main thread, copies and computes on the copy thread ----

    /** Full rate while sending; twice a second for the page's preview only; else nothing. */
    private long period() {
        boolean output = settings.b("enabled") && !settings.get("host").isEmpty();
        if (output) return 1000 / settings.i("rate");
        return SystemClock.elapsedRealtime() - pageSeenAt < 10_000 ? 500 : -1;
    }

    @Override public void run() {
        try {
            if (current == null) return;
            long period = period();
            main.postDelayed(this, period > 0 ? period : 1000);
            final boolean output = settings.b("enabled") && !settings.get("host").isEmpty();
            if (!output) stopSending(settings.b("enabled") ? "HyperHDR address not set" : "off");
            if (period < 0 || busy) return;
            SurfaceView sv = largestSurfaceView(current.getWindow().getDecorView());
            final Surface surface = sv == null ? null : sv.getHolder().getSurface();
            if (surface == null || !surface.isValid()) {
                stopSending("no video on screen");
                return;
            }
            probe(sv);
            busy = true;
            final int gen = generation;
            // PixelCopy copies on the calling thread up to Android 13: never Stremio's main thread
            work.post(() -> copy(surface, output, gen));
        } catch (Throwable t) {
            busy = false;
            capture = "copy failed: " + t;
            Log.w(TAG, "copy", t);
        }
    }

    private void copy(Surface surface, boolean output, int gen) {
        final long t0 = SystemClock.elapsedRealtimeNanos();
        try {
            PixelCopy.request(surface, bmp, result -> {
                try {
                    if (result == PixelCopy.SUCCESS) {
                        copiesOk++;
                        copyMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f;
                        process(output, gen);
                    } else {
                        copiesFailed++;
                        capture = result == PixelCopy.ERROR_SOURCE_NO_DATA
                                ? (videoTunneled ? "no picture: tunneled playback is on" : "no picture yet")
                                : "copy failed (" + result + ")";
                    }
                } catch (Throwable t) {
                    capture = "copy failed: " + t;
                    Log.w(TAG, "process", t);
                } finally {
                    busy = false; // only now: the next copy writes into the same bitmap
                }
            }, work);
        } catch (Throwable t) {
            busy = false;
            capture = "copy failed: " + t;
            Log.w(TAG, "copy", t);
        }
    }

    /**
     * Every 3 s (and when a new video view appears) the playing video's Media3 format is looked up: it covers videos that
     * no MediaCodec decodes (Stremio's FFmpeg renderer), which the codec hook never sees.
     */
    private void probe(SurfaceView sv) {
        long now = SystemClock.elapsedRealtime();
        SurfaceView prev = lastSurface == null ? null : lastSurface.get();
        if (sv != prev) {
            lastSurface = new WeakReference<>(sv);
            surfaceAt = now;
            probeMime = null;
            work.post(barTracker::reset); // a new video starts with its own bars
            work.post(autoWhite::reset);  // and its own brightness
            lastProbe = null;
            probeAt = 0;
        }
        if (now - probeAt < 3000) return;
        probeAt = now;
        work.post(() -> {
            try {
                String line = FormatProbe.find(sv);
                if (line == null || line.equals(lastProbe)) return;
                lastProbe = line;
                videoHdr = Formats.isHdr(line);
                String colour = Formats.part(line, "color=");
                videoHlg = colour != null && colour.contains("HLG");
                probeMime = Formats.part(line, "mimeType=");
                videoAt = System.currentTimeMillis();
                video = Formats.summary(line) + (videoTunneled ? ", tunneled" : "");
                Log.i(TAG, "video format: " + line + (videoHdr ? " -> HDR" : " -> SDR"));
            } catch (Throwable t) {
                Log.w(TAG, "format probe", t);
            }
        });
    }

    private static SurfaceView largestSurfaceView(View v) {
        if (v instanceof SurfaceView) return v.isShown() && v.getWidth() > 0 ? (SurfaceView) v : null;
        SurfaceView best = null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView s = largestSurfaceView(g.getChildAt(i));
                if (s != null && (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight())) best = s;
            }
        }
        return best;
    }

    // ---- the copy thread ----

    private void process(boolean output, int gen) {
        sender.watchdog();
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        Edges.Params p;
        Edges.Colour colour;
        Hdr.Curve curve;
        String set, host, read;
        int prio, rate, lift;
        boolean autoOn, peakTv;
        synchronized (settings) {
            p = Edges.Params.of(settings);
            String mode = settings.get("hdrMode");
            set = mode.equals("auto") ? (videoHdr ? "hdr" : "sdr") : mode;
            colour = Edges.Colour.of(settings, set);
            curve = Hdr.Curve.of(settings);
            read = settings.get("hdr.read");
            autoOn = settings.b("hdr.autoWhite");
            peakTv = settings.b("hdr.peakTv");
            lift = settings.i("hdr.lift");
            host = settings.get("host");
            prio = settings.i("priority");
            rate = settings.i("rate");
        }
        // black bars: found on this frame, kept steady over time (a new result must hold 1.5 s), then sampled inside them
        long now = SystemClock.elapsedRealtime();
        int[] found = p.bars ? Edges.content(px, W, H, p.barLevel, lastContent) : new int[]{0, 0, W - 1, H - 1};
        lastContent = barTracker.update(found, now, p.bars ? 1500 : 0);
        Edges.Result r = Edges.sampleIn(px, W, H, p, lastContent);
        // An HDR video's copy is first brought back to its light, then to the LEDs (see Hdr). Automatic: a hardware decoder's
        // buffer carries its dataspace and Android tone-maps the copy; Stremio's FFmpeg renderer writes the signal as is.
        String how = !videoHdr ? "sdr" : read.equals("auto") ? (hardwareDecoded() ? "android" : "pq") : read;
        hdrReading = how;
        int[] out = new int[r.colours.length];
        double[][] nits = new double[r.colours.length][];
        for (int i = 0; i < out.length; i++) nits[i] = Hdr.toNits(r.colours[i], how, videoHlg);
        if (nits.length > 0 && nits[0] != null) {
            // the TV's own peak (EDID) when asked for, and the white level following the film when automatic
            TvDisplay t = tv;
            if (t.readAt == 0 || now - t.readAt > 30_000) tv = t = TvDisplay.read(app);
            double peak = t.peak(peakTv, curve.peak);
            double key = autoWhite.update(nits, now);
            double white = autoOn ? Hdr.AutoWhite.white(key, lift, peak) : curve.white;
            curve = curve.withPeak(peak).withWhite(white);
            whiteNow = curve.white;
            peakNow = curve.peak;
            filmKey = key;
        }
        for (int i = 0; i < out.length; i++) out[i] = colour.apply(nits[i] == null ? r.colours[i] & 0xffffff : curve.encode(nits[i]));
        activeSet = set;
        rawColours = r.colours;
        outColours = out;
        areas = r.areas;
        content = r.content;
        if (fpsT0 == 0) fpsT0 = now;
        fpsN++;
        if (now - fpsT0 >= 2000) {
            fps = fpsN * 1000f / (now - fpsT0);
            fpsT0 = now;
            fpsN = 0;
        }
        // a copy that started before the video left (or the output was turned off) never re-lights the LEDs
        if (!output || gen != generation) return;
        capture = "sending";
        // an image lasts a little longer than the gap to the next one, so a stalled app lets go of the LEDs by itself
        int duration = Math.max(1000, 3 * 1000 / rate);
        sending = true;
        sender.offer(host, prio, Edges.image(out, p), duration);
    }

    /**
     * Hardware-decoded: a video MediaCodec is set up and not released, and it decodes the playing format (same mime;
     * Dolby Vision may be decoded by an HEVC, AVC or AV1 decoder). Stremio's FFmpeg renderer uses no MediaCodec.
     */
    private boolean hardwareDecoded() {
        WeakReference<Object> v = videoCodec;
        if (v == null || v.get() == null) return false;
        String playing = probeMime;
        if (playing == null || playing.equals(codecMime)) return true;
        return playing.equals("video/dolby-vision")
                && (codecMime.equals("video/hevc") || codecMime.equals("video/avc") || codecMime.equals("video/av01"));
    }

    private void stopSending(String why) {
        capture = why;
        generation++;
        if (!sending) return;
        sending = false;
        sender.clearSoon();
    }

    // ---- helpers ----

    /** The TV's own address on the home network (for the toast and the page). */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof Inet4Address && ad.isSiteLocalAddress()) return ad.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
