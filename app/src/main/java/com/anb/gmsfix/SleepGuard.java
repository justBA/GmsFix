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
 *  - Lúc vào sleep mode, PowerKeeper TẮT dữ liệu di động ("Set user data enabled to false…
 *    callingPackage=com.miui.powerkeeper") và TẮT Wi-Fi (CMD_WIFI_TOGGLED WorkSource powerkeeper), bật lại khi
 *    ra → GMS "ERR_NET_DOWN" 2 giờ 39 phút + 71 phút. Đó là tuỳ chọn Pin → Additional features →
 *    "Nighttime mode" = Settings.Secure "sec_pc_config_scenario_policies_open" (1 bật / 0 tắt).
 *    App ghi được bằng WRITE_SECURE_SETTINGS → không cần Shizuku.
 *  - (cloud_sleepmode_networkpolicy_enabled KHÔNG liên quan: nhánh đó còn cần key_open_earthquake_warning=1.)
 *  - Settings.System "cloud_aurogon_alarm_allow_list" (gói, cách nhau dấu phẩy; null = mặc định ROM:
 *    WeChat, QQ, XTC Watch): app trong danh sách bị đóng băng thì alarm không bị giữ lại. Thêm GMS → lần
 *    đầu thấy "THAW reason : alarm" cho GMS. Thêm GMS Fix để "Đánh thức GMS" chạy được trong sleep mode.
 *
 * Danh sách alarm là Settings.System ẩn: app đọc được nhưng không ghi được → ghi bằng Shizuku/root (Fix).
 * KeepAlive/BootReceiver tự giữ Nighttime mode = 0; HealthCheck báo nếu danh sách alarm bị đặt lại.
 */
final class SleepGuard {

    /** Pin → Additional features → Nighttime mode (Settings.Secure, "1" bật / "0" tắt). */
    static final String NIGHT_KEY = "sec_pc_config_scenario_policies_open";
    static final String ALARM_KEY = "cloud_aurogon_alarm_allow_list";
    static final String PREF_ON = "sleepGuard";
    static final Set<String> ROM_DEFAULT_ALARM =
            new LinkedHashSet<>(Arrays.asList("com.tencent.mm", "com.tencent.mobileqq", "com.xtc.watch"));

    static final String ISSUE_NET = "Nighttime mode đang bật (ngắt 4G + Wi-Fi ban đêm)";
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

    static String getSecure(Context ctx, String key) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Nighttime mode đang tắt (không đọc được thì coi như không biết → không báo). */
    static boolean netOk(Context ctx) {
        String v = getSecure(ctx, NIGHT_KEY);
        return v == null || "0".equals(v);
    }

    /** Ghi Nighttime mode bằng quyền của app. null = OK. */
    static String setNight(Context ctx, boolean enabled) {
        try {
            Settings.Secure.putString(ctx.getContentResolver(), NIGHT_KEY, enabled ? "1" : "0");
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Gọi định kỳ: đang bật tuỳ chọn mà Nighttime mode bị bật lại → tắt lại. true nếu vừa tắt lại. */
    static boolean ensure(Context ctx) {
        if (!wanted(ctx) || netOk(ctx)) return false;
        boolean ok = setNight(ctx, false) == null;
        if (ok) {
            SharedPreferences p = prefs(ctx);
            p.edit().putInt("nightReapplyCount", p.getInt("nightReapplyCount", 0) + 1)
                    .putLong("nightLastReapply", System.currentTimeMillis()).apply();
        }
        return ok;
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
                + "settings put secure " + NIGHT_KEY + " 0 && echo 'Ban đêm: tắt Nighttime mode (không ngắt 4G/Wi-Fi)' || rc=1\n"
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
        return alarm;
    }

    /** Áp dụng ngay nếu có Shizuku/root. null = OK; ngược lại là lý do không làm được. */
    static String applyNow(Context ctx, boolean on) {
        String err = setNight(ctx, !on); // Nighttime mode: không cần Shizuku
        if (err != null) return err;
        if (on ? alarmOk(ctx) : !alarmList(ctx).removeAll(alarmPkgs(ctx))) return null; // alarm đã đúng
        Shell.waitShizuku(2000);
        Shell.Mode mode = Shell.available(false);
        if (mode == null) return "Nighttime mode đã " + (on ? "tắt" : "bật") + "; danh sách alarm cần Shizuku — bấm Fix ngay";
        try {
            Shell.Result r = Shell.exec(mode, on ? applyScript(ctx) : restoreScript(ctx));
            return r.code == 0 ? null : r.out.trim();
        } catch (Throwable t) {
            return t.getMessage();
        }
    }

    /** Mô tả ngắn giá trị thật. */
    static String status(Context ctx) {
        return (netOk(ctx) ? "Nighttime mode: tắt" : "Nighttime mode: BẬT (ngắt mạng)")
                + " • alarm: " + (alarmOk(ctx) ? "có GMS + GMS Fix" : "chưa có GMS/GMS Fix");
    }
}
