package com.anb.gmsfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nới deep doze qua Settings.Global "device_idle_constants" (DeviceIdleController vẫn đọc khoá này).
 *
 * HyperOS đặt: vào deep doze ~30s sau khi tắt màn hình, cửa sổ thức (maintenance) cách 1h → 6h.
 * Khi deep doze, GMS báo "enter doze bit" → máy chủ giữ tin FCM ưu tiên thường tới cửa sổ thức kế tiếp.
 * Bộ kết hợp:
 *  - vào deep doze như Samsung/AOSP: tắt màn hình 30 phút + nằm yên 30 phút, cầm lên/di chuyển là đếm lại
 *    (motion 10 phút, sensing 4 phút, locating 30 giây);
 *  - khi đã ngủ sâu: cửa sổ thức mỗi 15 phút, tối đa 30 phút (HyperOS/AOSP: 1 giờ → 6 giờ).
 *
 * Đo trên máy (HyperOS 4): tắt màn hình là HyperOS bật Quick Doze → bỏ qua inactive_to/motion, ngủ sâu sau
 * quick_doze_delay_to (mặc định 1 phút; đặt 3 phút thì thực tế ~2 phút → có giới hạn). Phần có tác dụng
 * thật là khoảng thức idle_to/max_idle_to (đã kiểm chứng) + chờ ~2 phút trước khi ngủ sâu.
 *
 * Ghi bằng WRITE_SECURE_SETTINGS của app → không cần Shizuku. Xoá khoá = trả về mặc định của ROM.
 * (device_config device_idle bị Android 17 chặn kể cả với shell.)
 */
final class DozeTune {

    static final String KEY = "device_idle_constants";
    static final String PREF_ON = "dozeTune";
    static final String VALUE = "inactive_to=1800000,idle_after_inactive_to=1800000,motion_inactive_to=600000,"
            + "sensing_to=240000,locating_to=30000,idle_to=900000,max_idle_to=1800000,quick_doze_delay_to=120000";

    private DozeTune() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
    }

    static boolean wanted(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ON, false);
    }

    static String current(Context ctx) {
        try {
            return Settings.Global.getString(ctx.getContentResolver(), KEY);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Hệ thống đang dùng đúng bộ thông số của app. Android 17 không cho app thường đọc lại khoá này
     * (current() = null) → so trực tiếp với giá trị DeviceIdleController đang dùng (dumpsys, cần DUMP).
     */
    static boolean applied(Context ctx) {
        if (VALUE.equals(current(ctx))) return true;
        String out = dump();
        if (out == null) return false;
        for (String kv : VALUE.split(",")) {
            String[] p = kv.split("=");
            if (ms(out, p[0]) != Long.parseLong(p[1])) return false;
        }
        return true;
    }

    /** Hệ thống đang dùng thông số khác mặc định HyperOS nhưng không phải của app. */
    static boolean otherValues(Context ctx) {
        String out = dump();
        return out != null && !applied(ctx) && ms(out, "inactive_to") != 15000;
    }

    private static String dump() {
        try {
            String out = Shell.exec(Shell.Mode.DIRECT, "dumpsys deviceidle | grep -E '^    [a-z_]+_to='").out;
            return out.contains("inactive_to=") ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** null = OK, ngược lại là lỗi. */
    static String apply(Context ctx) {
        return put(ctx, VALUE);
    }

    static String restore(Context ctx) {
        return put(ctx, null);
    }

    private static String put(Context ctx, String v) {
        try {
            Settings.Global.putString(ctx.getContentResolver(), KEY, v);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Gọi định kỳ: đang bật mà bị đổi → ghi lại. true nếu vừa ghi lại. */
    static boolean ensure(Context ctx) {
        if (!wanted(ctx) || applied(ctx)) return false;
        boolean ok = apply(ctx) == null;
        if (ok) {
            SharedPreferences p = prefs(ctx);
            p.edit().putLong("dozeLastReapply", System.currentTimeMillis())
                    .putInt("dozeReapplyCount", p.getInt("dozeReapplyCount", 0) + 1).apply();
        }
        return ok;
    }

    static int reapplyCount(Context ctx) {
        return prefs(ctx).getInt("dozeReapplyCount", 0);
    }

    /**
     * Giá trị hệ thống đang dùng thật (dumpsys deviceidle, cần DUMP):
     * "ngủ sâu sau 1 giờ • thức mỗi 15 phút–30 phút". null = không đọc được.
     */
    static String effective() {
        try {
            String out = dump();
            if (out == null) return null;
            // HyperOS dùng Quick Doze khi tắt màn hình → thời gian vào ngủ sâu là quick_doze_delay_to
            long quick = ms(out, "quick_doze_delay_to");
            long idle = ms(out, "idle_to"), max = ms(out, "max_idle_to");
            if (quick < 0 || idle < 0 || max < 0) return null;
            return "ngủ sâu sau " + human(Math.min(quick, 120000)) + " • thức mỗi " + human(idle) + "–" + human(max);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Khoá, tên dễ hiểu, mặc định HyperOS (ms), có tác dụng trên HyperOS không (đo 2026-11-23). */
    static final Object[][] KEYS = {
            {"quick_doze_delay_to", "Tắt màn hình → ngủ sâu (Quick Doze)", 60000L, true},
            {"idle_to", "Khoảng thức gom tin lần đầu", 3600000L, true},
            {"max_idle_to", "Khoảng thức gom tin tối đa", 21600000L, true},
            {"inactive_to", "Chờ trước khi đếm (doze thường)", 15000L, false},
            {"idle_after_inactive_to", "Nằm yên trước khi ngủ sâu", 15000L, false},
            {"motion_inactive_to", "Sau khi bị cầm lên", 30000L, false},
            {"sensing_to", "Dò chuyển động", 15000L, false},
            {"locating_to", "Dò vị trí", 5000L, false},
    };

    /** Giá trị đang dùng thật cho từng khoá trong KEYS (ms, -1 nếu không đọc được). null = không có DUMP. */
    static long[] liveValues() {
        String out = dump();
        if (out == null) return null;
        long[] v = new long[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) v[i] = ms(out, (String) KEYS[i][0]);
        return v;
    }

    static String human(long ms) {
        if (ms < 0) return "?";
        long s = ms / 1000, h = s / 3600, mi = (s % 3600) / 60;
        if (h > 0) return mi > 0 ? h + " giờ " + mi + " phút" : h + " giờ";
        if (mi > 0) return mi + " phút";
        return s + " giây";
    }

    /** "+5m0s0ms" → 300000 ms; -1 nếu không có. */
    private static long ms(String out, String key) {
        Matcher m = Pattern.compile("(?m)^\\s+" + key + "=\\+(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matcher(out);
        if (!m.find()) return -1;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long mi = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long s = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        return ((h * 60 + mi) * 60 + s) * 1000;
    }

}
