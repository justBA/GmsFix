package com.anb.gmsfix;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Widget màn hình chính: trạng thái FCM + nút Fix ngay. */
public class FcmWidget extends AppWidgetProvider {

    private static final String ACTION_REFRESH = BuildConfig.APPLICATION_ID + ".WIDGET_REFRESH";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_REFRESH.equals(action) && !AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            super.onReceive(ctx, intent);
            return;
        }
        render(ctx, null);
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                boolean hasDump = ctx.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
                render(ctx, FcmStatus.query(Shell.available(false), hasDump));
            } finally {
                pr.finish();
            }
        }).start();
    }

    /** status null = đang đọc. Gọi được từ MainActivity để đồng bộ ngay sau khi đọc FCM. */
    static void render(Context ctx, FcmStatus status) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, FcmWidget.class));
        if (ids.length == 0) return;

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_fcm);
        if (status == null) {
            v.setTextViewText(R.id.wStatus, I18n.t("Đang đọc…"));
            v.setInt(R.id.wDot, "setColorFilter", ContextCompat.getColor(ctx, R.color.neutral));
        } else {
            v.setTextViewText(R.id.wStatus, status.label());
            v.setInt(R.id.wDot, "setColorFilter", ContextCompat.getColor(ctx, status.colorRes()));
            v.setTextViewText(R.id.wSub, status.summary);
            v.setTextViewText(R.id.wTime, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        v.setTextViewText(R.id.wFix, I18n.isEn() ? "⚡ Fix now" : "⚡ Fix ngay");
        v.setOnClickPendingIntent(R.id.wRefresh, PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, FcmWidget.class).setAction(ACTION_REFRESH), flags));
        v.setOnClickPendingIntent(R.id.wFix, PendingIntent.getActivity(ctx, 1,
                new Intent(ctx, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_FIX, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags));
        v.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(ctx, 2,
                new Intent(ctx, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags));
        mgr.updateAppWidget(ids, v);
    }
}
