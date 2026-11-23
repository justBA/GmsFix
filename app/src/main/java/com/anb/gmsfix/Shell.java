package com.anb.gmsfix;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Chạy shell script với quyền root (su) hoặc shell (Shizuku). */
final class Shell {

    /** DIRECT = chạy bằng uid của chính app (chỉ dùng cho lệnh cần quyền app đã được pm grant). */
    enum Mode { ROOT, SHIZUKU, DIRECT }

    static final class Result {
        final int code;
        final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }
    }

    private static volatile Boolean rootCache;

    private Shell() {}

    /** Thử su (có thể hiện prompt của Magisk/KernelSU) — chỉ gọi khi người dùng chủ động bấm. */
    static boolean probeRoot() {
        if (rootCache == null) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id -u"});
                if (!p.waitFor(15, TimeUnit.SECONDS)) {
                    p.destroy();
                    rootCache = false;
                } else {
                    rootCache = p.exitValue() == 0 && read(p.getInputStream()).trim().equals("0");
                }
            } catch (Exception e) {
                rootCache = false;
            }
        }
        return rootCache;
    }

    /** null = chưa thử. */
    static Boolean rootKnown() {
        return rootCache;
    }

    static boolean shizukuAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean shizukuGranted() {
        try {
            return !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Binder của Shizuku tới chậm vài trăm ms sau khi process khởi động (ShizukuProvider).
     * Gọi từ luồng nền trước khi cần Shizuku. Trả về true nếu Shizuku sẵn sàng và đã cấp quyền.
     */
    static boolean waitShizuku(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (shizukuAlive()) return shizukuGranted();
            if (System.currentTimeMillis() >= end) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    /** Chế độ khả dụng ngay (không xin quyền). probe=true thì thử su nếu chưa biết. */
    static Mode available(boolean probe) {
        if (probe ? probeRoot() : Boolean.TRUE.equals(rootCache)) return Mode.ROOT;
        if (shizukuAlive() && shizukuGranted()) return Mode.SHIZUKU;
        return null;
    }

    static Result exec(Mode mode, String script) throws Exception {
        Process p;
        if (mode == Mode.ROOT) {
            p = Runtime.getRuntime().exec(new String[]{"su"});
            try (OutputStream os = p.getOutputStream()) {
                os.write(("exec 2>&1\n" + script + "\nexit $?\n").getBytes(StandardCharsets.UTF_8));
            }
        } else if (mode == Mode.DIRECT) {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "exec 2>&1\n" + script});
            p.getOutputStream().close();
        } else {
            p = shizukuNewProcess(new String[]{"sh", "-c", "exec 2>&1\n" + script});
            p.getOutputStream().close();
        }
        String out = read(p.getInputStream());
        return new Result(p.waitFor(), out);
    }

    // Shizuku.newProcess là private từ API 13 nhưng vẫn hoạt động; gọi qua reflection.
    private static Process shizukuNewProcess(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, null, null);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
