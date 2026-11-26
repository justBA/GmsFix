package com.anb.gmsfix;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Đọc trạng thái kết nối FCM (mtalk.google.com) từ dumpsys của GcmService. */
final class FcmStatus {

    enum State { CONNECTED, DISCONNECTED, UNKNOWN, NO_SERVICE, NO_ACCESS }

    static final String CMD = "dumpsys activity service com.google.android.gms/.gcm.GcmService";

    private static final Pattern CONNECTED = Pattern.compile(
            "(?i)(connected\\s*[=:]\\s*true|\\bconnected to\\b|state\\s*[=:]\\s*connected\\b|\\bstatus\\s*[=:]\\s*connected\\b)");
    private static final Pattern DISCONNECTED = Pattern.compile(
            "(?i)(connected\\s*[=:]\\s*false|not connected|state\\s*[=:]\\s*(disconnected|connecting|idle)|\\bstatus\\s*[=:]\\s*disconnected)");
    private static final Pattern RELEVANT = Pattern.compile("(?i)(connect|mtalk|heartbeat|5228|5229|5230|443)");
    /** GMS không giao được tin vì app đang ở trạng thái "stopped" (bị force-stop / dọn RAM). */
    private static final Pattern STOPPED = Pattern.compile("Failed to broadcast to stopped app ([\\w.]+)");
    private static final Pattern HOST = Pattern.compile("(?i)(mtalk[\\w.-]*google\\.com[/\\d.:]*)");

    final State state;
    final String summary;
    final String detail;
    /** App bị mất tin FCM do đang stopped (từ log gần đây của GcmService). */
    final Set<String> stoppedApps;

    /** Output gốc của dumpsys GcmService (để AlertCenter phân tích), "" nếu không đọc được. */
    String raw = "";

    /** Thống kê 24h từ "Enhanced GcmService Diagnostics" (-1 = GMS không cung cấp). */
    int conns24 = -1, drops24 = -1, delayed24 = -1, maxLatSec24 = -1;
    /** Trong các sự cố/tin trễ GMS còn giữ (tối đa 10 mục mỗi loại, 24 giờ gần nhất): rớt > 1 phút, tin cần chú ý. */
    int longDrops = -1, seriousDelays = -1;

    private static final Pattern INCIDENT = Pattern.compile(
            "\\[INCIDENT #\\d+] (\\d{2}-\\d{2} \\d{2}:\\d{2}):\\d{2}\\.\\d+ UTC \\(Duration: (\\d+)s");
    private static final Pattern DELAY = Pattern.compile(
            "(\\d{2}-\\d{2} \\d{2}:\\d{2}):\\d{2}\\.\\d+ \\[MSG_DELAYED] id=\\S+ pkg=\\S+ pri=(\\w+)\\s+Total Delay: (\\d+)ms");

    private static final Pattern HOURLY = Pattern.compile(
            "(?m)^\\s*\\d{2}-\\d{2} \\d{2}:00 \\|\\s*(\\d+) \\|\\s*(\\d+) \\|\\s*\\S+\\s*\\|\\s*\\d+ \\|\\s*(\\d+) \\|\\s*(\\d+)s \\|");

    /** Dòng tóm tắt 24h, null nếu không có dữ liệu. */
    String stats24() {
        if (conns24 < 0) return null;
        // GMS đếm cả lần đổi Wi-Fi↔4G (rớt 0 giây) và tin ưu tiên cao trễ chỉ 1–2 giây → ghi rõ phần đáng chú ý
        if (longDrops < 0) return I18n.t("Rớt ") + drops24 + I18n.t(" • trễ ") + delayed24;
        return I18n.t("Rớt ") + drops24 + " (" + longDrops + I18n.t(" lâu) • trễ ") + delayed24 + " (" + seriousDelays + I18n.t(" đáng lo)");
    }

    /** "11-24 02:59" (UTC) → epoch ms; 0 nếu lỗi. */
    static long utcMillis(String mmddhhmm) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + "-" + mmddhhmm);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    String label() {
        switch (state) {
            case CONNECTED: return I18n.t("Đã kết nối");
            case DISCONNECTED: return I18n.t("Mất kết nối");
            case NO_SERVICE: return I18n.t("Không chạy");
            case NO_ACCESS: return I18n.t("Không đọc được");
            default: return I18n.t("Không rõ");
        }
    }

    int colorRes() {
        switch (state) {
            case CONNECTED: return R.color.ok;
            case DISCONNECTED:
            case NO_SERVICE: return R.color.err;
            case NO_ACCESS: return R.color.neutral;
            default: return R.color.warn;
        }
    }

    private FcmStatus(State state, String summary, String detail) {
        this(state, summary, detail, new LinkedHashSet<>());
    }

    private FcmStatus(State state, String summary, String detail, Set<String> stoppedApps) {
        this.state = state;
        this.summary = summary;
        this.detail = detail;
        this.stoppedApps = stoppedApps;
    }

    /** Đọc qua root/Shizuku nếu có; không thì tự chạy dumpsys bằng quyền DUMP đã được cấp. */
    static FcmStatus query(Shell.Mode mode, boolean hasDump) {
        if (mode == null) {
            if (!hasDump) return new FcmStatus(State.NO_ACCESS, I18n.t("Chạy \"Fix ngay\" 1 lần để đọc được mà không cần Shizuku"), "");
            mode = Shell.Mode.DIRECT;
        }
        String out;
        try {
            out = Shell.exec(mode, CMD).out;
        } catch (Exception e) {
            return new FcmStatus(State.NO_ACCESS, e.getMessage(), "");
        }
        if (out.contains("missing android.permission.PACKAGE_USAGE_STATS")) {
            return new FcmStatus(State.NO_ACCESS, I18n.t("Cần bấm \"Fix ngay\" 1 lần khi có Shizuku để cấp quyền"), out.trim());
        }
        if (out.contains("app ops") || out.contains("PACKAGE_USAGE_STATS")) {
            return new FcmStatus(State.NO_ACCESS, I18n.t("Cần bật Usage access — bấm để mở"), out.trim());
        }
        if (out.contains("Permission Denial") || out.contains("not allowed")) {
            return new FcmStatus(State.NO_ACCESS, I18n.t("Không có quyền đọc (DUMP)"), out.trim());
        }
        if (out.trim().isEmpty() || out.contains("No services match")) {
            return new FcmStatus(State.NO_SERVICE, I18n.t("GcmService không chạy"), out.trim());
        }

        List<String> keep = new ArrayList<>();
        State state = State.UNKNOWN;
        for (String line : out.split("\n")) {
            if (!RELEVANT.matcher(line).find()) continue;
            if (keep.size() < 40) keep.add(line.trim());
            if (state == State.UNKNOWN) {
                if (DISCONNECTED.matcher(line).find()) state = State.DISCONNECTED;
                else if (CONNECTED.matcher(line).find()) state = State.CONNECTED;
            }
        }
        String detail = keep.isEmpty() ? out.trim() : String.join("\n", keep);

        Set<String> stopped = new LinkedHashSet<>();
        Matcher sm = STOPPED.matcher(out);
        while (sm.find()) stopped.add(sm.group(1));

        String summary;
        Matcher h = HOST.matcher(out);
        if (h.find()) summary = h.group(1);
        else if (state == State.UNKNOWN) summary = I18n.t("Không đọc được trạng thái — bấm để xem chi tiết");
        else summary = "";
        if (!stopped.isEmpty()) {
            summary = (summary.isEmpty() ? "" : summary + " • ") + stopped.size() + I18n.t(" app bị mất tin (stopped)");
        }
        FcmStatus st = new FcmStatus(state, summary, detail, stopped);
        st.raw = out;
        Matcher hm = HOURLY.matcher(out);
        while (hm.find()) {
            if (st.conns24 < 0) st.conns24 = st.drops24 = st.delayed24 = st.maxLatSec24 = 0;
            st.conns24 += Integer.parseInt(hm.group(1));
            st.drops24 += Integer.parseInt(hm.group(2));
            st.delayed24 += Integer.parseInt(hm.group(3));
            st.maxLatSec24 = Math.max(st.maxLatSec24, Integer.parseInt(hm.group(4)));
        }
        if (st.conns24 >= 0) {
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            st.longDrops = 0;
            Matcher im = INCIDENT.matcher(out);
            while (im.find()) {
                if (utcMillis(im.group(1)) >= cutoff && Long.parseLong(im.group(2)) > 60) st.longDrops++;
            }
            st.seriousDelays = 0;
            Matcher dm = DELAY.matcher(out);
            while (dm.find()) {
                long ms = Long.parseLong(dm.group(3));
                boolean serious = "HIGH".equals(dm.group(2)) ? ms > AlertCenter.DELAY_THRESHOLD_MS
                        : ms > AlertCenter.NORMAL_DELAY_THRESHOLD_MS;
                if (serious && utcMillis(dm.group(1)) >= cutoff) st.seriousDelays++;
            }
        }
        return st;
    }
}
