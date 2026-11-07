package com.miku.snow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 前台服务：把 SnowView 作为系统悬浮层（TYPE_APPLICATION_OVERLAY）盖在屏幕最上层。
 *
 * 权限来源：SYSTEM_ALERT_WINDOW。用 Shizuku（shell 身份 = 系统权限）执行
 * `appops set ... SYSTEM_ALERT_WINDOW allow` 打开它，或手动/ADB 打开。
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "com.miku.snow.START";
    public static final String ACTION_STOP = "com.miku.snow.STOP";
    public static final String ACTION_REFRESH = "com.miku.snow.REFRESH";

    private static final String CHANNEL_ID = "snow_overlay";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private SnowView snowView;          // 真正的渲染器，两种模式都用它画
    private View content;               // 挂到窗口上的东西：普通模式是 snowView，分层防截屏是 SurfaceView
    private SurfaceView surfaceView;
    private boolean added;
    private boolean secureApplied;
    private boolean layerSecure;        // 只隐藏雪花这一层是否真的生效
    private boolean shellMode;          // 当前是不是 shell 子进程在画雪花
    private boolean skipApplied;        // 当前窗口是不是带「跳过截图」标记的那一层
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int screenW = 1080;
    private int screenH = 2400;
    /** 给界面看的：分层 secure 到底成没成 */
    public static volatile String layerState = "未开始";
    /** 最近一次 shell 子进程的日志尾巴，界面诊断里直接看 */
    public static volatile String shellLogTail = "";
    private String lastSecureError = "";
    private long lastFrameNanos;
    private boolean drawing;
    private Thread drawThread;
    /** 看门狗：切后台再回来，Surface 可能已经停画，这里发现超过 6 秒没新帧就重建整层 */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (added) {
                    boolean stale = !drawing
                            || (lastFrameNanos > 0
                            && System.nanoTime() - lastFrameNanos > 6_000_000_000L);
                    if (stale) {
                        removeOverlay();
                        addOverlay();
                    }
                }
            } catch (Throwable ignored) {
            }
            mainHandler.postDelayed(this, 4000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createChannel();
        mainHandler.postDelayed(watchdog, 1500);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START
                : (intent.getAction() == null ? ACTION_START : intent.getAction());

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        if (ACTION_REFRESH.equals(action)) {
            if (snowView != null) {
                snowView.setImages(ImageStore.load(this, ImageStore.MAX_IMAGES));
                applyConfig();
            }
            return START_STICKY;
        }

        if (!Settings.canDrawOverlays(this)) {
            // 有些 ROM 里 appops 已经 allow，但本进程读到的仍是旧状态：
            // 这里不直接退出，仍然尝试挂一次，失败再提示。
            Toast.makeText(this, "系统还没有放行悬浮窗，正在尝试直接挂载…", Toast.LENGTH_SHORT).show();
        }

        if (!added) {
            try {
                addOverlay();
            } catch (Throwable t) {
                Toast.makeText(this, "悬浮层创建被系统拒绝：" + t.getClass().getSimpleName()
                        + "。请用 Shizuku 授权或到系统设置里打开悬浮窗", Toast.LENGTH_LONG).show();
                stopSelf();
            }
        } else {
            applyConfig();
        }
        return START_STICKY;
    }

    private WindowManager.LayoutParams baseParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 只负责画雪，永远不吃触摸：这几个 flag 缺一个都可能被某些机型当成可点层
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        // 任何机型都把「不吃触摸」硬写回来，避免上层逻辑 |= 别的 flag 时丢掉了
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.setTitle("MikuSnow");
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }


    /** 加完窗口后再强制一次「不吃触摸」，某些平板上单靠创建时的 flag 会被忽略 */
    private void lockTouchThrough(View v) {
        try {
            v.setClickable(false);
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            if (lp != null) {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                windowManager.updateViewLayout(v, lp);
            }
        } catch (Throwable ignored) {
        }
    }

    private void addOverlay() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        shellMode = false;
        snowView = new SnowView(this);
        snowView.setImages(ImageStore.load(this, ImageStore.MAX_IMAGES));

        boolean wantSecure = false;
        try {
            wantSecure = Prefs.secure(this);
        } catch (Throwable ignored) {
        }
        Priv.refreshAsync(this);
        // SurfaceView 这条线在 Android 12 及以下会被系统当成可触摸层，
        // 打开后整屏滑不动（只有本应用窗口内还能滑）。老系统上跳过截图本来也打不上，
        // 所以这里直接退回普通窗口，宁可截图里有雪花，也不抢触摸。
        if (wantSecure && Build.VERSION.SDK_INT >= 33 && addSkipScreenshotLayer()) {
            skipApplied = true;
            layerState = "已用「跳过截图」标记：截图里没有雪花，屏幕照常显示";
        } else {
            skipApplied = false;
            addPlainLayer();
            if (wantSecure) {
                layerState = "这台机器不支持跳过截图，雪花会进截图";
            }
        }
        added = true;
        applyConfig();
    }

    /**
     * 用 SurfaceView 单独一层承载雪花，并给这层打「跳过截图」标记
     * （SurfaceControl 的 eSkipScreenshot，屏幕上照常显示，截图时整层不参与）。
     * 和 secure 不同：secure 会让截图留黑块，skip 是直接不画这一层。
     */
    private boolean addSkipScreenshotLayer() {
        try {
            SurfaceView sv = new SurfaceView(this);
            sv.setZOrderOnTop(true);
            sv.getHolder().setFormat(PixelFormat.TRANSLUCENT);
            snowView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    boolean ok = applySkipScreenshot(sv);
                    layerState = ok
                            ? "跳过截图已生效（雪花不进截图）"
                            : "跳过截图没有生效，雪花会进截图";
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                    if (w <= 0 || h <= 0) return;
                    snowView.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                    snowView.layout(0, 0, w, h);
                    snowView.engine().setBounds(w, h);
                    startDrawing();
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    stopDrawing();
                }
            });
            surfaceView = sv;
            WindowManager.LayoutParams lp = baseParams();
            content = sv;
            windowManager.addView(content, lp);
            lockTouchThrough(content);
            return true;
        } catch (Throwable t) {
            layerState = "建 SurfaceView 层失败：" + t.getClass().getSimpleName();
            return false;
        }
    }

    private boolean applySkipScreenshot(SurfaceView sv) {
        try {
            SurfaceControl sc = sv.getSurfaceControl();
            if (sc == null) return false;
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getConstructor().newInstance();
            java.lang.reflect.Method m =
                    txCls.getMethod("setSkipScreenshot", SurfaceControl.class, boolean.class);
            m.invoke(tx, sc, true);
            txCls.getMethod("apply").invoke(tx);
            return true;
        } catch (Throwable t) {
            lastSecureError = t.toString();
            // setSkipScreenshot 是隐藏 API，普通应用进程会被拦。
            // 有 root 的话把系统的隐藏 API 限制关掉，下次启动反射就能通。
            try {
                if (RootHelper.available() && !Prefs.hiddenUnlocked(this)) {
                    RootHelper.exec("settings put global hidden_api_policy 1");
                    RootHelper.exec("settings put global hidden_api_policy_pre_p_apps 1");
                    Prefs.setHiddenUnlocked(this, true);
                    layerState = "已解除隐藏 API 限制，重启应用后生效";
                }
            } catch (Throwable ignored2) {
            }
            return false;
        }
    }

    /** 用 shell 身份的子进程建层；起来后要确认真的在画，不能只看进程活着 */
    private boolean startShellLayer() {
        try {
            String out = RishHelper.startShellSnow(this, screenW, screenH,
                    Prefs.text(this), Prefs.count(this),
                    Prefs.speed(this), Prefs.size(this),
                    Prefs.spin(this), Prefs.flip(this), Prefs.tilt(this));

            // 等子进程把「开始绘制」写进日志，最多等 4 秒
            String log = "";
            for (int i = 0; i < 8; i++) {
                log = RishHelper.shellLog(this);
                if (log != null && log.contains("开始绘制")) break;
                try {
                    Thread.sleep(500);
                } catch (Throwable ignored) {
                }
            }
            shellLogTail = log == null ? "" : log.trim();
            if (log == null || !log.contains("开始绘制")) {
                layerState = "shell 子进程没起来：" + (log == null ? "" : log.trim());
                return false;
            }
            layerState = "shell 子进程已接管（雪花不进截图）";
            return true;
        } catch (Throwable t) {
            layerState = "shell 子进程异常：" + t;
            return false;
        }
    }

    private void addPlainLayer() {
        layerState = "普通窗口绘制中（雪花一定看得到；是否进截图由下面的实验通道决定）";
        WindowManager.LayoutParams lp = baseParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        content = snowView;
        layerSecure = false;
        secureApplied = false;
        windowManager.addView(content, lp);
        lockTouchThrough(content);
    }

    private void addSecureLayer() {
        surfaceView = new SurfaceView(this);
        surfaceView.setZOrderOnTop(true);
        snowView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                layerSecure = applyLayerSecure();
                if (!layerSecure) {
                    // 绝不退回整窗 FLAG_SECURE —— 那会让系统提示「该页面无法被截屏」，
                    // 正是用户要避免的效果。分层失败就保持可截屏，只记录原因。
                    secureApplied = false;
                    try {
                        Toast.makeText(OverlayService.this,
                                "分层 secure 没生效，雪花目前仍可被截到。原因见诊断",
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                if (w <= 0 || h <= 0) return;
                snowView.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                snowView.layout(0, 0, w, h);
                snowView.engine().setBounds(w, h);
                startDrawing();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopDrawing();
            }
        });

        WindowManager.LayoutParams lp = baseParams();
        content = surfaceView;
        secureApplied = false;
        windowManager.addView(content, lp);
        lockTouchThrough(content);
    }

    /** 只给 SnowView 那一层设 secure：截图里没有雪花，别的都在。 */
    private boolean applyLayerSecure() {
        if (Build.VERSION.SDK_INT < 29 || surfaceView == null) return false;
        try {
            SurfaceControl sc = surfaceView.getSurfaceControl();
            if (sc == null) {
                layerState = "拿不到 SurfaceControl，无法分层";
                return false;
            }
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getConstructor().newInstance();

            // 1) 先申请「受信任覆盖层」：只有受信任的层在截图里是「被剔除」，
            //    普通 secure 层只是被涂成黑块（这就是你现在看到的那个大黑块）
            java.lang.reflect.Method trusted =
                    txCls.getMethod("setTrustedOverlay", SurfaceControl.class, boolean.class);
            trusted.invoke(tx, sc, true);

            // 2) 再设 secure
            java.lang.reflect.Method setSecure =
                    txCls.getMethod("setSecure", SurfaceControl.class, boolean.class);
            setSecure.invoke(tx, sc, true);

            txCls.getMethod("apply").invoke(tx);
            secureApplied = true;
            layerState = "分层 secure 已生效（只隐藏雪花）";
            return true;
        } catch (SecurityException se) {
            lastSecureError = se.toString();
            layerState = "没有受信任覆盖层资格（普通应用 uid 会被拒），未设 secure；雪花照常能截，但不再整屏黑";
            return false;
        } catch (Throwable t) {
            lastSecureError = t.toString();
            layerState = "分层 secure 失败：" + t.getClass().getSimpleName() + " " + t.getMessage();
            return false;
        }
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!drawing || surfaceView == null) return;
            if (lastFrameNanos == 0) lastFrameNanos = frameTimeNanos;
            float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = frameTimeNanos;
            if (dt > 0f) snowView.engine().update(dt);

            Canvas canvas = null;
            SurfaceHolder holder = surfaceView.getHolder();
            try {
                canvas = holder.lockCanvas();
            } catch (Throwable ignored) {
            }
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    snowView.draw(canvas);
                } finally {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (drawing) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startDrawing() {
        if (drawing) return;
        drawing = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopDrawing() {
        drawing = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void applyConfig() {
        if (!added) return;

        boolean wantSecure = false;
        try {
            wantSecure = Prefs.secure(this);
        } catch (Throwable ignored) {
        }
        if (snowView == null && !shellMode) return;

        boolean wantShell = false;
        try {
            wantShell = Prefs.secure(this);
        } catch (Throwable ignored) {
        }

        // 开关变了必须重建窗口：跳过截图的标记是挂在那层 SurfaceControl 上的，
        // 只改偏好不重建，层还带着旧标记，关了开关也照样不进截图。
        if (wantShell != skipApplied) {
            removeOverlay();
            try {
                addOverlay();
            } catch (Throwable t) {
                Prefs.setSecure(this, false);
                Toast.makeText(this, "切换防截屏失败：" + t.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        snowView.setImages(ImageStore.load(this, ImageStore.MAX_IMAGES));
        snowView.setGlyphTheme(Prefs.textColor(this));
        // 「只飘图片 / 不飘文字」在确实有图片时才生效；
        // 一张图都没导入时，屏幕上飘出来的文字直接换成提示「还没有导入图片」，
        // 这样即使一个字都没填，屏幕也不会空着。
        int imgCount = ImageStore.count(this);
        boolean onlyImagesOn = Prefs.onlyImages(this) || Prefs.noText(this);
        boolean onlyImagesEff = onlyImagesOn && imgCount > 0;
        String floatText = Prefs.text(this);
        if (onlyImagesOn && imgCount == 0) {
            floatText = "还没有导入图片";
        }
        if (floatText.trim().isEmpty() && imgCount == 0) {
            floatText = "还没有导入图片";
        }
        snowView.applyConfig(
                floatText,
                Prefs.count(this),
                Prefs.speed(this),
                Prefs.size(this),
                Prefs.useImages(this),
                onlyImagesEff,
                Prefs.spin(this),
                Prefs.flip(this),
                Prefs.tilt(this),
                Prefs.singleChar(this));
    }

    private void removeOverlay() {
        stopDrawing();
        if (shellMode) {
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        RishHelper.stopShellSnow(app);
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
            shellMode = false;
        }
        if (content != null && added) {
            try {
                windowManager.removeView(content);
            } catch (Throwable ignored) {
            }
        }
        content = null;
        surfaceView = null;
        snowView = null;
        added = false;
        layerSecure = false;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(watchdog);
        removeOverlay();
        Prefs.setRunning(this, false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Miku 飘雪运行中",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("飘雪的常驻通知，可一键停止");
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = Prefs.text(this);
        String body = text.length() > 0 ? "「" + text + "」正在屏幕上飘落" : "图片正在屏幕上飘落";

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat_snow)
                .setContentTitle("Miku 飘雪运行中")
                .setContentText(body)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "停止", stopPi).build());
        return b.build();
    }
}
