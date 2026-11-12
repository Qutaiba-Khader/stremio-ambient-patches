package app.subsense.ambient;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The settings page, served by the app while it runs: http://<TV address>:<port>/. Home network only: a client outside
 * the private address ranges gets 403. Changes come as POST with the header X-Ambient (a web page elsewhere cannot send
 * that header to the TV without asking first, and this server never says yes), so another site cannot change them.
 *
 *   GET  /            the page
 *   GET  /api/state   settings, ranges, status and the live preview (JSON)
 *   POST /api/set     form fields key=value…: applied at once and stored
 *   POST /api/reset   form field prefix: "sdr.", "hdr." or "" (everything but the address and the port)
 */
final class WebServer extends Thread {
    private final Ambient ambient;
    private final int port;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16),
            r -> {
                Thread t = new Thread(r, "Ambient-page");
                t.setDaemon(true);
                return t;
            }, (r, executor) -> ((Request) r).drop());

    /** One connection; dropped (closed) when every page thread is busy and the queue is full. */
    private final class Request implements Runnable {
        final Socket socket;

        Request(Socket socket) { this.socket = socket; }

        @Override public void run() { handle(socket); }

        void drop() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    volatile String state = "starting";

    WebServer(Ambient ambient, int port) {
        super("Ambient-server");
        setDaemon(true);
        this.ambient = ambient;
        this.port = port;
        pool.allowCoreThreadTimeOut(true);
    }

    @Override public void run() {
        for (int attempt = 0; attempt < 60; attempt++) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                state = "listening on port " + port;
                while (true) {
                    Socket s = ss.accept();
                    pool.execute(new Request(s));
                }
            } catch (Exception e) {
                state = "port " + port + ": " + e.getMessage();
                Log.w(Ambient.TAG, "settings page: " + state);
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            long deadline = System.currentTimeMillis() + 15_000; // the whole request, however slowly it trickles in
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            if (!Net.isHomeNetwork(sock.getInetAddress())) {
                reply(out, 403, "text/plain", "home network only");
                return;
            }
            String requestLine = line(in, deadline);
            if (requestLine == null) return;
            String[] rl = requestLine.split(" ");
            if (rl.length < 2) return;
            String method = rl[0], path = rl[1];
            int contentLength = 0;
            boolean marked = false;
            String host = null;
            String h;
            int headers = 0;
            while ((h = line(in, deadline)) != null && !h.isEmpty()) {
                if (++headers > 100) return;
                int c = h.indexOf(':');
                if (c < 0) continue;
                String name = h.substring(0, c).trim().toLowerCase(), value = h.substring(c + 1).trim();
                if (name.equals("content-length")) contentLength = Math.min(Integer.parseInt(value), 65536);
                if (name.equals("x-ambient")) marked = true;
                if (name.equals("host")) host = value;
            }
            // a site elsewhere cannot point its own name at the TV (DNS rebinding): only addresses and local names
            if (!Net.isLocalHostHeader(host)) {
                reply(out, 403, "text/plain", "open this page by the TV's address");
                return;
            }
            byte[] body = new byte[Math.max(0, contentLength)];
            int got = 0;
            while (got < body.length) {
                if (System.currentTimeMillis() > deadline) return;
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                reply(out, 200, "text/html; charset=utf-8", Page.HTML);
            } else if (method.equals("GET") && path.equals("/api/state")) {
                ambient.pageSeenAt = android.os.SystemClock.elapsedRealtime();
                reply(out, 200, "application/json", state(null));
            } else if (method.equals("POST") && (path.equals("/api/set") || path.equals("/api/reset"))) {
                if (!marked) {
                    reply(out, 403, "text/plain", "missing X-Ambient header");
                    return;
                }
                Map<String, String> form = Net.form(new String(body, 0, got, StandardCharsets.UTF_8));
                String err;
                if (path.equals("/api/set")) {
                    err = ambient.change(form);
                } else {
                    String prefix = form.containsKey("prefix") ? form.get("prefix") : "";
                    if (!prefix.isEmpty() && !prefix.equals("sdr.") && !prefix.equals("hdr.")) {
                        reply(out, 400, "text/plain", "prefix: sdr., hdr. or empty");
                        return;
                    }
                    ambient.reset(prefix);
                    err = null;
                }
                reply(out, err == null ? 200 : 400, "application/json", state(err));
            } else {
                reply(out, 404, "text/plain", "not found");
            }
        } catch (Exception e) {
            Log.w(Ambient.TAG, "page request: " + e);
        }
    }

    private String state(String error) {
        Ambient a = ambient;
        StringBuilder sb = new StringBuilder("{\"settings\":").append(a.settings.toJson());
        sb.append(",\"ranges\":").append(Settings.defsJson());
        sb.append(",\"error\":");
        if (error == null) sb.append("null");
        else Json.str(sb, error);
        sb.append(",\"status\":{\"app\":");
        Json.str(sb, a.version);
        sb.append(",\"tv\":");
        Json.str(sb, Ambient.lanAddress());
        sb.append(",\"page\":");
        Json.str(sb, state);
        sb.append(",\"hyperhdr\":");
        Json.str(sb, a.senderState());
        sb.append(",\"capture\":");
        Json.str(sb, a.capture);
        sb.append(",\"video\":");
        Json.str(sb, a.video);
        sb.append(",\"videoHdr\":").append(a.videoHdr);
        sb.append(",\"videoTunneled\":").append(a.videoTunneled);
        sb.append(",\"videoAt\":").append(a.videoAt);
        sb.append(",\"activeSet\":");
        Json.str(sb, a.activeSet);
        sb.append(",\"fps\":").append(Math.round(a.fps * 10) / 10.0);
        sb.append(",\"copyMs\":").append(Math.round(a.copyMs * 10) / 10.0);
        sb.append(",\"copies\":").append(a.copiesOk).append(",\"copyFailures\":").append(a.copiesFailed);
        sb.append(",\"sent\":").append(a.sent());
        sb.append("},\"preview\":{\"w\":").append(Ambient.W).append(",\"h\":").append(Ambient.H).append(",\"raw\":");
        Json.colours(sb, a.rawColours);
        sb.append(",\"out\":");
        Json.colours(sb, a.outColours);
        sb.append(",\"areas\":");
        Json.floats(sb, a.areas);
        sb.append(",\"content\":");
        int[] c = a.content;
        sb.append(c == null ? "null" : "[" + c[0] + "," + c[1] + "," + c[2] + "," + c[3] + "]");
        return sb.append("}}").toString();
    }

    private static String line(InputStream in, long deadline) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (System.currentTimeMillis() > deadline) return null;
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void reply(OutputStream out, int code, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
