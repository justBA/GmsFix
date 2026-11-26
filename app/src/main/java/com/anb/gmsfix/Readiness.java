package com.anb.gmsfix;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;

import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * Bảng kiểm tra "đủ điều kiện để app chạy": app cần có, Shizuku (có chạy không, chạy bằng quyền gì,
 * đã cấp cho GMS Fix chưa) và từng quyền của GMS Fix. Chạy ở luồng nền (có dumpsys).
 */
final class Readiness {

    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private Readiness() {}

    /** Một dòng checklist. state: OK / MISSING (bắt buộc) / WARN (nên có) / UNKNOWN. */
    static final class Item {
        static final int OK = 0, MISSING = 1, WARN = 2, UNKNOWN = 3;
        final int state;
        final String name, detail;

        Item(int state, String name, String detail) {
            this.state = state;
            this.name = name;
            this.detail = detail;
        }
    }

    /** entries: String (tiêu đề nhóm) hoặc Item. */
    static final class Result {
        String title;
        int missRequired, missOptional;
        final java.util.List<Object> entries = new java.util.ArrayList<>();
    }

    private static final class Out {
        final Result r = new Result();

        void section(String title) {
            r.entries.add(title);
        }

        /** required=false: nên có (thiếu thì một phần tính năng không chạy). */
        void item(Boolean ok, boolean required, String name, String detail) {
            int st = ok == null ? Item.UNKNOWN : ok ? Item.OK : required ? Item.MISSING : Item.WARN;
            if (st == Item.MISSING) r.missRequired++;
            if (st == Item.WARN) r.missOptional++;
            r.entries.add(new Item(st, name, detail));
        }
    }

    private static String version(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi.applicationInfo.enabled ? pi.versionName : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean granted(Context ctx, String perm) {
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String prop(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Thiếu điều kiện để chạy Fix (không có Shizuku lẫn root thì MainActivity tự dẫn sang Shizuku). */
    static final class Block {
        final String title, message;
        final int action; // ACT_*
        Block(String title, String message, int action) {
            this.title = title;
            this.message = message;
            this.action = action;
        }
    }

    static final int ACT_NONE = 0, ACT_DEV_SETTINGS = 1, ACT_SHIZUKU = 2, ACT_GET_SHIZUKU = 3;

    /** Trước khi bật Developer/mở Shizuku: chưa cài Shizuku (và không có root) thì hướng dẫn cài luôn. */
    static Block beforeShell(Context ctx, boolean root) {
        if (version(ctx.getPackageManager(), Steps.GMS) == null) {
            return new Block(I18n.t("Không có Google Play services"),
                    I18n.t("Máy chưa cài hoặc đang tắt Google Play services — không có FCM để giữ."), ACT_NONE);
        }
        if (!root && version(ctx.getPackageManager(), SHIZUKU_PKG) == null) {
            return new Block(I18n.t("Chưa cài Shizuku"),
                    I18n.t("Fix cần Shizuku (hoặc root) để chạy lệnh.\n\n")
                            + I18n.t("1. Tải và cài Shizuku\n2. Bật Developer options → Wireless debugging\n")
                            + I18n.t("3. Mở Shizuku → ghép nối (lần đầu) → Start\n4. Quay lại bấm Fix ngay"),
                    ACT_GET_SHIZUKU);
        }
        return null;
    }

    /** Đã có Shizuku/root: kiểm tra những thứ làm Fix lỗi giữa chừng. null = chạy được. */
    static Block beforeRun(Context ctx, Shell.Mode mode) {
        Block b = beforeShell(ctx, mode == Shell.Mode.ROOT);
        if (b != null) return b;
        if (mode != Shell.Mode.SHIZUKU) return null;
        int uid = -1;
        try {
            if (Shizuku.isPreV11()) {
                return new Block(I18n.t("Shizuku quá cũ"), I18n.t("Cập nhật Shizuku lên bản mới (v11 trở lên) rồi bấm lại Fix ngay."), ACT_GET_SHIZUKU);
            }
            uid = Shizuku.getUid();
        } catch (Throwable ignored) {
        }
        // Xiaomi: shell (adb) chỉ cấp được WRITE_SECURE_SETTINGS khi bật "USB debugging (Security settings)"
        String adbSec = prop("persist.security.adbinput");
        if (uid == 2000 && !granted(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)
                && adbSec != null && !adbSec.isEmpty() && !"1".equals(adbSec)) {
            return new Block(I18n.t("Cần bật USB debugging (Security settings)"),
                    I18n.t("HyperOS chặn Shizuku cấp quyền WRITE_SECURE_SETTINGS cho tới khi bật mục này — Fix sẽ lỗi giữa chừng.\n\n")
                            + I18n.t("Developer options → USB debugging (Security settings) → bật (Xiaomi có thể yêu cầu đăng nhập Mi account và có SIM), rồi quay lại bấm Fix ngay."),
                    ACT_DEV_SETTINGS);
        }
        return null;
    }

    static Result check(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String self = ctx.getPackageName();
        Out o = new Out();

        // ---- Ứng dụng ----
        o.section(I18n.t("Ứng dụng"));
        String gms = version(pm, Steps.GMS);
        o.item(gms != null, true, "Google Play services", gms != null ? gms : I18n.t("chưa cài hoặc bị tắt"));
        String shz = version(pm, SHIZUKU_PKG);
        o.item(shz != null, false, "Shizuku", shz != null ? shz : I18n.t("chưa cài — cần để bấm Fix (không cần nếu có root)"));
        o.item(true, true, "GMS Fix", version(pm, self));

        // ---- Shizuku ----
        o.section("Shizuku");
        boolean alive = Shell.shizukuAlive();
        Boolean root = Shell.rootKnown();
        if (!alive) {
            o.item(false, false, I18n.t("Đang chạy"), Boolean.TRUE.equals(root) ? I18n.t("không cần — máy có root")
                    : I18n.t("chưa chạy — bấm Fix ngay, app sẽ bật Developer và mở Shizuku"));
        } else {
            int uid = -1, api = -1;
            try {
                uid = Shizuku.getUid();
                api = Shizuku.getVersion();
            } catch (Throwable ignored) {
            }
            String level = uid == 0 ? I18n.t("root (quyền cao nhất)")
                    : uid == 2000 ? I18n.t("adb/shell (đủ cho mọi bước Fix)")
                    : uid < 0 ? "?" : "uid " + uid;
            o.item(true, false, I18n.t("Đang chạy"), I18n.t("quyền: ") + level + (api > 0 ? " • API " + api : ""));
            boolean g = Shell.shizukuGranted();
            o.item(g, false, I18n.t("GMS Fix được cấp quyền"), g ? null
                    : I18n.t("chưa — bấm Fix ngay rồi chọn Allow, hoặc Shizuku → Ứng dụng được ủy quyền"));
        }
        o.item(root == null ? null : root, false, "Root", root == null ? I18n.t("chưa kiểm tra (không bắt buộc)")
                : root ? I18n.t("có") : I18n.t("không (không bắt buộc)"));

        // ---- Quyền của GMS Fix ----
        o.section(I18n.t("Quyền của GMS Fix"));
        boolean wss = granted(ctx, Manifest.permission.WRITE_SECURE_SETTINGS);
        String adbSec = prop("persist.security.adbinput");
        o.item(wss, true, "WRITE_SECURE_SETTINGS", wss ? I18n.t("FCM mở băng app, doze, Ban đêm, tắt/bật Developer")
                : I18n.t("bấm Fix ngay (Shizuku) để cấp")
                + ("1".equals(adbSec) ? "" : I18n.t("; Xiaomi cần bật USB debugging (Security settings) trước")));
        boolean dump = granted(ctx, Manifest.permission.DUMP);
        o.item(dump, true, "DUMP", dump ? I18n.t("đọc trạng thái FCM, Greezer, kiểm tra cài đặt") : I18n.t("bấm Fix ngay để cấp"));
        boolean usage = false;
        try {
            AppOpsManager aom = ctx.getSystemService(AppOpsManager.class);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), self)
                    : aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), self);
            usage = mode == AppOpsManager.MODE_ALLOWED
                    || (mode == AppOpsManager.MODE_DEFAULT && granted(ctx, Manifest.permission.PACKAGE_USAGE_STATS));
        } catch (Throwable ignored) {
        }
        o.item(usage, false, "Usage access", usage ? I18n.t("đọc FCM không cần Shizuku") : I18n.t("bấm Fix ngay để cấp"));
        boolean notif = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS);
        o.item(notif, false, I18n.t("Thông báo"), notif ? I18n.t("cảnh báo mất/trễ tin") : I18n.t("chưa cho phép — không hiện được cảnh báo"));
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                exact = ctx.getSystemService(AlarmManager.class).canScheduleExactAlarms();
            } catch (Throwable ignored) {
            }
        }
        o.item(exact, false, "Exact alarm", exact ? I18n.t("đánh thức GMS đúng giờ") : I18n.t("bấm Fix ngay để cấp"));
        if (AppPicker.miuiPermExists(ctx)) {
            boolean apps = !AppPicker.needsMiuiPerm(ctx);
            o.item(apps, false, I18n.t("Đọc danh sách ứng dụng đã cài"), apps ? null
                    : I18n.t("Cài đặt → Ứng dụng → GMS Fix → Quyền khác"));
        }

        // ---- HyperOS cho chính GMS Fix ----
        o.section(I18n.t("HyperOS cho GMS Fix"));
        boolean battery = false;
        try {
            battery = ctx.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(self);
        } catch (Throwable ignored) {
        }
        String nr = NoRestrictGuard.read(ctx);
        boolean inNr = nr != null && NoRestrictGuard.parse(nr).contains(self);
        o.item(battery || inNr, false, I18n.t("Pin: No restrictions"), battery || inNr ? null
                : I18n.t("Cài đặt pin của GMS Fix → No restrictions"));
        if (dump) {
            Map<String, String> ops = HealthCheck.parseAppops(HealthCheck.shell(
                    "dumpsys appops --package " + self + " | grep -E '^    Package |MIUIOP\\(10008\\)|MIUIOP\\(10053\\)|RUN_ANY_IN_BACKGROUND'"))
                    .get(self);
            String a = ops == null ? null : ops.get("MIUIOP(10008)");
            String sw = ops == null ? null : ops.get("MIUIOP(10053)");
            Boolean auto = a == null && sw == null ? null
                    : (a == null || a.equals("allow")) && (sw == null || sw.equals("allow"));
            o.item(auto, false, I18n.t("Tự khởi chạy"), auto == null ? I18n.t("không đọc được")
                    : auto ? null : I18n.t("bật trong Cài đặt → Ứng dụng → GMS Fix → Tự khởi chạy"));
            String bg = ops == null ? null : ops.get("RUN_ANY_IN_BACKGROUND");
            boolean bgOk = bg == null || !(bg.equals("ignore") || bg.equals("deny"));
            o.item(bgOk, false, I18n.t("Chạy nền"), bgOk ? null : I18n.t("đang bị hạn chế"));
        }

        Result r = o.r;
        r.title = r.missRequired > 0 ? I18n.t("Thiếu ") + r.missRequired + I18n.t(" điều kiện bắt buộc")
                : r.missOptional > 0 ? I18n.t("Đủ để chạy • ") + r.missOptional + I18n.t(" mục nên bật thêm")
                : I18n.t("Đủ điều kiện ✓");
        return r;
    }
}
