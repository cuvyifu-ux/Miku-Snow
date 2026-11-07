package com.miku.snow;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 兜底记录崩溃，写到 /sdcard/Android/data/<pkg>/files/crash.txt，方便把现场发回来 */
public final class CrashCatcher {

    private CrashCatcher() {
    }

    public static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "crash.txt");
    }

    public static void install(final Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                    pw.println("thread=" + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    File f = file(app);
                    f.getParentFile().mkdirs();
                    FileWriter w = new FileWriter(f, false);
                    w.write(sw.toString());
                    w.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    public static String last(Context c) {
        try {
            File f = file(c);
            if (!f.exists() || f.length() == 0) return "(没有崩溃记录)";
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 60) {
                sb.append(line).append('\n');
                n++;
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "(读取崩溃记录失败: " + t + ")";
        }
    }

    public static void clear(Context c) {
        try {
            File f = file(c);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
