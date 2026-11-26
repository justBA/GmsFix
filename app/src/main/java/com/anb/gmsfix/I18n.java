package com.anb.gmsfix;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Ngôn ngữ của app: Tiếng Việt / English / theo hệ thống.
 *
 * Chuỗi trong layout dùng resource (values = English, values-vi = Tiếng Việt) theo locale của app
 * (AppCompatDelegate.setApplicationLocales; Android 13+ cũng hiện trong Cài đặt → Ngôn ngữ của ứng dụng).
 * Chuỗi trong mã Java viết bằng tiếng Việt và đi qua {@link #t}, tra bản dịch trong {@link I18nEn}.
 * Đổi ngôn ngữ thì khởi động lại tiến trình để mọi hằng số/chuỗi đã dựng đều theo ngôn ngữ mới.
 */
final class I18n {

    static final String PREF = "lang"; // "" = theo hệ thống, "vi", "en"
    private static volatile boolean en;

    private I18n() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("gmsfix", Context.MODE_PRIVATE);
    }

    /** Lựa chọn của người dùng: "", "vi" hoặc "en". */
    static String chosen(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                LocaleList l = ctx.getSystemService(android.app.LocaleManager.class).getApplicationLocales();
                return l.isEmpty() ? "" : l.get(0).getLanguage();
            } catch (Throwable ignored) {
            }
        }
        return prefs(ctx).getString(PREF, "");
    }

    /** Ngôn ngữ thực dùng: "vi" hoặc "en" (máy không phải tiếng Việt → English). */
    static String effective(Context ctx) {
        String s = chosen(ctx);
        if (s.isEmpty()) s = Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage();
        return "vi".equals(s) ? "vi" : "en";
    }

    static void init(Context ctx) {
        en = "en".equals(effective(ctx));
        if (Build.VERSION.SDK_INT < 33) { // Android 13+ hệ thống tự nhớ
            String c = prefs(ctx).getString(PREF, "");
            AppCompatDelegate.setApplicationLocales(c.isEmpty()
                    ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(c));
        }
    }

    static boolean isEn() {
        return en;
    }

    /** Dịch chuỗi tiếng Việt trong mã nguồn; không có bản dịch thì giữ nguyên. */
    static String t(String vi) {
        if (!en) return vi;
        String s = I18nEn.MAP.get(vi);
        return s != null ? s : vi;
    }

    /** Activity gọi trong onCreate: ngôn ngữ bị đổi từ Cài đặt hệ thống trong lúc app chạy → khởi động lại. */
    static boolean restartIfChanged(Activity a) {
        if ("en".equals(effective(a)) == en) return false;
        restart(a);
        return true;
    }

    static void set(Activity a, String lang) {
        prefs(a).edit().putString(PREF, lang).commit();
        AppCompatDelegate.setApplicationLocales(lang.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(lang));
        restart(a);
    }

    private static void restart(Activity a) {
        Intent i = new Intent(a, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finishAffinity();
        Runtime.getRuntime().exit(0);
    }
}
