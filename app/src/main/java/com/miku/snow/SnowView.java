package com.miku.snow;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    private final int[] letterColors = {
            0xFFFFFFFF,
            0xFFEAF8FF,
            0xFFD6F2FF
    };

    private Bitmap[] images = new Bitmap[0];
    private boolean running;
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
                            boolean spin, boolean flip, boolean tilt) {
        engine.setText(text);
        engine.setConfig(count, speed, scale, useImages, imagesOnly, images.length, spin, flip, tilt);
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
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
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
                    canvas.scale(flipScale, 1f);
                    dst.set(-p.r, -p.r, p.r, p.r);
                    imagePaint.setAlpha(alpha);
                    canvas.drawBitmap(bmp, null, dst, imagePaint);
                }
            } else if (text.length() > 0) {
                char c = text.charAt(p.glyphIdx % text.length());
                String g = String.valueOf(c);
                canvas.scale(flipScale, 1f);
                letterPaint.setTextSize(p.r * 1.66f);
                letterPaint.setColor(letterColors[p.tint % letterColors.length]);
                letterPaint.setAlpha(alpha);
                letterPaint.getFontMetrics(fm);
                float baseline = -(fm.ascent + fm.descent) / 2f;
                float tw = letterPaint.measureText(g);
                canvas.drawText(g, -tw / 2f, baseline, letterPaint);
            }
            canvas.restore();
        }
    }
}
