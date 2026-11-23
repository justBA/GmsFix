package com.anb.gmsfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ban đêm (sleep mode của Greezer, ROM CN ~01:00–05:30): đóng băng cả app No Restrictions và CẮT MẠNG.
 *
 * Đo trên máy (đêm 23→24/11/2026):
 *  - SLEEP_MODE enter/exit khớp chính xác với GMS "ERR_NET_DOWN" (mất mạng 2 giờ 39 phút + 71 phút).
 *    Công tắc: Settings.System "cloud_sleepmode_networkpolicy_enabled" (on/off).
 *  - Settings.System "cloud_aurogon_alarm_allow_list" (gói, cách nhau dấu phẩy; null = mặc định ROM:
 *    WeChat, QQ, XTC Watch): app trong danh sách bị đóng băng thì alarm không bị giữ lại. Thêm GMS → lần
 *    đầu thấy "THAW reason : alarm" cho GMS. Thêm GMS Fix để "Đánh thức GMS" chạy được trong sleep mode.
 *
 * Hai khoá là Settings.System ẩn: app ĐỌC được nhưng KHÔNG ghi được → ghi bằng Shizuku/root (Fix).
 * Khoá "cloud_" có thể bị cloud Xiaomi đặt lại → KeepAlive/HealthCheck theo dõi và báo.
 */
final class SleepGuard {

    static final String NET_KEY = "cloud_sleepmode_networkpolicy_enabled";
    static final String ALARM_KEY = "cloud_aurogon_alarm_allow_list";
    static final String PREF_ON = "sleepGuard";
    static final Set<String> ROM_DEFAULT_ALARM =
            new LinkedHashSet<>(Arrays.asList("com.tencent.mm", "com.tencent.mobileqq", "com.xtc.watch"));

    static final String ISSUE_NET = "ban đêm bị cắt mạng (sleep mode)";
    static final String ISSUE_ALARM = "thiếu trong danh sách alarm ban đêm";

    private SleepGuard() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
    }

    static boolean wanted(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ON, true);
    }

    static String get(Context ctx, String key) {
        try {
            return Settings.System.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Gói cần có trong danh sách alarm. */
    static Set<String> alarmPkgs(Context ctx) {
        return new LinkedHashSet<>(Arrays.asList(Steps.GMS, ctx.getPackageName()));
    }

    static Set<String> alarmList(Context ctx) {
        String v = get(ctx, ALARM_KEY);
        if (v == null || v.trim().isEmpty() || v.equals("null")) return new LinkedHashSet<>(ROM_DEFAULT_ALARM);
        return NoRestrictGuard.parse(v);
    }

    static boolean netOk(Context ctx) {
        return "off".equals(get(ctx, NET_KEY));
    }

    static boolean alarmOk(Context ctx) {
        return alarmList(ctx).containsAll(alarmPkgs(ctx));
    }

    static boolean applied(Context ctx) {
        return netOk(ctx) && alarmOk(ctx);
    }

    private static String q(String s) {
        return "'" + s.replace("'", "") + "'";
    }

    /** Lệnh shell để bật (giữ mạng + thêm vào danh sách alarm, giữ nguyên các mục khác). */
    static String applyScript(Context ctx) {
        Set<String> list = alarmList(ctx);
        list.addAll(alarmPkgs(ctx));
        return "rc=0\n"
                + "settings put system " + NET_KEY + " off && echo 'Ban đêm: không cắt mạng' || rc=1\n"
                + "settings put system " + ALARM_KEY + " " + q(String.join(",", list))
                + " && echo 'Ban đêm: alarm của GMS + GMS Fix được đi qua' || rc=1\n"
                + "exit $rc";
    }

    /** Lệnh shell để tắt: trả công tắc về "on", bỏ GMS + GMS Fix khỏi danh sách (về mặc định ROM nếu chỉ còn vậy). */
    static String restoreScript(Context ctx) {
        Set<String> list = alarmList(ctx);
        list.removeAll(alarmPkgs(ctx));
        String alarm = list.equals(ROM_DEFAULT_ALARM) || list.isEmpty()
                ? "settings delete system " + ALARM_KEY
                : "settings put system " + ALARM_KEY + " " + q(String.join(",", list));
        return "settings put system " + NET_KEY + " on && " + alarm;
    }

    /** Áp dụng ngay nếu có Shizuku/root. null = OK; ngược lại là lý do không làm được. */
    static String applyNow(Context ctx, boolean on) {
        Shell.waitShizuku(2000);
        Shell.Mode mode = Shell.available(false);
        if (mode == null) return "cần Shizuku — bấm Fix ngay";
        try {
            Shell.Result r = Shell.exec(mode, on ? applyScript(ctx) : restoreScript(ctx));
            return r.code == 0 ? null : r.out.trim();
        } catch (Throwable t) {
            return t.getMessage();
        }
    }

    /** Mô tả ngắn giá trị thật. */
    static String status(Context ctx) {
        return (netOk(ctx) ? "Không cắt mạng" : "Cắt mạng khi ngủ")
                + " • alarm: " + (alarmOk(ctx) ? "có GMS + GMS Fix" : "chưa có GMS/GMS Fix");
    }
}
