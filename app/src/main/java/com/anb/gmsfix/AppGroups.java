package com.anb.gmsfix;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Phân nhóm app để hiển thị / bật tắt theo nhóm. */
final class AppGroups {

    enum Group {
        SYSTEM("Hệ thống / Google"),
        WEAR("Đồng hồ & thiết bị đeo"),
        BANK("Ngân hàng & ví"),
        CHAT("Chat & mạng xã hội"),
        MAIL("Email"),
        OTHER("Khác");

        final String title;

        Group(String title) {
            this.title = title;
        }
    }

    private static final Set<String> SYSTEM = set(
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.xiaomi.xmsf",
            "moe.shizuku.privileged.api", "com.tailscale.ipn");

    /** App đồng hồ: cần sống để nhận thông báo (notification listener) và giữ kết nối BLE. */
    private static final Set<String> WEAR = set(
            "com.huami.watch.hmwatchmanager", "com.xiaomi.wearable", "com.mi.health", "com.xiaomi.hm.health",
            "com.samsung.android.app.watchmanager", "com.google.android.apps.wear.companion",
            "com.garmin.android.apps.connectmobile", "com.huawei.health", "com.fitbit.FitbitMobile",
            "com.coros.coros", "com.suunto.android");

    private static final Set<String> BANK = set(
            "com.mservice.momotransfer", "vn.com.vng.zalopay", "com.bplus.vtpay", "com.vnpay.bidv",
            "mobile.acb.com.vn", "com.vietinbank.ipay", "vn.com.techcombank.bb.app", "com.vsmart.pay",
            "vn.finhay.finhay", "com.tikop", "com.mbmobile", "com.VCB", "com.vnpay.vpbankonline",
            "com.tpb.mb.gprsandroid", "vn.shb.mbanking", "com.sacombank.ewallet", "vn.com.msb.smartBanking",
            "com.vib.myvib2", "com.ocb.omniextra", "vn.hdbank.mobilebanking", "com.lpb.lienviet24h",
            "com.eximbank.ebmobile", "vn.cake.app", "com.timo.timoandroid", "vn.vietcredit",
            "com.shopeepay.vn", "vn.vnpay.vnpaywallet", "com.vnpay.agribank3g", "com.seabank.mobile",
            "com.eg.android.AlipayGphone", "com.mipay.wallet", "com.google.android.apps.walletnfcrel",
            "com.binance.dev", "com.bitget.exchange", "pro.huobi", "com.ascendex.exchange");

    private static final Set<String> CHAT = set(
            "com.zing.zalo", "org.telegram.messenger", "com.facebook.orca", "com.facebook.katana",
            "com.kchat.app", "com.discord", "com.instagram.android", "com.instagram.barcelona",
            "com.linkedin.android", "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "jp.naver.line.android",
            "com.skype.raider", "com.microsoft.teams", "com.Slack", "us.zoom.videomeetings", "com.tencent.mm",
            "com.snapchat.android", "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.android.mms",
            "com.google.android.apps.dynamite");

    private static final Set<String> MAIL = set(
            "com.google.android.gm", "com.android.email", "com.microsoft.office.outlook",
            "ch.protonmail.android", "com.yahoo.mobile.client.android.mail");

    private static final Pattern BANK_HINT = Pattern.compile(
            "(?i)(bank|banking|pay|wallet|momo|vnpay|ebank|mbank|finance|invest)");
    private static final Pattern CHAT_HINT = Pattern.compile("(?i)(messenger|chat|talk|\\bim\\b)");
    private static final Pattern MAIL_HINT = Pattern.compile("(?i)(mail)");

    private AppGroups() {}

    static Group of(PackageManager pm, String pkg) {
        String label = "";
        ApplicationInfo ai = null;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
            label = String.valueOf(ai.loadLabel(pm));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return of(pkg, label, ai);
    }

    static Group of(String pkg, String label, ApplicationInfo ai) {
        if (SYSTEM.contains(pkg)) return Group.SYSTEM;
        if (WEAR.contains(pkg)) return Group.WEAR;
        if (BANK.contains(pkg)) return Group.BANK;
        if (CHAT.contains(pkg)) return Group.CHAT;
        if (MAIL.contains(pkg)) return Group.MAIL;

        String hay = (pkg + " " + label).toLowerCase(Locale.ROOT);
        if (MAIL_HINT.matcher(hay).find()) return Group.MAIL;
        if (BANK_HINT.matcher(hay).find()) return Group.BANK;
        if (CHAT_HINT.matcher(hay).find()) return Group.CHAT;
        if (ai != null && Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_SOCIAL) {
            return Group.CHAT;
        }
        if (pkg.startsWith("com.google.android.")) return Group.SYSTEM;
        return Group.OTHER;
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }
}
