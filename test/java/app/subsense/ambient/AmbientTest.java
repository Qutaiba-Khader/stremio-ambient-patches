package app.subsense.ambient;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Unit tests of the extension's pure parts (settings, points, colours, the HyperHDR image and its flatbuffer, the page
 * server's helpers). Plain Java, no framework: tools/unit-test.sh compiles and runs them.
 */
public final class AmbientTest {
    private static int passed, failed;

    private static void check(boolean ok, String what) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        check(want == null ? got == null : want.equals(got), what + ": want " + want + ", got " + got);
    }

    public static void main(String[] args) throws Exception {
        settings();
        sampling();
        letterbox();
        geometryFuzz();
        colour();
        image();
        flatbuffers();
        net();
        formats();
        hdr();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- settings ----

    static void settings() {
        Settings s = new Settings();
        eq("", s.get("host"), "no HyperHDR address by default");
        check(s.b("enabled"), "on by default");
        check(!s.b("tunnel"), "tunneled playback off by default");
        eq(8, s.i("top"), "top default");
        eq(4, s.i("left"), "left default");
        eq(8095, s.i("port"), "page port default");
        eq(null, s.set("top", "12"), "top 12 accepted");
        eq(12, s.i("top"), "top stored");
        check(s.set("top", "0") != null, "top 0 refused");
        check(s.set("top", "65") != null, "top 65 refused");
        check(s.set("top", "x") != null, "top x refused");
        eq(12, s.i("top"), "refused value keeps the old one");
        check(s.set("nope", "1") != null, "unknown key refused");
        eq(null, s.set("tunnel", "on"), "on accepted");
        check(s.b("tunnel"), "tunnel on");
        eq(null, s.set("tunnel", "false"), "false accepted");
        check(!s.b("tunnel"), "tunnel off");
        check(s.set("enabled", "maybe") != null, "bad boolean refused");
        eq(null, s.set("hdrMode", "hdr"), "hdrMode hdr");
        check(s.set("hdrMode", "HDR10") != null, "bad hdrMode refused");
        eq(null, s.set("sdr.temp", "-100"), "negative temperature");
        eq(-100, s.i("sdr.temp"), "temperature stored");
        // the address
        for (String ok : new String[]{"", "192.168.0.50", "192.168.0.50:19400", "hyperhdr.local", "my-box:1"}) {
            eq(null, s.set("host", ok), "address accepted: " + ok);
        }
        for (String bad : new String[]{"http://x", "a b", "1.2.3.4:0", "1.2.3.4:70000", "1.2.3.4:", ":19400", "x;rm", "<script>"}) {
            check(s.set("host", bad) != null, "address refused: " + bad);
        }
        eq("my-box:1", s.get("host"), "last good address kept");
        eq("192.168.0.50", Settings.hostOf("192.168.0.50:19400"), "hostOf");
        eq(19400, Settings.portOf("192.168.0.50"), "default flatbuffer port");
        eq(1234, Settings.portOf("h:1234"), "portOf");
        // stored values: bad ones fall back to the default
        Map<String, Object> stored = new HashMap<>();
        stored.put("top", "20");
        stored.put("left", "999");
        stored.put("host", "bad host");
        stored.put("hdr.white", "150");
        stored.put("hdr.fix", "40"); // a setting of an older version: ignored
        Settings l = new Settings();
        l.load(stored);
        eq(20, l.i("top"), "stored top loaded");
        eq(4, l.i("left"), "bad stored left keeps the default");
        eq("", l.get("host"), "bad stored address keeps none");
        eq(150, l.i("hdr.white"), "stored hdr.white");
        check(l.get("hdr.fix") == null, "an old setting is not kept");
        // reset keeps the address and the port
        l.set("host", "10.0.0.2");
        l.set("port", "9000");
        l.set("sdr.bright", "150");
        l.set("hdr.bright", "150");
        l.reset("sdr.");
        eq(100, l.i("sdr.bright"), "reset sdr.");
        eq(150, l.i("hdr.bright"), "reset sdr. leaves hdr.");
        l.reset("");
        eq(100, l.i("hdr.bright"), "reset all");
        eq(20 == 20 ? 8 : 0, l.i("top"), "reset all puts top back");
        eq("10.0.0.2", l.get("host"), "reset all keeps the address");
        eq(9000, l.i("port"), "reset all keeps the port");
        // JSON
        Settings j = new Settings();
        j.set("host", "1.2.3.4");
        String js = j.toJson();
        check(js.startsWith("{\"enabled\":true,\"host\":\"1.2.3.4\""), "toJson start: " + js.substring(0, 40));
        check(js.contains("\"sdr.temp\":0"), "toJson numbers");
        check(Settings.defsJson().contains("\"top\":{\"min\":1,\"max\":64,\"def\":8}"), "defsJson ranges");
        check(Settings.defsJson().contains("\"host\":{\"min\":0,\"max\":0,\"def\":\"\"}"), "defsJson host");
        eq("\"a\\\"b\\u003c\"", Json.str("a\"b<"), "JSON escaping");
        for (Settings.Def d : Settings.DEFS) eq(null, new Settings().set(d.key, d.def), "default valid: " + d.key);
    }

    // ---- points ----

    static final int W = 96, H = 54;

    static int[] frame(int top, int right, int bottom, int left, int middle) {
        int[] px = new int[W * H];
        int band = 8;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int c = middle;
                if (y < band) c = top;
                else if (y >= H - band) c = bottom;
                else if (x < band) c = left;
                else if (x >= W - band) c = right;
                px[y * W + x] = 0xff000000 | c;
            }
        }
        return px;
    }

    static void sampling() {
        Edges.Params p = new Edges.Params();
        p.depth = 10; // 5.4 px: inside the 8 px bands
        int[] px = frame(0xff0000, 0xffff00, 0x0000ff, 0x00ff00, 0x404040);
        Edges.Result r = Edges.sample(px, W, H, p, null);
        eq(24, r.colours.length, "8+4+8+4 points");
        eq(96, r.areas.length, "four numbers per area");
        int k = 0;
        for (int i = 0; i < 8; i++) eq(0xff0000, r.colours[k++], "top point " + i);
        // side points span the full height: the corner ones see some top/bottom band, the middle ones only the side
        k = 8;
        eq(0xffff00, r.colours[k + 1], "right point 1");
        eq(0xffff00, r.colours[k + 2], "right point 2");
        k = 12;
        for (int i = 0; i < 8; i++) eq(0x0000ff, r.colours[k++], "bottom point " + i);
        eq(0x00ff00, r.colours[21], "left point 1");
        eq(0x00ff00, r.colours[22], "left point 2");
        // a deep area reaches the middle colour
        p.depth = 50;
        Edges.Result deep = Edges.sample(px, W, H, p, null);
        check(deep.colours[0] != 0xff0000, "a deep top area mixes in the middle");
        // inset: skip past the band
        p.depth = 5;
        p.inset = 20; // 10.8 px in: below the 8 px top band
        Edges.Result in = Edges.sample(px, W, H, p, null);
        int tl = in.colours[0];
        check(((tl >> 8) & 255) > ((tl >> 16) & 255) + 0x40, "inset top-left area is mostly the left band: " + Integer.toHexString(tl));
        eq(0x404040, in.colours[4], "inset top-middle area sits in the middle");
        // spread narrows the area around its middle
        p.inset = 0;
        p.spread = 50;
        Edges.Result sp = Edges.sample(px, W, H, p, null);
        float wFull = r.areas[2] - r.areas[0], wHalf = sp.areas[2] - sp.areas[0];
        check(wHalf < wFull * 0.7f, "spread 50 narrows the area (" + wHalf + " vs " + wFull + ")");
        float midFull = (r.areas[0] + r.areas[2]) / 2, midHalf = (sp.areas[0] + sp.areas[2]) / 2;
        check(Math.abs(midFull - midHalf) < 0.02f, "spread keeps the middle");
        // averages round
        eq(0x7f7f7f, Edges.average(new int[]{0x000000, 0xfefefe}, 2, 0, 0, 2, 1), "average");
        eq(0x808080, Edges.average(new int[]{0x000000, 0xffffff}, 2, 0, 0, 2, 1), "average rounds");
    }

    static void letterbox() {
        Edges.Params p = new Edges.Params();
        // 2.39:1 film in a 16:9 frame: black bars of 7 rows at the top and the bottom, picture red at its edges
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = 0xff000000 | (y < 7 || y >= H - 7 ? 0x050505 : 0xc01010);
        Edges.Result r = Edges.sample(px, W, H, p, null);
        eq(7, r.content[1], "content top below the bar");
        eq(H - 8, r.content[3], "content bottom above the bar");
        eq(0xc01010, r.colours[0], "top point reads the picture, not the bar");
        eq(0xc01010, r.colours[12], "bottom point reads the picture, not the bar");
        p.bars = false;
        Edges.Result nb = Edges.sample(px, W, H, p, null);
        check(nb.colours[0] != 0xc01010, "bars off: the top point reads the bar");
        // pure blue edges are picture, not bars
        p.bars = true;
        for (int i = 0; i < px.length; i++) px[i] = 0xff0000ff;
        eq(0, Edges.sample(px, W, H, p, null).content[1], "blue is not a bar");
        // an all-dark frame keeps the previous bounds
        for (int i = 0; i < px.length; i++) px[i] = 0xff020202;
        int[] prev = {3, 7, 92, 46};
        int[] got = Edges.sample(px, W, H, p, prev).content;
        check(java.util.Arrays.equals(prev, got), "dark frame keeps the previous content");
        eq(0, Edges.sample(px, W, H, p, null).content[1], "dark first frame: whole frame");
        // pillarbox (4:3 in 16:9): 12 columns on each side
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = 0xff000000 | (x < 12 || x >= W - 12 ? 0 : 0x20c020);
        Edges.Result pb = Edges.sample(px, W, H, p, null);
        eq(12, pb.content[0], "pillarbox left");
        eq(W - 13, pb.content[2], "pillarbox right");
        eq(0x20c020, pb.colours[20], "left point reads the picture");
        // the bar level decides
        p.barLevel = 1;
        eq(12, Edges.sample(px, W, H, p, null).content[0], "bar level 1: pure black is still a bar");
        for (int y = 0; y < H; y++) for (int x = 0; x < 12; x++) px[y * W + x] = 0xff010101;
        eq(0, Edges.sample(px, W, H, p, null).content[0], "bar level 1: 1 is not below 1, so no bar");
    }

    static void geometryFuzz() {
        Random rnd = new Random(7);
        int[] px = new int[W * H];
        for (int i = 0; i < px.length; i++) px[i] = 0xff000000 | rnd.nextInt(0xffffff);
        for (int n = 0; n < 3000; n++) {
            Edges.Params p = new Edges.Params();
            p.top = 1 + rnd.nextInt(64);
            p.bottom = 1 + rnd.nextInt(64);
            p.left = 1 + rnd.nextInt(64);
            p.right = 1 + rnd.nextInt(64);
            p.inset = rnd.nextInt(31);
            p.depth = 1 + rnd.nextInt(50);
            p.spread = 10 + rnd.nextInt(91);
            p.bars = rnd.nextBoolean();
            p.barLevel = 1 + rnd.nextInt(100);
            Edges.Result r;
            try {
                r = Edges.sample(px, W, H, p, null);
            } catch (RuntimeException e) {
                check(false, "sample threw for " + p.top + "/" + p.right + "/" + p.bottom + "/" + p.left + ": " + e);
                continue;
            }
            boolean inside = true;
            for (int i = 0; i < r.areas.length; i += 4) {
                inside &= r.areas[i] >= 0 && r.areas[i + 1] >= 0 && r.areas[i + 2] <= 1 && r.areas[i + 3] <= 1;
                inside &= r.areas[i] < r.areas[i + 2] && r.areas[i + 1] < r.areas[i + 3];
            }
            check(inside, "areas inside the frame");
            check(r.colours.length == p.count(), "one colour per point");
            Edges.Image im = Edges.image(r.colours, p);
            check(im.rgb.length == im.w * im.h * 3 && im.w >= 2 && im.h >= 3 && im.w <= 256 * 64, "image size " + im.w + "x" + im.h);
        }
    }

    // ---- colour ----

    static Settings set(String... kv) {
        Settings s = new Settings();
        for (int i = 0; i < kv.length; i += 2) eq(null, s.set(kv[i], kv[i + 1]), kv[i]);
        return s;
    }

    static void colour() {
        Edges.Colour neutral = Edges.Colour.of(new Settings(), "sdr");
        check(neutral.neutral(), "defaults are neutral");
        for (int c : new int[]{0, 0xffffff, 0x123456, 0xff00ff}) eq(c, neutral.apply(0xff000000 | c), "neutral keeps " + Integer.toHexString(c));
        eq(0x808080, Edges.Colour.of(set("sdr.bright", "200"), "sdr").apply(0x404040), "brightness 200%");
        eq(0xffffff, Edges.Colour.of(set("sdr.bright", "300"), "sdr").apply(0xc0c0c0), "brightness clamps");
        eq(0x000000, Edges.Colour.of(set("sdr.bright", "0"), "sdr").apply(0xffffff), "brightness 0");
        eq(0x000000, Edges.Colour.of(set("sdr.black", "20"), "sdr").apply(0x303030), "black level cuts dark");
        eq(0xffffff, Edges.Colour.of(set("sdr.black", "20"), "sdr").apply(0xffffff), "black level keeps white");
        int g = Edges.Colour.of(set("sdr.gamma", "200"), "sdr").apply(0x808080) & 255;
        check(g < 0x48 && g > 0x38, "gamma 2.0 darkens the middle: " + g);
        int gray = Edges.Colour.of(set("sdr.sat", "0"), "sdr").apply(0xff0000);
        check(((gray >> 16) & 255) == ((gray >> 8) & 255) && ((gray >> 8) & 255) == (gray & 255), "saturation 0 is grey");
        int vivid = Edges.Colour.of(set("sdr.sat", "200"), "sdr").apply(0x806060);
        check(((vivid >> 16) & 255) > 0x80 && ((vivid >> 8) & 255) < 0x60, "saturation 200 spreads the channels");
        int warm = Edges.Colour.of(set("sdr.temp", "-100"), "sdr").apply(0xffffff);
        check((warm & 255) < ((warm >> 16) & 255), "warm: less blue than red");
        int cool = Edges.Colour.of(set("sdr.temp", "100"), "sdr").apply(0xffffff);
        check(((cool >> 16) & 255) < (cool & 255), "cool: less red than blue");
        // the HDR set is its own
        Settings both = set("hdr.bright", "50");
        check(Edges.Colour.of(both, "sdr").neutral(), "the SDR set ignores HDR values");
        eq(0x404040, Edges.Colour.of(both, "hdr").apply(0x808080), "the HDR set");
    }

    // ---- the image ----

    static void image() {
        Edges.Params p = new Edges.Params(); // 8, 4, 8, 4
        int[] c = new int[24];
        for (int i = 0; i < c.length; i++) c[i] = (i + 1) * 0x010101;
        Edges.Image im = Edges.image(c, p);
        eq(8, im.w, "image width");
        eq(6, im.h, "image height");
        for (int x = 0; x < 8; x++) eq(c[x], px(im, x, 0), "top row " + x);
        for (int y = 0; y < 4; y++) eq(c[8 + y], px(im, 7, y + 1), "right column " + y);
        for (int x = 0; x < 8; x++) eq(c[12 + x], px(im, x, 5), "bottom row " + x);
        for (int y = 0; y < 4; y++) eq(c[20 + y], px(im, 0, y + 1), "left column " + y);
        eq(0x0c0c0c, px(im, 3, 2), "inside = the mean (12.5 -> 12)");
        // unequal counts: width a multiple of both
        p.top = 3;
        p.bottom = 4;
        p.left = 2;
        p.right = 5;
        int[] d = new int[14];
        for (int i = 0; i < d.length; i++) d[i] = i * 16;
        Edges.Image u = Edges.image(d, p);
        eq(12, u.w, "lcm(3,4)");
        eq(12, u.h, "lcm(2,5)+2");
        eq(d[0], px(u, 3, 0), "top share 0 is 4 wide");
        eq(d[1], px(u, 4, 0), "top share 1 starts at 4");
        eq(d[3 + 5 + 3], px(u, 11, 11), "last bottom share");
        p.top = 1;
        p.bottom = 1;
        eq(2, Edges.image(new int[1 + 5 + 1 + 2], p).w, "one top and one bottom point: width 2");
        eq(12, Edges.lcm(4, 6), "lcm");
    }

    static int grey(int v) { return v << 16 | v << 8 | v; }

    static int px(Edges.Image im, int x, int y) {
        int i = 3 * (y * im.w + x);
        return (im.rgb[i] & 255) << 16 | (im.rgb[i + 1] & 255) << 8 | (im.rgb[i + 2] & 255);
    }

    // ---- flatbuffers: read back with an independent reader that follows the format's rules ----

    static final class Fbr {
        final ByteBuffer bb;

        Fbr(byte[] framed) {
            int len = ByteBuffer.wrap(framed).order(ByteOrder.BIG_ENDIAN).getInt(0);
            eq(framed.length - 4, len, "length prefix");
            byte[] msg = java.util.Arrays.copyOfRange(framed, 4, framed.length);
            bb = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN);
        }

        int root() {
            int t = bb.getInt(0);
            table(t);
            return t;
        }

        void table(int t) {
            check(t % 4 == 0, "table 4-aligned at " + t);
            int vt = t - bb.getInt(t);
            check(vt % 2 == 0 && vt >= 0 && vt + 4 <= bb.limit(), "vtable inside and 2-aligned");
            int vsize = bb.getShort(vt), tsize = bb.getShort(vt + 2);
            check(vsize >= 4 && vsize % 2 == 0, "vtable size");
            for (int s = 0; s < (vsize - 4) / 2; s++) check(bb.getShort(vt + 4 + 2 * s) < tsize, "field inside the table");
        }

        /** The field's position, or -1 when absent. */
        int field(int t, int slot) {
            int vt = t - bb.getInt(t);
            int vsize = bb.getShort(vt);
            if (4 + 2 * slot >= vsize) return -1;
            int o = bb.getShort(vt + 4 + 2 * slot);
            return o == 0 ? -1 : t + o;
        }

        int i32(int t, int slot, int def) {
            int f = field(t, slot);
            if (f >= 0) check(f % 4 == 0, "int 4-aligned");
            return f < 0 ? def : bb.getInt(f);
        }

        int u8(int t, int slot) {
            int f = field(t, slot);
            return f < 0 ? 0 : bb.get(f) & 255;
        }

        int ref(int t, int slot) {
            int f = field(t, slot);
            check(f >= 0, "reference present");
            check(f % 4 == 0, "reference 4-aligned");
            int target = f + bb.getInt(f);
            check(target > f && target < bb.limit(), "reference forward and inside");
            return target;
        }

        byte[] vector(int at) {
            check(at % 4 == 0, "vector 4-aligned");
            int n = bb.getInt(at);
            check(at + 4 + n <= bb.limit(), "vector inside");
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = bb.get(at + 4 + i);
            return out;
        }
    }

    static void flatbuffers() {
        Fbr reg = new Fbr(Fb.register("Stremio Ambient", 90));
        int r = reg.root();
        eq(Fb.COMMAND_REGISTER, reg.u8(r, 0), "register command type");
        int t = reg.ref(r, 1);
        reg.table(t);
        int s = reg.ref(t, 0);
        eq("Stremio Ambient", new String(reg.vector(s), java.nio.charset.StandardCharsets.UTF_8), "origin");
        eq(0, reg.bb.get(s + 4 + 15) & 255, "string ends with 0");
        eq(90, reg.i32(t, 1, -7), "priority");

        Fbr clr = new Fbr(Fb.clear(77));
        r = clr.root();
        eq(Fb.COMMAND_CLEAR, clr.u8(r, 0), "clear command type");
        t = clr.ref(r, 1);
        clr.table(t);
        eq(77, clr.i32(t, 0, -7), "clear priority");

        Random rnd = new Random(3);
        for (int n = 0; n < 200; n++) {
            int w = 1 + rnd.nextInt(40), h = 1 + rnd.nextInt(40);
            byte[] rgb = new byte[w * h * 3];
            rnd.nextBytes(rgb);
            int dur = rnd.nextInt(5000);
            byte[] msg = Fb.image(rgb, w, h, dur);
            check(msg.length % 4 == 0, "message padded to 4");
            Fbr im = new Fbr(msg);
            r = im.root();
            eq(Fb.COMMAND_IMAGE, im.u8(r, 0), "image command type");
            int img = im.ref(r, 1);
            im.table(img);
            eq(1, im.u8(img, 0), "RawImage union type");
            eq(dur, im.i32(img, 2, -1), "duration");
            int raw = im.ref(img, 1);
            im.table(raw);
            eq(w, im.i32(raw, 1, -1), "width");
            eq(h, im.i32(raw, 2, -1), "height");
            check(java.util.Arrays.equals(rgb, im.vector(im.ref(raw, 0))), "pixels");
        }
    }

    // ---- the page server ----

    static void net() throws Exception {
        Map<String, String> f = Net.form("host=192.168.0.5%3A19400&top=12&x&sdr.temp=-5&e=a%20b+c");
        eq("192.168.0.5:19400", f.get("host"), "form decodes");
        eq("12", f.get("top"), "form top");
        eq("", f.get("x"), "form key without value");
        eq("-5", f.get("sdr.temp"), "form dotted key");
        eq("a b c", f.get("e"), "form spaces");
        eq(0, Net.form("").size(), "empty form");
        for (String home : new String[]{"192.168.0.20", "10.1.2.3", "172.16.0.1", "127.0.0.1", "169.254.1.1", "100.100.1.1", "fd00::1", "fe80::1", "::1"}) {
            check(Net.isHomeNetwork(InetAddress.getByName(home)), "home network: " + home);
        }
        for (String away : new String[]{"8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860::8888", "1.1.1.1"}) {
            check(!Net.isHomeNetwork(InetAddress.getByName(away)), "not home network: " + away);
        }
        check(!Net.isHomeNetwork(null), "no address");
        for (String ok : new String[]{"192.168.0.7:8095", "192.168.0.7", "localhost:8095", "127.0.0.1:18095", "[fe80::1]:8095",
                "[::1]", "streamer:8095", "googletv.local", "tv.lan:8095", "tv.home.arpa"}) {
            check(Net.isLocalHostHeader(ok), "local Host: " + ok);
        }
        for (String bad : new String[]{null, "", "evil.com", "evil.com:8095", "192.168.0.7.evil.com", "a b", "x:99a", "[]:1", "tv.local.evil.com"}) {
            check(!Net.isLocalHostHeader(bad), "public Host refused: " + bad);
        }
    }

    static void formats() {
        String hdr = "id=1, mimeType=video/hevc, container=video/mp4, bitrate=49714, codecs=hvc1.2.4.L93.90, res=1280x720, "
                + "decRes=1280x720, color=BT2020/Limited range/ST2084 PQ/10/10, fps=30.0, maxSubLayers=1, language=";
        check(Formats.isHdr(hdr), "PQ is HDR");
        check(Formats.isHdr(hdr.replace("ST2084 PQ", "HLG")), "HLG is HDR");
        check(!Formats.isHdr(hdr.replace("ST2084 PQ", "SDR SMPTE 170M")), "SDR transfer");
        check(!Formats.isHdr("id=1, mimeType=video/avc, color=NA/NA/NA/8/8, label=HLG demo ST2084 PQ"), "a label never makes it HDR");
        check(Formats.isHdr("id=1, mimeType=video/dolby-vision, codecs=dvhe.05.06, color=NA/NA/NA/10/10"), "Dolby Vision is HDR");
        check(!Formats.isHdr(null), "no line");
        eq("video/hevc 1280x720, BT2020/Limited range/ST2084 PQ/10/10", Formats.summary(hdr), "summary");
        eq("1280x720", Formats.part(hdr, "res="), "res= is not decRes=");
        eq("video/avc, colour not given", Formats.summary("id=1, mimeType=video/avc"), "summary without colour");
    }

    // ---- HDR light ----

    static void hdr() {
        // PQ round trip and reference points
        for (double n : new double[]{0.01, 1, 10, 100, 203, 1000, 4000, 10000}) {
            check(Math.abs(Hdr.pqEotf(Hdr.pqOetf(n)) - n) < n * 1e-6 + 1e-6, "PQ round trip " + n);
        }
        check(Math.abs(Hdr.pqOetf(203) - 0.5807) < 0.001, "203 nits is PQ 0.58");
        check(Math.abs(Hdr.pqOetf(100) - 0.5081) < 0.001, "100 nits is PQ 0.51");
        // Android's tone curve: kept below 650, continuous, rising, 4000 -> 1000, and invertible
        eq(400.0, Hdr.toneMap(400), "below the knee: kept");
        check(Math.abs(Hdr.toneMap(650) - 650) < 1e-6, "continuous at the knee");
        check(Math.abs(Hdr.toneMap(1438.235) - 900) < 0.5, "control point 1438 -> 900");
        check(Math.abs(Hdr.toneMap(4000) - 1000) < 1e-6, "4000 -> 1000");
        eq(1000.0, Hdr.toneMap(9000), "over 4000: the display's top");
        double last = -1;
        boolean up = true, inv = true;
        for (double n = 0; n <= 4000; n += 7) {
            double t = Hdr.toneMap(n);
            up &= t >= last - 1e-9;
            last = t;
            inv &= Math.abs(Hdr.untoneMap(t) - n) < Math.max(0.01, n * 1e-6);
        }
        check(up, "tone curve rises");
        check(inv, "untoneMap undoes toneMap from 0 to 4000 nits");
        // the model of Android 14's copy (sRGB-encoded, 1000 nits = full): 203 nits -> 49 %, 100 -> 35 %
        eq(124L, Math.round(Hdr.androidReadback(203) * 255), "Android's copy of 203 nits");
        eq(89L, Math.round(Hdr.androidReadback(100) * 255), "Android's copy of 100 nits");
        check(Math.abs(Hdr.srgbToLinear(Hdr.linearToSrgb(0.3)) - 0.3) < 1e-9 && Hdr.linearToSrgb(0.001) < 0.02, "sRGB curve round trip");
        // decoding Android's 8-bit copy gets the light back (within the 8-bit step)
        double worst = 0;
        for (double n : new double[]{5, 20, 50, 100, 203, 400, 600, 800, 1000, 1500, 2000, 3000}) {
            int v = (int) Math.round(Hdr.androidReadback(n) * 255);
            double got = Hdr.androidToNits(v << 16 | v << 8 | v, false)[0];
            // one 8-bit step around v (grey), in nits: the precision the copy allows
            double lo = Hdr.androidToNits(grey(v - 1), false)[0], hi = Hdr.androidToNits(grey(Math.min(255, v + 1)), false)[0];
            check(got >= lo && got <= hi && n >= lo && n <= hi, "Android copy of " + n + " nits decodes to " + Math.round(got));
            worst = Math.max(worst, Math.abs(got - n) / n);
        }
        check(worst < 0.2, "decoding error within the copy's 8-bit step (worst " + Math.round(worst * 100) + " %)");
        // a colour: Android scales it by its brightest BT.2020 channel; built here the same way, then decoded
        double cr = 1200, cg = 1000, m20 = 0.6274 * cr + 0.3293 * cg, gain = Hdr.toneMap(m20) / m20;
        int er = (int) Math.round(Hdr.linearToSrgb(Math.min(1, cr * gain / 1000)) * 255), eg = (int) Math.round(Hdr.linearToSrgb(cg * gain / 1000) * 255);
        double[] c = Hdr.androidToNits(er << 16 | eg << 8, false);
        check(Math.abs(c[0] - 1200) < 100 && Math.abs(c[1] - 1000) < 90, "a bright warm white (1200/1000 nits, under Android's clip) decodes back: " + Math.round(c[0]) + "/" + Math.round(c[1]));
        check(Math.abs(c[1] / c[0] - 1000.0 / 1200) < 0.03, "and keeps its hue");
        double[] hlg = Hdr.androidToNits(255 << 16, true);
        eq(1000L, Math.round(hlg[0]), "HLG: full red is 1000 nits, nothing to uncompress");
        // the PQ signal itself (software decoding)
        int code = (int) Math.round(Hdr.pqOetf(203) * 255);
        check(Math.abs(Hdr.pqToNits(code << 16)[0] - 203) < 10, "raw PQ 203 nits");
        check(Math.abs(Hdr.hlgToNits(255 << 16)[0] - 1000) < 1, "raw HLG 1.0 = 1000 nits");
        check(Hdr.hlgToNits(0)[0] == 0, "raw HLG 0");
        // to the LEDs
        Hdr.Curve k = new Hdr.Curve(203, 1000, 0.25);
        check(Math.abs(k.led(203) - 0.75) < 1e-9, "white with 25 % headroom: 75 % of the LEDs");
        check(k.led(1000) == 1 && k.led(4000) == 1, "brightest highlight and over: full");
        check(k.led(0) == 0, "black");
        check(Math.abs(k.led(101.5) - 0.375) < 1e-9, "half of white: half of 75 %");
        last = -1;
        up = true;
        for (double n = 0; n <= 1500; n += 3) {
            double l = k.led(n);
            up &= l >= last && l <= 1;
            last = l;
        }
        check(up, "LED curve rises and stays within 0..1");
        check(k.led(400) - k.led(203) > k.led(1000) - k.led(800), "highlights ease out");
        Hdr.Curve none = new Hdr.Curve(203, 1000, 0);
        check(none.led(203) == 1 && none.led(300) == 1, "no headroom: white is full, above it clips");
        eq(0xffffff & (0xe0 << 16 | 0xe0 << 8 | 0xe0), k.encode(new double[]{203, 203, 203}), "white encodes to 0.75^(1/2.2) = 224");
        // a red highlight stays red (the curve on its brightest channel, the colour scaled with it)
        int red = k.encode(new double[]{1000, 100, 100});
        check(((red >> 16) & 255) == 255 && Math.abs(((red >> 8) & 255) - Math.round(Math.pow(0.1, 1 / 2.2) * 255)) <= 1, "red highlight keeps its hue: " + Integer.toHexString(red));
        // the old dim copy is lit properly now: Android's copy of 100 nits (89) becomes about 162 with the defaults
        Settings def = new Settings();
        int lit = Hdr.toLed(89 << 16 | 89 << 8 | 89, "android", false, Hdr.Curve.of(def));
        check(((lit >> 16) & 255) >= 158 && ((lit >> 16) & 255) <= 166, "100 nits on the LEDs: " + ((lit >> 16) & 255) + " (was 89)");
        eq(0x123456, Hdr.toLed(0x123456, "sdr", false, k), "read as SDR: unchanged");
        for (String key : new String[]{"hdr.read", "hdr.white", "hdr.headroom", "hdr.peak"}) check(def.get(key) != null, "setting " + key);
        eq("auto", def.get("hdr.read"), "HDR reading automatic by default");
        check(new Settings().set("hdr.read", "raw") != null, "unknown reading refused");
        Settings r = set("hdr.white", "100", "hdr.peak", "2000");
        r.reset("hdr.");
        eq(203, r.i("hdr.white"), "reset HDR set puts the white back");
    }
}

