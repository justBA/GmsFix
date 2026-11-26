package com.anb.gmsfix;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

/**
 * HyperOS (ROM CN) freeze GMS khi tắt màn hình / vào "sleep mode" với NET_SOCKET → socket FCM bị cắt
 * và GMS không reconnect cho tới khi được thaw (có lúc tới vài giờ). Danh sách NO_RESTRICT_APP bị
 * bỏ qua trong sleep mode khi isCnModel, và không có cách tắt khi không có root.
 *
 * Cách giảm thiểu: định kỳ query 1 ContentProvider của GMS → Greezer thaw GMS (reason "provider"),
 * GMS phát hiện socket chết và reconnect → nhận hết tin nhắn đang chờ trên server.
 */
public class KeepAlive extends BroadcastReceiver {

    static final String ACTION = BuildConfig.APPLICATION_ID + ".KEEPALIVE";
    static final String PREF = "keepAlive";
    static final String PREF_INTERVAL = "kaIntervalMin";
    static final int[] INTERVAL_CHOICES_MIN = {5, 10, 15, 20};
    /** Đánh thức xong mà FCM vẫn chưa kết nối → lần sau sớm hơn. */
    static final long RETRY_MS = 3 * 60 * 1000L;

    private static final Uri GSERVICES = Uri.parse("content://com.google.android.gsf.gservices");
    /**
     * Giữ GMS thức đủ lâu để reconnect + nhận tin. Log đêm cho thấy Greezer freeze lại GMS ~5s sau
     * một lần thaw do provider → poke liên tục ~16s rồi đọc trạng thái FCM để xác nhận.
     */
    private static final long[] POKES_MS = {0, 4000, 8000, 12000, 16000};

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!enabled(ctx)) {
            cancel(ctx);
            return;
        }
        schedule(ctx);
        DozeTune.ensure(ctx);
        SleepGuard.ensure(ctx); // Nighttime mode bị bật lại → tắt (quyền của app)
        BroadcastCtrl.ensure(ctx); // nhẹ: 1 lần đọc setting; ghi lại nếu bị cloud/HyperOS đổi
        NoRestrictGuard.ensure(ctx); // PowerKeeper dựng lại danh sách sau khi khởi động → thêm lại
        if (!ACTION.equals(intent.getAction())) return; // boot / update: chỉ cần lên lịch
        if (isCharging(ctx)) return; // đang sạc → Greezer không freeze GMS (systemblack + plugged)
        android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
        if (pm != null && pm.isInteractive()) return; // màn hình bật → GMS đang được thaw, khỏi tốn pin

        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String err = null;
                for (int i = 0; i < POKES_MS.length; i++) {
                    if (i > 0) SystemClock.sleep(POKES_MS[i] - POKES_MS[i - 1]);
                    String e = poke(ctx.getContentResolver());
                    if (e != null) err = e;
                }
                // Xác nhận bằng dumpsys GcmService (quyền DUMP đã cấp, không cần Shizuku)
                boolean hasDump = ctx.checkSelfPermission(android.Manifest.permission.DUMP)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                FcmStatus st = FcmStatus.query(null, hasDump);
                boolean ok = st.state == FcmStatus.State.CONNECTED;
                // Chưa kết nối: giữ GMS thức thêm ~20s (tổng < 60s — giới hạn broadcast nền)
                for (int i = 0; i < 5 && !ok && hasDump; i++) {
                    SystemClock.sleep(4000);
                    poke(ctx.getContentResolver());
                    st = FcmStatus.query(null, hasDump);
                    ok = st.state == FcmStatus.State.CONNECTED;
                }
                if (!ok && hasDump) schedule(ctx, RETRY_MS); // hồi phục nhanh thay vì chờ đủ chu kỳ
                SharedPreferences p = ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
                p.edit()
                        .putLong("kaLast", System.currentTimeMillis())
                        .putString("kaErr", err)
                        .putString("kaFcm", st.label())
                        .putInt("kaRuns", p.getInt("kaRuns", 0) + 1)
                        .putInt("kaOk", p.getInt("kaOk", 0) + (ok ? 1 : 0))
                        .apply();
                FcmWidget.render(ctx, st);
                AlertCenter.checkFcm(ctx, st.raw);
                HealthCheck.runAndNotify(ctx, false);
            } finally {
                pr.finish();
            }
        }).start();
    }

    /** null = OK. */
    static String poke(ContentResolver cr) {
        try (Cursor c = cr.query(GSERVICES, null, null, new String[]{"gcm_heartbeat_ms"}, null)) {
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE).getBoolean(PREF, true);
    }

    static int intervalMin(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE).getInt(PREF_INTERVAL, 10);
    }

    static void schedule(Context ctx) {
        schedule(ctx, intervalMin(ctx) * 60 * 1000L);
    }

    static void schedule(Context ctx, long delayMs) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(ctx);
        long at = SystemClock.elapsedRealtime() + delayMs;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
    }

    static void cancel(Context ctx) {
        ctx.getSystemService(AlarmManager.class).cancel(pending(ctx));
    }

    static void apply(Context ctx) {
        if (enabled(ctx)) schedule(ctx);
        else cancel(ctx);
    }

    /** Mô tả ngắn lần đánh thức gần nhất. */
    static String lastRun(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
        long t = p.getLong("kaLast", 0);
        if (t == 0) return I18n.t("Chưa chạy (chỉ chạy khi dùng pin)");
        long min = (System.currentTimeMillis() - t) / 60000;
        String err = p.getString("kaErr", null);
        int runs = p.getInt("kaRuns", 0), ok = p.getInt("kaOk", 0);
        String ago = min < 60 ? min + I18n.t(" phút trước") : (min / 60) + I18n.t(" giờ trước");
        return ago + " • FCM " + (err == null ? p.getString("kaFcm", "?").toLowerCase() : I18n.t("lỗi"))
                + "\n" + ok + "/" + runs + I18n.t(" lần nối lại OK");
    }

    private static PendingIntent pending(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, KeepAlive.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean isCharging(Context ctx) {
        BatteryManager bm = ctx.getSystemService(BatteryManager.class);
        return bm != null && bm.isCharging();
    }
}
