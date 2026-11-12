package app.subsense.ambient;

/**
 * The maths of the ambient light, pure Java: where the points are, their average colours, the colour adjustments and the
 * small border image HyperHDR receives.
 *
 * Points go round the picture: top left to right, right top to bottom, bottom left to right, left top to bottom. Each
 * point averages one rectangle ("area"): the edge is cut into equal shares, one per point; the area covers `spread` % of
 * its share, starts `inset` % of the picture's height in from the edge and reaches `depth` % of the height into the
 * picture (the sides use the same number of pixels, so every area is as deep on screen). With `bars` on, black bars
 * are found first and the edges are those of the picture inside them.
 */
final class Edges {
    private Edges() {}

    static final class Params {
        int top = 8, right = 4, bottom = 8, left = 4;
        int inset = 0, depth = 10, spread = 100;
        boolean bars = true;
        int barLevel = 24;

        static Params of(Settings s) {
            Params p = new Params();
            p.top = s.i("top");
            p.right = s.i("right");
            p.bottom = s.i("bottom");
            p.left = s.i("left");
            p.inset = s.i("inset");
            p.depth = s.i("depth");
            p.spread = s.i("spread");
            p.bars = s.b("bars");
            p.barLevel = s.i("barLevel");
            return p;
        }

        int count() { return top + right + bottom + left; }
    }

    static final class Result {
        /** One 0xRRGGBB per point, in the order above. */
        int[] colours;
        /** Per point x0, y0, x1, y1 as fractions of the whole frame (for the page's preview). */
        float[] areas;
        /** The picture inside the black bars: left, top, right, bottom (inclusive pixels). */
        int[] content;
    }

    /** Samples one frame (w x h ARGB pixels). previous = the last frame's content, kept when the frame is all dark. */
    static Result sample(int[] px, int w, int h, Params p, int[] previous) {
        int[] c = p.bars ? content(px, w, h, p.barLevel, previous) : new int[]{0, 0, w - 1, h - 1};
        int l = c[0], t = c[1], r = c[2], b = c[3];
        float cw = r - l + 1, ch = b - t + 1;
        float inset = ch * p.inset / 100f, depth = Math.max(1f, ch * p.depth / 100f);
        Result res = new Result();
        res.content = c;
        res.colours = new int[p.count()];
        res.areas = new float[p.count() * 4];
        int k = 0;
        for (int i = 0; i < p.top; i++, k++) {
            float[] s = share(l, cw, i, p.top, p.spread);
            put(res, k, px, w, h, s[0], t + inset, s[1], t + inset + depth);
        }
        for (int i = 0; i < p.right; i++, k++) {
            float[] s = share(t, ch, i, p.right, p.spread);
            put(res, k, px, w, h, r + 1 - inset - depth, s[0], r + 1 - inset, s[1]);
        }
        for (int i = 0; i < p.bottom; i++, k++) {
            float[] s = share(l, cw, i, p.bottom, p.spread);
            put(res, k, px, w, h, s[0], b + 1 - inset - depth, s[1], b + 1 - inset);
        }
        for (int i = 0; i < p.left; i++, k++) {
            float[] s = share(t, ch, i, p.left, p.spread);
            put(res, k, px, w, h, l + inset, s[0], l + inset + depth, s[1]);
        }
        return res;
    }

    /** Share i of n along an edge that starts at start and is len long, narrowed to spread % around its middle. */
    private static float[] share(int start, float len, int i, int n, int spread) {
        float a = start + len * i / n, z = start + len * (i + 1) / n;
        float mid = (a + z) / 2, half = (z - a) * spread / 200f;
        return new float[]{mid - half, mid + half};
    }

    private static void put(Result res, int k, int[] px, int w, int h, float x0, float y0, float x1, float y1) {
        int ix0 = clamp((int) Math.floor(x0), 0, w - 1), iy0 = clamp((int) Math.floor(y0), 0, h - 1);
        int ix1 = clamp((int) Math.ceil(x1), ix0 + 1, w), iy1 = clamp((int) Math.ceil(y1), iy0 + 1, h);
        res.colours[k] = average(px, w, ix0, iy0, ix1, iy1);
        res.areas[4 * k] = ix0 / (float) w;
        res.areas[4 * k + 1] = iy0 / (float) h;
        res.areas[4 * k + 2] = ix1 / (float) w;
        res.areas[4 * k + 3] = iy1 / (float) h;
    }

    static int average(int[] px, int w, int x0, int y0, int x1, int y1) {
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = px[y * w + x];
                sr += (c >> 16) & 255;
                sg += (c >> 8) & 255;
                sb += c & 255;
                n++;
            }
        }
        if (n == 0) return 0;
        return (int) ((sr + n / 2) / n) << 16 | (int) ((sg + n / 2) / n) << 8 | (int) ((sb + n / 2) / n);
    }

    /**
     * The picture inside black bars: rows/columns from each edge in which no channel of any pixel reaches level, at most a
     * third of the frame from each side. A frame that is dark all over (a black scene) keeps the previous bounds.
     */
    static int[] content(int[] px, int w, int h, int level, int[] previous) {
        int t = 0, b = h - 1, l = 0, r = w - 1;
        while (t < h / 3 && rowMax(px, w, t) < level) t++;
        while (b > h - 1 - h / 3 && rowMax(px, w, b) < level) b--;
        boolean allDark = t >= h / 3 && b <= h - 1 - h / 3 && rowMax(px, w, h / 2) < level;
        if (allDark) return previous != null ? previous.clone() : new int[]{0, 0, w - 1, h - 1};
        while (l < w / 3 && colMax(px, w, l, t, b) < level) l++;
        while (r > w - 1 - w / 3 && colMax(px, w, r, t, b) < level) r--;
        return new int[]{l, t, r, b};
    }

    private static int rowMax(int[] px, int w, int y) {
        int m = 0;
        for (int x = 0; x < w; x++) m = Math.max(m, maxChannel(px[y * w + x]));
        return m;
    }

    private static int colMax(int[] px, int w, int x, int y0, int y1) {
        int m = 0;
        for (int y = y0; y <= y1; y++) m = Math.max(m, maxChannel(px[y * w + x]));
        return m;
    }

    /** The brightest channel: pure blue is not black. */
    static int maxChannel(int c) { return Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255)); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---- colour ----

    /** One set of colour adjustments (SDR or HDR) read from the settings. */
    static final class Colour {
        float bright = 1, black = 0, gamma = 1, sat = 1;
        float mr = 1, mg = 1, mb = 1;

        static Colour of(Settings s, String set) {
            Colour c = new Colour();
            c.bright = s.i(set + ".bright") / 100f;
            c.black = s.i(set + ".black") / 100f;
            c.gamma = s.i(set + ".gamma") / 100f;
            c.sat = s.i(set + ".sat") / 100f;
            float t = s.i(set + ".temp") / 100f;
            // warmer (t < 0) takes blue and some green away, cooler (t > 0) takes red and some green away
            if (t < 0) { c.mg = 1 + 0.12f * t; c.mb = 1 + 0.35f * t; }
            if (t > 0) { c.mr = 1 - 0.35f * t; c.mg = 1 - 0.12f * t; }
            return c;
        }

        boolean neutral() {
            return bright == 1 && black == 0 && gamma == 1 && sat == 1 && mr == 1 && mg == 1 && mb == 1;
        }

        int apply(int rgb) {
            if (neutral()) return rgb & 0xffffff;
            float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
            if (black > 0) {
                r = Math.max(0, (r - black) / (1 - black));
                g = Math.max(0, (g - black) / (1 - black));
                b = Math.max(0, (b - black) / (1 - black));
            }
            if (gamma != 1) {
                r = (float) Math.pow(r, gamma);
                g = (float) Math.pow(g, gamma);
                b = (float) Math.pow(b, gamma);
            }
            if (sat != 1) {
                float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = y + (r - y) * sat;
                g = y + (g - y) * sat;
                b = y + (b - y) * sat;
            }
            r *= mr * bright;
            g *= mg * bright;
            b *= mb * bright;
            return to8(r) << 16 | to8(g) << 8 | to8(b);
        }
    }

    private static int to8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    // ---- the image for HyperHDR ----

    static final class Image {
        byte[] rgb;
        int w, h;
    }

    /**
     * The border image HyperHDR's LED layout samples: its edges hold the points (each point stretched over its share), the
     * inside the mean of all points. Width = a multiple of both top and bottom counts, height = 2 + a multiple of both
     * side counts, so every point gets the same share (up to 256; beyond that the larger count decides).
     */
    static Image image(int[] colours, Params p) {
        int w = lcm(p.top, p.bottom);
        if (w > 256) w = Math.max(p.top, p.bottom);
        if (w < 2) w = 2; // one point at the top and one at the bottom: the left and right columns must still differ
        int sides = lcm(p.left, p.right);
        if (sides > 256) sides = Math.max(p.left, p.right);
        int h = sides + 2;
        int[] im = new int[w * h];
        long ar = 0, ag = 0, ab = 0;
        for (int c : colours) {
            ar += (c >> 16) & 255;
            ag += (c >> 8) & 255;
            ab += c & 255;
        }
        int n = Math.max(1, colours.length);
        java.util.Arrays.fill(im, (int) (ar / n) << 16 | (int) (ag / n) << 8 | (int) (ab / n));
        int top0 = 0, right0 = p.top, bottom0 = right0 + p.right, left0 = bottom0 + p.bottom;
        for (int x = 0; x < w; x++) {
            im[x] = colours[top0 + x * p.top / w];
            im[(h - 1) * w + x] = colours[bottom0 + x * p.bottom / w];
        }
        for (int y = 0; y < sides; y++) {
            im[(y + 1) * w] = colours[left0 + y * p.left / sides];
            im[(y + 1) * w + w - 1] = colours[right0 + y * p.right / sides];
        }
        Image out = new Image();
        out.w = w;
        out.h = h;
        out.rgb = new byte[w * h * 3];
        for (int i = 0; i < im.length; i++) {
            out.rgb[3 * i] = (byte) (im[i] >> 16);
            out.rgb[3 * i + 1] = (byte) (im[i] >> 8);
            out.rgb[3 * i + 2] = (byte) im[i];
        }
        return out;
    }

    static int lcm(int a, int b) {
        int x = a, y = b;
        while (y != 0) {
            int t = x % y;
            x = y;
            y = t;
        }
        return a / x * b;
    }
}
