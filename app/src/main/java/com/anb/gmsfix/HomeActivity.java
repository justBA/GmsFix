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
        setContentView(R.layout.activity_home);
        prefs = getSharedPreferences("gmsfix", MODE_PRIVATE);

        View row = LayoutInflater.from(this).inflate(R.layout.item_status, findViewById(R.id.fcmRow), false);
        ((TextView) row.findViewById(R.id.label)).setText("Kết nối FCM");
        fcmPill = row.findViewById(R.id.pill);
        fcmSub = row.findViewById(R.id.sub);
        row.setOnClickListener(v -> refreshFcm());
        ((LinearLayout) findViewById(R.id.fcmRow)).addView(row);

        statusFcm = setupFeature(R.id.featFcm, R.drawable.ic_bolt, "Giữ FCM sống",
                "Không cần root • dùng hằng ngày",
                "Miễn hạn chế pin/Doze/đóng băng cho app đã chọn, tự đánh thức GMS định kỳ khi dùng pin, "
                        + "tự fix lại sau khi khởi động lại.",
                MainActivity.class);
        statusTheme = setupFeature(R.id.featTheme, R.drawable.ic_palette, "Chỉnh bằng theme",
                "Sửa gốc rễ • thử nghiệm",
                "Theme ẩn (không đổi giao diện) ghi đè danh sách cứng của ROM: bỏ GMS khỏi danh sách đen, "
                        + "app đã chọn không bị đóng băng kể cả sleep mode ban đêm.",
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
        setPill(statusFcm, apps + " app • đánh thức GMS: " + (ka ? "bật" : "tắt"), ka ? R.color.ok : R.color.neutral);

        if (!ThemeFix.supported(this)) {
            setPill(statusTheme, "ROM không hỗ trợ", R.color.err);
            return;
        }
        boolean active = ThemeFix.activeInApp(this);
        if (!active) {
            setPill(statusTheme, "Chưa áp dụng", R.color.neutral);
            return;
        }
        setPill(statusTheme, "Đang kiểm tra…", R.color.neutral);
        boolean hasDump = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
        new Thread(() -> {
            Boolean black = hasDump ? ThemeFix.gmsSystemBlack() : null;
            main.post(() -> {
                if (Boolean.FALSE.equals(black)) setPill(statusTheme, "Đã áp dụng ✓", R.color.ok);
                else if (Boolean.TRUE.equals(black)) setPill(statusTheme, "Đã áp dụng • cần khởi động lại", R.color.warn);
                else setPill(statusTheme, "Đã áp dụng", R.color.ok);
            });
        }).start();
    }

    private void refreshFcm() {
        setPill(fcmPill, "Đang đọc…", R.color.neutral);
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
}
