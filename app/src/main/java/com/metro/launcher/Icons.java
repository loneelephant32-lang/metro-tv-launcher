package com.metro.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;


/**
 * Turns an app icon into a Metro tile: the app's primary colour for the tile, and the logo cut out of
 * whatever square/rounded plate the icon was drawn on, so it can sit directly on that colour (or be
 * painted white in accent mode).
 */
final class Icons {
    /** Bump when the extraction changes so cached results are recomputed. */
    static final int VERSION = 10;
    static final int FALLBACK = 0xFF647687;

    Bitmap glyph;
    int color;
    /** The logo is the same colour as the tile, so it has to be drawn white to be seen. */
    boolean white;
    /** The logo is baked into a photo/patterned plate we couldn't cut away, so it must keep its own colours. */
    boolean picture;

    static Icons extract(Drawable d, int px) {
        Icons r = new Icons();
        Bitmap full = null;
        Integer plate = null;
        if (Build.VERSION.SDK_INT >= 26 && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            Drawable fore = a.getForeground(), back = a.getBackground();
            if (fore != null) {
                // Layers are 108 units with the logo inside the central 66: scale so that zone fills px.
                full = render(fore, px, (px * 108 / 66 - px) / 2);
                if (bounds(full) == null) full = null;
            }
            if (full != null && back != null) {
                int avg = average(render(back, 24, 0));
                if (avg != 0) plate = avg;
            }
        }
        if (full == null) {
            full = render(d, px, 0);
            int[][] ring = plateRing(full);
            if (ring != null) {
                plate = ringColor(ring);
                full = removePlate(full, ring);
            }
        }
        if (plate == null && coverage(full) > 0.55f) {
            r.glyph = full;
            r.color = average(full);
            r.picture = true;
            return r;
        }
        r.glyph = cropFit(full, px);

        int vivid = dominantVivid(r.glyph);
        boolean fromPlate;
        // A neutral plate (black, white, grey, pastel) isn't the brand colour: the logo's own colour is.
        if (plate != null && !neutral(plate)) {
            r.color = plate | 0xFF000000;
            fromPlate = true;
        } else if (vivid != 0) {
            r.color = vivid;
            fromPlate = false;
        } else {
            r.color = FALLBACK;
            r.white = true;
            return r;
        }
        float same = fractionWithin(r.glyph, r.color, 0, 56), strong = fractionWithin(r.glyph, r.color, 90, 999);
        // A badge is a solid shape in the tile colour with marks inside (Spotify's disc, a folder); text
        // and line logos cover little of their own outline.
        boolean badge = strong >= 0.12f && same >= 0.15f && solidity(r.glyph) >= 0.6f;
        if (badge) {
            fade(r.glyph, r.color);
        } else if (!fromPlate || same >= 0.85f) {
            // Windows 8 style: the logo in white on the app's colour.
            r.white = true;
        }
        return r;
    }

    static Bitmap render(Drawable d, int px, int overflow) {
        Bitmap bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        d.setBounds(-overflow, -overflow, px + overflow, px + overflow);
        d.draw(new Canvas(bm));
        return bm;
    }

    static int average(Bitmap bm) {
        int[] p = new int[bm.getWidth() * bm.getHeight()];
        bm.getPixels(p, 0, bm.getWidth(), 0, 0, bm.getWidth(), bm.getHeight());
        long r = 0, g = 0, b = 0, n = 0;
        for (int c : p) {
            if (Color.alpha(c) < 128) continue;
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++;
        }
        return n == 0 ? 0 : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /**
     * Estimate the plate behind the logo from a ring just inside the icon's edge. Flat and smoothly
     * graded plates are both supported: the plate at any pixel is interpolated from the ring.
     * Returns null when the icon has no plate (transparent background, or a photo/patterned one).
     */
    static int[][] plateRing(Bitmap bm) {
        int w = bm.getWidth(), in = Math.max(2, w * 8 / 100);
        int[] top = new int[w], bottom = new int[w], left = new int[w], right = new int[w];
        int lo = w / 4, hi = w - w / 4, n = 0, opaque = 0;
        for (int i = 0; i < w; i++) {
            int t = Math.max(lo, Math.min(hi - 1, i));
            top[i] = bm.getPixel(t, in);
            bottom[i] = bm.getPixel(t, w - 1 - in);
            left[i] = bm.getPixel(in, t);
            right[i] = bm.getPixel(w - 1 - in, t);
        }
        int rough = 0, steps = 0;
        for (int[] edge : new int[][]{top, bottom, left, right}) {
            for (int i = lo; i < hi; i++) {
                n++;
                if (Color.alpha(edge[i]) > 220) opaque++;
                if (i > lo) { steps++; if (dist(edge[i], edge[i - 1]) > 22) rough++; }
            }
        }
        if (opaque < n * 9 / 10 || rough > steps / 8) return null;
        return new int[][]{top, bottom, left, right};
    }

    static int ringColor(int[][] ring) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int[] edge : ring) for (int i = edge.length / 4; i < edge.length - edge.length / 4; i++) {
            r += Color.red(edge[i]); g += Color.green(edge[i]); b += Color.blue(edge[i]); n++;
        }
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    static int dist(int a, int b) {
        return Math.max(Math.abs(Color.red(a) - Color.red(b)),
                Math.max(Math.abs(Color.green(a) - Color.green(b)), Math.abs(Color.blue(a) - Color.blue(b))));
    }

    static int lerp(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Fade out every pixel close to the (interpolated) plate colour, leaving only the logo. */
    static Bitmap removePlate(Bitmap bm, int[][] ring) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] top = ring[0], bottom = ring[1], left = ring[2], right = ring[3];
        int[] p = new int[w * h];
        bm.getPixels(p, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = p[y * w + x];
                int across = lerp(left[y], right[y], x / (float) (w - 1)), down = lerp(top[x], bottom[x], y / (float) (h - 1));
                int plate = lerp(across, down, 0.5f);
                float f = Math.max(0, Math.min(1, (dist(c, plate) - 26) / 46f));
                p[y * w + x] = ((int) (Color.alpha(c) * f) << 24) | (c & 0xFFFFFF);
            }
        }
        bm.setPixels(p, 0, w, 0, 0, w, h);
        return bm;
    }

    /** Share of the logo's visible pixels whose distance from the tile colour is in [min, max). */
    static float fractionWithin(Bitmap bm, int color, int min, int max) {
        int w = bm.getWidth(), h = bm.getHeight(), n = 0, same = 0;
        int[] p = new int[w * h];
        bm.getPixels(p, 0, w, 0, 0, w, h);
        for (int c : p) {
            if ((c >>> 24) < 160) continue;
            n++;
            int dd = dist(c, color);
            if (dd >= min && dd < max) same++;
        }
        return n == 0 ? 0 : same / (float) n;
    }

    /** Fade out logo pixels that match the tile colour. */
    static void fade(Bitmap bm, int color) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] p = new int[w * h];
        bm.getPixels(p, 0, w, 0, 0, w, h);
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            float f = Math.max(0, Math.min(1, (dist(c, color) - 34) / 46f));
            p[i] = ((int) (Color.alpha(c) * f) << 24) | (c & 0xFFFFFF);
        }
        bm.setPixels(p, 0, w, 0, 0, w, h);
    }

    /** How much of the logo's bounding box it actually fills. */
    static float solidity(Bitmap bm) {
        Rect b = bounds(bm);
        if (b == null) return 0;
        int w = bm.getWidth(), n = 0;
        int[] p = new int[w * bm.getHeight()];
        bm.getPixels(p, 0, w, 0, 0, w, bm.getHeight());
        for (int y = b.top; y < b.bottom; y++) for (int x = b.left; x < b.right; x++) if ((p[y * w + x] >>> 24) > 160) n++;
        return n / (float) (b.width() * b.height());
    }

    static float coverage(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight(), n = 0;
        int[] p = new int[w * h];
        bm.getPixels(p, 0, w, 0, 0, w, h);
        for (int c : p) if ((c >>> 24) > 200) n++;
        return n / (float) p.length;
    }

    static Rect bounds(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] p = new int[w * h];
        bm.getPixels(p, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, b = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((p[y * w + x] >>> 24) > 24) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
        return r < 0 ? null : new Rect(l, t, r + 1, b + 1);
    }

    /** Trim empty space so every logo is drawn at a similar visual size. */
    static Bitmap cropFit(Bitmap bm, int px) {
        Rect b = bounds(bm);
        if (b == null) return bm;
        float s = Math.min(px / (float) b.width(), px / (float) b.height());
        s = Math.min(s, 3f);
        float w = b.width() * s, h = b.height() * s;
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        RectF dst = new RectF((px - w) / 2, (px - h) / 2, (px + w) / 2, (px + h) / 2);
        new Canvas(out).drawBitmap(bm, b, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        bm.recycle();
        return out;
    }

    static boolean neutral(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        float l = luminance(c);
        return l < 0.06f || l > 0.9f || hsv[1] < 0.18f || (l > 0.72f && hsv[1] < 0.35f);
    }

    static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /** The most common saturated colour in the logo, or 0 if the logo is black/white/grey. */
    static int dominantVivid(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] p = new int[w * h];
        bm.getPixels(p, 0, w, 0, 0, w, h);
        int[] count = new int[4096];
        long[] r = new long[4096], g = new long[4096], b = new long[4096];
        float[] hsv = new float[3];
        int opaque = 0;
        for (int c : p) {
            if ((c >>> 24) < 160) continue;
            opaque++;
            Color.colorToHSV(c, hsv);
            if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;
            int k = ((c >> 12) & 0xF00) | ((c >> 8) & 0xF0) | ((c >> 4) & 0xF);
            count[k]++;
            r[k] += Color.red(c); g[k] += Color.green(c); b[k] += Color.blue(c);
        }
        int best = -1;
        for (int k = 0; k < 4096; k++) if (count[k] > 0 && (best < 0 || count[k] > count[best])) best = k;
        if (best < 0 || count[best] < Math.max(4, opaque / 40)) return 0;
        int n = count[best];
        return Color.rgb((int) (r[best] / n), (int) (g[best] / n), (int) (b[best] / n));
    }

    private Icons() { }
}
