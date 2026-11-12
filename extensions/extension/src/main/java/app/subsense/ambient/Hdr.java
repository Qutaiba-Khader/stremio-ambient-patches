package app.subsense.ambient;

/**
 * HDR light: from what PixelCopy hands the app back to the picture's real light (nits), then to LED brightness.
 *
 * <b>How Android 14 reads an HDR video back</b> (frameworks/base libs/hwui Readback.cpp + Tonemapper.cpp, frameworks/native
 * libs/shaders + libs/tonemap ToneMapper13, all android14-release). A hardware decoder's buffer carries its dataspace
 * (BT.2020 PQ, or HLG) in the buffer's metadata; Readback takes it from there and runs libtonemap for a display of
 * 1000 nits (kMaxDisplayBrightnessNits), content up to 4000 nits (kMaxLuminanceNits):
 * <ul>
 *   <li>PQ: light below 650 nits is kept; 650 to 4000 nits is pressed into 650 to 1000 (a curve on the PQ scale, control
 *   point 1438 nits → 900).</li>
 *   <li>HLG: the BT.2100 system gamma for a 1000-nit display, no compression.</li>
 *   <li>then divided by 1000 (libshaders NormalizeLuminance for an SDR output) into linear sRGB, which Skia encodes
 *   with the sRGB curve of the 8-bit bitmap (hwui sets no fakeOutputDataspace, so not libshaders' own gamma 2.2).</li>
 *   <li>The tone curve's gain comes from the colour's brightest channel in the source gamut (BT.2020) and scales the
 *   whole colour.</li>
 * </ul>
 * So the copy's value v means 1000·sRGB⁻¹(v) nits: HDR reference white (203 nits) arrives as 49 %, 100 nits as 35 %,
 * 10 nits as 10 % — the "very dim" LEDs. {@link #androidToNits} undoes it.
 *
 * A video that no MediaCodec decodes (Stremio's FFmpeg renderer) is written into the window with no dataspace: then the
 * copy holds the PQ signal itself, and {@link #pqToNits} applies.
 *
 * <b>To the LEDs</b> ({@link Curve}): the light is taken relative to a white level (nits that are "full white", 203 =
 * BT.2408's HDR reference white); with a headroom, that white lights the LEDs only to (100 − headroom) % and light from
 * white up to the brightest highlight (nits, about the TV's peak) uses the rest, easing out, so highlights still stand out
 * instead of all clipping at white. The result is gamma-2.2-encoded like an SDR picture and then goes through the HDR
 * colour set as before.
 */
final class Hdr {
    private Hdr() {}

    static final double DISPLAY_NITS = 1000, CONTENT_NITS = 4000, KNEE_NITS = 650;

    // ---- PQ (SMPTE ST 2084) ----

    static double pqEotf(double code) {
        final double m1 = 2610 / 16384.0, m2 = 2523 / 4096.0 * 128, c1 = 3424 / 4096.0, c2 = 2413 / 4096.0 * 32, c3 = 2392 / 4096.0 * 32;
        double e = Math.pow(Math.max(0, code), 1 / m2);
        return 10000 * Math.pow(Math.max(e - c1, 0) / (c2 - c3 * e), 1 / m1);
    }

    static double pqOetf(double nits) {
        final double m1 = 2610 / 16384.0, m2 = 2523 / 4096.0 * 128, c1 = 3424 / 4096.0, c2 = 2413 / 4096.0 * 32, c3 = 2392 / 4096.0 * 32;
        double t = Math.pow(Math.max(0, nits) / 10000, m1);
        return Math.pow((c1 + c2 * t) / (1 + c3 * t), m2);
    }

    // ---- Android 14's readback (ToneMapper13, PQ to SDR) ----

    private static final double X1 = KNEE_NITS, Y1 = KNEE_NITS, X3 = CONTENT_NITS, Y3 = DISPLAY_NITS;
    private static final double X2 = X1 + (X3 - X1) * 4.0 / 17.0, Y2 = DISPLAY_NITS * 0.9;
    private static final double G1 = pqOetf(X1), G2 = pqOetf(X2), G3 = pqOetf(X3);
    private static final double SLOPE2 = (Y2 - Y1) / (G2 - G1), SLOPE3 = (Y3 - Y2) / (G3 - G2);

    /** ToneMapper13's curve for PQ content on a 1000-nit display (what Android applies). */
    static double toneMap(double nits) {
        if (nits < X1) return nits;
        if (nits > X3) return Y3;
        double g = pqOetf(nits);
        return g <= G2 ? (g - G2) * SLOPE2 + Y2 : (g - G3) * SLOPE3 + Y3;
    }

    /** The inverse of {@link #toneMap}: display nits (0..1000) back to content nits (0..4000). */
    static double untoneMap(double out) {
        if (out < Y1) return out;
        if (out >= Y3) return X3;
        double g = out <= Y2 ? G2 + (out - Y2) / SLOPE2 : G3 + (out - Y3) / SLOPE3;
        return pqEotf(g);
    }

    /** sRGB transfer (IEC 61966-2-1), what the copy is encoded with. */
    static double srgbToLinear(double v) { return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }

    static double linearToSrgb(double l) { return l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055; }

    /** The model of Android 14's copy of grey PQ content of these nits (one channel, 0..1). */
    static double androidReadback(double nits) {
        return linearToSrgb(toneMap(nits) / DISPLAY_NITS);
    }

    /**
     * One colour read back by Android 14 to content nits per channel. The tone curve scales a colour by its brightest
     * channel (in BT.2020), so the inverse is taken on that channel and the others follow. hlg: no compression to undo.
     */
    static double[] androidToNits(int rgb, boolean hlg) {
        double r = DISPLAY_NITS * srgbToLinear(((rgb >> 16) & 255) / 255.0);
        double g = DISPLAY_NITS * srgbToLinear(((rgb >> 8) & 255) / 255.0);
        double b = DISPLAY_NITS * srgbToLinear((rgb & 255) / 255.0);
        // libshaders takes the gain from the brightest channel in the SOURCE gamut (ToSrcRGB: BT.2020), so measure there
        double r20 = 0.6274 * r + 0.3293 * g + 0.0433 * b, g20 = 0.0691 * r + 0.9195 * g + 0.0114 * b, b20 = 0.0164 * r + 0.0880 * g + 0.8956 * b;
        double max = Math.max(r20, Math.max(g20, b20));
        if (!hlg && max >= KNEE_NITS) {
            double k = untoneMap(max) / max;
            r *= k;
            g *= k;
            b *= k;
        }
        return new double[]{r, g, b};
    }

    /** One colour that holds the PQ signal itself (no dataspace on the window) to nits per channel. */
    static double[] pqToNits(int rgb) {
        return new double[]{pqEotf(((rgb >> 16) & 255) / 255.0), pqEotf(((rgb >> 8) & 255) / 255.0), pqEotf((rgb & 255) / 255.0)};
    }

    /** One colour that holds the HLG signal itself to display nits per channel (BT.2100, 1000-nit display, gamma 1.2). */
    static double[] hlgToNits(int rgb) {
        return new double[]{hlgNits(((rgb >> 16) & 255) / 255.0), hlgNits(((rgb >> 8) & 255) / 255.0), hlgNits((rgb & 255) / 255.0)};
    }

    private static double hlgNits(double code) {
        final double a = 0.17883277, b = 1 - 4 * a, c = 0.5 - a * Math.log(4 * a);
        double e = code <= 0.5 ? code * code / 3 : (Math.exp((code - c) / a) + b) / 12;
        return DISPLAY_NITS * Math.pow(Math.max(0, e), 1.2);
    }

    // ---- to the LEDs ----

    /** White level, highlight headroom and brightest highlight, from the HDR set. */
    static final class Curve {
        final double white, peak, headroom;

        Curve(double white, double peak, double headroom) {
            this.white = Math.max(1, white);
            this.peak = Math.max(this.white + 1, peak);
            this.headroom = Math.max(0, Math.min(0.9, headroom));
        }

        static Curve of(Settings s) {
            return new Curve(s.i("hdr.white"), s.i("hdr.peak"), s.i("hdr.headroom") / 100.0);
        }

        /** LED light (0..1, linear) for this many nits. */
        double led(double nits) {
            if (nits <= 0) return 0;
            double base = 1 - headroom;
            if (nits <= white) return base * nits / white;
            if (headroom == 0 || nits >= peak) return 1;
            double t = (nits - white) / (peak - white);
            return base + headroom * t * (2 - t); // eases out: the first highlights above white gain the most
        }

        /**
         * Nits per channel to an SDR-encoded colour (gamma 2.2), ready for the colour set. The curve is taken on the
         * brightest channel and the colour scaled with it, so a bright red stays red (per channel it would turn pink).
         */
        int encode(double[] nits) {
            double max = Math.max(nits[0], Math.max(nits[1], nits[2]));
            if (max <= 0) return 0;
            double k = led(max) / max;
            return to8(nits[0] * k) << 16 | to8(nits[1] * k) << 8 | to8(nits[2] * k);
        }

        private static int to8(double linear) {
            return (int) Math.max(0, Math.min(255, Math.round(Math.pow(Math.max(0, linear), 1 / 2.2) * 255)));
        }
    }

    /** How the copy of an HDR video is read: "android" (tone-mapped by Android), "pq" (the signal itself: PQ or HLG), "sdr" (as is). */
    static int toLed(int rgb, String how, boolean hlg, Curve curve) {
        switch (how) {
            case "android": return curve.encode(androidToNits(rgb, hlg));
            case "pq": return curve.encode(hlg ? hlgToNits(rgb) : pqToNits(rgb));
            default: return rgb & 0xffffff;
        }
    }
}
