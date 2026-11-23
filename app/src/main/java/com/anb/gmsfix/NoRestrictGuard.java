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

    static Set<String> wantedPkgs(Context ctx) {
        Set<String> w = new LinkedHashSet<>(HealthCheck.enabledPkgs(ctx));
        w.add(ctx.getPackageName());
        return w;
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
     * Thêm lại app bị thiếu (giữ nguyên app của người dùng/ROM). Trả về số app đã thêm, 0 nếu không thiếu,
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
        if (add.isEmpty()) return 0;
        SharedPreferences p = prefs(ctx);
        Shell.waitShizuku(3000); // binder Shizuku có thể chưa tới ngay sau khi app khởi động
        Shell.Mode mode = Shell.available(false);
        if (mode == null) {
            p.edit().putString("nrWriteErr", "Cần Shizuku hoặc root để thêm lại " + add.size() + " app").apply();
            return NEED_SHELL;
        }
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
        if (!after.containsAll(add)) {
            p.edit().putString("nrWriteErr", "Ghi xong nhưng đọc lại vẫn thiếu (ROM chặn?)").apply();
            return -1;
        }
        p.edit().remove("nrWriteErr").putString("nrLastMode", mode.name())
                .putLong("nrLastFix", System.currentTimeMillis())
                .putInt("nrFixCount", p.getInt("nrFixCount", 0) + 1)
                .putInt("nrLastAdded", add.size())
                .apply();
        return add.size();
    }

    static String lastError(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String w = p.getString("nrWriteErr", null);
        return w != null ? w : p.getString("nrReadErr", null);
    }
}
