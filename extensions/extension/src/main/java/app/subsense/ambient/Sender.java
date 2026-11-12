package app.subsense.ambient;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The connection to HyperHDR's flatbuffer server, written to only from the network thread.
 *
 * - Only the newest image waits to be sent (one slot): a slow or stuck network never queues frames up.
 * - HyperHDR closes a client after a few seconds without messages; the reply reader notices the close, and the next
 *   image reconnects at once. Only a failed connect waits (5 s) before the next try.
 * - A write stuck for more than 2 s (HyperHDR's host gone without a reset) is cut by {@link #watchdog}, run from the
 *   copy thread, so the network thread is free again.
 * - clear() hands the LEDs back (HyperHDR shows the next source in line).
 */
final class Sender {
    static final String ORIGIN = "Stremio Ambient";

    private static final class Frame {
        final String host;
        final int priority, duration;
        final Edges.Image image;

        Frame(String host, int priority, Edges.Image image, int duration) {
            this.host = host;
            this.priority = priority;
            this.image = image;
            this.duration = duration;
        }
    }

    private final Handler net;
    private final AtomicReference<Frame> next = new AtomicReference<>();
    private final Runnable drain = this::drain;
    private volatile Socket socket;
    private volatile boolean dead;
    private volatile long writingSince;
    private OutputStream out;
    private String target = "";
    private int priority;
    private long retryAt;
    volatile String state = "not connected";
    volatile long sent;

    Sender(Handler net) {
        this.net = net;
    }

    /** Queues one image: it replaces one that is still waiting. Any thread. */
    void offer(String host, int prio, Edges.Image im, int durationMs) {
        if (next.getAndSet(new Frame(host, prio, im, durationMs)) == null) net.post(drain);
    }

    /** Hands the LEDs back; drops a waiting image. Any thread. */
    void clearSoon() {
        next.set(null);
        net.post(this::clear);
    }

    /** From the copy thread: cuts a write that has been stuck for over 2 s. */
    void watchdog() {
        long since = writingSince;
        if (since != 0 && SystemClock.elapsedRealtime() - since > 2000) {
            Socket s = socket;
            Log.w(Ambient.TAG, "HyperHDR write stuck: closing the connection");
            try {
                if (s != null) s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void drain() {
        Frame f = next.getAndSet(null);
        if (f != null) image(f);
    }

    private void image(Frame f) {
        if (!f.host.equals(target) || f.priority != priority) {
            close();
            target = f.host;
            priority = f.priority;
            retryAt = 0;
        }
        if (f.host.isEmpty()) {
            state = "HyperHDR address not set";
            return;
        }
        if (dead) close(); // HyperHDR closed it (idle timeout): reconnect now, no waiting
        try {
            if (out == null) {
                if (SystemClock.elapsedRealtime() < retryAt) return;
                connect();
            }
            write(Fb.image(f.image.rgb, f.image.w, f.image.h, f.duration));
            sent++;
        } catch (Exception e) {
            fail(e);
        }
    }

    private void clear() {
        if (out == null || dead) {
            close();
            return;
        }
        try {
            write(Fb.clear(priority));
            out.flush();
            state = "connected, idle (LEDs handed back)";
        } catch (Exception e) {
            close();
        }
    }

    private void write(byte[] b) throws Exception {
        writingSince = SystemClock.elapsedRealtime();
        try {
            out.write(b);
        } finally {
            writingSince = 0;
        }
    }

    private void connect() throws Exception {
        state = "connecting to " + target;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(Settings.hostOf(target), Settings.portOf(target)), 2000);
        s.setTcpNoDelay(true);
        socket = s;
        dead = false;
        out = s.getOutputStream();
        final InputStream in = s.getInputStream();
        // HyperHDR answers every message: read the answers so its send buffer never fills; the end of the stream means
        // HyperHDR closed the connection
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                while (in.read(buf) > 0) { /* answers are not needed */ }
            } catch (Exception ignored) {
            }
            if (socket == s) dead = true;
        }, "Ambient-reply");
        reader.setDaemon(true);
        reader.start();
        write(Fb.register(ORIGIN, priority));
        state = "connected to " + target + " (priority " + priority + ")";
        Log.i(Ambient.TAG, state);
    }

    private void fail(Exception e) {
        state = "cannot reach " + target + ": " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : "");
        Log.w(Ambient.TAG, state);
        close();
        retryAt = SystemClock.elapsedRealtime() + 5000;
    }

    private void close() {
        Socket s = socket;
        socket = null;
        out = null;
        dead = false;
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }
}
