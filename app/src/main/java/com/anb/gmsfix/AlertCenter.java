package com.anb.gmsfix;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cảnh báo cho người dùng, mỗi loại bật/tắt riêng:
 *  - LOST: GMS bỏ tin vì app đang stopped ("Failed to broadcast to stopped app")
 *  - DELAYED: tin FCM đến trễ (GcmService "[MSG_DELAYED]")
 *  - SETTINGS: cài đặt bị HyperOS đổi (No Restrictions, Tự khởi chạy, chạy nền, Doze, dữ liệu nền, stopped)
 *  - NOTIF_OFF: app đã chọn đang bị tắt thông báo
 * Chỉ xét các app trong danh sách đang bật. Không báo lặp cùng một tin / cùng một trạng thái.
 */
final class AlertCenter {

    enum Type {
        LOST(I18n.t("Mất thông báo"), I18n.t("App bị dừng nên GMS bỏ tin"), 11),
        DELAYED(I18n.t("Thông báo bị trễ"), I18n.t("Tin ưu tiên cao trễ hơn 2 phút"), 12),
        SETTINGS(I18n.t("Cài đặt bị đổi"), I18n.t("HyperOS đổi lại quyền chạy nền"), 13),
        NOTIF_OFF(I18n.t("App tắt thông báo"), I18n.t("App trong danh sách bị tắt thông báo"), 14);

        final String title, desc;
        final int notifId;

        Type(String title, String desc, int notifId) {
            this.title = title;
            this.desc = desc;
            this.notifId = notifId;
        }

        String pref() {
            return "alert_" + name();
        }
    }

    static final String CHANNEL = "alerts";
    static final long DELAY_THRESHOLD_MS = 2 * 60 * 1000L;
    /**
     * Tin ưu tiên thường: máy chủ giữ lại khi máy ngủ sâu tới lần thức kế tiếp (Nới deep doze: tối đa 30 phút)
     * → trễ trong mức đó là đúng thiết kế, chỉ ghi khi vượt 35 phút.
     */
    static final long NORMAL_DELAY_THRESHOLD_MS = 35 * 60 * 1000L;
    private static final int HISTORY_MAX = 200;
    private static final int SEEN_MAX = 300;

    private static final Pattern LOST = Pattern.compile(
            "(\\d{2}-\\d{2} \\d{2}:\\d{2}):\\d{2}\\.\\d+ net=\\d+: Failed to broadcast to stopped app ([\\w.]+) \\(id=([^ ]+)");
    private static final Pattern DELAYED = Pattern.compile(
            "(\\d{2}-\\d{2} \\d{2}:\\d{2}):\\d{2}\\.\\d+ \\[MSG_DELAYED] id=(\\S+) pkg=([\\w.]+) pri=(\\w+)\\s+"
                    + "Total Delay: (\\d+)ms[^\\n]*\\n\\s*Root Cause: (\\S+)");

    private AlertCenter() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx, Type t) {
        return prefs(ctx).getBoolean(t.pref(), t != Type.NOTIF_OFF); // "App tắt thông báo" mặc định tắt
    }

    static void setEnabled(Context ctx, Type t, boolean on) {
        prefs(ctx).edit().putBoolean(t.pref(), on).apply();
        if (!on) NotificationManagerCompat.from(ctx).cancel(t.notifId);
    }

    // ---- tin bị mất / trễ (từ dumpsys GcmService) ----

    /** Gọi khi có output GcmService mới. Không cần quyền gì thêm ngoài DUMP. */
    static void checkFcm(Context ctx, String gcmOut) {
        if (gcmOut == null || gcmOut.isEmpty()) return;
        Set<String> wanted = new LinkedHashSet<>(HealthCheck.enabledPkgs(ctx));
        // "Mọi app có FCM" đang bật → theo dõi cả app ngoài danh sách (vd. app bị dừng nên mất tin)
        if (BroadcastCtrl.wanted(ctx) && BroadcastCtrl.allFcm(ctx)) wanted.addAll(BroadcastCtrl.fcmApps(ctx));
        PackageManager pm = ctx.getPackageManager();
        Set<String> seen = new LinkedHashSet<>(Arrays.asList(prefs(ctx).getString("alertSeen", "").split("\n")));
        boolean firstRun = !prefs(ctx).contains("alertSeen");

        Map<String, List<String>> lost = new LinkedHashMap<>();
        Matcher m = LOST.matcher(gcmOut);
        while (m.find()) {
            String pkg = m.group(2), id = "L" + m.group(3);
            if (!wanted.contains(pkg) || !seen.add(id)) continue;
            lost.computeIfAbsent(pkg, k -> new ArrayList<>()).add(m.group(1));
        }

        List<String> delayed = new ArrayList<>();      // HIGH: thường là thông báo người dùng thấy
        List<String> delayedQuiet = new ArrayList<>(); // NORMAL: thường là tin ngầm (đồng bộ, badge…)
        m = DELAYED.matcher(gcmOut);
        while (m.find()) {
            String id = "D" + m.group(2), pkg = m.group(3);
            long ms = Long.parseLong(m.group(5));
            if (ms < DELAY_THRESHOLD_MS || !wanted.contains(pkg) || !seen.add(id)) continue;
            // giờ trong phần chẩn đoán của GMS là UTC → đổi sang giờ máy
            String line = HealthCheck.label(pm, pkg) + I18n.t(": trễ ") + (ms / 60000) + I18n.t(" phút (nguyên nhân ")
                    + cause(m.group(6)) + I18n.t(", lúc ") + utcToLocal(m.group(1)) + ")";
            if ("HIGH".equals(m.group(4))) delayed.add(line);
            else if (ms > NORMAL_DELAY_THRESHOLD_MS) delayedQuiet.add(line);
        }

        List<String> s = new ArrayList<>(seen);
        if (s.size() > SEEN_MAX) s = s.subList(s.size() - SEEN_MAX, s.size());
        prefs(ctx).edit().putString("alertSeen", String.join("\n", s)).apply();
        if (firstRun) return; // lần đầu: chỉ ghi nhận các tin cũ, không báo dồn

        if (!lost.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : lost.entrySet()) {
                sb.append("• ").append(HealthCheck.label(pm, e.getKey())).append(": ")
                        .append(e.getValue().size()).append(" tin (").append(localFmt(e.getValue().get(0))).append(")\n");
            }
            raise(ctx, Type.LOST, lost.size() + I18n.t(" app bị mất thông báo"), sb.toString().trim()
                    + I18n.t("\nMở app đó lên 1 lần, hoặc Fix ngay để bỏ trạng thái dừng."));
        }
        if (!delayed.isEmpty()) {
            raise(ctx, Type.DELAYED, delayed.size() + I18n.t(" tin ưu tiên cao đến trễ"), "• " + String.join("\n• ", delayed)
                    + I18n.t("\nTin ưu tiên cao thường là thông báo hiển thị (tin nhắn, OTP…)."));
        }
        for (String q : delayedQuiet) {
            int c = q.indexOf(": ");
            addHistory(ctx, Type.DELAYED, (c > 0 ? q.substring(0, c) : "?") + I18n.t(" — tin thường, có thể là tin ngầm ⏐ ") + q);
        }
    }

    private static String cause(String c) {
        switch (c) {
            case "APP_STOPPED_OR_CRASHED": return I18n.t("app bị dừng");
            case "NONE": return I18n.t("mất kết nối/máy chủ");
            default: return c.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    // ---- cài đặt bị đổi / tắt thông báo (từ HealthCheck) ----

    static void checkHealth(Context ctx, HealthCheck.Report r) {
        PackageManager pm = ctx.getPackageManager();
        StringBuilder settings = new StringBuilder(), notif = new StringBuilder();
        for (Map.Entry<String, List<String>> e : r.issues.entrySet()) {
            List<String> other = new ArrayList<>(e.getValue());
            boolean off = other.remove(HealthCheck.NOTIF_OFF);
            other.remove(HealthCheck.BATTERY_NR); // lựa chọn của người dùng, không phải HyperOS đổi → chỉ hiện trong app
            String label = HealthCheck.label(pm, e.getKey());
            if (off) notif.append("• ").append(label).append('\n');
            if (!other.isEmpty()) settings.append("• ").append(label).append(": ").append(String.join(", ", other)).append('\n');
        }
        onState(ctx, Type.SETTINGS, settings.toString().trim(),
                I18n.t("Có app bị HyperOS đổi cài đặt"), I18n.t("\nBấm Fix ngay để áp dụng lại (cần Shizuku)."));
        onState(ctx, Type.NOTIF_OFF, notif.toString().trim(),
                I18n.t("Có app đang tắt thông báo"), I18n.t("\nTự bật trong Cài đặt → Thông báo nếu muốn nhận."));
    }

    /** Cảnh báo theo trạng thái: chỉ báo khi nội dung thay đổi và không rỗng. */
    private static void onState(Context ctx, Type t, String text, String title, String hint) {
        SharedPreferences p = prefs(ctx);
        String key = "alertState_" + t.name();
        if (text.isEmpty()) {
            p.edit().remove(key).apply();
            NotificationManagerCompat.from(ctx).cancel(t.notifId);
            return;
        }
        if (text.equals(p.getString(key, null))) return;
        p.edit().putString(key, text).apply();
        raise(ctx, t, title, text + hint);
    }

    // ---- thông báo + lịch sử ----

    private static void raise(Context ctx, Type t, String title, String text) {
        addHistory(ctx, t, appsOf(text) + " — " + text.replace("\n", " ⏐ "));
        if (!enabled(ctx, t)) return;
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, I18n.t("Cảnh báo thông báo"),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, t.notifId,
                new Intent(ctx, MainActivity.class).putExtra(MainActivity.EXTRA_HEALTH, t == Type.SETTINGS || t == Type.NOTIF_OFF)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManagerCompat.from(ctx).notify(t.notifId, new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(title)
                .setContentText(text.replace('\n', ' '))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    /** Tên app từ các dòng "• Tên: …" hoặc "• Tên" → "A, B, C". */
    private static String appsOf(String text) {
        List<String> names = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.startsWith("• ")) continue;
            String n = line.substring(2);
            int c = n.indexOf(": ");
            names.add(c > 0 ? n.substring(0, c) : n);
        }
        return names.isEmpty() ? "?" : String.join(", ", new LinkedHashSet<>(names));
    }

    private static void addHistory(Context ctx, Type t, String title) {
        SharedPreferences p = prefs(ctx);
        List<String> h = new ArrayList<>(Arrays.asList(p.getString("alertHist", "").split("\n")));
        h.removeIf(String::isEmpty);
        h.add(0, new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date())
                + " • " + t.title + ": " + title + (enabled(ctx, t) ? "" : I18n.t(" (đã tắt báo)")));
        if (h.size() > HISTORY_MAX) h = h.subList(0, HISTORY_MAX);
        p.edit().putString("alertHist", String.join("\n", h)).apply();
    }

    private static final Pattern UTC_TIME = Pattern.compile("(\\d{2}-\\d{2} \\d{2}:\\d{2}) UTC");

    /** "11-24 06:59" (giờ máy, dạng trong log sự kiện GcmService) → "06:59 24/11". */
    static String localFmt(String mmddhhmm) {
        String[] p = mmddhhmm.split("[- ]");
        return p.length == 3 ? p[2] + " " + p[1] + "/" + p[0] : mmddhhmm;
    }

    /** "11-23 13:05" (UTC, dạng trong dumpsys GcmService) → "20:05 23/11" theo giờ máy. */
    static String utcToLocal(String mmddhhmm) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Calendar now = java.util.Calendar.getInstance();
            Date d = in.parse(now.get(java.util.Calendar.YEAR) + "-" + mmddhhmm);
            if (d != null && d.getTime() > now.getTimeInMillis() + 86_400_000L) { // qua năm mới
                d = in.parse((now.get(java.util.Calendar.YEAR) - 1) + "-" + mmddhhmm);
            }
            return d == null ? mmddhhmm + " UTC" : new SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return mmddhhmm + " UTC";
        }
    }

    /** Mới nhất trước. Mỗi dòng: "dd/MM HH:mm • <loại>: <nội dung>". Mục cũ ghi giờ UTC được đổi sang giờ máy. */
    static List<String> historyList(Context ctx) {
        List<String> h = new ArrayList<>(Arrays.asList(prefs(ctx).getString("alertHist", "").split("\n")));
        h.removeIf(String::isEmpty);
        for (int i = 0; i < h.size(); i++) {
            Matcher m = UTC_TIME.matcher(h.get(i));
            if (!m.find()) continue;
            StringBuffer sb = new StringBuffer();
            do m.appendReplacement(sb, Matcher.quoteReplacement(utcToLocal(m.group(1)))); while (m.find());
            m.appendTail(sb);
            h.set(i, sb.toString());
        }
        return h;
    }

    static void clearHistory(Context ctx) {
        prefs(ctx).edit().remove("alertHist").apply();
    }
}
