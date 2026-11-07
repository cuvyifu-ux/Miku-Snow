package com.miku.snow;

import android.content.Context;
import android.content.SharedPreferences;

/** 参数持久化：文字与所有滑杆/开关都自动保存，下次打开原样恢复。 */
public final class Prefs {
    private static final String NAME = "snow_prefs";

    /** 默认文字（可在 App 里改，改完自动保存）。 */
    public static final String DEFAULT_TEXT = "MIKU";
    public static final int MAX_TEXT = 12;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------------- 文字 ----------------
    public static String text(Context c) {
        return sanitize(sp(c).getString("text", DEFAULT_TEXT));
    }

    public static void setText(Context c, String v) {
        sp(c).edit().putString("text", sanitize(v)).apply();
    }

    /** 去掉换行/控制字符，最多保留 MAX_TEXT 个字。 */
    public static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7f) continue;
            if (n >= MAX_TEXT) break;
            b.append(ch);
            n++;
        }
        return b.toString();
    }

    // ---------------- 数量 ----------------
    public static int count(Context c) {
        return Math.max(20, Math.min(110, sp(c).getInt("count", 44)));
    }

    public static void setCount(Context c, int v) {
        sp(c).edit().putInt("count", Math.max(20, Math.min(110, v))).apply();
    }

    // ---------------- 速度 ----------------
    public static float speed(Context c) {
        return speedRaw(c) / 100f;
    }

    public static int speedRaw(Context c) {
        return Math.max(60, Math.min(220, sp(c).getInt("speed", 100)));
    }

    public static void setSpeedRaw(Context c, int v) {
        sp(c).edit().putInt("speed", Math.max(60, Math.min(220, v))).apply();
    }

    // ---------------- 大小 ----------------
    public static float size(Context c) {
        return sizeRaw(c) / 100f;
    }

    public static int sizeRaw(Context c) {
        return Math.max(60, Math.min(220, sp(c).getInt("size", 100)));
    }

    public static void setSizeRaw(Context c, int v) {
        sp(c).edit().putInt("size", Math.max(60, Math.min(220, v))).apply();
    }

    // ---------------- 动画开关 ----------------
    public static boolean spin(Context c) {
        return sp(c).getBoolean("spin", true);
    }

    public static void setSpin(Context c, boolean v) {
        sp(c).edit().putBoolean("spin", v).apply();
    }

    public static boolean flip(Context c) {
        return sp(c).getBoolean("flip", true);
    }

    public static void setFlip(Context c, boolean v) {
        sp(c).edit().putBoolean("flip", v).apply();
    }

    public static boolean tilt(Context c) {
        return sp(c).getBoolean("tilt", true);
    }

    public static void setTilt(Context c, boolean v) {
        sp(c).edit().putBoolean("tilt", v).apply();
    }

    // ---------------- 图片 ----------------
    public static boolean useImages(Context c) {
        return sp(c).getBoolean("use_images", true);
    }

    public static void setUseImages(Context c, boolean v) {
        sp(c).edit().putBoolean("use_images", v).apply();
    }

    public static boolean onlyImages(Context c) {
        return sp(c).getBoolean("only_images", false);
    }

    public static void setOnlyImages(Context c, boolean v) {
        sp(c).edit().putBoolean("only_images", v).apply();
    }

    // ---------------- 防截屏 / 防录屏 ----------------
    /** 给悬浮层加 FLAG_SECURE：截屏、录屏、投屏里都不会出现飘雪的内容。 */
    public static boolean secure(Context c) {
        return sp(c).getBoolean("secure", true);
    }

    public static void setSecure(Context c, boolean v) {
        sp(c).edit().putBoolean("secure", v).apply();
    }

    // ---------------- ADB 激活脚本 ----------------
    public static java.io.File adbScriptFile(Context c) {
        java.io.File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new java.io.File(dir, "activate.sh");
    }

    public static String adbScriptPath(Context c) {
        return adbScriptFile(c).getAbsolutePath();
    }

    /** 首次启动时把只为本应用授权的脚本写到外部目录，用户 adb shell sh 它即可 */
    public static void ensureAdbScript(Context c) {
        try {
            java.io.File f = adbScriptFile(c);
            if (f.exists() && f.length() > 0) return;
            StringBuilder sb = new StringBuilder();
            sb.append("#!/system/bin/sh\n");
            sb.append("# Miku 飘雪 - 只为本应用打开悬浮窗权限（ADB 激活）\n");
            sb.append("PKG=").append(c.getPackageName()).append("\n");
            sb.append("echo \"grant SYSTEM_ALERT_WINDOW to $PKG\"\n");
            sb.append("appops set $PKG SYSTEM_ALERT_WINDOW allow\n");
            sb.append("echo \"--- read back ---\"\n");
            sb.append("appops get $PKG SYSTEM_ALERT_WINDOW\n");
            sb.append("echo done\n");
            f.getParentFile().mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(sb.toString());
            w.close();
            f.setReadable(true, false);
            f.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    public static String readAdbScript(Context c) {
        try {
            java.io.File f = adbScriptFile(c);
            if (!f.exists()) return "(脚本还没生成)";
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "(读取失败: " + t + ")";
        }
    }

    // ---------------- 主题 ----------------
    /** 0=跟随系统 1=始终浅色 2=始终深色 3=莫奈取色 */
    public static int themeMode(Context c) {
        return Math.max(0, Math.min(3, sp(c).getInt("theme", 0)));
    }

    public static void setThemeMode(Context c, int v) {
        sp(c).edit().putInt("theme", Math.max(0, Math.min(3, v))).apply();
    }

    // ---------------- 首次使用 ----------------
    public static boolean firstRun(Context c) {
        return sp(c).getBoolean("first_run", true);
    }

    public static void setFirstRun(Context c, boolean v) {
        sp(c).edit().putBoolean("first_run", v).apply();
    }

    // ---------------- 运行状态 ----------------
    public static boolean running(Context c) {
        return sp(c).getBoolean("running", false);
    }

    public static void setRunning(Context c, boolean v) {
        sp(c).edit().putBoolean("running", v).apply();
    }
}
