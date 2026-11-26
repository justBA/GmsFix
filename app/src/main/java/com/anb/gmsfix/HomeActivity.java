package com.anb.gmsfix;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

/** Trang chủ: chọn 1 trong 2 cách fix, mỗi cách mở màn hình riêng. */
public class HomeActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView fcmPill, fcmSub, statusFcm, statusTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        I18n.sync(this);
        setContentView(R.layout.activity_home);
        findViewById(R.id.btnLang).setOnClickListener(v -> chooseLanguage());
        prefs = getSharedPreferences("gmsfix", MODE_PRIVATE);

        View row = LayoutInflater.from(this).inflate(R.layout.item_status, findViewById(R.id.fcmRow), false);
        ((TextView) row.findViewById(R.id.label)).setText(I18n.t("Kết nối FCM"));
        fcmPill = row.findViewById(R.id.pill);
        fcmSub = row.findViewById(R.id.sub);
        row.setOnClickListener(v -> refreshFcm());
        ((LinearLayout) findViewById(R.id.fcmRow)).addView(row);

        statusFcm = setupFeature(R.id.featFcm, R.drawable.ic_bolt, I18n.t("Giữ FCM sống"),
                I18n.t("Không cần root • dùng hằng ngày"),
                I18n.t("Miễn hạn chế pin/Doze/đóng băng cho app đã chọn, tự đánh thức GMS định kỳ khi dùng pin, ")
                        + I18n.t("tự fix lại sau khi khởi động lại."),
                MainActivity.class);
        statusTheme = setupFeature(R.id.featTheme, R.drawable.ic_palette, I18n.t("Chỉnh bằng theme"),
                I18n.t("Chưa hoạt động • thử nghiệm"),
                I18n.t("Theme ẩn (không đổi giao diện) ghi đè danh sách cứng của ROM: bỏ GMS khỏi danh sách đen, ")
                        + I18n.t("app đã chọn không bị đóng băng kể cả sleep mode ban đêm.") + "\n" + I18n.t("Hiện chưa hoạt động: app Theme chặn theme không chính chủ (DRM)."),
                ThemeActivity.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFcm();
        refreshFeatures();
    }

    private TextView setupFeature(int id, @DrawableRes int icon, String title, String tag, String desc, Class<?> screen) {
        View card = findViewById(id);
        ImageView iv = card.findViewById(R.id.icon);
        iv.setImageResource(icon);
        int primary = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary);
        int container = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimaryContainer);
        iv.setImageTintList(ColorStateList.valueOf(primary));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(container);
        card.findViewById(R.id.iconBg).setBackground(bg);
        ((TextView) card.findViewById(R.id.title)).setText(title);
        ((TextView) card.findViewById(R.id.tag)).setText(tag);
        ((TextView) card.findViewById(R.id.desc)).setText(desc);
        card.setOnClickListener(v -> startActivity(new Intent(this, screen)));
        return card.findViewById(R.id.status);
    }

    private void refreshFeatures() {
        int apps = prefs.getStringSet("pkgs", java.util.Collections.emptySet()).size()
                - prefs.getStringSet("pkgsOff", java.util.Collections.emptySet()).size();
        boolean ka = KeepAlive.enabled(this);
        setPill(statusFcm, apps + I18n.t(" app • đánh thức GMS: ") + (ka ? I18n.t("bật") : I18n.t("tắt")), ka ? R.color.ok : R.color.neutral);

        if (!ThemeFix.supported(this)) {
            setPill(statusTheme, I18n.t("ROM không hỗ trợ"), R.color.err);
            return;
        }
        boolean active = ThemeFix.activeInApp(this);
        if (!active) {
            setPill(statusTheme, I18n.t("Chưa áp dụng"), R.color.neutral);
            return;
        }
        setPill(statusTheme, I18n.t("Đang kiểm tra…"), R.color.neutral);
        boolean hasDump = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
        new Thread(() -> {
            Boolean black = hasDump ? ThemeFix.gmsSystemBlack() : null;
            main.post(() -> {
                if (Boolean.FALSE.equals(black)) setPill(statusTheme, I18n.t("Đã áp dụng ✓"), R.color.ok);
                else if (Boolean.TRUE.equals(black)) setPill(statusTheme, I18n.t("Đã áp dụng • cần khởi động lại"), R.color.warn);
                else setPill(statusTheme, I18n.t("Đã áp dụng"), R.color.ok);
            });
        }).start();
    }

    private void refreshFcm() {
        setPill(fcmPill, I18n.t("Đang đọc…"), R.color.neutral);
        boolean hasDump = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
        new Thread(() -> {
            FcmStatus s = FcmStatus.query(Shell.available(false), hasDump);
            main.post(() -> {
                setPill(fcmPill, s.label(), s.colorRes());
                fcmSub.setText(s.summary);
                fcmSub.setVisibility(s.summary.isEmpty() ? View.GONE : View.VISIBLE);
                FcmWidget.render(this, s);
            });
        }).start();
    }

    private void setPill(TextView pill, String text, @ColorRes int colorRes) {
        int c = ContextCompat.getColor(this, colorRes);
        pill.setText(text);
        pill.setTextColor(c);
        GradientDrawable bg = new GradientDrawable();
        float d = getResources().getDisplayMetrics().density;
        bg.setCornerRadius(100 * d);
        pill.setPadding((int) (12 * d), (int) (4 * d), (int) (12 * d), (int) (4 * d));
        bg.setColor((c & 0x00FFFFFF) | 0x22000000);
        pill.setBackground(bg);
    }

    private void chooseLanguage() {
        String[] codes = {"", "vi", "en"};
        String[] names = {I18n.isEn() ? "System default" : "Theo hệ thống", "Tiếng Việt", "English"};
        String cur = I18n.chosen(this);
        int sel = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(cur)) sel = i;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(I18n.isEn() ? "Language" : "Ngôn ngữ")
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    d.dismiss();
                    if (!codes[w].equals(cur)) I18n.set(this, codes[w]);
                })
                .show();
    }
}
