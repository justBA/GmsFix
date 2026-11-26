package com.anb.gmsfix;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

/** Màn hình "Chỉnh bằng theme": tạo theme ẩn sửa danh sách Greezer trong ROM. */
public class ThemeActivity extends AppCompatActivity {

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView pillApplied, pillGreezer, pillThemeApp, pillRom;
    private TextView step1Desc;
    private MaterialButton step1Btn, step2Btn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme);
        prefs = getSharedPreferences("gmsfix", MODE_PRIVATE);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());

        LinearLayout rows = findViewById(R.id.statusRows);
        pillApplied = addStatusRow(rows, I18n.t("Theme đã áp dụng"), I18n.t("Giá trị mới đã có hiệu lực trên máy"));
        pillGreezer = addStatusRow(rows, I18n.t("Greezer đã nhận"), I18n.t("GMS không còn trong danh sách đen (cần khởi động lại)"));
        pillThemeApp = addStatusRow(rows, I18n.t("App Theme"), null);
        pillRom = addStatusRow(rows, I18n.t("ROM hỗ trợ"), null);

        LinearLayout steps = findViewById(R.id.steps);
        View s1 = addStep(steps, 1, I18n.t("Tạo theme từ danh sách hiện tại"), "", I18n.t("Tạo theme"));
        step1Desc = s1.findViewById(R.id.desc);
        step1Btn = s1.findViewById(R.id.action);
        step1Btn.setOnClickListener(v -> buildTheme());
        View s2 = addStep(steps, 2, I18n.t("Nhập & áp dụng trong app Theme"),
                I18n.t("Của tôi → Theme → Nhập → chọn ") + ThemeFix.FILE_NAME + I18n.t(" trong Download → Áp dụng “GMS Fix – FCM”"),
                I18n.t("Mở app Theme"));
        step2Btn = s2.findViewById(R.id.action);
        step2Btn.setOnClickListener(v -> openThemeApp());
        addStep(steps, 3, I18n.t("Khởi động lại máy"),
                I18n.t("Greezer chỉ đọc danh sách lúc khởi động. Sau khi khởi động lại, mục “Greezer đã nhận” sẽ chuyển xanh."),
                null);

        findViewById(R.id.btnPickApps).setOnClickListener(v ->
                AppPicker.show(this, getPkgs(), sel -> {
                    prefs.edit().putStringSet("pkgs", new LinkedHashSet<>(sel)).apply();
                    refreshApps();
                }));

        View adv = findViewById(R.id.adv);
        TextView advTitle = findViewById(R.id.advTitle);
        advTitle.setOnClickListener(v -> {
            boolean show = adv.getVisibility() != View.VISIBLE;
            adv.setVisibility(show ? View.VISIBLE : View.GONE);
            advTitle.setText((show ? "▾" : "▸") + I18n.t(" Nâng cao: theme bị tự trả về mặc định?"));
        });
        findViewById(R.id.btnRemove).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Xoá app Theme?"))
                .setMessage(I18n.t("Chỉ làm khi theme GMS Fix bị tự trả về mặc định.\n\n")
                        + I18n.t("App Theme (Theme Store + Theme Manager) bị gỡ cho người dùng hiện tại (pm uninstall -k --user 0). Theme đang áp dụng được ")
                        + I18n.t("giữ nguyên, nhưng không đổi được theme/hình nền cho tới khi Khôi phục."))
                .setPositiveButton(I18n.t("Xoá"), (d, w) -> runPrivileged(
                        "rc=1; for p in " + String.join(" ", ThemeFix.THEME_PKGS)
                                + "; do pm uninstall -k --user 0 $p && rc=0; done; exit $rc", I18n.t("Đã xoá app Theme")))
                .setNegativeButton(I18n.t("Hủy"), null)
                .show());
        findViewById(R.id.btnRestore).setOnClickListener(v -> runPrivileged(
                "rc=1; for p in " + String.join(" ", ThemeFix.THEME_PKGS)
                        + "; do cmd package install-existing --user 0 $p && rc=0; done; exit $rc", I18n.t("Đã khôi phục app Theme")));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---- trạng thái ----

    private void refresh() {
        refreshApps();
        boolean supported = ThemeFix.supported(this);
        setPill(pillRom, supported ? I18n.t("Có") : I18n.t("Không"), supported ? R.color.ok : R.color.err);
        step1Btn.setEnabled(supported);

        boolean installed = ThemeFix.themeAppInstalled(this);
        setPill(pillThemeApp, installed ? I18n.t("Đang cài") : I18n.t("Đã xoá"), installed ? R.color.neutral : R.color.warn);
        step2Btn.setEnabled(installed);
        step2Btn.setText(installed ? I18n.t("Mở app Theme") : I18n.t("App Theme đã xoá — Khôi phục ở Nâng cao"));

        boolean applied = supported && ThemeFix.activeInApp(this);
        setPill(pillApplied, applied ? I18n.t("Đã áp dụng ✓") : I18n.t("Chưa"), applied ? R.color.ok : R.color.neutral);

        // Greezer chỉ có ý nghĩa khi theme đã áp dụng. "systemblack" vắng mặt trong lý do của lần xét gần
        // nhất KHÔNG chứng minh danh sách đã đổi (Greezer có thể dừng sớm ở whiteapp/NO_RESTRICT_APP).
        if (!applied) {
            setPill(pillGreezer, I18n.t("Chưa (theme chưa áp dụng)"), R.color.neutral);
            return;
        }
        setPill(pillGreezer, I18n.t("Đang đọc…"), R.color.neutral);
        boolean hasDump = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
        new Thread(() -> {
            Boolean black = hasDump ? ThemeFix.gmsSystemBlack() : null;
            main.post(() -> {
                if (black == null) setPill(pillGreezer, I18n.t("Không rõ"), R.color.neutral);
                else if (black) setPill(pillGreezer, I18n.t("Cần khởi động lại"), R.color.warn);
                else setPill(pillGreezer, I18n.t("Có vẻ đã nhận (gián tiếp)"), R.color.ok);
            });
        }).start();
    }

    private void refreshApps() {
        Set<String> pkgs = getEnabledPkgs();
        PackageManager pm = getPackageManager();
        Map<AppGroups.Group, Integer> count = new EnumMap<>(AppGroups.Group.class);
        for (String p : pkgs) count.merge(AppGroups.of(pm, p), 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<AppGroups.Group, Integer> e : count.entrySet()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("• ").append(e.getKey().title).append(": ").append(e.getValue());
        }
        ((TextView) findViewById(R.id.appsTitle)).setText(I18n.t("App được miễn đóng băng (") + pkgs.size() + ")");
        ((TextView) findViewById(R.id.appsSummary)).setText(sb.length() == 0 ? I18n.t("Chưa chọn app nào") : sb);
        step1Desc.setText(I18n.t("Bỏ GMS khỏi danh sách đen của ROM, ") + pkgs.size()
                + I18n.t(" app đang bật không bị đóng băng (kể cả sleep mode). Lưu vào Download/") + ThemeFix.FILE_NAME + ".");
    }

    // ---- hành động ----

    /** Chụp màn hình hiện tại của chính GMS Fix (vẽ lại view, không cần quyền). */
    private android.graphics.Bitmap screenshot() {
        try {
            View v = getWindow().getDecorView();
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(v.getWidth(), v.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(b));
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void buildTheme() {
        Set<String> pkgs = getEnabledPkgs();
        android.graphics.Bitmap shot = screenshot();
        new Thread(() -> {
            String err = null, path = null;
            try {
                path = ThemeFix.saveToDownloads(this, ThemeFix.buildMtz(this, pkgs, shot));
            } catch (Exception e) {
                err = e.toString();
            }
            String fPath = path, fErr = err;
            main.post(() -> {
                if (fErr != null) {
                    new MaterialAlertDialogBuilder(this).setTitle(I18n.t("Không tạo được theme"))
                            .setMessage(fErr).setPositiveButton(I18n.t("Đóng"), null).show();
                    return;
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(I18n.t("Đã tạo theme"))
                        .setMessage(I18n.t("Đã lưu ") + fPath + " (" + pkgs.size() + " app).\n\n"
                                + I18n.t("Tiếp theo: mở app Theme → Của tôi → Theme → Nhập → chọn file → Áp dụng, ")
                                + I18n.t("rồi khởi động lại máy."))
                        .setPositiveButton(I18n.t("Mở app Theme"), (d, w) -> openThemeApp())
                        .setNegativeButton(I18n.t("Để sau"), null)
                        .show();
            });
        }).start();
    }

    private void openThemeApp() {
        Intent i = ThemeFix.launchIntent(this);
        if (i == null) {
            toast(I18n.t("App Theme đã xoá — mở Nâng cao → Khôi phục"));
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Lệnh cần quyền shell (root/Shizuku). */
    private void runPrivileged(String script, String okMsg) {
        if (Shell.shizukuAlive() && !Shell.shizukuGranted()) {
            try {
                Shizuku.requestPermission(2001);
            } catch (Throwable ignored) {
            }
            toast(I18n.t("Cấp quyền cho GMS Fix trong Shizuku rồi bấm lại"));
            return;
        }
        new Thread(() -> {
            Shell.Mode mode = Shell.available(true);
            if (mode == null) {
                main.post(() -> new MaterialAlertDialogBuilder(this)
                        .setTitle(I18n.t("Cần Shizuku hoặc root"))
                        .setMessage(I18n.t("Bật Shizuku (Wireless debugging) rồi thử lại."))
                        .setPositiveButton(I18n.t("Mở Shizuku"), (d, w) -> openShizuku())
                        .setNegativeButton(I18n.t("Đóng"), null)
                        .show());
                return;
            }
            boolean ok;
            String out;
            try {
                Shell.Result r = Shell.exec(mode, script);
                out = r.out.trim();
                ok = r.code == 0;
            } catch (Exception e) {
                ok = false;
                out = e.toString();
            }
            boolean fOk = ok;
            String fOut = out;
            main.post(() -> {
                toast(fOk ? okMsg : I18n.t("Lỗi: ") + fOut);
                refresh();
            });
        }).start();
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (i == null) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)));
            return;
        }
        startActivity(i);
    }

    // ---- helpers ----

    private Set<String> getPkgs() {
        Set<String> def = new LinkedHashSet<>();
        def.add(Steps.GMS);
        return new LinkedHashSet<>(prefs.getStringSet("pkgs", def));
    }

    private Set<String> getEnabledPkgs() {
        Set<String> s = getPkgs();
        s.removeAll(prefs.getStringSet("pkgsOff", new LinkedHashSet<>()));
        return s;
    }

    private TextView addStatusRow(LinearLayout parent, String label, String sub) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_status, parent, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        if (sub != null) {
            TextView s = row.findViewById(R.id.sub);
            s.setText(sub);
            s.setVisibility(View.VISIBLE);
        }
        parent.addView(row);
        return row.findViewById(R.id.pill);
    }

    private View addStep(LinearLayout parent, int n, String title, String desc, String action) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_guide_step, parent, false);
        TextView num = v.findViewById(R.id.num);
        num.setText(String.valueOf(n));
        num.setTextColor(MaterialColors.getColor(num, com.google.android.material.R.attr.colorOnPrimary));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(MaterialColors.getColor(num, com.google.android.material.R.attr.colorPrimary));
        num.setBackground(bg);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.desc)).setText(desc);
        if (action != null) {
            MaterialButton b = v.findViewById(R.id.action);
            b.setText(action);
            b.setVisibility(View.VISIBLE);
        }
        parent.addView(v);
        return v;
    }

    private void setPill(TextView pill, String text, @ColorRes int colorRes) {
        int c = ContextCompat.getColor(this, colorRes);
        float d = getResources().getDisplayMetrics().density;
        pill.setText(text);
        pill.setTextColor(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(100 * d);
        bg.setColor((c & 0x00FFFFFF) | 0x22000000);
        pill.setBackground(bg);
        pill.setPadding((int) (12 * d), (int) (4 * d), (int) (12 * d), (int) (4 * d));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppPicker.onPermissionResult(requestCode);
    }
}
