package app.subsense.ambient;

import android.content.Context;
import android.hardware.display.DeviceProductInfo;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

/**
 * What Android knows about the TV over HDMI, read from its EDID: the maker and model (DeviceProductInfo, Android 12+)
 * and the HDR static metadata block (Display.HdrCapabilities: desired max, max frame-average and min luminance, HDR
 * types). A TV that leaves the luminance out gets SurfaceFlinger's stand-in values (500 / 500 / 0): those are treated
 * as "not reported".
 */
final class TvDisplay {
    String name = "", maker = "", types = "";
    int year;
    float max, avg, min;
    boolean reported;
    long readAt;

    static TvDisplay read(Context context) {
        TvDisplay t = new TvDisplay();
        t.readAt = android.os.SystemClock.elapsedRealtime();
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return t;
            Display.HdrCapabilities c = d.getHdrCapabilities();
            if (c != null) {
                t.max = c.getDesiredMaxLuminance();
                t.avg = c.getDesiredMaxAverageLuminance();
                t.min = c.getDesiredMinLuminance();
                StringBuilder sb = new StringBuilder();
                for (int type : c.getSupportedHdrTypes()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION ? "Dolby Vision"
                            : type == Display.HdrCapabilities.HDR_TYPE_HDR10 ? "HDR10"
                            : type == Display.HdrCapabilities.HDR_TYPE_HLG ? "HLG"
                            : type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS ? "HDR10+" : "type " + type);
                }
                t.types = sb.toString();
                t.reported = t.max > 0 && !(t.max == 500f && t.avg == 500f && t.min == 0f);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                DeviceProductInfo p = d.getDeviceProductInfo();
                if (p != null) {
                    t.name = p.getName() == null ? "" : p.getName().trim();
                    t.maker = p.getManufacturerPnpId() == null ? "" : p.getManufacturerPnpId();
                    if (p.getModelYear() > 0) t.year = p.getModelYear();
                    else if (p.getManufactureYear() > 0) t.year = p.getManufactureYear();
                }
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    /** The brightest highlight to use: the TV's own peak when it reports one, else the manual value. */
    double peak(boolean fromTv, double manual) {
        return fromTv && reported ? Math.max(100, Math.min(4000, Math.round(max))) : manual;
    }

    void json(StringBuilder sb) {
        sb.append("{\"name\":");
        Json.str(sb, name);
        sb.append(",\"maker\":");
        Json.str(sb, maker);
        sb.append(",\"year\":").append(year).append(",\"types\":");
        Json.str(sb, types);
        sb.append(",\"reported\":").append(reported).append(",\"max\":").append(Math.round(max))
                .append(",\"avg\":").append(Math.round(avg)).append(",\"min\":").append(Math.round(min * 10000) / 10000.0).append('}');
    }
}
