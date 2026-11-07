package com.miku.snow;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 「能不能用跳过截图」的统一判定 + 后台探测。
 * 判定放在后台线程做（rish / su 都会阻塞几秒），主线程只读缓存，避免点开始/停止时卡死。
 */
public final class Priv {

    private static volatile boolean cached = false;
    private static volatile String detail = "未检测";
    private static volatile boolean checking = false;

    private Priv() {
    }

    /** 某条通道刚刚明确成功了，直接置为可用，不必再等后台探测；顺带落盘，重启后不丢 */
    public static void markOk(Context c, String why) {
        cached = true;
        detail = "Shizuku：" + why;
        try {
            c.getSharedPreferences("snow_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("priv_ok", true).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 上次明确成功过；用来在刚启动、探测还没回来时先把开关放亮 */
    public static boolean rememberedOk(Context c) {
        try {
            return c.getSharedPreferences("snow_prefs", Context.MODE_PRIVATE)
                    .getBoolean("priv_ok", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean cachedOk() {
        return cached;
    }

    public static String detail() {
        return detail;
    }

    public static void refreshAsync(final Context c) {
        if (c == null || checking) return;
        checking = true;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String root = "不可用";
                String shz = "不可用";

                try {
                    if (RootHelper.available()) root = "可用";
                } catch (Throwable t) {
                    root = "异常";
                }

                boolean termOk = false;
                try {
                    termOk = RishHelper.checkShell(app);
                } catch (Throwable t) {
                    termOk = false;
                }

                int uid = -1;
                try {
                    if (ShizukuHelper.authorized()) {
                        uid = ShizukuHelper.shellUid();
                        shz = uid == 2000 ? "已授权（shell）" : ("已授权（uid " + uid + "）");
                    }
                } catch (Throwable t) {
                    shz = "不可用";
                }
                if (termOk) {
                    shz = "已授权（终端方式）";
                }

                // 只要拿到其中任何一条身份就算可用；Shizuku 授权成功本身就说明管理器给了 shell binder
                boolean ok = root.equals("可用") || termOk || uid == 2000
                        || shz.startsWith("已授权");

                final boolean fok = ok;
                final String fdetail = "root：" + root + "\nShizuku：" + shz;

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cached = fok;
                        detail = fdetail;
                        checking = false;
                    }
                });
            }
        }).start();
    }

    public static boolean canUseSkip(Context c) {
        return cached;
    }
}
