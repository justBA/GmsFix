package com.anb.gmsfix;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sau khi khởi động lại, Greezer reset trạng thái runtime (LM allowlist, IM GMS). Các lệnh này chỉ cần
 * quyền DUMP (đã pm grant) → tự áp dụng lại bằng uid của app, không cần Shizuku/Developer.
 * Các thiết lập khác (MILLET_NO_RESTRICT_APP, Doze whitelist, appops) vẫn còn sau reboot.
 * Chỉ khi không tự làm được mới nhắc người dùng.
 */
public class BootReceiver extends BroadcastReceiver {

    static final String CHANNEL = "reapply";
    static final int NOTIF_ID = 1;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                boolean ok = reapplyRuntime(ctx);
                BroadcastCtrl.ensure(ctx);
                DozeTune.ensure(ctx);
                SleepGuard.ensure(ctx);
                ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE).edit()
                        .putLong("bootFixAt", System.currentTimeMillis())
                        .putBoolean("bootFixOk", ok)
                        .apply();
                if (!ok) remind(ctx);
                HealthCheck.runAndNotify(ctx, true);
            } finally {
                pr.finish();
            }
        }).start();
    }

    /** Áp dụng lại phần Greezer bị reset khi reboot, bằng quyền DUMP. */
    static boolean reapplyRuntime(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) return false;
        SharedPreferences p = ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
        Set<String> pkgs = new LinkedHashSet<>(p.getStringSet("pkgs", new LinkedHashSet<>()));
        pkgs.removeAll(p.getStringSet("pkgsOff", new LinkedHashSet<>()));
        pkgs.add(ctx.getPackageName());
        StringBuilder sc = new StringBuilder("rc=0\n");
        if (pkgs.contains(Steps.GMS)) sc.append("dumpsys greezer IM GMS disable >/dev/null 2>&1 || rc=1\n");
        for (String pkg : pkgs) {
            if (!pkg.matches("[A-Za-z0-9_.]+")) continue;
            sc.append("dumpsys greezer LM add ").append(pkg).append(" >/dev/null 2>&1 || rc=1\n");
        }
        sc.append("exit $rc");
        try {
            return Shell.exec(Shell.Mode.DIRECT, sc.toString()).code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void remind(Context ctx) {
        if (!ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE).getBoolean("remind", true)) return;
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Nhắc fix lại",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_FIX, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle("Máy vừa khởi động lại — chưa tự fix được")
                .setContentText("Bấm để fix lại GMS / FCM (Greezer đã bị reset)")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }
}
