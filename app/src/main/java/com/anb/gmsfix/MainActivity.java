package com.anb.gmsfix;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    static final String EXTRA_FIX = "fix";
    static final String EXTRA_HEALTH = "health";

    private static final int SHIZUKU_REQ = 1001;
    private static final int NOTIF_REQ = 1002;
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases";
    private static final Pattern PKG_NAME = Pattern.compile("[A-Za-z0-9_.]+");
    /** Chờ người dùng bấm Start trong Shizuku tối đa bao lâu thì bỏ luồng tự động. */
    private static final long AWAIT_SHIZUKU_MS = 10 * 60 * 1000;

    // Thứ tự quan trọng: tắt adb cuối cùng vì nó sẽ ngắt Shizuku
    private static final String[][] DEV_OFF = {
            {Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0"},
            {"adb_wifi_enabled", "0"},
            {Settings.Global.ADB_ENABLED, "0"},
    };
    private static final String[][] DEV_ON = {
            {Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1"},
            {Settings.Global.ADB_ENABLED, "1"},
            {"adb_wifi_enabled", "1"},
    };

    private enum Tone { OK, ERR, WARN, NEUTRAL }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder fullLog = new StringBuilder();
    private SharedPreferences prefs;

    private TextView pillShizuku, pillRoot, pillDev, pillFcm, subFcm, runHint;
    private String fcmDetail = "";
    private Set<String> fcmStopped = new LinkedHashSet<>();
    private TextView kaStatus;
    private TextView pillHealth, subHealth, pillFcm24, subFcm24;
    private MaterialButton btnKaInterval;
    private MaterialSwitch swBcCtrl, swBcAuto;
    private TextView bcStatus, dozeStatus, dozeValues;
    private MaterialSwitch swDoze;
    private LinearLayout alertRecent;
    private HealthCheck.Report health;
    private boolean showHealthWhenReady;
    private LinearLayout appsList;
    private TextView appsEmpty, appsTitle;
    private MaterialButton btnRun, btnDevOn, btnDevOff, btnPickApps;
    private View cardResults;
    private LinearLayout resultRows;
    private TextView resultSummary, resultMode;
    private boolean busy;
    private boolean resumed;
    private boolean pendingRun;

    private final Shizuku.OnRequestPermissionResultListener permListener = (code, result) -> {
        if (code != SHIZUKU_REQ) return;
        boolean run = pendingRun;
        pendingRun = false;
        if (result == PackageManager.PERMISSION_GRANTED && run) {
            startRun(Shell.Mode.SHIZUKU);
        } else {
            toast(I18n.t("Shizuku: quyền bị từ chối"));
            setBusy(false);
        }
        refreshStatus();
    };
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> main.post(() -> {
        refreshStatus();
        maybeContinueAutoFix();
    });
    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(this::refreshStatus);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        I18n.sync(this);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("gmsfix", MODE_PRIVATE);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        LinearLayout statusRows = findViewById(R.id.statusRows);
        View fcmRow = addStatusRow(statusRows, I18n.t("Kết nối FCM"), v -> showFcmDetail());
        pillFcm = fcmRow.findViewById(R.id.pill);
        subFcm = fcmRow.findViewById(R.id.sub);
        View fcm24Row = addStatusRow(statusRows, I18n.t("FCM 24 giờ qua"), null);
        pillFcm24 = fcm24Row.findViewById(R.id.pill);
        subFcm24 = fcm24Row.findViewById(R.id.sub);
        View healthRow = addStatusRow(statusRows, I18n.t("Kiểm tra cài đặt"), v -> showHealth());
        com.google.android.material.divider.MaterialDivider div = new com.google.android.material.divider.MaterialDivider(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(4), 0, dp(4));
        statusRows.addView(div, dlp);
        pillShizuku = addStatusRow(statusRows, "Shizuku", null).findViewById(R.id.pill);
        pillRoot = addStatusRow(statusRows, "Root", null).findViewById(R.id.pill);
        pillDev = addStatusRow(statusRows, "Developer options", null).findViewById(R.id.pill);
        pillHealth = healthRow.findViewById(R.id.pill);
        subHealth = healthRow.findViewById(R.id.sub);

        appsList = findViewById(R.id.appsList);
        appsEmpty = findViewById(R.id.appsEmpty);
        appsTitle = findViewById(R.id.appsTitle);
        runHint = findViewById(R.id.runHint);
        btnRun = findViewById(R.id.btnRun);
        btnDevOn = findViewById(R.id.btnDevOn);
        btnDevOff = findViewById(R.id.btnDevOff);
        btnPickApps = findViewById(R.id.btnPickApps);
        cardResults = findViewById(R.id.cardResults);
        resultRows = findViewById(R.id.resultRows);
        resultSummary = findViewById(R.id.resultSummary);
        resultMode = findViewById(R.id.resultMode);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            refreshStatus();
            refreshFcm();
        });
        findViewById(R.id.btnFullLog).setOnClickListener(v -> showText(I18n.t("Log đầy đủ"), fullLog.toString()));
        btnPickApps.setOnClickListener(v ->
                AppPicker.show(this, getPkgs(), sel -> {
                    setPkgs(sel);
                    renderApps();
                }));
        btnRun.setOnClickListener(v -> onFixClicked());
        btnDevOn.setOnClickListener(v -> onDevOnClicked());
        btnDevOff.setOnClickListener(v -> onDevOffClicked());

        MaterialSwitch swAutoOff = findViewById(R.id.swAutoOff);
        swAutoOff.setChecked(prefs.getBoolean("autoOff", true));
        swAutoOff.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("autoOff", on).apply();
            refreshStatus();
        });
        MaterialSwitch swRemind = findViewById(R.id.swRemind);
        swRemind.setChecked(prefs.getBoolean("remind", true));
        swRemind.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("remind", on).apply();
            if (on) ensureNotificationPermission();
        });
        if (swRemind.isChecked()) ensureNotificationPermission();

        kaStatus = findViewById(R.id.kaStatus);
        btnKaInterval = findViewById(R.id.btnKaInterval);
        btnKaInterval.setOnClickListener(v -> chooseKaInterval());

        for (int[] rs : new int[][]{{R.id.row_swAutoOff, R.id.swAutoOff}, {R.id.row_swRemind, R.id.swRemind},
                {R.id.row_swKeepAlive, R.id.swKeepAlive}, {R.id.row_swBcCtrl, R.id.swBcCtrl}, {R.id.row_swBcAuto, R.id.swBcAuto}, {R.id.row_swBcAll, R.id.swBcAll}, {R.id.row_swDoze, R.id.swDoze}, {R.id.row_swNight, R.id.swNight}}) {
            MaterialSwitch target = findViewById(rs[1]);
            findViewById(rs[0]).setOnClickListener(v -> { if (target.isEnabled()) target.toggle(); });
        }
        setupAlerts();
        swBcCtrl = findViewById(R.id.swBcCtrl);
        swBcAuto = findViewById(R.id.swBcAuto);
        bcStatus = findViewById(R.id.bcStatus);
        swBcCtrl.setChecked(BroadcastCtrl.wanted(this));
        swBcAuto.setChecked(BroadcastCtrl.autoReapply(this));
        swBcCtrl.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(BroadcastCtrl.PREF_ON, on).apply();
            String err = on ? BroadcastCtrl.apply(this) : BroadcastCtrl.restore(this);
            if (err != null) toast(I18n.t("Không ghi được: ") + err);
            refreshBroadcastCtrl();
        refreshAlerts();
        });
        bcStatus.setOnClickListener(v -> chooseBcMode());
        MaterialSwitch swBcAll = findViewById(R.id.swBcAll);
        swBcAll.setChecked(BroadcastCtrl.allFcm(this));
        swBcAll.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(BroadcastCtrl.PREF_ALL_FCM, on).apply();
            if (BroadcastCtrl.wanted(this)) {
                String err = BroadcastCtrl.apply(this);
                if (err != null) toast(I18n.t("Không ghi được: ") + err);
            }
            refreshBroadcastCtrl();
        });
        swDoze = findViewById(R.id.swDoze);
        dozeStatus = findViewById(R.id.dozeStatus);
        dozeValues = findViewById(R.id.dozeValues);
        findViewById(R.id.btnDozeInfo).setOnClickListener(v -> showDozeInfo());
        MaterialSwitch swNight = findViewById(R.id.swNight);
        swNight.setChecked(SleepGuard.wanted(this));
        swNight.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(SleepGuard.PREF_ON, on).apply();
            ((TextView) findViewById(R.id.nightStatus)).setText(I18n.t("Đang áp dụng…"));
            new Thread(() -> {
                String err = SleepGuard.applyNow(this, on);
                main.post(() -> {
                    if (err != null) toast(I18n.t("Ban đêm: ") + err);
                    refreshNight();
                });
            }).start();
        });
        findViewById(R.id.btnNightInfo).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Ban đêm (sleep mode)"))
                .setMessage(I18n.t("Khi phát hiện bạn ngủ (máy để yên ban đêm), PowerKeeper bật sleep mode: TẮT 4G + Wi-Fi ")
                        + I18n.t("(tuỳ chọn Pin → Additional features → Nighttime mode) và đóng băng cả app No Restrictions ")
                        + I18n.t("→ FCM mất kết nối, tin tới dồn lúc sáng.\n\n")
                        + I18n.t("Bật:\n• tắt Nighttime mode (") + SleepGuard.NIGHT_KEY + I18n.t(" = 0) — app tự giữ, không cần Shizuku\n")
                        + I18n.t("• thêm Google Play services + GMS Fix vào ") + SleepGuard.ALARM_KEY + " (alarm heartbeat/"
                        + I18n.t("đánh thức được đi qua khi đang bị đóng băng) — cần Shizuku (Fix ngay)\n\n")
                        + I18n.t("Tốn pin ban đêm hơn vì máy giữ mạng để nhận tin.\n\nHiện tại: ") + SleepGuard.status(this))
                .setPositiveButton("OK", null)
                .show());
        swDoze.setChecked(DozeTune.wanted(this));
        swDoze.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(DozeTune.PREF_ON, on).remove("dozeReapplyCount").apply();
            String err = on ? DozeTune.apply(this) : DozeTune.restore(this);
            if (err != null) toast(I18n.t("Không ghi được: ") + err);
            refreshDoze();
        });
        swBcAuto.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(BroadcastCtrl.PREF_AUTO, on).apply();
            refreshBroadcastCtrl();
        });
        MaterialSwitch swKeepAlive = findViewById(R.id.swKeepAlive);
        swKeepAlive.setChecked(KeepAlive.enabled(this));
        swKeepAlive.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(KeepAlive.PREF, on).apply();
            KeepAlive.apply(this);
            refreshKeepAlive();
        });
        KeepAlive.apply(this);

        ((TextView) findViewById(R.id.topTitle)).setText(I18n.t("Giữ FCM sống"));
        ((TextView) findViewById(R.id.topSub)).setText(I18n.t("Miễn hạn chế + đánh thức GMS định kỳ"));

        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        renderApps();

        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_HEALTH, false)) {
            intent.removeExtra(EXTRA_HEALTH);
            NotificationManagerCompat.from(this).cancel(HealthCheck.NOTIF_ID);
            showHealthWhenReady = true;
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_FIX, false)) {
            intent.removeExtra(EXTRA_FIX);
            NotificationManagerCompat.from(this).cancel(BootReceiver.NOTIF_ID);

            main.post(this::onFixClicked);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshStatus();
        refreshFcm();
        refreshKeepAlive();
        refreshHealth();
        refreshBroadcastCtrl();
        refreshDoze();
        refreshNight();
        maybeContinueAutoFix();
    }

    private void chooseKaInterval() {
        int[] c = KeepAlive.INTERVAL_CHOICES_MIN;
        String[] labels = new String[c.length];
        int cur = KeepAlive.intervalMin(this), sel = 1;
        for (int i = 0; i < c.length; i++) {
            labels[i] = c[i] + I18n.t(" phút") + (c[i] == 5 ? I18n.t(" (nhanh nhất, tốn pin hơn)") : c[i] == 10 ? I18n.t(" (khuyên dùng)") : "");
            if (c[i] == cur) sel = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Chu kỳ đánh thức GMS"))
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    prefs.edit().putInt(KeepAlive.PREF_INTERVAL, c[w]).apply();
                    KeepAlive.apply(this);
                    refreshKeepAlive();
                    d.dismiss();
                })
                .setNegativeButton(I18n.t("Hủy"), null)
                .show();
    }

    private void refreshKeepAlive() {
        btnKaInterval.setText(I18n.t("Mỗi ") + KeepAlive.intervalMin(this) + I18n.t(" phút ▾"));
        btnKaInterval.setEnabled(KeepAlive.enabled(this));
        kaStatus.setText(KeepAlive.enabled(this) ? KeepAlive.lastRun(this) : I18n.t("Đang tắt"));
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) + " (" + pkg + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Chỉ giữ các app hiện vẫn đang stopped (log GcmService có thể đã cũ). */
    private Set<String> stillStopped(Set<String> pkgs) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : pkgs) {
            try {
                if ((getPackageManager().getApplicationInfo(p, 0).flags
                        & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0) out.add(p);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return out;
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        super.onDestroy();
    }

    // ---- status ----

    private View addStatusRow(LinearLayout parent, String label, View.OnClickListener click) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_status, parent, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        if (click != null) row.setOnClickListener(click);
        parent.addView(row);
        return row;
    }

    private void refreshStatus() {
        boolean alive = Shell.shizukuAlive(), granted = alive && Shell.shizukuGranted();
        if (!alive) setPill(pillShizuku, I18n.t("Chưa chạy"), Tone.NEUTRAL);
        else if (!granted) setPill(pillShizuku, I18n.t("Chưa cấp quyền"), Tone.WARN);
        else setPill(pillShizuku, I18n.t("Sẵn sàng"), Tone.OK);

        Boolean root = Shell.rootKnown();
        if (root == null) setPill(pillRoot, I18n.t("Chưa kiểm tra"), Tone.NEUTRAL);
        else setPill(pillRoot, root ? I18n.t("Có") : I18n.t("Không"), root ? Tone.OK : Tone.NEUTRAL);

        boolean dev = devEnabled();
        setPill(pillDev, dev ? I18n.t("Đang bật") : I18n.t("Đang tắt"), dev ? Tone.WARN : Tone.OK);

        boolean autoOff = prefs.getBoolean("autoOff", true);
        String hint;
        if (Boolean.TRUE.equals(root) || granted) {
            hint = I18n.t("Có quyền — bấm để fix ngay") + (autoOff ? I18n.t(", xong sẽ tự tắt Developer") : "");
        } else if (canWriteSecure()) {
            hint = I18n.t("Sẽ bật Developer và mở Shizuku → bấm Start rồi quay lại, app tự chạy tiếp");
        } else {
            hint = I18n.t("Lần đầu: cần bật Shizuku thủ công (Wireless debugging)");
        }
        runHint.setText(hint);
    }

    private void refreshFcm() {
        setPill(pillFcm, I18n.t("Đang đọc…"), Tone.NEUTRAL);
        new Thread(() -> {
            FcmStatus s = FcmStatus.query(Shell.available(false), hasDump());
            AlertCenter.checkFcm(this, s.raw);
            Set<String> stopped = stillStopped(s.stoppedApps);
            main.post(() -> {
                fcmDetail = s.detail;
                String st24 = s.stats24();
                if (st24 == null) {
                    setPill(pillFcm24, I18n.t("Không có dữ liệu"), Tone.NEUTRAL);
                    subFcm24.setVisibility(View.GONE);
                } else {
                    // nhãn theo phần đáng chú ý: rớt > 1 phút, tin trễ vượt ngưỡng cảnh báo
                    int bad = Math.max(0, s.longDrops) + Math.max(0, s.seriousDelays);
                    boolean known = s.longDrops >= 0;
                    setPill(pillFcm24, known ? (bad == 0 ? I18n.t("Ổn định") : bad + I18n.t(" sự cố")) : s.drops24 + I18n.t(" lần rớt"),
                            known ? (bad == 0 ? Tone.OK : bad > 5 ? Tone.ERR : Tone.WARN)
                                    : s.drops24 == 0 ? Tone.OK : Tone.WARN);
                    subFcm24.setText(st24);
                    subFcm24.setVisibility(View.VISIBLE);
                }
                fcmStopped = stopped;
                setPill(pillFcm, s.label(), s.colorRes());
                FcmWidget.render(this, s);
                String sub = s.state == FcmStatus.State.CONNECTED
                        ? (s.stoppedApps.isEmpty() ? "" : s.stoppedApps.size() + I18n.t(" app bị mất tin (stopped) • bấm xem"))
                        : s.summary;
                subFcm.setText(sub);
                subFcm.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }).start();
    }

    private void showFcmDetail() {
        if (fcmDetail.contains("missing android.permission.PACKAGE_USAGE_STATS")) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(I18n.t("Cần cấp quyền 1 lần qua Shizuku"))
                    .setMessage(I18n.t("Android yêu cầu quyền PACKAGE_USAGE_STATS được cấp bằng lệnh pm grant ")
                            + I18n.t("(công tắc Usage access trong Cài đặt là chưa đủ).\n\n")
                            + I18n.t("Bấm \"Fix ngay\" 1 lần khi Shizuku đang chạy — sau đó đọc FCM không cần Shizuku nữa."))
                    .setPositiveButton(I18n.t("Fix ngay"), (d, w) -> onFixClicked())
                    .setNeutralButton(I18n.t("Chi tiết"), (d, w) -> showText("FCM (GcmService)", fcmDetail))
                    .setNegativeButton(I18n.t("Đóng"), null)
                    .show();
            return;
        }
        if (fcmDetail.contains("app ops") || fcmDetail.contains("PACKAGE_USAGE_STATS")) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(I18n.t("Cần quyền Usage access"))
                    .setMessage(I18n.t("Để đọc trạng thái FCM mà không cần Shizuku, bật \"GMS Fix\" trong ")
                            + I18n.t("Quyền truy cập dữ liệu sử dụng (Usage access).\n\n")
                            + I18n.t("Hoặc bấm \"Fix ngay\" khi có Shizuku — app sẽ tự cấp."))
                    .setPositiveButton(I18n.t("Mở Cài đặt"), (d, w) -> openUsageAccessSettings())
                    .setNeutralButton(I18n.t("Chi tiết"), (d, w) -> showText("FCM (GcmService)", fcmDetail))
                    .setNegativeButton(I18n.t("Đóng"), null)
                    .show();
            return;
        }
        if (!fcmStopped.isEmpty()) {
            StringBuilder msg = new StringBuilder(I18n.t("GMS đã nhận tin nhưng bỏ vì các app này đang bị dừng ")
                    + I18n.t("(force-stop / dọn RAM). Chúng sẽ không nhận thông báo cho tới khi được mở lại:\n\n"));
            for (String p : fcmStopped) msg.append("• ").append(appLabel(p)).append("\n");
            msg.append(I18n.t("\nThêm vào danh sách để fix (No Restrictions + bỏ trạng thái stopped)?"));
            new MaterialAlertDialogBuilder(this)
                    .setTitle(fcmStopped.size() + I18n.t(" app bị mất thông báo"))
                    .setMessage(msg.toString())
                    .setPositiveButton(I18n.t("Thêm & fix"), (d, w) -> {
                        Set<String> pk = getPkgs();
                        pk.addAll(fcmStopped);
                        setPkgs(pk);
                        Set<String> off = getOff();
                        off.removeAll(fcmStopped);
                        setOff(off);
                        renderApps();
                        onFixClicked();
                    })
                    .setNeutralButton(I18n.t("Chi tiết"), (d, w) -> showText("FCM (GcmService)", fcmDetail))
                    .setNegativeButton(I18n.t("Đóng"), null)
                    .show();
            return;
        }
        if (fcmDetail.isEmpty()) {
            toast(I18n.t("Chưa có dữ liệu"));
            return;
        }
        showText("FCM (GcmService)", fcmDetail);
    }


    // ---- cảnh báo ----

    private void setupAlerts() {
        LinearLayout box = findViewById(R.id.alertSwitches);
        alertRecent = findViewById(R.id.alertRecent);
        for (AlertCenter.Type t : AlertCenter.Type.values()) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_switch_row, box, false);
            ((TextView) row.findViewById(R.id.title)).setText(t.title);
            ((TextView) row.findViewById(R.id.sub)).setText(t.desc);
            MaterialSwitch sw = row.findViewById(R.id.toggle);
            sw.setSaveEnabled(false); // các hàng dùng chung id → không để Android khôi phục đè trạng thái
            sw.setChecked(AlertCenter.enabled(this, t));
            sw.setOnCheckedChangeListener((b, on) -> AlertCenter.setEnabled(this, t, on));
            row.setOnClickListener(v -> sw.toggle());
            box.addView(row);
        }
        findViewById(R.id.btnAlertClear).setOnClickListener(v ->
                startActivity(new Intent(this, AlertLogActivity.class)));
    }

    private void refreshAlerts() {
        java.util.List<String> h = AlertCenter.historyList(this);
        alertRecent.removeAllViews();
        if (h.isEmpty()) h = java.util.Collections.singletonList(I18n.t("Chưa có cảnh báo nào"));
        for (String line : h.subList(0, Math.min(3, h.size()))) {
            TextView tv = new TextView(this);
            tv.setText(line);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            tv.setPadding(0, dp(3), 0, dp(3));
            alertRecent.addView(tv);
        }
        MaterialButton all = findViewById(R.id.btnAlertClear);
        int n = AlertCenter.historyList(this).size();
        all.setText(n == 0 ? I18n.t("Xem log ›") : I18n.t("Xem tất cả (") + n + ") ›");
    }

    // ---- kiểm soát broadcast (aurogon_enable) ----

    private void chooseBcMode() {
        String[] labels = {I18n.t("Chỉ tin FCM cho app trong danh sách (khuyên dùng)"), I18n.t("Mọi broadcast (tốn pin hơn)")};
        int sel = BroadcastCtrl.fcmOnly(this) ? 0 : 1;
        new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Đánh thức app bị đóng băng khi"))
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    prefs.edit().putString(BroadcastCtrl.PREF_MODE, w == 0 ? "fcm" : "all").apply();
                    if (BroadcastCtrl.wanted(this)) {
                        String err = BroadcastCtrl.apply(this);
                        if (err != null) toast(I18n.t("Không ghi được: ") + err);
                    }
                    refreshBroadcastCtrl();
                    d.dismiss();
                })
                .setNegativeButton(I18n.t("Hủy"), null)
                .show();
    }

    private void refreshDoze() {
        boolean wanted = DozeTune.wanted(this);
        new Thread(() -> {
            if (wanted) DozeTune.ensure(this);
            boolean applied = DozeTune.applied(this);
            boolean other = !applied && DozeTune.otherValues(this);
            long[] v = DozeTune.liveValues();
            int re = DozeTune.reapplyCount(this);
            // giá trị thật đang dùng: quick_doze_delay_to • idle_to → max_idle_to
            String values = v == null ? I18n.t("Không đọc được (thiếu quyền DUMP)")
                    : I18n.t("Ngủ sâu sau ") + DozeTune.human(Math.min(v[0], 120000)) + I18n.t(" • thức mỗi ")
                    + DozeTune.human(v[1]) + " → " + DozeTune.human(v[2]);
            String status = !wanted ? (applied ? I18n.t("⚠ Đang tắt trong app nhưng hệ thống vẫn nới")
                    : other ? I18n.t("⚠ Hệ thống đang có thông số doze khác") : "")
                    : (applied ? I18n.t("✓ Đang áp dụng") : I18n.t("⚠ Chưa có hiệu lực"))
                    + (re > 0 ? I18n.t(" • HyperOS đổi lại ") + re + I18n.t(" lần (đã ghi lại)") : "");
            main.post(() -> {
                dozeValues.setText(values);
                dozeStatus.setText(status);
                dozeStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }).start();
    }

    private void refreshNight() {
        boolean wanted = SleepGuard.wanted(this);
        boolean ok = SleepGuard.applied(this);
        ((TextView) findViewById(R.id.nightValues)).setText(SleepGuard.status(this));
        TextView st = findViewById(R.id.nightStatus);
        if (wanted) SleepGuard.ensure(this);
        ok = SleepGuard.applied(this);
        ((TextView) findViewById(R.id.nightValues)).setText(SleepGuard.status(this));
        int re = prefs.getInt("nightReapplyCount", 0);
        String s = wanted ? (ok ? I18n.t("✓ Đang áp dụng") + (re > 0 ? I18n.t(" • Nighttime mode bị bật lại ") + re + I18n.t(" lần (đã tắt)") : "")
                : SleepGuard.alarmOk(this) ? I18n.t("⚠ Chưa có hiệu lực") : I18n.t("⚠ Danh sách alarm chưa có GMS • bấm Fix ngay (cần Shizuku)"))
                : (ok ? I18n.t("⚠ Đang tắt trong app nhưng hệ thống vẫn giữ") : "");
        st.setText(s);
        st.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showDozeInfo() {
        new Thread(() -> {
            long[] v = DozeTune.liveValues();
            StringBuilder sb = new StringBuilder();
            sb.append(I18n.t("Tắt màn hình là HyperOS cho máy ngủ sâu rất nhanh. Khi ngủ sâu, máy chủ Google giữ tin FCM ")
                    + I18n.t("ưu tiên thường (Gmail, Grab, Shopee…) tới lần máy thức kế tiếp — mặc định cách 1 → 6 giờ. ")
                    + I18n.t("Tin ưu tiên cao (chat, OTP) không bị ảnh hưởng.\n\n")
                    + I18n.t("Bật: máy thức gom tin mỗi 15 → 30 phút và chờ ~2 phút trước khi ngủ sâu. Tốn pin hơn chút. ")
                    + I18n.t("Không cần Shizuku; app tự ghi lại sau khởi động lại.\n\n")
                    + I18n.t("Giá trị đang dùng (mặc định HyperOS):\n"));
            for (int i = 0; i < DozeTune.KEYS.length; i++) {
                Object[] k = DozeTune.KEYS[i];
                if (!(Boolean) k[3] && i == 3) sb.append(I18n.t("\nHyperOS bỏ qua (dùng Quick Doze):\n"));
                sb.append("• ").append(k[1]).append(": ")
                        .append(v == null ? "?" : DozeTune.human(v[i]))
                        .append(" (").append(DozeTune.human((Long) k[2])).append(")\n");
            }
            sb.append(I18n.t("\nQuick Doze: HyperOS giới hạn tối đa ~2 phút."));
            main.post(() -> new MaterialAlertDialogBuilder(this)
                    .setTitle(I18n.t("Nới deep doze"))
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show());
        }).start();
    }

    /** Danh sách app có FCM được thêm tự động (ngoài danh sách đã chọn) vào mở băng khi có tin. */
    private void showAutoFcmApps() {
        PackageManager pm = getPackageManager();
        Set<String> mine = getPkgs();
        Set<String> off = getOff();
        List<String> names = new ArrayList<>();
        int skipped = 0;
        for (String p : BroadcastCtrl.fcmApps(this)) {
            if (mine.contains(p)) continue;
            if (off.contains(p)) { skipped++; continue; }
            names.add(HealthCheck.label(pm, p));
        }
        names.sort(String::compareToIgnoreCase);
        new MaterialAlertDialogBuilder(this)
                .setTitle(names.size() + I18n.t(" app thêm tự động"))
                .setMessage(I18n.t("Có tin FCM thì được mở băng (không cần chọn). Muốn Fix đầy đủ (tự khởi chạy, bỏ ")
                        + I18n.t("trạng thái dừng…) thì thêm app vào danh sách.\n\n• ") + String.join("\n• ", names))
                .setPositiveButton("OK", null)
                .show();
    }

    private void refreshBroadcastCtrl() {
        boolean wanted = BroadcastCtrl.wanted(this);
        swBcAuto.setEnabled(wanted);
        findViewById(R.id.row_swBcAuto).setAlpha(wanted ? 1f : 0.5f);
        boolean fcmMode = BroadcastCtrl.fcmOnly(this);
        findViewById(R.id.swBcAll).setEnabled(wanted && fcmMode);
        findViewById(R.id.row_swBcAll).setAlpha(wanted && fcmMode ? 1f : 0.5f);
        TextView allSub = findViewById(R.id.bcAllSub);
        allSub.setText(I18n.t("Không chỉ app trong danh sách • ") + BroadcastCtrl.fcmApps(this).size() + I18n.t(" app có FCM • xem ›"));
        allSub.setOnClickListener(v -> showAutoFcmApps());
        bcStatus.setVisibility(View.VISIBLE);
        if (wanted) BroadcastCtrl.ensure(this);
        boolean applied = BroadcastCtrl.applied(this);
        String value = BroadcastCtrl.shortValue(BroadcastCtrl.current(this));
        if (!wanted) {
            bcStatus.setText(applied
                    ? I18n.t("Đang tắt trong app nhưng hệ thống vẫn có ") + value
                    : "");
            if (!applied) bcStatus.setVisibility(View.GONE);
            return;
        }
        if (!applied) {
            bcStatus.setText(I18n.t("⚠ Chưa có hiệu lực (") + value + ")"
                    + (BroadcastCtrl.autoReapply(this) ? I18n.t(" • sẽ tự ghi lại") : I18n.t(" • gạt lại công tắc")));
            return;
        }
        bcStatus.setText("✓ " + value + I18n.t(" ▾ • đang đọc Greezer…"));
        new Thread(() -> {
            int thaws = hasDump() ? BroadcastCtrl.recentThaws() : -1;
            int re = BroadcastCtrl.reapplyCount(this);
            main.post(() -> bcStatus.setText("✓ " + value + " ▾"
                    + (thaws < 0 ? "" : " • " + thaws + I18n.t(" lần mở băng"))
                    + (re > 0 ? I18n.t(" • tự ghi lại ") + re + I18n.t(" lần") : "")));
        }).start();
    }

    // ---- kiểm tra cài đặt ----

    private void refreshHealth() {
        setPill(pillHealth, I18n.t("Đang kiểm tra…"), Tone.NEUTRAL);
        new Thread(() -> {
            int added = NoRestrictGuard.ensure(this);
            if (added > 0) main.post(() -> toast(I18n.t("Đã cập nhật ") + added + I18n.t(" app trong No Restrictions")));
            HealthCheck.Report r = HealthCheck.runAndNotify(this, true);
            main.post(() -> {
                health = r;
                if (!r.complete && r.count() == 0) {
                    setPill(pillHealth, I18n.t("Chưa đủ quyền"), Tone.NEUTRAL);
                    subHealth.setText(I18n.t("Bấm \"Fix ngay\" 1 lần để cấp quyền đọc"));
                    subHealth.setVisibility(View.VISIBLE);
                } else if (r.count() == 0) {
                    setPill(pillHealth, I18n.t("Tất cả ổn"), Tone.OK);
                    subHealth.setVisibility(View.GONE);
                } else {
                    setPill(pillHealth, r.count() + I18n.t(" app có vấn đề"), Tone.WARN);
                    subHealth.setText(I18n.t("Bấm để xem chi tiết"));
                    subHealth.setVisibility(View.VISIBLE);
                }
                refreshAlerts();
                if (showHealthWhenReady) {
                    showHealthWhenReady = false;
                    showHealth();
                }
            });
        }).start();
    }

    private void showHealth() {
        if (health == null) {
            toast(I18n.t("Đang kiểm tra…"));
            return;
        }
        if (health.count() == 0) {
            toast(I18n.t("Tất cả app đã chọn đều ổn"));
            return;
        }
        String msg = health.text(getPackageManager())
                + I18n.t("\n\n\"Fix ngay\" sẽ áp dụng lại (cần Shizuku hoặc root).")
                + (health.issues.toString().contains(HealthCheck.NOTIF_OFF)
                ? I18n.t("\nRiêng \"thông báo đang bị tắt\" phải tự bật trong Cài đặt → Thông báo của app đó.") : "");
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(health.count() + I18n.t(" app có thể bị mất thông báo"))
                .setMessage(msg)
                .setNegativeButton(I18n.t("Đóng"), null);
        if (!health.onlyUserFixable()) b.setPositiveButton(I18n.t("Fix ngay"), (d, w) -> onFixClicked());
        b.show();
    }

    // ---- apps ----

    private Set<String> getPkgs() {
        Set<String> def = new LinkedHashSet<>();
        def.add(Steps.GMS);
        return new LinkedHashSet<>(prefs.getStringSet("pkgs", def));
    }

    private void setPkgs(Set<String> pkgs) {
        Set<String> added = new LinkedHashSet<>(pkgs);
        added.removeAll(getPkgs());
        NoRestrictGuard.adoptAdded(this, added);
        prefs.edit().putStringSet("pkgs", new LinkedHashSet<>(pkgs)).apply();
        if (BroadcastCtrl.wanted(this)) BroadcastCtrl.apply(this);
    }

    /** App đã thêm nhưng đang tắt (giữ trong danh sách, không fix). */
    private Set<String> getOff() {
        return new LinkedHashSet<>(prefs.getStringSet("pkgsOff", new LinkedHashSet<>()));
    }

    private void setOff(Set<String> off) {
        prefs.edit().putStringSet("pkgsOff", new LinkedHashSet<>(off)).apply();
        if (BroadcastCtrl.wanted(this)) BroadcastCtrl.apply(this);
    }

    private Set<String> getEnabledPkgs() {
        Set<String> s = getPkgs();
        s.removeAll(getOff());
        return s;
    }

    private Set<String> getCollapsed() {
        return new LinkedHashSet<>(prefs.getStringSet("groupsCollapsed", new LinkedHashSet<>()));
    }

    private void renderApps() {
        appsList.removeAllViews();
        PackageManager pm = getPackageManager();
        Set<String> pkgs = getPkgs();
        Set<String> off = getOff();
        Set<String> collapsed = getCollapsed();
        LayoutInflater inf = LayoutInflater.from(this);
        Set<String> alwaysOn = NoRestrictGuard.alwaysOn(this);
        String nrRaw = NoRestrictGuard.read(this);
        Set<String> nrNow = nrRaw == null ? null : NoRestrictGuard.parse(nrRaw);

        // Gom theo nhóm, trong nhóm sắp theo tên
        java.util.Map<AppGroups.Group, List<String[]>> groups = new java.util.EnumMap<>(AppGroups.Group.class);
        for (String p : pkgs) {
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                label = null;
            }
            groups.computeIfAbsent(AppGroups.of(pm, p), g -> new ArrayList<>()).add(new String[]{p, label});
        }

        for (java.util.Map.Entry<AppGroups.Group, List<String[]>> e : groups.entrySet()) {
            AppGroups.Group g = e.getKey();
            List<String[]> apps = e.getValue();
            apps.sort((a, b) -> String.valueOf(a[1]).compareToIgnoreCase(String.valueOf(b[1])));
            List<String> groupPkgs = new ArrayList<>();
            int on = 0;
            for (String[] a : apps) {
                groupPkgs.add(a[0]);
                if (!off.contains(a[0])) on++;
            }
            boolean isCollapsed = collapsed.contains(g.name());

            View header = inf.inflate(R.layout.item_group_header, appsList, false);
            ((TextView) header.findViewById(R.id.title)).setText(g.title);
            ((TextView) header.findViewById(R.id.count)).setText(on + "/" + apps.size() + I18n.t(" đang bật"));
            header.findViewById(R.id.chevron).setRotation(isCollapsed ? -90 : 0);
            MaterialSwitch gToggle = header.findViewById(R.id.toggle);
            gToggle.setSaveEnabled(false); // id trùng giữa các nhóm: khôi phục trạng thái sẽ gạt tắt cả danh sách
            gToggle.setChecked(on > 0);
            gToggle.setOnCheckedChangeListener((b, checked) -> {
                Set<String> o = getOff();
                if (checked) o.removeAll(groupPkgs);
                else o.addAll(groupPkgs);
                setOff(o);
                main.post(this::renderApps);
            });
            header.setOnClickListener(v -> {
                Set<String> c = getCollapsed();
                if (!c.remove(g.name())) c.add(g.name());
                prefs.edit().putStringSet("groupsCollapsed", c).apply();
                renderApps();
            });
            appsList.addView(header);
            if (isCollapsed) continue;

            for (String[] a : apps) {
                String p = a[0];
                View row = inf.inflate(R.layout.item_selected_app, appsList, false);
                ImageView icon = row.findViewById(R.id.icon);
                TextView label = row.findViewById(R.id.label);
                boolean always = alwaysOn.contains(p);
                TextView sub = row.findViewById(R.id.pkg);
                boolean mismatch = !always && nrNow != null && nrNow.contains(p);
                sub.setText(always ? I18n.t("Luôn chạy nền") : mismatch ? I18n.t("Chỉ khi có tin • ⚠ pin đang No restrictions ›") : I18n.t("Chỉ khi có tin"));
                if (mismatch) {
                    sub.setTextColor(color(R.color.warn));
                    sub.setOnClickListener(v -> openBatteryPage(p, false));
                }
                ImageView tier = row.findViewById(R.id.tier);
                tier.setImageTintList(ColorStateList.valueOf(always ? color(R.color.warn)
                        : 0x66888888));
                tier.setOnClickListener(v -> toggleTier(p, String.valueOf(label.getText())));
                if (a[1] != null) {
                    label.setText(a[1]);
                    try {
                        icon.setImageDrawable(pm.getApplicationIcon(p));
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                } else {
                    label.setText(I18n.t("Chưa cài"));
                    icon.setImageResource(R.drawable.ic_warning);
                    icon.setImageTintList(ColorStateList.valueOf(color(R.color.warn)));
                }
                boolean enabled = !off.contains(p);
                row.setAlpha(enabled ? 1f : 0.5f);
                MaterialSwitch toggle = row.findViewById(R.id.toggle);
                toggle.setSaveEnabled(false); // id trùng giữa các hàng (xem gToggle)
                toggle.setChecked(enabled);
                toggle.setOnCheckedChangeListener((b, checked) -> {
                    Set<String> o = getOff();
                    if (checked) o.remove(p);
                    else o.add(p);
                    setOff(o);
                    main.post(this::renderApps);
                });
                row.setOnClickListener(v -> toggle.toggle());
                row.setOnLongClickListener(v -> {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(I18n.t("Xoá khỏi danh sách?"))
                            .setMessage(label.getText() + "\n" + p)
                            .setPositiveButton(I18n.t("Xoá"), (d, w) -> {
                                Set<String> s = getPkgs();
                                s.remove(p);
                                setPkgs(s);
                                Set<String> o = getOff();
                                o.remove(p);
                                setOff(o);
                                renderApps();
                            })
                            .setNegativeButton(I18n.t("Hủy"), null)
                            .show();
                    return true;
                });
                appsList.addView(row);
            }
        }

        int enabledCount = getEnabledPkgs().size();
        appsTitle.setText(pkgs.isEmpty() ? I18n.t("Ứng dụng áp dụng")
                : I18n.t("Ứng dụng áp dụng (") + enabledCount + "/" + pkgs.size() + ")");
        appsEmpty.setVisibility(pkgs.isEmpty() ? View.VISIBLE : View.GONE);
        btnRun.setEnabled(!busy && enabledCount > 0);
        if (enabledCount == 0) btnRun.setText(pkgs.isEmpty() ? I18n.t("Chọn ít nhất 1 app") : I18n.t("Bật ít nhất 1 app"));
        else if (!busy) btnRun.setText(I18n.t("Fix ngay"));
    }

    /** Đổi mức app: "Luôn chạy nền" ⇄ "Chỉ khi có tin", rồi cập nhật No Restrictions (cần Shizuku/root). */
    private void toggleTier(String p, String label) {
        Set<String> a = NoRestrictGuard.alwaysOn(this);
        boolean nowAlways = !a.contains(p);
        if (nowAlways) a.add(p);
        else a.remove(p);
        NoRestrictGuard.setAlwaysOn(this, a);
        renderApps();
        if (!nowAlways && !BroadcastCtrl.wanted(this)) {
            toast(label + I18n.t(": cần bật \"Cho FCM đánh thức app bị đóng băng\" để không mất tin"));
        }
        if (NoRestrictGuard.noBatteryUi(p)) {
            // GMS: không có lựa chọn pin → GMS Fix tự thêm (cần Shizuku); "chỉ khi có tin" thì để PowerKeeper xoá
            new Thread(() -> {
                int r = NoRestrictGuard.ensure(this);
                main.post(() -> {
                    if (r == NoRestrictGuard.NEED_SHELL) toast(label + I18n.t(" • bấm Fix ngay (Shizuku) để áp dụng"));
                    refreshHealth();
                });
            }).start();
            return;
        }
        // Nguồn gốc là cài đặt pin của PowerKeeper → mở đúng trang để người dùng chọn
        openBatteryPage(p, nowAlways);
    }

    /** Mở trang Battery details của app; nhắc chọn đúng mục theo mức. */
    private void openBatteryPage(String p, boolean always) {
        toast(always ? I18n.t("Chọn \"No restrictions\" để luôn chạy nền")
                : I18n.t("Chọn \"Battery saver (recommended)\" để được đóng băng khi rảnh"));
        try {
            startActivity(NoRestrictGuard.batteryPage(p));
        } catch (Exception e) {
            toast(I18n.t("Không mở được cài đặt pin: ") + e.getMessage());
        }
    }

    // ---- fix flow ----

    /**
     * Một chạm: có root/Shizuku → chạy luôn. Không có → bật Developer + Wireless debugging,
     * mở Shizuku, đợi người dùng bấm Start rồi tự chạy tiếp (maybeContinueAutoFix).
     */
    private void onFixClicked() {
        if (busy || getEnabledPkgs().isEmpty()) return;
        setBusy(true);
        new Thread(() -> {
            Shell.Mode mode = Shell.probeRoot() ? Shell.Mode.ROOT : null;
            main.post(this::refreshStatus);
            if (mode == null && Shell.shizukuAlive()) {
                if (!Shell.shizukuGranted()) {
                    requestShizukuPermission();
                    return;
                }
                mode = Shell.Mode.SHIZUKU;
            }
            if (mode != null) {
                startRun(mode);
                return;
            }
            // Chưa có quyền: bật debugging và đẩy sang Shizuku
            boolean ok = applyGlobal(DEV_ON);
            main.post(() -> {
                setBusy(false);
                refreshStatus();
                if (!ok) {
                    showFirstTimeHelp();
                    return;
                }
                prefs.edit().putLong("awaitShizuku", System.currentTimeMillis()).apply();
                openShizuku();
            });
        }).start();
    }

    private void maybeContinueAutoFix() {
        long since = prefs.getLong("awaitShizuku", 0);
        if (since == 0 || !resumed || busy) return;
        if (System.currentTimeMillis() - since > AWAIT_SHIZUKU_MS) {
            prefs.edit().remove("awaitShizuku").apply();
            return;
        }
        if (!Shell.shizukuAlive()) return;
        prefs.edit().remove("awaitShizuku").apply();
        toast(I18n.t("Shizuku đã chạy — tiếp tục fix"));
        onFixClicked();
    }

    private void requestShizukuPermission() {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Đã từng bị từ chối → Shizuku không hiện hộp thoại xin quyền nữa, phải bật lại trong app Shizuku
            main.post(() -> {
                setBusy(false);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(I18n.t("Shizuku đang chặn GMS Fix"))
                        .setMessage(I18n.t("Trước đây quyền Shizuku của GMS Fix đã bị từ chối, nên Shizuku không hỏi lại nữa.\n\n")
                                + I18n.t("Mở Shizuku → Ứng dụng được ủy quyền (Authorized applications) → bật GMS Fix, ")
                                + I18n.t("rồi quay lại bấm Fix ngay."))
                        .setPositiveButton(I18n.t("Mở Shizuku"), (d, w) -> openShizuku())
                        .setNegativeButton(I18n.t("Đóng"), null)
                        .show();
            });
            return;
        }
        pendingRun = true;
        main.post(() -> Shizuku.requestPermission(SHIZUKU_REQ));
    }

    private void showFirstTimeHelp() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Lần đầu cần bật thủ công"))
                .setMessage(I18n.t("1. Cài đặt → Giới thiệu điện thoại → bấm \"Phiên bản OS\" 7 lần\n")
                        + I18n.t("2. Developer options → bật Wireless debugging\n")
                        + I18n.t("3. Mở Shizuku → ghép nối (lần đầu) → Start\n")
                        + I18n.t("4. Quay lại bấm \"Fix ngay\"\n\n")
                        + I18n.t("Từ lần sau app sẽ tự bật Developer và tự tắt sau khi fix."))
                .setPositiveButton(I18n.t("Mở Cài đặt"), (d, w) -> openDeveloperSettings())
                .setNeutralButton(I18n.t("Mở Shizuku"), (d, w) -> openShizuku())
                .setNegativeButton(I18n.t("Đóng"), null)
                .show();
    }

    private void startRun(Shell.Mode mode) {
        new Thread(() -> {
            List<String> pkgs = new ArrayList<>();
            for (String p : getEnabledPkgs()) if (PKG_NAME.matcher(p).matches()) pkgs.add(p);
            List<Steps.Step> steps = Steps.build(pkgs, NoRestrictGuard.alwaysOn(this),
                    NoRestrictGuard.targetValue(this), getPackageName(),
                    BroadcastCtrl.wanted(this) && BroadcastCtrl.allFcm(this)
                            ? BroadcastCtrl.fcmApps(this) : java.util.Collections.emptySet(),
                    SleepGuard.wanted(this) ? SleepGuard.applyScript(this) : null);
            List<View> rows = new ArrayList<>();
            main.post(() -> {
                fullLog.setLength(0);
                resultRows.removeAllViews();
                for (Steps.Step s : steps) rows.add(addStepRow(s.title));
                resultMode.setText(I18n.t("Chế độ: ") + (mode == Shell.Mode.ROOT ? "root" : "Shizuku")
                        + " • " + pkgs.size() + " app");
                setPill(resultSummary, I18n.t("Đang chạy…"), Tone.NEUTRAL);
                cardResults.setVisibility(View.VISIBLE);
            });

            int ok = 0, warn = 0, hard = 0;
            for (int i = 0; i < steps.size(); i++) {
                Steps.Step step = steps.get(i);
                int idx = i;
                main.post(() -> setStepState(rows.get(idx), null, false, I18n.t("Đang chạy…"), ""));
                String out, summary;
                boolean failed;
                try {
                    Shell.Result r = Shell.exec(mode, step.script);
                    failed = Steps.Step.failed(r);
                    summary = Steps.summary(r, failed);
                    out = r.out.trim() + "\n(exit " + r.code + ")";
                } catch (Exception e) {
                    failed = true;
                    summary = String.valueOf(e.getMessage());
                    out = e.toString();
                }
                if (!failed) ok++;
                else if (step.soft) warn++;
                else hard++;
                synchronized (fullLog) {
                    fullLog.append("▶ ").append(step.title).append('\n').append(out).append("\n\n");
                }
                boolean f = failed;
                String sum = f && step.soft ? I18n.t("Bỏ qua (ROM không hỗ trợ): ") + summary : summary;
                String detail = out;
                main.post(() -> setStepState(rows.get(idx), !f, step.soft, sum, detail));
            }

            int okN = ok, warnN = warn, hardN = hard, total = steps.size();
            main.post(() -> {
                String text = hardN == 0 ? (okN + "/" + total + I18n.t(" thành công")) : (hardN + I18n.t(" bước lỗi"));
                setPill(resultSummary, text, hardN == 0 ? (warnN == 0 ? Tone.OK : Tone.WARN)
                        : okN == 0 ? Tone.ERR : Tone.WARN);
                setBusy(false);
                refreshStatus();
                refreshFcm();
                afterRun(hardN == 0);
                // GMS vừa bị force-stop, kết nối lại FCM mất vài giây
                main.postDelayed(this::refreshFcm, 8000);
                main.postDelayed(this::refreshFcm, 20000);
            });
        }).start();
    }

    private void afterRun(boolean allOk) {
        main.post(this::refreshHealth);
        if (!prefs.getBoolean("autoOff", true) || !devEnabled()) return;
        if (allOk) {
            turnOffDeveloper(true);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Có bước bị lỗi"))
                .setMessage(I18n.t("Vẫn tắt Developer options ngay? (Chọn \"Để sau\" nếu muốn xem log và chạy lại.)"))
                .setPositiveButton(I18n.t("Tắt"), (d, w) -> turnOffDeveloper(true))
                .setNegativeButton(I18n.t("Để sau"), null)
                .show();
    }

    private View addStepRow(String title) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_step, resultRows, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.summary)).setText(I18n.t("Chờ"));
        resultRows.addView(row);
        return row;
    }

    /** ok: null = đang chạy. */
    private void setStepState(View row, Boolean ok, boolean soft, String summary, String detail) {
        ImageView icon = row.findViewById(R.id.icon);
        CircularProgressIndicator progress = row.findViewById(R.id.progress);
        ((TextView) row.findViewById(R.id.summary)).setText(summary);
        if (ok == null) {
            icon.setVisibility(View.INVISIBLE);
            progress.setVisibility(View.VISIBLE);
            return;
        }
        progress.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        if (ok) {
            icon.setImageResource(R.drawable.ic_check);
            icon.setImageTintList(ColorStateList.valueOf(color(R.color.ok)));
        } else if (soft) {
            icon.setImageResource(R.drawable.ic_warning);
            icon.setImageTintList(ColorStateList.valueOf(color(R.color.warn)));
        } else {
            icon.setImageResource(R.drawable.ic_error);
            icon.setImageTintList(ColorStateList.valueOf(color(R.color.err)));
        }
        String title = ((TextView) row.findViewById(R.id.title)).getText().toString();
        row.setOnClickListener(v -> showText(title, detail));
    }

    // ---- developer options ----

    private void onDevOnClicked() {
        setBusy(true);
        new Thread(() -> {
            boolean ok = applyGlobal(DEV_ON);
            main.post(() -> {
                setBusy(false);
                refreshStatus();
                if (ok) openShizuku();
                else showFirstTimeHelp();
            });
        }).start();
    }

    private void onDevOffClicked() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Tắt Developer options?"))
                .setMessage(I18n.t("USB & Wireless debugging cũng sẽ tắt, Shizuku sẽ ngừng chạy."))
                .setPositiveButton(I18n.t("Tắt"), (d, w) -> turnOffDeveloper(false))
                .setNegativeButton(I18n.t("Hủy"), null)
                .show();
    }

    private void turnOffDeveloper(boolean auto) {
        setBusy(true);
        new Thread(() -> {
            boolean ok = applyGlobal(DEV_OFF);
            main.post(() -> {
                setBusy(false);
                refreshStatus();
                if (ok) toast(auto ? I18n.t("Fix xong — đã tự tắt Developer options") : I18n.t("Đã tắt Developer options"));
                else toast(I18n.t("Không có quyền để tắt Developer options"));
            });
        }).start();
    }

    /** Ghi Settings.Global: trực tiếp nếu app có WRITE_SECURE_SETTINGS, không thì qua root/Shizuku. */
    private boolean applyGlobal(String[][] kvs) {
        if (canWriteSecure()) {
            try {
                for (String[] kv : kvs) Settings.Global.putString(getContentResolver(), kv[0], kv[1]);
                return true;
            } catch (Exception ignored) {
                // rơi xuống dùng shell
            }
        }
        Shell.Mode mode = Shell.available(false);
        if (mode == null) return false;
        StringBuilder script = new StringBuilder();
        for (String[] kv : kvs) {
            script.append("settings put global ").append(kv[0]).append(' ').append(kv[1]).append("; ");
        }
        script.append("pm grant ").append(getPackageName()).append(' ')
                .append(Manifest.permission.WRITE_SECURE_SETTINGS).append(" >/dev/null 2>&1; ")
                .append("pm grant ").append(getPackageName()).append(' ')
                .append(Manifest.permission.PACKAGE_USAGE_STATS).append(" >/dev/null 2>&1; ")
                .append("cmd appops set ").append(getPackageName()).append(" GET_USAGE_STATS allow >/dev/null 2>&1; true");
        try {
            return Shell.exec(mode, script.toString()).code == 0;
        } catch (Exception e) {
            // Tắt adb có thể giết Shizuku giữa chừng — setting vẫn đã được ghi
            return true;
        }
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (i == null) {
            prefs.edit().remove("awaitShizuku").apply();
            new MaterialAlertDialogBuilder(this)
                    .setTitle(I18n.t("Chưa cài Shizuku"))
                    .setMessage(I18n.t("Cài Shizuku để app có quyền chạy lệnh (không cần root)."))
                    .setPositiveButton(I18n.t("Tải Shizuku"), (d, w) ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL))))
                    .setNegativeButton(I18n.t("Đóng"), null)
                    .show();
            return;
        }
        toast(Shell.shizukuAlive() ? I18n.t("Shizuku đang chạy rồi")
                : I18n.t("Bấm \"Start\" trong Shizuku rồi quay lại — app sẽ tự chạy tiếp"));
        startActivity(i);
    }

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        }
    }

    private boolean devEnabled() {
        return Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    private boolean canWriteSecure() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasDump() {
        return checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_REQ);
        }
    }

    // ---- ui helpers ----

    private void setBusy(boolean b) {
        main.post(() -> {
            busy = b;
            btnDevOn.setEnabled(!b);
            btnDevOff.setEnabled(!b);
            btnPickApps.setEnabled(!b);
            renderApps();
            if (b) btnRun.setText(I18n.t("Đang chạy…"));
        });
    }

    private void setPill(TextView pill, String text, Tone tone) {
        setPill(pill, text, tone == Tone.OK ? R.color.ok : tone == Tone.ERR ? R.color.err
                : tone == Tone.WARN ? R.color.warn : R.color.neutral);
    }

    private void setPill(TextView pill, String text, @ColorRes int colorRes) {
        int c = color(colorRes);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(100));
        bg.setColor((c & 0x00FFFFFF) | 0x2E000000);
        pill.setBackground(bg);
        pill.setTextColor(c);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextSize(13);
        pill.setPadding(dp(12), dp(4), dp(12), dp(4));
        pill.setText(text);
    }

    private void showText(String title, String text) {
        TextView tv = new TextView(this);
        tv.setText(text.isEmpty() ? I18n.t("(trống)") : text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(I18n.t("Đóng"), null)
                .show();
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private int color(@ColorRes int id) {
        return ContextCompat.getColor(this, id);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppPicker.onPermissionResult(requestCode);
    }
}
