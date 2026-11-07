package com.miku.snow;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root 通道：设备已 root 时，用 `su -c` 直接以 uid=0 执行命令。
 *
 * 用途和 Shizuku 通道一样——给本应用打开 SYSTEM_ALERT_WINDOW（系统悬浮窗权限），
 * 只是身份从 shell(2000) 换成了 root(0)。所有原始输出都会保留，方便排查。
 */
public final class RootHelper {

    private static String lastLog = "";

    private RootHelper() {
    }

    /** 设备上是否真的有可用的 su（执行 `id` 且拿到 uid=0）。 */
    public static boolean available() {
        String out = exec("id");
        return out != null && out.contains("uid=0");
    }

    /** 以 root 执行一条命令，返回 stdout+stderr；没有 su / 被拒绝时返回 null。 */
    public static String exec(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 用 root 给本应用打开悬浮窗权限：先 `appops get` 看现状，再 set，
     * 最后回读确认。返回日志文本。
     */
    public static String grantOverlay(Context context) {
        String pkg = context.getPackageName();
        StringBuilder log = new StringBuilder();

        String id = exec("id");
        log.append("$ id\n  ").append(id == null ? "(没有 su，或被拒绝)" : id).append('\n');
        if (id == null || !id.contains("uid=0")) {
            lastLog = log.toString();
            return lastLog;
        }

        String before = exec("appops get " + pkg + " SYSTEM_ALERT_WINDOW");
        log.append("$ appops get ").append(pkg).append(" SYSTEM_ALERT_WINDOW\n  ")
                .append(before == null ? "(无输出)" : before).append('\n');

        String[] cmds = {
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "appops set --uid " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
        };
        for (String c : cmds) {
            String out = exec(c);
            log.append("$ ").append(c).append("\n  ")
                    .append(out == null ? "(执行失败)" : (out.length() == 0 ? "(无输出)" : out))
                    .append('\n');
        }

        String after = exec("appops get " + pkg + " SYSTEM_ALERT_WINDOW");
        log.append("$ appops get ").append(pkg).append(" SYSTEM_ALERT_WINDOW\n  ")
                .append(after == null ? "(无输出)" : after).append('\n');

        lastLog = log.toString();
        return lastLog;
    }

    public static String lastLog() {
        return lastLog;
    }
}
