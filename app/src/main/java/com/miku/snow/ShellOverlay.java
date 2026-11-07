package com.miku.snow;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/**
 * shell 身份的子进程：由 rish 用 app_process 拉起，自己建一层「受信任覆盖层」，
 * 在这一层上把雪花画出来。因为建层的是 shell（uid 2000），这层在截图里会被剔除，
 * 而不是像应用 uid 那样被涂成黑块。
 *
 * 参数顺序：w h text count speed scale spin flip tilt
 */
public final class ShellOverlay {

    private static final String LOG_PATH = "/data/local/tmp/miku_shell.log";

    private static PrintWriter log;

    public static void main(String[] args) {
        try {
            log = new PrintWriter(new FileOutputStream(LOG_PATH, false), true);
        } catch (Throwable ignored) {
        }
        try {
            run(args);
        } catch (Throwable t) {
            log(t);
            if (log != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                log.println("FATAL " + sw);
                log.flush();
            }
        }
    }

    private static void log(String s) {
        if (log != null) {
            log.println(s);
            log.flush();
        }
    }

    private static void log(Throwable t) {
        log("ERR " + t.getClass().getSimpleName() + " " + t.getMessage());
    }

    private static void run(String[] args) throws Exception {
        int w = args.length > 0 ? Integer.parseInt(args[0]) : 1080;
        int h = args.length > 1 ? Integer.parseInt(args[1]) : 2400;
        String text = args.length > 2 ? args[2] : "MIKU";
        int count = args.length > 3 ? Integer.parseInt(args[3]) : 44;
        float speed = args.length > 4 ? Float.parseFloat(args[4]) : 1f;
        float scale = args.length > 5 ? Float.parseFloat(args[5]) : 1f;
        boolean spin = args.length > 6 && args[6].equals("1");
        boolean flip = args.length > 7 && args[7].equals("1");
        boolean tilt = args.length > 8 && args[8].equals("1");

        log("args w=" + w + " h=" + h + " text=" + text + " count=" + count
                + " speed=" + speed + " scale=" + scale);

        SurfaceControl sc = createLayer(w, h);
        if (sc == null) {
            log("层创建失败，退出");
            return;
        }

        Surface surface = newSurface(sc);
        if (surface == null) {
            log("拿不到 Surface，退出");
            return;
        }
        log("surface 拿到，开始绘制");

        SnowEngine engine = new SnowEngine(20261107L);
        engine.setText(text);
        engine.setConfig(count, speed, scale, false, false, 0, spin, flip, tilt);
        engine.setBounds(w, h);

        Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        letter.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        letter.setStyle(Paint.Style.FILL);
        letter.setShadowLayer(8f, 0f, 2f, 0x55000000);
        Paint.FontMetrics fm = new Paint.FontMetrics();
        int[] colors = {0xFFFFFFFF, 0xFFEAF8FF, 0xFFD6F2FF};
        RectF dst = new RectF();

        long last = System.nanoTime();
        while (true) {
            long now = System.nanoTime();
            float dt = (now - last) / 1_000_000_000f;
            last = now;
            if (dt > 0.05f) dt = 0.05f;
            engine.update(dt);

            Canvas canvas = null;
            try {
                canvas = surface.lockCanvas(null);
            } catch (Throwable t) {
                log(t);
            }
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    drawFrame(canvas, engine, letter, fm, colors, dst);
                } finally {
                    try {
                        surface.unlockCanvasAndPost(canvas);
                    } catch (Throwable t) {
                        log(t);
                    }
                }
            }
            Thread.sleep(16);
        }
    }

    private static void drawFrame(Canvas canvas, SnowEngine engine, Paint letter,
                                  Paint.FontMetrics fm, int[] colors, RectF dst) {
        ArrayList<SnowEngine.P> list = engine.particles();
        String text = engine.text();
        for (int i = 0; i < list.size(); i++) {
            SnowEngine.P p = list.get(i);
            if (p.alpha <= 0.01f) continue;
            float cx = engine.x(p);
            float cy = p.y;
            if (cy + p.r < -4 || cy - p.r > canvas.getHeight() + 4) continue;
            int alpha = (int) (255f * p.alpha);
            if (alpha <= 0) continue;
            float flipScale = (float) Math.cos(p.flip);

            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(p.rot);
            if (p.skew != 0f) canvas.skew(p.skew, 0f);
            if (text.length() > 0) {
                char c = text.charAt(p.glyphIdx % text.length());
                String g = String.valueOf(c);
                canvas.scale(flipScale, 1f);
                letter.setTextSize(p.r * 1.66f);
                letter.setColor(colors[p.tint % colors.length]);
                letter.setAlpha(alpha);
                letter.getFontMetrics(fm);
                float baseline = -(fm.ascent + fm.descent) / 2f;
                float tw = letter.measureText(g);
                canvas.drawText(g, -tw / 2f, baseline, letter);
            }
            canvas.restore();
        }
    }

    private static Object call(Object target, Class<?> cls, String name,
                               Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = cls.getMethod(name, types);
        return m.invoke(target, args);
    }

    private static void tx(Object transaction, String name, Object... args) throws Exception {
        Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
        for (java.lang.reflect.Method m : txCls.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != args.length) continue;
            Class<?>[] pt = m.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) continue;
                if (pt[i] == int.class || pt[i] == boolean.class || pt[i] == float.class) continue;
                if (!pt[i].isInstance(args[i])) { match = false; break; }
            }
            if (!match) continue;
            m.invoke(transaction, args);
            return;
        }
        throw new NoSuchMethodException(name);
    }

    private static Object displayControl() throws Exception {
        Class<?> scCls = Class.forName("android.view.SurfaceControl");
        IBinder token = (IBinder) scCls.getMethod("getInternalDisplayToken").invoke(null);
        if (token == null) return null;
        try {
            java.lang.reflect.Constructor<?> c =
                    scCls.getDeclaredConstructor(IBinder.class, String.class);
            c.setAccessible(true);
            return c.newInstance(token, "miku-display");
        } catch (Throwable e) {
            log(e);
        }
        try {
            java.lang.reflect.Constructor<?> c = scCls.getDeclaredConstructor(IBinder.class);
            c.setAccessible(true);
            return c.newInstance(token);
        } catch (Throwable e) {
            log(e);
        }
        return null;
    }

    private static SurfaceControl createLayer(int w, int h) {
        Class<?> scCls;
        Class<?> bCls;
        try {
            scCls = Class.forName("android.view.SurfaceControl");
            bCls = Class.forName("android.view.SurfaceControl$Builder");
        } catch (Throwable e) {
            log(e);
            return null;
        }

        // 方案 A：挂到物理屏幕的显示层下面（SH 里 createSurface 的 parent 就是 display token）
        try {
            Object display = displayControl();
            log("displayControl=" + (display != null));
            if (display != null) {
                Object builder = bCls.getConstructor().newInstance();
                call(builder, bCls, "setName", new Class[]{String.class}, "miku_snow");
                call(builder, bCls, "setBufferSize", new Class[]{int.class, int.class}, w, h);
                call(builder, bCls, "setFormat", new Class[]{int.class}, PixelFormat.RGBA_8888);
                call(builder, bCls, "setParent", new Class[]{scCls}, display);
                Object sc = call(builder, bCls, "build", new Class[]{});

                Object t = Class.forName("android.view.SurfaceControl$Transaction")
                        .getConstructor().newInstance();
                try { tx(t, "setLayerStack", sc, 0); } catch (Throwable e) { log(e); }
                try { tx(t, "setLayer", sc, 0x7FFFFFFF); } catch (Throwable e) { log(e); }
                try { tx(t, "setPosition", sc, 0, 0); } catch (Throwable e) { log(e); }
                try { tx(t, "setMatrix", sc, 1f, 0f, 0f, 1f); } catch (Throwable e) { log(e); }
                try { tx(t, "setAlpha", sc, 1f); } catch (Throwable e) { log(e); }
                try { tx(t, "setCrop", sc, new android.graphics.Rect(0, 0, w, h)); } catch (Throwable e) { log(e); }
                try { tx(t, "setTrustedOverlay", sc, true); log("setTrustedOverlay ok"); } catch (Throwable e) { log(e); }
                try { tx(t, "setSecure", sc, true); log("setSecure ok"); } catch (Throwable e) { log(e); }
                try { tx(t, "show", sc); } catch (Throwable e) { log(e); }
                tx(t, "apply");
                log("方案A建层成功");
                dumpSurfaceFlinger();
                return (SurfaceControl) sc;
            }
        } catch (Throwable e) {
            log(e);
        }

        // 方案 B：先建独立层，再 reparent 到屏幕
        try {
            Object builder = bCls.getConstructor().newInstance();
            call(builder, bCls, "setName", new Class[]{String.class}, "miku_snow2");
            call(builder, bCls, "setBufferSize", new Class[]{int.class, int.class}, w, h);
            call(builder, bCls, "setFormat", new Class[]{int.class}, PixelFormat.RGBA_8888);
            Object sc = call(builder, bCls, "build", new Class[]{});

            Object t = Class.forName("android.view.SurfaceControl$Transaction")
                    .getConstructor().newInstance();
            Object display = displayControl();
            if (display != null) {
                try { tx(t, "reparent", sc, display); log("reparent ok"); } catch (Throwable e) { log(e); }
            }
            try { tx(t, "setLayer", sc, 0x7FFFFFFF); } catch (Throwable e) { log(e); }
            try { tx(t, "setTrustedOverlay", sc, true); log("setTrustedOverlay ok"); } catch (Throwable e) { log(e); }
            try { tx(t, "setSecure", sc, true); log("setSecure ok"); } catch (Throwable e) { log(e); }
            try { tx(t, "setLayerStack", sc, 0); } catch (Throwable e) { log(e); }
            try { tx(t, "show", sc); } catch (Throwable e) { log(e); }
            tx(t, "apply");
            log("方案B建层成功");
            return (SurfaceControl) sc;
        } catch (Throwable e) {
            log(e);
        }
        return null;
    }

    /** 让 SurfaceFlinger 自己报一下有没有叫 miku 的层 */
    private static void dumpSurfaceFlinger() {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c", "dumpsys SurfaceFlinger --list 2>/dev/null | grep -i miku"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = br.readLine()) != null) sb.append(line).append('|');
            p.waitFor();
            log("SF层列表: " + (sb.length() == 0 ? "（没有 miku 层）" : sb.toString()));
        } catch (Throwable e) {
            log(e);
        }
    }

    private static Surface newSurface(SurfaceControl sc) {
        try {
            java.lang.reflect.Constructor<Surface> c =
                    Surface.class.getConstructor(SurfaceControl.class);
            c.setAccessible(true);
            return c.newInstance(sc);
        } catch (Throwable e) {
            log(e);
        }
        return null;
    }
}
