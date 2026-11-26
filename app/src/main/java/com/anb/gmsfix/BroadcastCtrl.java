package com.anb.gmsfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tắt "kiểm soát broadcast" của Greezer qua Settings.Global "aurogon_enable" (mục "broadcastctrl:false").
 *
 * GreezeManagerService.isRestrictReceiver(): khi mBroadcastCtrlCloud = false → app đang bị đóng băng được
 * thaw ngay khi có broadcast gửi tới (reason "broadcast_cl") thay vì bị giữ lại ("need cached broadcast").
 * → tin FCM (c2dm RECEIVE) tới app bị đóng băng (kể cả sleep mode ban đêm) được giao ngay.
 * Đánh đổi: mọi broadcast tới mọi app đều đánh thức app → tốn pin hơn.
 *
 * Ghi Settings.Global bằng WRITE_SECURE_SETTINGS của app → không cần Shizuku.
 * Cloud của Xiaomi (CloudDataUpdate) có thể ghi đè "aurogon_enable".
 */
final class BroadcastCtrl {

    static final String KEY = "aurogon_enable";
    static final String ENTRY = "broadcastctrl:false";
    static final String PREF_MODE = "bcMode"; // "fcm" (mặc định) | "all"
    static final String FCM_ACTION = "com.google.android.c2dm.intent.RECEIVE";
    static final String PREF_ON = "bcCtrlOff";
    static final String PREF_AUTO = "bcAutoReapply";
    /** Thêm mọi app có FCM (không chỉ app trong danh sách) vào danh sách mở băng. Mặc định bật. */
    static final String PREF_ALL_FCM = "bcAllFcm";
    private static final String PREF_ORIGINAL = "bcOriginal";

    private BroadcastCtrl() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
    }

    static boolean wanted(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ON, false);
    }

    static boolean autoReapply(Context ctx) {
        return prefs(ctx).getBoolean(PREF_AUTO, true);
    }

    static boolean allFcm(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ALL_FCM, true);
    }

    /** Mọi app đã cài có receiver nhận tin FCM (c2dm RECEIVE), trừ hệ thống "android" và GMS. Sắp xếp ổn định. */
    static java.util.Set<String> fcmApps(Context ctx) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        try {
            for (android.content.pm.ResolveInfo ri : ctx.getPackageManager().queryBroadcastReceivers(
                    new android.content.Intent(FCM_ACTION), 0)) {
                if (ri.activityInfo == null) continue;
                String p = ri.activityInfo.packageName;
                if (!p.equals("android") && !p.equals(Steps.GMS)) out.add(p);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static boolean fcmOnly(Context ctx) {
        return !"all".equals(prefs(ctx).getString(PREF_MODE, "fcm"));
    }

    /**
     * Mục broadcastctrl mong muốn.
     *  - fcm: "broadcastctrl:true#pkg/c2dm.RECEIVE#…" → Greezer vẫn kiểm soát broadcast, nhưng
     *    isAllowBroadcast() cho qua (và thaw app) khi tin FCM gửi tới app trong danh sách.
     *  - all: "broadcastctrl:false" → mọi broadcast đều thaw app (tốn pin hơn).
     */
    static String desiredEntry(Context ctx) {
        if (!fcmOnly(ctx)) return ENTRY;
        StringBuilder sb = new StringBuilder("broadcastctrl:true");
        // app trong danh sách trước, rồi mọi app có FCM khác (đã sắp xếp → chuỗi ổn định để so sánh applied())
        java.util.Set<String> all = new java.util.LinkedHashSet<>(HealthCheck.enabledPkgs(ctx));
        if (allFcm(ctx)) {
            java.util.Set<String> off = prefs(ctx).getStringSet("pkgsOff", java.util.Collections.emptySet());
            for (String p : fcmApps(ctx)) if (!off.contains(p)) all.add(p); // app bị tắt trong danh sách thì tôn trọng
        }
        for (String p : all) {
            if (p.matches("[A-Za-z0-9_.]+") && !p.equals(Steps.GMS)) sb.append('#').append(p).append('/').append(FCM_ACTION);
        }
        return sb.toString();
    }

    static String current(Context ctx) {
        try {
            return Settings.Global.getString(ctx.getContentResolver(), KEY);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mục broadcastctrl hiện tại khớp với cấu hình mong muốn. */
    static boolean applied(Context ctx) {
        String v = current(ctx);
        if (v == null) return false;
        String want = desiredEntry(ctx);
        for (String e : v.split(";")) if (e.trim().equals(want)) return true;
        return false;
    }

    /** Ghi mục broadcastctrl:false, giữ nguyên các mục khác của cloud. null = OK, ngược lại là lỗi. */
    static String apply(Context ctx) {
        String v = current(ctx);
        SharedPreferences p = prefs(ctx);
        if (!p.contains(PREF_ORIGINAL)) p.edit().putString(PREF_ORIGINAL, v == null ? "" : v).apply();
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (String e : v.split(";")) {
                String t = e.trim();
                if (!t.isEmpty() && !t.startsWith("broadcastctrl:")) out.add(t);
            }
        }
        out.add(desiredEntry(ctx));
        return put(ctx, String.join(";", out));
    }

    /** Trả lại giá trị trước khi GMS Fix sửa. */
    static String restore(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String orig = p.getString(PREF_ORIGINAL, "");
        String err = put(ctx, orig.isEmpty() ? null : orig);
        if (err == null) p.edit().remove(PREF_ORIGINAL).apply();
        return err;
    }

    private static String put(Context ctx, String value) {
        try {
            Settings.Global.putString(ctx.getContentResolver(), KEY, value);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * Gọi định kỳ (KeepAlive, khởi động máy, mở app). Nếu đang muốn bật mà bị đổi và cho phép tự ghi lại
     * → ghi lại. Trả về true nếu vừa phải ghi lại.
     */
    static boolean ensure(Context ctx) {
        if (!wanted(ctx) || applied(ctx) || !autoReapply(ctx)) return false;
        boolean ok = apply(ctx) == null;
        if (ok) {
            SharedPreferences p = prefs(ctx);
            p.edit().putLong("bcLastReapply", System.currentTimeMillis())
                    .putInt("bcReapplyCount", p.getInt("bcReapplyCount", 0) + 1).apply();
        }
        return ok;
    }

    /** Số lần Greezer mở băng app nhờ broadcast trong lịch sử gần đây (bằng chứng đang có tác dụng). -1 = không đọc được. */
    static int recentThaws() {
        try {
            String out = Shell.exec(Shell.Mode.DIRECT, "dumpsys greezer | grep -cE 'reason : broadcast(_cl)? '").out.trim();
            return Integer.parseInt(out.split("\\s+")[0]);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int reapplyCount(Context ctx) {
        return prefs(ctx).getInt("bcReapplyCount", 0);
    }

    /** Mô tả ngắn để hiển thị. */
    static String shortValue(String v) {
        if (v == null) return I18n.t("(trống)");
        Matcher m = Pattern.compile("broadcastctrl:[^;]*").matcher(v);
        if (!m.find()) return v.length() > 40 ? v.substring(0, 40) + "…" : v;
        String e = m.group();
        if (e.startsWith("broadcastctrl:false")) return I18n.t("mọi broadcast");
        int n = e.split("#").length - 1;
        return n > 0 ? I18n.t("chỉ tin FCM, ") + n + " app" : I18n.t("kiểm soát mặc định");
    }
}
