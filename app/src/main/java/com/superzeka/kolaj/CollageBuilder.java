package com.superzeka.kolaj;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.InputStream;
import java.util.List;

/**
 * Packs any number of photos into a single image. Photos are spread over rows of
 * near-square cells; rows hold an equal share so the whole canvas is filled with no gaps.
 * Each photo is decoded at just the resolution its cell needs, so memory use stays flat
 * no matter how many photos are selected.
 */
final class CollageBuilder {

    interface Progress {
        void onProgress(int done, int total);
    }

    private CollageBuilder() {
    }

    static Bitmap build(ContentResolver cr, List<Uri> uris, int width, int height, int spacing,
                        int background, Progress progress) {
        int n = uris.size();
        // Above 16 MP fall back to RGB_565 to halve memory; there is no transparency anyway.
        Bitmap.Config config = (long) width * height > 4096L * 4096L ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        Bitmap out = Bitmap.createBitmap(width, height, config);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(background);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        int cols = Math.max(1, (int) Math.round(Math.sqrt(n * (double) width / height)));
        int rows = Math.max(1, (int) Math.ceil(n / (double) cols));
        int base = n / rows;
        int extra = n % rows;

        // Shrink spacing if the cells would otherwise vanish (thousands of photos).
        int maxPerRow = base + (extra > 0 ? 1 : 0);
        int s = spacing;
        while (s > 0 && (width - s * (maxPerRow + 1) < maxPerRow || height - s * (rows + 1) < rows)) {
            s--;
        }

        int index = 0;
        float rowH = (height - s * (rows + 1f)) / rows;
        for (int r = 0; r < rows; r++) {
            int k = base + (r < extra ? 1 : 0);
            if (k == 0) continue;
            float cellW = (width - s * (k + 1f)) / k;
            float top = s + r * (rowH + s);
            for (int c = 0; c < k && index < n; c++) {
                float left = s + c * (cellW + s);
                drawPhoto(cr, uris.get(index), canvas, paint, left, top, cellW, rowH);
                index++;
                if (progress != null) progress.onProgress(index, n);
            }
        }
        return out;
    }

    private static void drawPhoto(ContentResolver cr, Uri uri, Canvas canvas, Paint paint,
                                  float left, float top, float cellW, float cellH) {
        Bitmap bmp = null;
        try {
            int rotation = readRotation(cr, uri);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;

            boolean swap = rotation == 90 || rotation == 270;
            int srcW = swap ? bounds.outHeight : bounds.outWidth;
            int srcH = swap ? bounds.outWidth : bounds.outHeight;
            // Center-crop: the photo must cover the cell in both directions.
            float cover = Math.max(cellW / srcW, cellH / srcH);
            int sample = 1;
            // Halve the decode size while it still stays at least as large as the cell needs.
            while (sample * 2 * cover <= 1f) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = cr.openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return;

            int bw = swap ? bmp.getHeight() : bmp.getWidth();
            int bh = swap ? bmp.getWidth() : bmp.getHeight();
            float scale = Math.max(cellW / bw, cellH / bh);

            Matrix m = new Matrix();
            // Rotate around the bitmap center, then scale and center inside the cell.
            m.postTranslate(-bmp.getWidth() / 2f, -bmp.getHeight() / 2f);
            m.postRotate(rotation);
            m.postScale(scale, scale);
            m.postTranslate(left + cellW / 2f, top + cellH / 2f);

            canvas.save();
            canvas.clipRect(left, top, left + cellW, top + cellH);
            canvas.drawBitmap(bmp, m, paint);
            canvas.restore();
        } catch (Exception ignored) {
            // Unreadable photo: leave its cell as background and keep going.
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static int readRotation(ContentResolver cr, Uri uri) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
