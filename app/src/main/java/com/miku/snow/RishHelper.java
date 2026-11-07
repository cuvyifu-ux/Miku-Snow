package com.miku.snow;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;

/**
 * rish 通道：直接用 Shizuku 官方那套 rish + rish_shizuku.dex，在应用私有目录里
 * 用 app_process 起一个 shell，走 Shizuku 的 user service 拿 shell 身份。
 *
 * 和 Shizuku 客户端 API 的区别：它不依赖 binder 有没有投递到本进程的 provider，
 * 由 rish 自己这一支 app_process 去连 Shizuku，第一次连会由 Shizuku 弹授权框。
 */
public final class RishHelper {

    private static String lastLog = "";
    private static boolean prepared = false;
    private static volatile boolean shellReady = false;
    private static volatile boolean checked = false;

    /** 有没有 shell（uid 2000）身份可用；结果缓存，避免每次点开关都跑一遍 rish */
    public static boolean isShellReady() {
        return shellReady;
    }

    public static boolean checkShell(Context c) {
        if (checked && shellReady) return true;   // 只缓存「成功」，失败要允许重试
        String out = exec(c, "id -u");
        checked = true;
        shellReady = out != null && out.trim().contains("2000");
        return shellReady;
    }

    private RishHelper() {
    }

    public static String lastLog() {
        return lastLog;
    }

    /** 界面上那一行状态 */
    public static String state(Context c) {
        String out = exec(c, "id -u");
        if (out == null) return "未就绪";
        out = out.trim();
        if (out.contains("2000")) return "可用（shell uid 2000）";
        if (out.contains("0")) return "可用（root uid 0）";
        if (out.contains("Cannot find") || out.contains("app_process")) return "没跑起来";
        return out.isEmpty() ? "未就绪" : out;
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "rish");
    }

    public static synchronized boolean prepare(Context c) {
        File dir = dir(c);
        if (!dir.exists() && !dir.mkdirs()) {
            lastLog = "无法创建 rish 目录";
            return false;
        }
        File script = new File(dir, "rish");
        File dex = new File(dir, "rish_shizuku.dex");
        if (prepared && script.exists() && dex.exists()) return true;
        try {
            if (!dex.exists() || dex.length() != 59672) {
                dex.delete();
                copy(c, "rish_shizuku.dex", dex);
            }
            // 上次已经 chmod 400，先放开写权限再覆盖
            script.setWritable(true, true);
            script.delete();
            copy(c, "rish", script);

            // 把脚本里的 PKG 换成真正包名
            String text = read(script);
            if (text == null) {
                lastLog = "rish 脚本读取失败";
                return false;
            }
            text = text.replace("\"PKG\"", "\"" + c.getPackageName() + "\"");
            write(script, text);

            script.setReadable(true, true);
            script.setExecutable(true, true);
            script.setWritable(false, true);

            // Android 14+：app_process 不允许加载「可写」的 dex
            boolean ok = dex.setReadable(true, true);
            ok &= dex.setWritable(false, true);
            prepared = true;
            lastLog = "rish 已就绪：" + script.getAbsolutePath();
            return true;
        } catch (Throwable t) {
            lastLog = "rish 准备失败：" + t;
            return false;
        }
    }

    /** 执行一条命令，返回 stdout+stderr；失败返回 null。 */
    public static String exec(Context c, String cmd) {
        if (!prepare(c)) return null;
        File script = new File(dir(c), "rish");
        ProcessBuilder pb;
        if (new File("/system/bin/sh").exists()) {
            pb = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath(), "-c", cmd);
        } else {
            pb = new ProcessBuilder("sh", script.getAbsolutePath(), "-c", cmd);
        }
        pb.directory(dir(c));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("RISH_APPLICATION_ID", c.getPackageName());
        env.put("HOME", c.getFilesDir().getAbsolutePath());
        try {
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            // rish 可能在 Shizuku 那边等授权，绝不能无限等
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                lastLog = "rish 超时（20 秒没返回）";
                return null;
            }
            lastLog = sb.toString().trim();
            return lastLog;
        } catch (Throwable t) {
            lastLog = "rish 执行失败：" + t;
            return null;
        }
    }

    /** rish 拿 shell 身份给本应用开悬浮窗。 */
    public static boolean grantOverlay(Context c) {
        String pkg = c.getPackageName();
        String out = exec(c, "id; appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                + "settings put global hidden_api_policy 1; "
                + "appops get " + pkg + " SYSTEM_ALERT_WINDOW");
        return out != null;
    }

    /** 用 shell 身份拉起画雪花的子进程（它自己建受信任覆盖层） */
    public static String startShellSnow(Context c, int w, int h, String text, int count,
                                        float speed, float scale,
                                        boolean spin, boolean flip, boolean tilt) {
        if (!prepare(c)) return null;
        String apk = c.getApplicationInfo().sourceDir;
        String safeText = text == null ? "" : text.replace("\"", "");
        String cmd = "pkill -f miku_snow >/dev/null 2>&1; "
                + "nohup app_process -Djava.class.path=" + apk
                + " /system/bin --nice-name=miku_snow com.miku.snow.ShellOverlay "
                + w + " " + h + " \"" + safeText + "\" " + count + " " + speed + " " + scale + " "
                + (spin ? 1 : 0) + " " + (flip ? 1 : 0) + " " + (tilt ? 1 : 0)
                + " >/dev/null 2>&1 &";
        return exec(c, cmd);
    }

    public static String stopShellSnow(Context c) {
        return exec(c, "pkill -f miku_snow >/dev/null 2>&1; echo done");
    }

    public static String shellLog(Context c) {
        String out = exec(c, "cat /data/local/tmp/miku_shell.log 2>/dev/null | tail -20");
        return out == null ? "" : out;
    }

    private static void copy(Context c, String name, File dst) throws Exception {
        InputStream in = c.getAssets().open(name);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
    }

    private static String read(File f) {
        try {
            InputStream in = new java.io.FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            return new String(data, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(File f, String text) throws Exception {
        OutputStream out = new FileOutputStream(f);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }
}
