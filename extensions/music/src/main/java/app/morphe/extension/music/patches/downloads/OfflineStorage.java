/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * File access shared by the offline catalogue.
 * <p>
 * Plain streams are used rather than {@code Files.readString} and {@code Files.writeString},
 * which Android only provides from API 33 while this extension supports API 26.
 */
final class OfflineStorage {

    private OfflineStorage() {
    }

    static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            //noinspection CharsetObjectCanBeUsed
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void writeText(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void writeJpeg(File file, Bitmap bitmap) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
        }
    }

    /**
     * Covers are stored at 1200px, so list rows decode a sample rather than the full bitmap.
     *
     * @param maxSize Longest edge to decode.
     */
    @Nullable
    static Bitmap decodeArtwork(File file, int maxSize) {
        if (file == null || !file.isFile()) return null;
        String path = file.getAbsolutePath();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / maxSize);
        return BitmapFactory.decodeFile(path, options);
    }

    /**
     * Galeri'den seçilen resmi belleği yormadan (inSampleSize ile) okur ve tam kare (1:1) kırpar.
     */
    @Nullable
    static Bitmap decodeStreamAndCropSquare(InputStream input, int targetSize) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        byte[] data = buffer.toByteArray();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int minEdge = Math.min(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, minEdge / targetSize);

        Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (raw == null) return null;

        int edge = Math.min(raw.getWidth(), raw.getHeight());
        int x = (raw.getWidth() - edge) / 2;
        int y = (raw.getHeight() - edge) / 2;

        Bitmap cropped = Bitmap.createBitmap(raw, x, y, edge, edge);
        if (cropped != raw) raw.recycle();

        if (edge != targetSize) {
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
            if (scaled != cropped) cropped.recycle();
            return scaled;
        }
        return cropped;
    }

    /**
     * İlk 4 şarkının kapağından 2x2 ızgara kolaj kapağı oluşturur.
     */
    @Nullable
    static Bitmap createCollage(List<Bitmap> bitmaps, int targetSize) {
        if (bitmaps == null || bitmaps.isEmpty()) return null;
        if (bitmaps.size() < 4) return bitmaps.get(0);

        Bitmap result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int half = targetSize / 2;

        for (int i = 0; i < 4; i++) {
            Bitmap bmp = bitmaps.get(i);
            if (bmp == null) continue;
            int left = (i % 2) * half;
            int top = (i / 2) * half;
            Rect src = centerSquareRect(bmp.getWidth(), bmp.getHeight());
            Rect dst = new Rect(left, top, left + half, top + half);
            canvas.drawBitmap(bmp, src, dst, paint);
        }
        return result;
    }

    private static Rect centerSquareRect(int width, int height) {
        int edge = Math.min(width, height);
        int left = (width - edge) / 2;
        int top = (height - edge) / 2;
        return new Rect(left, top, left + edge, top + edge);
    }

    /** Best effort removal, so a stale file cannot silently keep a download alive. */
    static void delete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Logger.printException(() -> "Could not delete: " + file);
        }
    }
}
