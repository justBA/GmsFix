package com.anb.gmsfix;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm tra lại các cài đặt mà HyperOS hay tự đổi (dọn dẹp, cập nhật cloud, người dùng vô tình tắt).
 * Chạy bằng quyền của chính app (DUMP + PackageManager) — không cần Shizuku/Developer.
 */
final class HealthCheck {

    static final class Report {
        /** pkg → danh sách vấn đề (chuỗi tiếng Việt ngắn). Chỉ chứa app có vấn đề. */
        final Map<String, List<String>> issues = new LinkedHashMap<>();
        /** false nếu không đọc được dumpsys (chưa cấp DUMP). */
        boolean complete = true;

        int count() {
            return issues.size();
        }

        /** Vấn đề chỉ người dùng tự sửa được (thông báo bị tắt) — Fix ngay không tự bật lại. */
        boolean onlyUserFixable() {
            for (List<String> l : issues.values()) {
                for (String s : l) if (!s.equals(NOTIF_OFF)) return false;
            }
            return true;
        }

        String text(PackageManager pm) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : issues.entrySet()) {
                sb.append("• ").append(label(pm, e.getKey())).append(": ")
                        .append(String.join(", ", e.getValue())).append('\n');
            }
            return sb.toString().trim();
        }

        /** Chữ ký để so sánh lần trước/lần này (tránh báo lặp). */
        String signature() {
            return issues.toString();
        }
    }

    static final String STOPPED = "đang bị dừng (mất tin FCM)";
    static final String NO_AUTOSTART = "tắt Tự khởi chạy";
    static final String NO_BG = "bị hạn chế chạy nền";
    static final String NO_DOZE = "không có trong Doze whitelist";
    static final String NO_RESTRICT = "mất No Restrictions";
    /** App "Chỉ khi có tin" nhưng cài đặt pin vẫn "No restrictions" → không được đóng băng (tốn pin). */
    static final String BATTERY_NR = "cài đặt pin đang No restrictions";
    static final String NO_BG_DATA = "chưa được dùng dữ liệu nền";
    static final String NOTIF_OFF = "thông báo đang bị tắt";

    private static final Pattern NETPOLICY_UID = Pattern.compile("UID=(\\d+) policy=\\d+ \\(ALLOW_METERED_BACKGROUND\\)");
    private static final Pattern NOTIF_NONE = Pattern.compile("AppSettings: ([\\w.]+) \\(\\d+\\) importance=NONE");

    static final String CHANNEL = "health";
    static final int NOTIF_ID = 2;
    /** Kiểm tra tự động tối đa mỗi 6 giờ (dumpsys appops khá nặng). */
    static final long AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private HealthCheck() {}

    static java.util.Set<String> enabledPkgs(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
        java.util.Set<String> s = new java.util.LinkedHashSet<>(p.getStringSet("pkgs", new java.util.LinkedHashSet<>()));
        s.removeAll(p.getStringSet("pkgsOff", new java.util.LinkedHashSet<>()));
        return s;
    }

    /** Chạy nếu đã quá hạn (hoặc force), lưu kết quả, báo thông báo nếu có vấn đề MỚI. */
    static Report runAndNotify(Context ctx, boolean force) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("hcLast", 0) < AUTO_INTERVAL_MS) return null;
        Report r = run(ctx, enabledPkgs(ctx));
        String sig = r.signature();
        boolean changed = !sig.equals(p.getString("hcSig", ""));
        p.edit().putLong("hcLast", now)
                .putInt("hcCount", r.count())
                .putString("hcText", r.text(ctx.getPackageManager()))
                .putString("hcSig", sig)
                .apply();
        androidx.core.app.NotificationManagerCompat.from(ctx).cancel(NOTIF_ID); // thông báo cũ (trước khi có AlertCenter)
        if (r.complete || r.count() > 0) AlertCenter.checkHealth(ctx, r);
        return r;
    }

    private static void notify(Context ctx, Report r) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new android.app.NotificationChannel(CHANNEL, "Cảnh báo cài đặt bị đổi",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT));
        }
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 3,
                new android.content.Intent(ctx, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_HEALTH, true)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        String text = r.text(ctx.getPackageManager());
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(NOTIF_ID,
                new androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                        .setSmallIcon(R.drawable.ic_warning)
                        .setContentTitle(r.count() + " app có thể bị mất thông báo")
                        .setContentText(text.replace("\n", " "))
                        .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build());
    }

    static Report run(Context ctx, Collection<String> pkgs) {
        Report r = new Report();
        PackageManager pm = ctx.getPackageManager();
        boolean hasDump = ctx.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;

        String appops = "", doze = "", netpolicy = "", notif = "";
        if (hasDump) {
            appops = sh("dumpsys appops | grep -E '^    Package |MIUIOP\\(10008\\)|RUN_ANY_IN_BACKGROUND'");
            doze = sh("dumpsys deviceidle whitelist");
            netpolicy = sh("dumpsys netpolicy");
            notif = sh("dumpsys notification | grep 'AppSettings:'");
        }
        if (appops.isEmpty() || doze.isEmpty()) r.complete = false;

        Map<String, Map<String, String>> ops = parseAppops(appops);
        Set<String> bgDataUids = new HashSet<>();
        Matcher m = NETPOLICY_UID.matcher(netpolicy);
        while (m.find()) bgDataUids.add(m.group(1));
        Set<String> notifOff = new HashSet<>();
        m = NOTIF_NONE.matcher(notif);
        while (m.find()) notifOff.add(m.group(1));

        String noRestrict = NoRestrictGuard.read(ctx);
        if (noRestrict == null) r.complete = false; // không đọc được → báo "chưa đủ quyền", không im lặng
        String noRestrictNorm = noRestrict == null ? null : "," + noRestrict.replace(" ", "") + ",";
        Set<String> alwaysOn = NoRestrictGuard.alwaysOn(ctx);

        for (String p : pkgs) {
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfo(p, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // app đã gỡ
            }
            List<String> list = new ArrayList<>();
            if ((ai.flags & ApplicationInfo.FLAG_STOPPED) != 0) list.add(STOPPED);

            Map<String, String> o = ops.get(p);
            if (o != null) {
                String auto = o.get("MIUIOP(10008)");
                if (auto != null && !auto.equals("allow")) list.add(NO_AUTOSTART);
                String bg = o.get("RUN_ANY_IN_BACKGROUND");
                if (bg != null && (bg.equals("ignore") || bg.equals("deny"))) list.add(NO_BG);
            }
            if (!doze.isEmpty() && !doze.contains("," + p + ",")) list.add(NO_DOZE);
            if (noRestrictNorm != null && alwaysOn.contains(p) && !noRestrictNorm.contains("," + p + ",")) list.add(NO_RESTRICT);
            if (noRestrictNorm != null && !alwaysOn.contains(p) && noRestrictNorm.contains("," + p + ",")) list.add(BATTERY_NR);
            if (!netpolicy.isEmpty() && !bgDataUids.contains(String.valueOf(ai.uid))
                    && !netpolicy.contains("UID=" + ai.uid + "\n") /* default allowlist */) {
                list.add(NO_BG_DATA);
            }
            if (notifOff.contains(p)) list.add(NOTIF_OFF);

            if (!list.isEmpty()) r.issues.put(p, list);
        }
        return r;
    }

    /** "    Package pkg:" rồi các dòng "      OP (mode):" hoặc "      OP: mode=xxx". */
    private static Map<String, Map<String, String>> parseAppops(String out) {
        Map<String, Map<String, String>> res = new HashMap<>();
        Pattern pkgLine = Pattern.compile("^ {4}Package ([\\w.]+):");
        Pattern opParen = Pattern.compile("^ +(\\S+) \\((\\w+)\\)");
        Pattern opMode = Pattern.compile("^ +(\\S+): mode=(\\w+)");
        Map<String, String> cur = null;
        for (String line : out.split("\n")) {
            Matcher pm = pkgLine.matcher(line);
            if (pm.find()) {
                cur = res.computeIfAbsent(pm.group(1), k -> new HashMap<>());
                continue;
            }
            if (cur == null) continue;
            Matcher a = opParen.matcher(line);
            if (a.find()) {
                cur.put(a.group(1), a.group(2));
                continue;
            }
            Matcher b = opMode.matcher(line);
            if (b.find()) cur.put(b.group(1), b.group(2));
        }
        return res;
    }

    private static String sh(String cmd) {
        try {
            return Shell.exec(Shell.Mode.DIRECT, cmd).out;
        } catch (Exception e) {
            return "";
        }
    }

    static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
