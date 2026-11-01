package app.iole.tv;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;

/**
 * App artwork for tiles. Real apps ship their own TV banner; for the ones that don't, this draws a
 * tvOS-style card: a soft gradient in the app's own colour with a white version of its symbol.
 * (The approach follows the idea used by Hume Launcher, Apache-2.0; this is an independent implementation.)
 */
final class AppArt {
    private AppArt() {}

    /** The sharpest icon the app offers (xxxhdpi where available). */
    static Drawable icon(PackageManager pm, ActivityInfo ai, String pkg) {
        try {
            if (ai != null) {
                int res = ai.getIconResource();
                if (res != 0) {
                    Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                    Drawable d = Build.VERSION.SDK_INT >= 22
                            ? r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
                            : r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH);
                    if (d != null) return d;
                }
                return ai.loadIcon(pm);
            }
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            try { return pm.getApplicationIcon(pkg); } catch (Exception e2) { return null; }
        }
    }

    /** Average colour of the icon, kept fairly dark so a white symbol reads on it. */
    static int tint(Drawable d) {
        if (d == null) return 0xFF2C2C2E;
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, 16, 16); d.draw(new Canvas(b)); d.setBounds(old);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            int p = b.getPixel(x, y);
            if (Color.alpha(p) < 128) continue;
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
        }
        b.recycle();
        if (n == 0) return 0xFF2C2C2E;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[1] = Math.min(hsv[1], 0.8f); hsv[2] = Math.min(hsv[2], 0.55f);
        return Color.HSVToColor(hsv);
    }

    /** The icon reduced to a flat white symbol, or null when the icon is full-bleed art. */
    static Bitmap whiteGlyph(Drawable icon, int size) {
        if (icon == null) return null;
        Drawable d = icon; float zoom = 1f;
        if (Build.VERSION.SDK_INT >= 26 && d instanceof AdaptiveIconDrawable) {
            Drawable fg = ((AdaptiveIconDrawable) d).getForeground();
            if (fg != null) { d = fg; zoom = 1.5f; }
        }
        int big = Math.round(size * zoom);
        Bitmap full = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds(); d.setBounds(0, 0, big, big); d.draw(new Canvas(full)); d.setBounds(old);
        int off = (big - size) / 2;
        int[] px = new int[size * size];
        full.getPixels(px, 0, size, off, off, size, size);
        full.recycle();
        int solid = 0;
        for (int v : px) if (Color.alpha(v) > 128) solid++;
        if (solid > px.length * 0.6f || solid < px.length * 0.02f) return null;
        for (int i = 0; i < px.length; i++) px[i] = (Color.alpha(px[i]) << 24) | 0xFFFFFF;
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }

    /** 640x360 tvOS-style card as PNG bytes. */
    static byte[] card(Drawable icon) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        float[] hsv = new float[3];
        Color.colorToHSV(tint(icon), hsv);
        float sat = hsv[1] < 0.15f ? 0.1f : 0.68f;
        int top = Color.HSVToColor(new float[]{hsv[0], sat * 0.85f, sat < 0.2f ? 0.46f : 0.86f});
        int bottom = Color.HSVToColor(new float[]{(hsv[0] + 8f) % 360f, Math.min(1f, sat * 1.15f), sat < 0.2f ? 0.24f : 0.56f});
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)); // top sheen
        c.drawRect(0, 0, w, h * 0.5f, p);
        p.setShader(null);
        Bitmap glyph = whiteGlyph(icon, (int) (h * 0.48f));
        if (glyph != null) {
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sp.setColorFilter(new PorterDuffColorFilter(0x40000000, PorterDuff.Mode.SRC_IN));
            sp.setMaskFilter(new BlurMaskFilter(h * 0.025f, BlurMaskFilter.Blur.NORMAL));
            float x = (w - glyph.getWidth()) / 2f, y = (h - glyph.getHeight()) / 2f;
            c.drawBitmap(glyph.extractAlpha(), x, y + h * 0.015f, sp);
            c.drawBitmap(glyph, x, y, new Paint(Paint.FILTER_BITMAP_FLAG));
            glyph.recycle();
        } else if (icon != null) {
            int sz = (int) (h * 0.46f);
            Rect old = icon.copyBounds();
            icon.setBounds((w - sz) / 2, (h - sz) / 2, (w + sz) / 2, (h + sz) / 2);
            icon.draw(c); icon.setBounds(old);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        b.recycle();
        return bos.toByteArray();
    }
}
