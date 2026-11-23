package com.anb.gmsfix;

import android.Manifest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** Các bước fix, sinh ra theo danh sách package được chọn. */
final class Steps {

    static final String GMS = "com.google.android.gms";

    private static final Pattern FAIL = Pattern.compile(
            "(?i)(can't find service|exception|permission denial|not allowed|unknown (command|option|package|operation)"
                    + "|package .* not found|error:)");

    // Thêm package vào 1 setting dạng list, không trùng (so sánh bỏ dấu cách), in "pkg: đã có/đã thêm".
    private static final String ADD_FN =
            "add_to() { v=\"$(settings $1 get system \"$2\")\"; "
                    + "case \",$(echo \"$v\" | tr -d ' '),\" in *\",$4,\"*) echo \"$4: đã có\" ;; "
                    + "*) if [ -z \"$v\" ] || [ \"$v\" = null ]; then n=\"$4\"; else n=\"$v$3$4\"; fi; "
                    + "settings $1 put system \"$2\" \"$n\" && echo \"$4: đã thêm\" ;; esac; }\n"
                    + "rc=0\n";

    static final class Step {
        final String title;
        final String script;
        /** Bước "mềm": lỗi chỉ là cảnh báo (tính năng có thể không tồn tại trên ROM này). */
        final boolean soft;

        Step(String title, String script, boolean soft) {
            this.title = title;
            this.script = script;
            this.soft = soft;
        }

        static boolean failed(Shell.Result r) {
            return r.code != 0 || FAIL.matcher(r.out).find();
        }
    }

    private Steps() {}

    /**
     * @param alwaysOn app "Luôn chạy nền" (giữ trong No Restrictions); app còn lại trong pkgs là "Chỉ khi có tin"
     * @param noRestrictValue giá trị MILLET_NO_RESTRICT_APP mới (NoRestrictGuard.targetValue), null = không cần đổi
     */
    static List<Step> build(Collection<String> pkgs, Collection<String> alwaysOn, String noRestrictValue, String selfPkg) {
        List<Step> steps = new ArrayList<>();

        // GMS Fix cũng cần được miễn hạn chế để KeepAlive (đánh thức GMS định kỳ) chạy được khi tắt màn hình
        List<String> withSelf = new ArrayList<>(pkgs);
        if (!withSelf.contains(selfPkg)) withSelf.add(selfPkg);

        // No Restrictions: thêm app "Luôn chạy nền", gỡ app "Chỉ khi có tin" (để Greezer được đóng băng → đỡ pin)
        StringBuilder s = new StringBuilder("rc=0\n");
        if (noRestrictValue != null) {
            s.append("settings --user 0 put system MILLET_NO_RESTRICT_APP '").append(noRestrictValue.replace("'", ""))
                    .append("' || rc=1\n");
        }
        s.append("m=\",$(settings --user 0 get system MILLET_NO_RESTRICT_APP | tr -d ' '),\"\n");
        for (String p : withSelf) {
            boolean keep = alwaysOn.contains(p) || p.equals(selfPkg);
            s.append("case \"$m\" in *\",").append(p).append(",\"*) ")
                    .append(keep ? "echo '" + p + ": luôn chạy nền';; *) echo '" + p + ": THIẾU'; rc=1;; esac\n"
                            : "echo '⚠ " + p + ": cài đặt pin đang No restrictions → đổi sang Battery saver';; "
                            + "*) echo '" + p + ": chỉ khi có tin';; esac\n");
        }
        steps.add(new Step("Xiaomi No Restrictions (2 mức)", s.append("exit $rc").toString(), false));

        if (pkgs.contains(GMS)) {
            steps.add(new Step("Tắt Xiaomi GMS freezer", "dumpsys greezer IM GMS disable", false));
        }

        steps.add(new Step("Greezer / Aurogon allowlist",
                forEach(pkgs, "dumpsys greezer LM add %s"), false));

        // Miễn Doze / độ trễ thấp / bucket active: CHỈ app "Luôn chạy nền". App "Chỉ khi có tin" không cần
        // (tin FCM ưu tiên cao được Android cho thức tạm) — miễn Doze cho nhiều app thì máy không ngủ sâu được.
        List<String> always = new ArrayList<>();
        List<String> fcmOnly = new ArrayList<>();
        for (String p : pkgs) (alwaysOn.contains(p) ? always : fcmOnly).add(p);
        List<String> alwaysSelf = new ArrayList<>(always);
        if (!alwaysSelf.contains(selfPkg)) alwaysSelf.add(selfPkg);

        if (!always.isEmpty()) {
            s = new StringBuilder(ADD_FN);
            for (String p : always) s.append("add_to '' cloud_lowlatency_whitelist ',' ").append(p).append(" || rc=1\n");
            steps.add(new Step("Low-latency (luôn chạy nền)", s.append("exit $rc").toString(), false));
        }

        StringBuilder dz = new StringBuilder("rc=0\nwl=\"$(dumpsys deviceidle whitelist)\"\n");
        for (String p : alwaysSelf) dz.append("dumpsys deviceidle whitelist +").append(p).append(" >/dev/null && echo '")
                .append(p).append(": miễn Doze' || rc=1\n");
        // gỡ app "Chỉ khi có tin" khỏi danh sách người dùng (do GMS Fix/PowerKeeper thêm trước đây)
        for (String p : fcmOnly) dz.append("case \"$wl\" in *\"user,").append(p).append(",\"*) dumpsys deviceidle whitelist -")
                .append(p).append(" >/dev/null && echo '").append(p).append(": bỏ miễn Doze (chỉ khi có tin)';; esac\n");
        steps.add(new Step("Doze whitelist (luôn chạy nền)", dz.append("exit $rc").toString(), false));

        steps.add(new Step("Cho phép chạy nền",
                forEach(withSelf, "cmd appops set %1$s RUN_ANY_IN_BACKGROUND allow && "
                        + "cmd appops set %1$s RUN_IN_BACKGROUND allow && echo \"%1$s: allow\""), false));

        // Cho dùng dữ liệu nền kể cả khi bật Tiết kiệm dữ liệu (FCM + app nhận push qua 4G)
        StringBuilder nd = new StringBuilder("rc=0\n");
        for (String p : withSelf) {
            nd.append("u=$(cmd package list packages -U ").append(p).append(" | grep '^package:").append(p)
                    .append(" uid:' | sed 's/.*uid://; s/,.*//'); ")
                    .append("if [ -n \"$u\" ]; then cmd netpolicy add restrict-background-whitelist $u && echo '")
                    .append(p).append(": cho phép' || rc=1; fi\n");
        }
        steps.add(new Step("Cho dùng dữ liệu nền", nd.append("exit $rc").toString(), true));

        if (!always.isEmpty()) {
            steps.add(new Step("Standby bucket = active (luôn chạy nền)",
                    forEach(always, "am set-standby-bucket %1$s active && echo \"%1$s: active\""), true));
        }

        List<String> autostart = new ArrayList<>(pkgs);
        autostart.add(selfPkg); // để nhận BOOT_COMPLETED trên MIUI/HyperOS
        steps.add(new Step("MIUI tự khởi chạy",
                forEach(autostart, "cmd appops set %1$s 10008 allow && echo \"%1$s: allow\""), true));

        steps.add(new Step("Cấp quyền cho GMS Fix",
                "pm grant " + selfPkg + " " + Manifest.permission.WRITE_SECURE_SETTINGS + " && echo 'WRITE_SECURE_SETTINGS' && "
                        + "pm grant " + selfPkg + " " + Manifest.permission.DUMP + " && echo 'DUMP' && "
                        + "pm grant " + selfPkg + " " + Manifest.permission.PACKAGE_USAGE_STATS + " && "
                        + "cmd appops set " + selfPkg + " GET_USAGE_STATS allow && echo 'Usage access (đọc FCM không cần Shizuku)' && "
                        + "cmd appops set " + selfPkg + " SCHEDULE_EXACT_ALARM allow && echo 'Exact alarm (đánh thức GMS)'",
                false));

        // Không force-stop: mọi thiết lập ở trên có hiệu lực ngay, còn force-stop để lại cờ "stopped"
        // → GMS bỏ tin FCM ("Failed to broadcast to stopped app"). Chỉ gỡ cờ stopped cho app đang bị dừng
        // và đọc lại để xác nhận — thất bại thì báo lỗi, không che.
        StringBuilder us = new StringBuilder("rc=0\nisstop() { dumpsys package \"$1\" | grep -m1 -q 'stopped=true'; }\n");
        for (String p : pkgs) {
            us.append("if isstop ").append(p).append("; then cmd package unstop ").append(p).append(" >/dev/null 2>&1; ")
                    .append("if isstop ").append(p).append("; then echo '").append(p).append(": vẫn bị dừng — mở app 1 lần'; rc=1; ")
                    .append("else echo '").append(p).append(": đã bỏ trạng thái dừng'; fi; fi\n");
        }
        us.append("[ $rc = 0 ] && echo 'Không app nào bị dừng'\nexit $rc");
        steps.add(new Step("Bỏ trạng thái dừng (stopped)", us.toString(), false));

        steps.add(new Step("Kiểm tra lại", verifyScript(pkgs, alwaysOn), false));
        return steps;
    }

    private static String forEach(Collection<String> pkgs, String fmt) {
        StringBuilder s = new StringBuilder("rc=0\n");
        for (String p : pkgs) s.append(String.format(fmt, p)).append(" || rc=1\n");
        return s.append("exit $rc").toString();
    }

    private static String verifyScript(Collection<String> pkgs, Collection<String> alwaysOn) {
        StringBuilder s = new StringBuilder("rc=0\n"
                + "m=\",$(settings --user 0 get system MILLET_NO_RESTRICT_APP | tr -d ' '),\"\n"
                + "c=\",$(settings get system cloud_lowlatency_whitelist | tr -d ' '),\"\n"
                + "w=\"$(dumpsys deviceidle whitelist)\"\n"
                + "ok() { echo \"✓ $1\"; }\n"
                + "ko() { echo \"✗ $1\"; rc=1; }\n");
        for (String p : pkgs) {
            if (alwaysOn.contains(p)) s.append("case \"$m\" in *\",").append(p).append(",\"*) ok '").append(p).append(": No Restrictions';; *) ko '").append(p).append(": No Restrictions';; esac\n");
            if (alwaysOn.contains(p)) s.append("case \"$c\" in *\",").append(p).append(",\"*) ok '").append(p).append(": low-latency';; *) ko '").append(p).append(": low-latency';; esac\n");
            if (alwaysOn.contains(p)) s.append("case \"$w\" in *\",").append(p).append(",\"*) ok '").append(p).append(": Doze whitelist';; *) ko '").append(p).append(": Doze whitelist';; esac\n");
            s.append("cmd appops get ").append(p).append(" RUN_ANY_IN_BACKGROUND | grep -q deny && ko '").append(p)
                    .append(": chạy nền' || ok '").append(p).append(": chạy nền'\n");
        }
        return s.append("exit $rc").toString();
    }

    /** Dòng tóm tắt ngắn cho 1 bước. */
    static String summary(Shell.Result r, boolean failed) {
        String out = r.out.trim();
        if (out.isEmpty()) return failed ? "exit " + r.code : "OK";
        String[] lines = out.split("\n");

        int pass = 0, fail = 0;
        String firstFail = null;
        for (String l : lines) {
            if (l.startsWith("✓")) pass++;
            else if (l.startsWith("✗")) {
                fail++;
                if (firstFail == null) firstFail = l.substring(1).trim();
            }
        }
        if (pass + fail > 0) {
            return fail == 0 ? "Tất cả " + pass + " mục đều đạt" : fail + "/" + (pass + fail) + " mục chưa đạt • " + firstFail;
        }

        if (failed) {
            for (String l : lines) if (FAIL.matcher(l).find()) return l.trim();
            return lines[lines.length - 1].trim();
        }
        if (lines.length <= 2) return out.replace("\n", " • ");
        return lines.length + " dòng • " + lines[lines.length - 1].trim();
    }
}
