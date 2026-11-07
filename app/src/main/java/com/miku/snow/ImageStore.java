package com.miku.snow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 自定义图片仓库：把用户选的图片压到最长边 420px 的 PNG，存进 App 私有目录。
 * 不入库到相册、不上传网络，纯本地。
 */
public final class ImageStore {

    public static final int MAX_IMAGES = 8;
    private static final int MAX_SIDE = 420;

    private ImageStore() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "snow_images");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static int count(Context c) {
        File[] fs = dir(c).listFiles();
        return fs == null ? 0 : fs.length;
    }

    /** 当前仓库里的图片文件（按文件名排序，和飘落顺序一致） */
    public static File[] files(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return new File[0];
        Arrays.sort(fs);
        return fs;
    }

    /** 删掉某一张，返回是否删成功 */
    public static boolean delete(Context c, String name) {
        if (name == null) return false;
        File f = new File(dir(c), name);
        return f.exists() && f.delete();
    }

    public static void clear(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) {
            for (File f : fs) f.delete();
        }
    }

    /** 导入一批图片，返回成功新增的数量。 */
    public static int importUris(Context c, List<Uri> uris) {
        int added = 0;
        int current = count(c);
        for (Uri uri : uris) {
            if (current + added >= MAX_IMAGES) break;
            try {
                Bitmap bmp = decode(c, uri, MAX_SIDE);
                if (bmp == null) continue;
                String name = "img_" + System.currentTimeMillis() + "_" + added + ".png";
                File out = new File(dir(c), name);
                FileOutputStream fos = new FileOutputStream(out);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                bmp.recycle();
                added++;
            } catch (Throwable ignored) {
            }
        }
        trimToLimit(c);
        return added;
    }

    private static void trimToLimit(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        int extra = fs.length - MAX_IMAGES;
        for (int i = 0; i < extra; i++) fs[i].delete();
    }

    public static Bitmap[] load(Context c, int max) {
        File[] fs = dir(c).listFiles();
        if (fs == null || fs.length == 0) return new Bitmap[0];
        Arrays.sort(fs);
        List<Bitmap> out = new ArrayList<>();
        for (File f : fs) {
            if (out.size() >= max) break;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) out.add(b);
        }
        return out.toArray(new Bitmap[0]);
    }

    private static Bitmap decode(Context c, Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) return null;
        BitmapFactory.decodeStream(in, null, bounds);
        in.close();

        int sample = 1;
        int w = bounds.outWidth, h = bounds.outHeight;
        while (w / sample > maxSide * 2 || h / sample > maxSide * 2) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        in = c.getContentResolver().openInputStream(uri);
        if (in == null) return null;
        Bitmap src = BitmapFactory.decodeStream(in, null, opts);
        in.close();
        if (src == null) return null;

        int sw = src.getWidth(), sh = src.getHeight();
        int longSide = Math.max(sw, sh);
        if (longSide > maxSide) {
            float k = maxSide / (float) longSide;
            Bitmap scaled = Bitmap.createScaledBitmap(src, Math.max(1, (int) (sw * k)), Math.max(1, (int) (sh * k)), true);
            if (scaled != src) src.recycle();
            return scaled;
        }
        return src;
    }
}
