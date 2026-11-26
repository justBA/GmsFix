package com.anb.gmsfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Giữ các app đã chọn trong Settings.System "MILLET_NO_RESTRICT_APP".
 *
 * Hai mức: "Luôn chạy nền" (giữ trong danh sách) và "Chỉ khi có tin" (không có trong danh sách → Greezer
 * được đóng băng, BroadcastCtrl mở băng khi có tin FCM).
 *
 * Nguồn gốc thật của danh sách là cài đặt pin của PowerKeeper (Battery details → "No restrictions"): mỗi lần
 * khởi động lại hoặc đổi chế độ pin của bất kỳ app nào, PowerKeeper ghi đè toàn bộ khoá này từ dữ liệu của nó
 * (đo 2026-11-23). Vì vậy app KHÔNG gỡ app khỏi khoá (sẽ bị ghi đè lại) mà hướng người dùng đổi cài đặt pin
 * (batteryPage); chỉ THÊM app "Luôn chạy nền" bị thiếu — chủ yếu Google Play services, vốn không có lựa chọn
 * pin trong giao diện nên PowerKeeper luôn xoá.
 *
 * Quan sát trên HyperOS 4: sau khi khởi động lại, PowerKeeper dựng lại danh sách này từ dữ liệu
 * "Tiết kiệm pin → Không hạn chế" của chính nó → các app thêm bằng lệnh bị mất. Ở đây app tự thêm lại
 * bằng quyền của nó (WRITE_SECURE_SETTINGS) — nếu ROM cho phép; mọi lỗi được ghi lại để hiển thị.
 */
final class NoRestrictGuard {

    static final String KEY = "MILLET_NO_RESTRICT_APP";

    private NoRestrictGuard() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
    }

    /** null = không đọc được (lỗi lưu ở "nrReadErr"). */
    static String read(Context ctx) {
        try {
            String v = Settings.System.getString(ctx.getContentResolver(), KEY);
            prefs(ctx).edit().remove("nrReadErr").apply();
            return v == null ? "" : v;
        } catch (Throwable t) {
            prefs(ctx).edit().putString("nrReadErr", t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
            return null;
        }
    }

    static Set<String> parse(String v) {
        Set<String> s = new LinkedHashSet<>();
        if (v == null) return s;
        for (String e : v.split(",")) {
            String t = e.trim();
            if (!t.isEmpty() && !t.equals("null")) s.add(t);
        }
        return s;
    }

    static final String PREF_ALWAYS_ON = "pkgsAlwaysOn";

    /**
     * App "Luôn chạy nền" (giữ trong No Restrictions → Greezer không bao giờ đóng băng).
     * App còn lại trong danh sách là "Chỉ khi có tin": được đóng băng khi rảnh, tin FCM tới thì
     * BroadcastCtrl (aurogon broadcastctrl) mở băng → tiết kiệm pin.
     * Chưa chọn lần nào → nhóm Hệ thống (GMS, MiPush…), Đồng hồ, và app mà cài đặt pin đang để "No restrictions"
     * (theo đúng lựa chọn sẵn có của người dùng — không báo ⚠ lệch cho cả danh sách khi mới cài trên máy khác).
     */
    static Set<String> alwaysOn(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.contains(PREF_ALWAYS_ON)) return new LinkedHashSet<>(p.getStringSet(PREF_ALWAYS_ON, new LinkedHashSet<>()));
        Set<String> d = new LinkedHashSet<>();
        Set<String> nr = parse(read(ctx));
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String pkg : p.getStringSet("pkgs", new LinkedHashSet<>())) {
            AppGroups.Group g = AppGroups.of(pm, pkg);
            if (g == AppGroups.Group.SYSTEM || g == AppGroups.Group.WEAR || nr.contains(pkg)) d.add(pkg);
        }
        return d;
    }

    /** App vừa thêm vào danh sách: cài đặt pin đang "No restrictions" thì cho vào "Luôn chạy nền" luôn. */
    static void adoptAdded(Context ctx, Set<String> added) {
        SharedPreferences p = prefs(ctx);
        if (added.isEmpty() || !p.contains(PREF_ALWAYS_ON)) return; // chưa chọn lần nào → alwaysOn() tự theo cài đặt pin
        Set<String> nr = parse(read(ctx));
        Set<String> a = alwaysOn(ctx);
        boolean ch = false;
        for (String pkg : added) if (nr.contains(pkg)) ch |= a.add(pkg);
        if (ch) setAlwaysOn(ctx, a);
    }

    static void setAlwaysOn(Context ctx, Set<String> s) {
        prefs(ctx).edit().putStringSet(PREF_ALWAYS_ON, new LinkedHashSet<>(s)).apply();
    }

    /** Cần có trong No Restrictions: app đang bật + "Luôn chạy nền", và chính GMS Fix. */
    static Set<String> wantedPkgs(Context ctx) {
        Set<String> w = new LinkedHashSet<>(HealthCheck.enabledPkgs(ctx));
        w.retainAll(alwaysOn(ctx));
        w.add(ctx.getPackageName());
        return w;
    }

    /** Cần gỡ khỏi No Restrictions: app đang bật ở mức "Chỉ khi có tin" (app ngoài danh sách thì không đụng). */
    static Set<String> unwantedPkgs(Context ctx) {
        Set<String> u = new LinkedHashSet<>(HealthCheck.enabledPkgs(ctx));
        u.removeAll(alwaysOn(ctx));
        u.remove(ctx.getPackageName());
        return u;
    }

    /** App "Chỉ khi có tin" mà cài đặt pin vẫn để "No restrictions" (PowerKeeper giữ trong danh sách). */
    static List<String> batteryMismatch(Context ctx) {
        String v = read(ctx);
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        Set<String> have = parse(v);
        for (String p : unwantedPkgs(ctx)) if (have.contains(p)) out.add(p);
        return out;
    }

    /** Trang "Battery details" của app (HyperOS: chọn No restrictions / Battery saver). */
    static android.content.Intent batteryPage(String pkg) {
        return new android.content.Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                android.net.Uri.parse("package:" + pkg)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** App không có lựa chọn pin trong giao diện (PowerKeeper không giữ được) → chỉ GMS Fix thêm được. */
    static boolean noBatteryUi(String pkg) {
        return Steps.GMS.equals(pkg);
    }

    /** Giá trị danh sách mới: chỉ THÊM app còn thiếu (không gỡ — PowerKeeper sẽ ghi đè). null = không cần đổi. */
    static String targetValue(Context ctx) {
        String v = read(ctx);
        if (v == null) return null;
        Set<String> have = parse(v);
        Set<String> next = new LinkedHashSet<>(have);
        next.addAll(wantedPkgs(ctx));
        if (next.equals(have)) return null;
        return String.join(", ", next).replace("'", "");
    }

    /** App đã chọn mà đang thiếu trong danh sách. null = không đọc được. */
    static List<String> missing(Context ctx) {
        String v = read(ctx);
        if (v == null) return null;
        Set<String> have = parse(v);
        List<String> miss = new ArrayList<>();
        for (String p : wantedPkgs(ctx)) if (!have.contains(p)) miss.add(p);
        return miss;
    }

    /** -2 = thiếu app nhưng không có Shizuku/root để ghi (app thường bị Android chặn ghi khoá này). */
    static final int NEED_SHELL = -2;

    /**
     * Thêm app "Luôn chạy nền" bị thiếu, gỡ app "Chỉ khi có tin" (giữ nguyên app của người dùng/ROM).
     * Trả về số app đã thêm + gỡ, 0 nếu đã đúng,
     * NEED_SHELL nếu cần Shizuku/root, -1 nếu ghi lỗi (lỗi ở "nrWriteErr"/"nrReadErr").
     *
     * Ghi bằng shell (Shizuku/root) vì Android cấm app thường ghi khoá System không công khai
     * ("You cannot keep your settings in the secure settings") kể cả có WRITE_SECURE_SETTINGS.
     */
    static int ensure(Context ctx) {
        String v = read(ctx);
        if (v == null) return -1;
        Set<String> have = parse(v);
        List<String> add = new ArrayList<>();
        for (String p : wantedPkgs(ctx)) if (!have.contains(p)) add.add(p);
        List<String> rm = new ArrayList<>(); // không gỡ nữa (xem đầu file) — giữ biến cho phần xác nhận
        SharedPreferences p = prefs(ctx);
        if (add.isEmpty()) {
            p.edit().remove("nrWriteErr").apply(); // đã đủ → xoá lỗi cũ
            return 0;
        }
        Shell.waitShizuku(3000); // binder Shizuku có thể chưa tới ngay sau khi app khởi động
        Shell.Mode mode = Shell.available(false);
        if (mode == null) {
            p.edit().putString("nrWriteErr", I18n.t("Cần Shizuku hoặc root để cập nhật ") + (add.size() + rm.size()) + " app").apply();
            return NEED_SHELL;
        }
        have.removeAll(rm);
        have.addAll(add);
        String value = String.join(", ", have).replace("'", "");
        try {
            Shell.Result r = Shell.exec(mode, "settings --user 0 put system " + KEY + " '" + value + "'");
            if (r.code != 0) throw new IllegalStateException(r.out.trim());
        } catch (Throwable t) {
            p.edit().putString("nrWriteErr", t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
            return -1;
        }
        // Xác nhận đã ghi thật
        Set<String> after = parse(read(ctx));
        boolean removed = true;
        for (String r : rm) if (after.contains(r)) removed = false;
        if (!after.containsAll(add) || !removed) {
            p.edit().putString("nrWriteErr", I18n.t("Ghi xong nhưng đọc lại vẫn thiếu (ROM chặn?)")).apply();
            return -1;
        }
        p.edit().remove("nrWriteErr").putString("nrLastMode", mode.name())
                .putLong("nrLastFix", System.currentTimeMillis())
                .putInt("nrFixCount", p.getInt("nrFixCount", 0) + 1)
                .putInt("nrLastAdded", add.size())
                .putInt("nrLastRemoved", rm.size())
                .apply();
        return add.size() + rm.size();
    }

    static String lastError(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String w = p.getString("nrWriteErr", null);
        return w != null ? w : p.getString("nrReadErr", null);
    }
}
