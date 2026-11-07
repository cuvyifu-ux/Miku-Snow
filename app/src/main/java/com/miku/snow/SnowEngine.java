package com.miku.snow;

import java.util.ArrayList;
import java.util.Random;

/**
 * 飘雪物理引擎：纯 Java，不依赖 Android，方便在桌面上做自动化验证。
 *
 * 规则：
 * 1. 粒子来自“自定义文字”的每个字（或用户导入的图片），从屏幕上方飘落；
 * 2. 每个粒子有自己的旋转（rot）、翻转（flip，scaleX = cos(flip)）、倾斜（skew）；
 * 3. 开启“倾斜”时粒子带水平速度斜着落下，撞到左右边界回弹；
 * 4. 出生拒绝采样 + 每帧分离，保证互不重叠；
 * 5. 落到屏幕底端淡出并回收，再从顶部重新入场。
 */
public final class SnowEngine {

    public static final int KIND_GLYPH = 0;
    public static final int KIND_IMAGE = 1;

    /** 一个雪花粒子。字段公开，绘制端直接读取，绘制过程零分配。 */
    public static final class P {
        public float baseX;
        public float y;
        public float r;            // 包围盒半径
        public float vy;           // 下落速度 px/s
        public float vx;           // 水平速度（倾斜模式）
        public float driftAmp;
        public float driftPhase;
        public float driftSpeed;
        public float rot;
        public float rotSpeed;
        public float flip;
        public float flipSpeed;
        public float skew;         // 斜切量，canvas.skew(skew, 0)
        public float alpha;
        public float sizeRatio;
        public int kind;
        public int glyphIdx;       // 文字里的第几个字
        public int imageIdx;
        public int tint;
    }

    private final ArrayList<P> ps = new ArrayList<P>();
    private final Random rnd;
    private float w, h;

    private String text = "MIKU";
    private int targetCount = 44;
    private float speedFactor = 1f;
    private float sizeFactor = 1f;
    private boolean useImages = true;
    private boolean imagesOnly = false;
    private int imageCount = 0;
    private boolean spin = true;
    private boolean flipEnabled = true;
    private boolean tiltEnabled = true;

    private float effScale = 1f;
    private int effective = 44;

    private static final float GLYPH_MIN_R = 16f;
    private static final float GLYPH_MAX_R = 54f;
    private static final float IMAGE_MIN_R = 24f;
    private static final float IMAGE_MAX_R = 66f;

    public SnowEngine(long seed) {
        this.rnd = new Random(seed);
    }

    public ArrayList<P> particles() {
        return ps;
    }

    public String text() {
        return text;
    }

    public float boundsWidth() {
        return w;
    }

    public float boundsHeight() {
        return h;
    }

    public int effectiveCount() {
        return effective;
    }

    public float x(P p) {
        return p.baseX + (float) Math.sin(p.driftPhase) * p.driftAmp;
    }

    /** 换文字：已有的字形粒子立刻换成新文字的随机字，图片粒子不受影响。 */
    public void setText(String t) {
        String s = clean(t);
        if (s.equals(text)) return;
        text = s;
        for (P p : ps) {
            if (p.kind == KIND_GLYPH && text.length() > 0) {
                p.glyphIdx = rnd.nextInt(text.length());
            }
        }
    }

    public void setConfig(int count, float speed, float scale,
                          boolean useImages, boolean imagesOnly, int imageCount,
                          boolean spin, boolean flip, boolean tilt) {
        this.targetCount = Math.max(4, Math.min(140, count));
        this.speedFactor = Math.max(0.2f, Math.min(3f, speed));
        this.sizeFactor = Math.max(0.4f, Math.min(2.5f, scale));
        this.useImages = useImages;
        this.imagesOnly = imagesOnly && imageCount > 0;
        this.imageCount = Math.max(0, imageCount);
        this.spin = spin;
        this.flipEnabled = flip;
        this.tiltEnabled = tilt;

        computeFit();
        while (ps.size() > effective) ps.remove(ps.size() - 1);
        for (P p : ps) {
            reclassify(p);
            if (w > 0) {
                p.baseX = clamp(p.baseX, p.r, w - p.r);
                clampDrift(p);
            }
            if (!spin) { p.rotSpeed = 0f; p.rot = 0f; }
            if (!flip) { p.flip = 0f; p.flipSpeed = 0f; }
            if (!tilt) { p.vx = 0f; p.skew = 0f; }
        }
        ensureCount();
    }

    public void setBounds(float width, float height) {
        if (width <= 0 || height <= 0) return;
        boolean first = (w <= 0 || h <= 0);
        w = width;
        h = height;
        computeFit();
        while (ps.size() > effective) ps.remove(ps.size() - 1);
        if (first) {
            ps.clear();
            fillInitial();
        } else {
            for (P p : ps) {
                applySize(p);
                p.baseX = clamp(p.baseX, p.r, w - p.r);
                if (p.y + p.r > h + 2) respawn(p);
            }
        }
    }

    private void computeFit() {
        effective = targetCount;
        effScale = sizeFactor;
        if (w <= 0 || h <= 0) return;
        float area = w * h;
        float avgBase = (GLYPH_MIN_R + GLYPH_MAX_R) * 0.5f;
        float allow = area * 0.40f;
        float need = targetCount * 4.2f * avgBase * avgBase * sizeFactor * sizeFactor;
        if (need > allow) {
            float fit = (float) Math.sqrt(allow / need);
            fit = Math.max(0.55f, fit);
            effScale = sizeFactor * fit;
            float per = 4.2f * (avgBase * effScale) * (avgBase * effScale);
            int cap = (int) (allow / per);
            effective = Math.max(6, Math.min(targetCount, cap));
        }
    }

    private void fillInitial() {
        for (int i = 0; i < effective; i++) {
            P p = new P();
            spawn(p, false);
            p.y = p.r + rnd.nextFloat() * Math.max(1f, h - 2f * p.r);
            int guard = 0;
            while (overlapsAny(p.baseX, p.y, p.r, p) && guard++ < 20) {
                p.baseX = p.r + rnd.nextFloat() * Math.max(1f, w - 2 * p.r);
                p.y = p.r + rnd.nextFloat() * Math.max(1f, h - 2f * p.r);
                clampDrift(p);
            }
            ps.add(p);
        }
    }

    private void ensureCount() {
        while (ps.size() < effective) {
            P p = new P();
            spawn(p, true);
            ps.add(p);
        }
    }

    private void reclassify(P p) {
        boolean wantImage = useImages && imageCount > 0 && (imagesOnly || rnd.nextFloat() < 0.32f);
        boolean canGlyph = text.length() > 0;
        p.kind = ((wantImage || !canGlyph) && imageCount > 0) ? KIND_IMAGE : KIND_GLYPH;
        if (p.kind == KIND_IMAGE) {
            p.imageIdx = rnd.nextInt(imageCount);
        } else {
            p.glyphIdx = text.length() > 0 ? rnd.nextInt(text.length()) : 0;
        }
        p.sizeRatio = rnd.nextFloat();
        applySize(p);
    }

    private void applySize(P p) {
        float lo = p.kind == KIND_IMAGE ? IMAGE_MIN_R : GLYPH_MIN_R;
        float hi = p.kind == KIND_IMAGE ? IMAGE_MAX_R : GLYPH_MAX_R;
        p.r = (lo + (hi - lo) * p.sizeRatio) * effScale;
        p.r = Math.max(6f, Math.min(p.r, Math.min(w <= 0 ? 4000f : w, h <= 0 ? 4000f : h) * 0.16f));
    }

    private void spawn(P p, boolean guaranteed) {
        reclassify(p);
        p.rot = rnd.nextFloat() * 360f;
        p.rotSpeed = (rnd.nextFloat() < 0.5f ? -1 : 1) * (9f + rnd.nextFloat() * 30f);
        p.flip = rnd.nextFloat() * 6.2832f;
        p.flipSpeed = (rnd.nextFloat() < 0.5f ? -1 : 1) * (0.22f + rnd.nextFloat() * 0.50f);
        p.driftAmp = Math.min(6f + rnd.nextFloat() * 18f, 0.30f * p.r);
        p.driftPhase = rnd.nextFloat() * 6.2832f;
        p.driftSpeed = 0.12f + rnd.nextFloat() * 0.38f;
        p.alpha = 0.72f + rnd.nextFloat() * 0.28f;
        p.tint = rnd.nextInt(3);

        float speedBase = h > 0 ? h * (0.045f + rnd.nextFloat() * 0.035f) : 60f;
        float sizeRatio = (p.r - GLYPH_MIN_R) / (GLYPH_MAX_R - GLYPH_MIN_R);
        sizeRatio = clamp(sizeRatio, 0f, 1f);
        p.vy = speedBase * (0.86f + 0.14f * sizeRatio) * speedFactor * (0.95f + rnd.nextFloat() * 0.10f);

        if (tiltEnabled) {
            float dir = rnd.nextFloat() < 0.5f ? -1f : 1f;
            p.vx = dir * (0.10f + rnd.nextFloat() * 0.22f) * p.vy;
            p.skew = dir * (0.10f + rnd.nextFloat() * 0.20f);
        } else {
            p.vx = 0f;
            p.skew = 0f;
        }
        if (!spin) { p.rot = 0f; p.rotSpeed = 0f; }
        if (!flipEnabled) { p.flip = 0f; p.flipSpeed = 0f; }

        if (w <= 0 || h <= 0) {
            p.baseX = 0;
            p.y = -p.r;
            return;
        }

        if (guaranteed) {
            for (int attempt = 0; attempt < 28; attempt++) {
                float bx = p.r + rnd.nextFloat() * Math.max(1f, w - 2 * p.r);
                float yy = -p.r - rnd.nextFloat() * h * 0.55f;
                if (!overlapsAny(bx, yy, p.r, p)) {
                    p.baseX = bx;
                    p.y = yy;
                    clampDrift(p);
                    return;
                }
            }
            float minY = 0f;
            for (P o : ps) if (o != p) minY = Math.min(minY, o.y - o.r);
            p.baseX = clamp(p.r + rnd.nextFloat() * Math.max(1f, w - 2 * p.r), p.r, w - p.r);
            p.y = minY - 2f * p.r - rnd.nextFloat() * 40f;
            clampDrift(p);
        } else {
            p.baseX = clamp(p.r + rnd.nextFloat() * Math.max(1f, w - 2 * p.r), p.r, w - p.r);
            p.y = -p.r - rnd.nextFloat() * h * 0.4f;
            clampDrift(p);
        }
    }

    private boolean overlapsAny(float bx, float yy, float r, P self) {
        for (P o : ps) {
            if (o == self) continue;
            float ox = x(o);
            float dx = bx - ox;
            float dy = yy - o.y;
            float minD = (r + o.r) * 1.04f;
            if (dx * dx + dy * dy < minD * minD) return true;
        }
        return false;
    }

    private void clampDrift(P p) {
        float roomLeft = Math.max(0f, p.baseX - p.r);
        float roomRight = Math.max(0f, w - p.r - p.baseX);
        p.driftAmp = Math.min(p.driftAmp, Math.min(roomLeft, roomRight));
    }

    private void respawn(P p) {
        spawn(p, true);
        p.y = -p.r - rnd.nextFloat() * h * 0.35f;
    }

    public void update(float dt) {
        if (w <= 0 || h <= 0) return;
        if (dt <= 0) return;
        if (dt > 0.05f) dt = 0.05f;

        ensureCount();
        ArrayList<P> list = ps;
        for (int i = 0; i < list.size(); i++) {
            P p = list.get(i);
            p.driftPhase += p.driftSpeed * dt;
            p.y += p.vy * dt;
            p.rot += p.rotSpeed * dt;
            p.flip += p.flipSpeed * dt;

            if (p.vx != 0f) {
                p.baseX += p.vx * dt;
                if (p.baseX - p.r < 0f) {
                    p.baseX = p.r;
                    p.vx = -p.vx;
                    p.skew = -p.skew;
                } else if (p.baseX + p.r > w) {
                    p.baseX = w - p.r;
                    p.vx = -p.vx;
                    p.skew = -p.skew;
                }
            }

            float fadeZone = Math.max(1f, h * 0.12f);
            float remain = h - (p.y + p.r);
            p.alpha = clamp(remain / fadeZone, 0f, 1f) * (p.kind == KIND_IMAGE ? 0.92f : 1f);

            if (p.y - p.r > h + 4f) {
                respawn(p);
            }
        }

        separate(list, 8);
        for (P p : list) clampDrift(p);
    }

    private void separate(ArrayList<P> list, int iterations) {
        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < list.size(); i++) {
                P a = list.get(i);
                float ax = x(a);
                for (int j = i + 1; j < list.size(); j++) {
                    P b = list.get(j);
                    float bx = x(b);
                    float dx = bx - ax;
                    float dy = b.y - a.y;
                    float minD = (a.r + b.r) * 1.03f;
                    float d2 = dx * dx + dy * dy;
                    if (d2 >= minD * minD) continue;

                    if (dy > 0 && a.vy > b.vy) {
                        float avg = (a.vy + b.vy) * 0.5f;
                        a.vy = avg; b.vy = avg;
                    } else if (dy < 0 && b.vy > a.vy) {
                        float avg = (a.vy + b.vy) * 0.5f;
                        a.vy = avg; b.vy = avg;
                    }

                    float d = (float) Math.sqrt(d2);
                    float nx, ny;
                    if (d < 0.001f) {
                        nx = 1f; ny = 0f; d = 0.001f;
                    } else {
                        nx = dx / d; ny = dy / d;
                    }
                    float overlap = minD - d;
                    float push = Math.min(overlap * 0.5f, 6f);
                    float pushX = push * nx;
                    float pushY = push * ny * 0.55f;
                    a.baseX = clamp(a.baseX - pushX, a.r, w - a.r);
                    b.baseX = clamp(b.baseX + pushX, b.r, w - b.r);
                    a.y = Math.max(-a.r * 2f, a.y - pushY);
                    b.y = Math.max(-b.r * 2f, b.y + pushY);
                    ax = x(a);
                }
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 与 Prefs.sanitize 保持一致：去控制字符，最多 12 个字。 */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7f) continue;
            if (n >= 12) break;
            b.append(ch);
            n++;
        }
        return b.toString();
    }
}
