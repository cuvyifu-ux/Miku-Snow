package com.miku.snow;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;

/**
 * 透明覆盖层的绘制视图：每帧驱动 SnowEngine，绘制自定义文字与图片。
 * 绘制顺序：translate -> rotate -> skew(倾斜) -> scaleX(3D 翻转)。
 */
@SuppressLint("ViewConstructor")
public final class SnowView extends View implements Choreographer.FrameCallback {

    private final SnowEngine engine = new SnowEngine(20261107L);
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Paint.FontMetrics fm = new Paint.FontMetrics();

    /** 偏冷的白，落在任何背景上都像雪。 */
    private int[] letterColors = {
            0xFFFFFFFF,
            0xFFEAF8FF,
            0xFFD6F2FF
    };

    /** 换一组字色（下标见 Prefs.textColor） */
    public void setGlyphTheme(int idx) {
        switch (idx) {
            case 1:
                letterColors = new int[]{0xFF7FE7FF, 0xFF4FC3F7, 0xFFB3F0FF};
                break;
            case 2:
                letterColors = new int[]{0xFFFFC2D9, 0xFFFF8FB1, 0xFFFFE3EE};
                break;
            case 3:
                letterColors = new int[]{0xFF6FF7C8, 0xFF2ED8A7, 0xFFC8FFE9};
                break;
            case 4:
                letterColors = new int[]{0xFFFFE08A, 0xFFFFC64D, 0xFFFFF3C4};
                break;
            case 5:
                letterColors = new int[]{0xFFD8B4FF, 0xFFB388FF, 0xFFEEDCFF};
                break;
            case 6:
                // 自定义色卡：整组字统一用这一种颜色
                letterColors = new int[]{Prefs.customColor(getContext())};
                break;
            default:
                letterColors = new int[]{0xFFFFFFFF, 0xFFEAF8FF, 0xFFD6F2FF};
                break;
        }
        invalidate();
    }

    private Bitmap[] images = new Bitmap[0];
    private boolean running;

    // 陀螺仪：图片跟着手机左右倾斜，反向开关把角度反过来
    private boolean gyroEnabled;
    private boolean gyroReverse;
    private float gyroDeg;
    private SensorManager sensorManager;
    private Sensor gravSensor;
    private boolean sensorListening;

    private final SensorEventListener gravListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            float x = e.values[0];
            float y = e.values[1];
            float z = e.values[2];
            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            if (mag < 0.5f) return;
            // 重力在屏幕平面上的横向分量 → 手机左右歪了多少度
            float s = (-x / mag);
            if (s > 1f) s = 1f;
            if (s < -1f) s = -1f;
            float deg = (float) Math.toDegrees(Math.asin(s));
            if (deg > 26f) deg = 26f;
            if (deg < -26f) deg = -26f;
            if (gyroReverse) deg = -deg;
            // 低通，避免手抖时图片乱抖
            gyroDeg = gyroDeg * 0.82f + deg * 0.18f;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    /** 陀螺仪倾斜开关（overlay 每次 applyConfig 都会调） */
    public void setGyro(boolean enabled, boolean reverse) {
        gyroEnabled = enabled;
        gyroReverse = reverse;
        if (!enabled) {
            gyroDeg = 0f;
            unregisterSensor();
        } else if (isAttachedToWindow()) {
            registerSensor();
        }
    }

    private void registerSensor() {
        if (sensorListening) return;
        try {
            if (sensorManager == null) {
                sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            }
            if (sensorManager == null) return;
            if (gravSensor == null) {
                gravSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (gravSensor == null) gravSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (gravSensor == null) return;
            sensorManager.registerListener(gravListener, gravSensor, SensorManager.SENSOR_DELAY_UI);
            sensorListening = true;
        } catch (Throwable ignored) {
        }
    }

    private void unregisterSensor() {
        if (!sensorListening) return;
        try {
            if (sensorManager != null) sensorManager.unregisterListener(gravListener);
        } catch (Throwable ignored) {
        }
        sensorListening = false;
    }
    private long lastFrameNanos;

    public SnowView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        letterPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        letterPaint.setStyle(Paint.Style.FILL);
        letterPaint.setShadowLayer(8f, 0f, 2f, 0x55000000);
        imagePaint.setAlpha(255);
        setWillNotDraw(false);
    }

    public void setImages(Bitmap[] bitmaps) {
        this.images = bitmaps == null ? new Bitmap[0] : bitmaps;
    }

    public SnowEngine engine() {
        return engine;
    }

    public void applyConfig(String text, int count, float speed, float scale,
                            boolean useImages, boolean imagesOnly,
                            boolean spin, boolean flip, boolean tilt, boolean singleChar) {
        engine.setText(text);
        engine.setConfig(count, speed, scale, useImages, imagesOnly, images.length, spin, flip, tilt, singleChar);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        engine.setBounds(w, h);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
        if (gyroEnabled) registerSensor();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        unregisterSensor();
        super.onDetachedFromWindow();
    }

    public void start() {
        if (running) return;
        running = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNanos == 0) lastFrameNanos = frameTimeNanos;
        float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = frameTimeNanos;
        if (dt > 0f) engine.update(dt);
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ArrayList<SnowEngine.P> list = engine.particles();
        String text = engine.text();
        for (int i = 0; i < list.size(); i++) {
            SnowEngine.P p = list.get(i);
            if (p.alpha <= 0.01f) continue;
            float cx = engine.x(p);
            float cy = p.y;
            if (cy + p.r < -4 || cy - p.r > getHeight() + 4) continue;

            int alpha = (int) (255f * p.alpha);
            if (alpha <= 0) continue;
            float flipScale = (float) Math.cos(p.flip);

            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(p.rot);
            if (p.skew != 0f) canvas.skew(p.skew, 0f);

            if (p.kind == SnowEngine.KIND_IMAGE && p.imageIdx < images.length) {
                Bitmap bmp = images[p.imageIdx];
                if (bmp != null && !bmp.isRecycled()) {
                    if (gyroEnabled && Math.abs(gyroDeg) > 0.1f) canvas.rotate(gyroDeg);
                    canvas.scale(flipScale, 1f);
                    dst.set(-p.r, -p.r, p.r, p.r);
                    imagePaint.setAlpha(alpha);
                    canvas.drawBitmap(bmp, null, dst, imagePaint);
                }
            } else if (text.length() > 0) {
                String g;
                if (engine.singleChar()) {
                    g = String.valueOf(text.charAt(p.glyphIdx % text.length()));
                } else {
                    g = text;   // 整段文字当一个粒子
                }
                canvas.scale(flipScale, 1f);
                letterPaint.setTextSize(p.r * 1.66f);
                letterPaint.setColor(letterColors[p.tint % letterColors.length]);
                letterPaint.setAlpha(alpha);
                letterPaint.getFontMetrics(fm);
                float baseline = -(fm.ascent + fm.descent) / 2f;
                float tw = letterPaint.measureText(g);
                if (!engine.singleChar()) {
                    // 整段文字可能比屏幕还宽，等比缩到画面 85% 以内
                    float maxW = getWidth() * 0.85f;
                    if (maxW > 0 && tw > maxW) {
                        float k = maxW / tw;
                        canvas.scale(k, k);
                    }
                }
                canvas.drawText(g, -tw / 2f, baseline, letterPaint);
            }
            canvas.restore();
        }
    }
}
