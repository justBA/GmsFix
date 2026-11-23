package com.anb.gmsfix;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sửa danh sách cứng của Greezer bằng theme MIUI.
 *
 * Greezer (system_server) đọc các mảng trong framework-ext-res (package "android.miui") bằng
 * Resources.getStringArray(). Trên MIUI/HyperOS, MiuiResources.getStringArray() lấy giá trị từ theme
 * đang áp dụng trước (MiuiResourcesImpl.getThemeStringArray ← ThemeValues ← theme_values.xml của
 * component "framework-miui-res"). Theme chỉ chứa theme_values.xml → không đụng giao diện.
 *
 *  - greezer_power_black_list: bỏ GMS (đây là "systemblack" khiến GMS bị freeze khi dùng pin)
 *  - greezer_no_restrict_ctrl_white_list: thêm app → không bị freeze trong sleep mode (ROM CN)
 *  - greezer_power_white_list: thêm app → "whiteapp"
 */
final class ThemeFix {

    static final String FILE_NAME = "GmsFix-FCM.mtz";
    /** Engine theme (ghi /data/system/theme, uid system_theme). Trên HyperOS 4 không còn icon. */
    static final String THEME_PKG = "com.android.thememanager";
    /** App Theme người dùng thấy (icon "Theme") trên HyperOS 4. */
    static final String THEME_STORE_PKG = "com.miui.themestore";
    static final String[] THEME_PKGS = {THEME_STORE_PKG, THEME_PKG};
    static final String MIUI_RES_PKG = "android.miui";

    static final String BLACK = "greezer_power_black_list";
    static final String NO_RESTRICT_WHITE = "greezer_no_restrict_ctrl_white_list";
    static final String WHITE = "greezer_power_white_list";

    private ThemeFix() {}

    /** Đọc mảng hiện tại (đã qua theme nếu có) từ resource hệ thống. */
    static List<String> array(Context ctx, String name) {
        Resources r = ctx.getResources();
        int id = r.getIdentifier(name, "array", MIUI_RES_PKG);
        if (id == 0) return null;
        try {
            return new ArrayList<>(Arrays.asList(r.getStringArray(id)));
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    /** true nếu ROM có các mảng Greezer (HyperOS / MIUI có Greezer). */
    static boolean supported(Context ctx) {
        return array(ctx, BLACK) != null && array(ctx, NO_RESTRICT_WHITE) != null;
    }

    /** Theme đã có hiệu lực trong process của app (GMS không còn trong danh sách đen). */
    static boolean activeInApp(Context ctx) {
        List<String> b = array(ctx, BLACK);
        return b != null && !b.contains(Steps.GMS);
    }

    static String themeValuesXml(Context ctx, Collection<String> pkgs) {
        Set<String> black = new LinkedHashSet<>(orEmpty(array(ctx, BLACK)));
        black.remove(Steps.GMS);
        black.removeAll(pkgs);

        Set<String> noRestrict = new LinkedHashSet<>(orEmpty(array(ctx, NO_RESTRICT_WHITE)));
        noRestrict.add(Steps.GMS);
        noRestrict.addAll(pkgs);

        Set<String> white = new LinkedHashSet<>(orEmpty(array(ctx, WHITE)));
        white.add(Steps.GMS);
        white.addAll(pkgs);

        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<MIUI_Theme_Values>\n");
        appendArray(s, BLACK, black);
        appendArray(s, NO_RESTRICT_WHITE, noRestrict);
        appendArray(s, WHITE, white);
        return s.append("</MIUI_Theme_Values>\n").toString();
    }

    /**
     * @param screenshot ảnh chụp màn hình GMS Fix dùng làm preview (null = không có preview).
     *
     * MTZ Studio 5.7.5 chỉ hiện theme import có ít nhất hai nhóm giao diện trong Library;
     * framework-miui-res không được tính. Dùng icons (icon gốc của app) và com.miui.home
     * (theme_values.xml rỗng, không ghi đè resource launcher) để đủ hai nhóm.
     */
    static byte[] buildMtz(Context ctx, Collection<String> pkgs, android.graphics.Bitmap screenshot) throws Exception {
        byte[] values = themeValuesXml(ctx, pkgs).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream icons = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(icons)) {
            put(z, ctx.getPackageName() + ".png", png(appIcon(ctx, 192)));
        }

        // Component framework-miui-res là 1 zip lồng bên trong mtz
        byte[] framework = themeComponent(values);
        byte[] launcher = themeComponent(("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<MIUI_Theme_Values/>\n").getBytes(StandardCharsets.UTF_8));

        String desc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<theme>\n"
                + "    <version>" + BuildConfig.VERSION_NAME + "</version>\n"
                + "    <uiVersion>16</uiVersion>\n"
                + "    <author>GMS Fix</author>\n"
                + "    <designer>GMS Fix</designer>\n"
                + "    <title>GMS Fix – FCM</title>\n"
                + "    <description>Không đổi giao diện. Chỉ sửa danh sách đóng băng của Greezer để "
                + "Google Play services (FCM) và các app đã chọn không bị đóng băng.</description>\n"
                + "</theme>\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "description.xml", desc.getBytes(StandardCharsets.UTF_8));
            put(z, "framework-miui-res", framework);
            put(z, "icons", icons.toByteArray());
            put(z, "com.miui.home", launcher);
            if (screenshot != null) {
                byte[] big = jpg(scale(screenshot, 1080));
                byte[] small = png(scale(screenshot, 360));
                for (String code : new String[]{"launcher", "lockscreen"}) {
                    put(z, "preview/preview_" + code + "_0.jpg", big);
                    put(z, "preview/preview_" + code + "_small_0.png", small);
                }
                put(z, "preview/preview_cover_0.jpg", big);
            }
        }
        return out.toByteArray();
    }

    private static android.graphics.Bitmap appIcon(Context ctx, int size) {
        android.graphics.drawable.Drawable d = ctx.getApplicationInfo().loadIcon(ctx.getPackageManager());
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private static android.graphics.Bitmap scale(android.graphics.Bitmap src, int width) {
        int h = Math.round(src.getHeight() * (width / (float) src.getWidth()));
        return android.graphics.Bitmap.createScaledBitmap(src, width, h, true);
    }

    private static byte[] png(android.graphics.Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    private static byte[] jpg(android.graphics.Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, o);
        return o.toByteArray();
    }

    /** Lưu vào Download/, ghi đè bản cũ. Trả về đường dẫn hiển thị. */
    static String saveToDownloads(Context ctx, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            cr.delete(col, MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{FILE_NAME});
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(col, v);
            if (uri == null) throw new IllegalStateException("Không tạo được file trong Download");
            try (OutputStream os = cr.openOutputStream(uri)) {
                os.write(data);
            }
        } else {
            File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(data);
            }
        }
        return "Download/" + FILE_NAME;
    }

    /**
     * Greezer (system_server) có còn coi GMS là systemblack không. null = không đọc được.
     * Đọc qua dumpsys greezer bằng quyền DUMP của app.
     */
    static Boolean gmsSystemBlack() {
        try {
            String out = Shell.exec(Shell.Mode.DIRECT,
                    "dumpsys greezer | grep -A3 '\\[com.google.android.gms\\] uid' | grep -m1 latestNoControl").out;
            if (!out.contains("reason=")) return null;
            return out.contains("systemblack");
        } catch (Exception e) {
            return null;
        }
    }

    /** App Theme còn mở được không (Theme Store trên HyperOS 4, hoặc thememanager trên bản cũ). */
    static boolean themeAppInstalled(Context ctx) {
        return launchIntent(ctx) != null;
    }

    static android.content.Intent launchIntent(Context ctx) {
        for (String p : THEME_PKGS) {
            android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    private static void appendArray(StringBuilder s, String name, Collection<String> items) {
        s.append("    <string-array name=\"").append(name).append("\" package=\"").append(MIUI_RES_PKG).append("\">\n");
        for (String i : items) {
            if (i.matches("[A-Za-z0-9_.]+")) s.append("        <item>").append(i).append("</item>\n");
        }
        s.append("    </string-array>\n");
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    /** Component MTZ là ZIP không có phần mở rộng, chứa resource XML. */
    private static byte[] themeComponent(byte[] values) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "theme_values.xml", values);
        }
        return out.toByteArray();
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? new ArrayList<>() : l;
    }
}
