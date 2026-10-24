package com.metro.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.IdentityHashMap;

/**
 * A big flat Metro tile drawn straight onto the canvas. Live content is written on the tile itself:
 * a brand mark top-left, a badge top-right, and caption / title / progress along the bottom, with a
 * faint oversized logo as a watermark. Live tiles slide between faces upwards, Windows 8 style.
 */
public class TileView extends View {
    static final int ICON = 0, TEXT = 1, MEDIA = 2;
    /** Tiles are 20% see-through; the logo and text on them stay solid. */
    static final int TILE_ALPHA = 204;

    static final class Face {
        int type;
        String caption, title, sub, badge;
        float progress = -1;
        boolean live, playing;
        /** Cover art or a video thumbnail, filling the tile behind the usual text. */
        Bitmap art;

        static Face icon() { return new Face(); }

        static Face text(String caption, String title, String sub, float progress) {
            Face f = new Face();
            f.type = TEXT;
            f.caption = caption;
            f.title = title;
            f.sub = sub;
            f.progress = progress;
            return f;
        }
    }

    int color = 0xFF1BA1E2, ringColor = 0xFFFFFFFF;
    /** The logo in colour, and as a white-silhouette alpha mask for accent tiles and white brand marks. */
    Bitmap glyph, mono;
    Rect glyphBounds;
    boolean whiteGlyph;
    int sysGlyph;
    String label = "", brand;
    Object tag2;

    Face[] faces = {Face.icon()};
    int face, prevFace;
    float slide = 1, glow;
    private ValueAnimator flipAnim, glowAnim;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), contour = new Paint(Paint.ANTI_ALIAS_FLAG),
            halo = new Paint(Paint.FILTER_BITMAP_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            white = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint capP = new TextPaint(Paint.ANTI_ALIAS_FLAG), brandP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            bigP = new TextPaint(Paint.ANTI_ALIAS_FLAG), hugeP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG), subP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            badgeP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** Ellipsized text, cached per source string so drawing a frame allocates nothing. Cleared on resize. */
    private final IdentityHashMap<String, CharSequence> fitted = new IdentityHashMap<>(), fittedUpper = new IdentityHashMap<>();
    private final RectF dst = new RectF();
    private final float d;
    /**
     * The glow is one tiny pre-blurred alpha sprite, shared by every tile and stretched around the
     * focused one. Blurring live on the GPU looked the same but cached ~20 MB of textures on this TV.
     */
    private static Bitmap glowSprite;
    private static int glowR;
    private final Rect gs = new Rect(), artSrc = new Rect();
    /** Darkens the top and bottom of artwork so the brand mark and text stay readable on any picture. */
    private final Paint scrim = new Paint();

    public TileView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        contour.setStyle(Paint.Style.STROKE);
        contour.setStrokeWidth(2 * d);
        if (glowSprite == null) {
            glowR = Math.round(20 * d);
            int size = glowR * 4;
            // An alpha-only bitmap draws in whatever colour the paint has, so one sprite serves every tile.
            glowSprite = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setMaskFilter(new BlurMaskFilter(glowR * 0.55f, BlurMaskFilter.Blur.OUTER));
            new Canvas(glowSprite).drawRect(glowR, glowR, size - glowR, size - glowR, p);
        }
        line.setColor(0xFFFFFFFF);
        white.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        mark.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        mark.setAlpha(34);
        text(capP, Fonts.MED, 11f, 0xF0FFFFFF);
        capP.setLetterSpacing(0.1f);
        text(brandP, Fonts.MED, 11.5f, 0xFFFFFFFF);
        brandP.setLetterSpacing(0.12f);
        text(bigP, Fonts.REG, 24f, 0xFFFFFFFF);
        text(hugeP, Fonts.REG, 31f, 0xFFFFFFFF);
        text(titleP, Fonts.MED, 15.5f, 0xFFFFFFFF);
        text(subP, Fonts.MED, 12f, 0xE6FFFFFF);
        text(badgeP, Fonts.MED, 10f, 0xFFFFFFFF);
        badgeP.setLetterSpacing(0.08f);
    }

    /**
     * Text drawn at a fractional pixel position gets smeared across two pixel rows by the GPU, which
     * looks soft on a 720p panel; snapping every baseline to a whole pixel keeps glyphs crisp.
     */
    private static float px(float v) { return Math.round(v); }

    private void text(TextPaint p, android.graphics.Typeface tf, float sp, int color) {
        p.setTypeface(tf);
        // Whole-pixel sizes rasterize sharper than fractional ones.
        p.setTextSize(Math.round(sp * d));
        p.setColor(color);
        if (android.os.Build.VERSION.SDK_INT >= 29) p.setHinting(Paint.HINTING_ON);
    }

    /** Fades and turnstiles apply per draw call instead of rendering the tile offscreen first. */
    @Override
    public boolean hasOverlappingRendering() { return false; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fitted.clear();
        fittedUpper.clear();
        scrim.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x8C000000, 0x14000000, 0x33000000, 0xD9000000},
                new float[]{0, 0.32f, 0.5f, 1}, Shader.TileMode.CLAMP));
    }

    void setColor(int c) { color = c; invalidate(); }

    void setGlyph(Bitmap g, Bitmap m, boolean whiteG) {
        // Logos are drawn well below their stored size; mipmaps keep that shrink cheap on the GPU and free of shimmer.
        if (g != null) g.setHasMipMap(true);
        if (m != null) m.setHasMipMap(true);
        glyph = g;
        mono = m;
        whiteGlyph = whiteG;
        glyphBounds = g == null ? null : Icons.bounds(g);
        invalidate();
    }

    void setFaces(Face... f) {
        faces = f;
        if (face >= f.length) face = 0;
        if (prevFace >= f.length) prevFace = 0;
        fitted.clear();
        fittedUpper.clear();
        invalidate();
    }

    void showFace(int i) {
        if (flipAnim != null) flipAnim.cancel();
        face = prevFace = Math.min(i, faces.length - 1);
        slide = 1;
        invalidate();
    }

    boolean canFlip() { return faces.length > 1; }

    /** Windows 8 live tile peek: the next face pushes the current one up and out. */
    void flip() {
        if (!canFlip()) return;
        if (flipAnim != null) flipAnim.cancel();
        prevFace = face;
        face = (face + 1) % faces.length;
        flipAnim = ValueAnimator.ofFloat(0, 1);
        flipAnim.setDuration(700);
        flipAnim.setInterpolator(new DecelerateInterpolator(2f));
        flipAnim.addUpdateListener(a -> { slide = (float) a.getAnimatedValue(); invalidate(); });
        flipAnim.start();
    }

    /** Scale and turnstile animations elsewhere move the pivot; focus must always grow from the centre. */
    void centrePivot() {
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight() / 2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (glow > 0) {
            // Contour glow: a soft halo in the tile's own colour, brightened, spilling past its edges.
            halo.setColor(lighten(color, 0.45f));
            halo.setAlpha((int) (255 * glow));
            drawGlow(c, w, h);
            drawGlow(c, w, h);
        }
        c.save();
        c.clipRect(0, 0, w, h);
        if (slide < 1 && prevFace != face) {
            c.save();
            c.translate(0, -slide * h);
            drawBackground(c, w, h);
            drawFace(c, faces[prevFace], w, h);
            c.restore();
            c.save();
            c.translate(0, (1 - slide) * h);
            drawBackground(c, w, h);
            drawFace(c, faces[face], w, h);
            c.restore();
        } else {
            drawBackground(c, w, h);
            drawFace(c, faces[face], w, h);
        }
        c.restore();
        if (glow > 0) {
            float o = contour.getStrokeWidth() / 2;
            contour.setColor(ringColor);
            contour.setAlpha((int) (255 * glow));
            c.drawRect(o, o, w - o, h - o, contour);
        }
    }

    /** Nine-slice the glow sprite around the tile: four corners plus four stretched edges. */
    private void drawGlow(Canvas c, int w, int h) {
        int r = glowR, size = glowSprite.getWidth(), m = size / 2;
        slice(c, 0, 0, r, r, -r, -r, 0, 0);
        slice(c, size - r, 0, size, r, w, -r, w + r, 0);
        slice(c, 0, size - r, r, size, -r, h, 0, h + r);
        slice(c, size - r, size - r, size, size, w, h, w + r, h + r);
        slice(c, m, 0, m + 1, r, 0, -r, w, 0);
        slice(c, m, size - r, m + 1, size, 0, h, w, h + r);
        slice(c, 0, m, r, m + 1, -r, 0, 0, h);
        slice(c, size - r, m, size, m + 1, w, 0, w + r, h);
    }

    private void slice(Canvas c, int sl, int st, int sr, int sb, float dl, float dt, float dr, float db) {
        gs.set(sl, st, sr, sb);
        dst.set(dl, dt, dr, db);
        c.drawBitmap(glowSprite, gs, dst, halo);
    }

    private void drawBackground(Canvas c, int w, int h) {
        fill.setColor(color);
        fill.setAlpha(TILE_ALPHA);
        c.drawRect(0, 0, w, h, fill);
    }

    private boolean wide(int w) { return w > 200 * d; }

    private boolean tall(int h) { return h > 200 * d; }

    private void drawFace(Canvas c, Face f, int w, int h) {
        boolean big = wide(w), huge = big && tall(h);
        float pad = (huge ? 22 : big ? 18 : 15) * d;
        if (f.art != null) drawArt(c, f.art, w, h);
        else if (big && glyph != null) drawWatermark(c, w, h);
        TextPaint titleBig = huge ? hugeP : bigP;

        if (f.type == ICON) {
            float box = isWordmark() ? (huge ? 34 : big ? 30 : 20) : (huge ? 64 : big ? 46 : 40);
            drawIcon(c, pad, pad, box * d, whiteGlyph);
            // A wordmark already spells the name out.
            if (isWordmark() && big) return;
            drawFitted(c, label, pad, h - pad, w - 2 * pad, big ? titleBig : titleP);
            return;
        }

        // Header: brand mark top-left, badge top-right.
        float top = pad;
        if (big) {
            float bh = (isWordmark() ? (huge ? 24 : 18) : (huge ? 28 : 22)) * d;
            float x = drawIcon(c, pad, top, bh, true);
            if (!isWordmark() && brand != null) {
                CharSequence b = upper(brand, brandP, w * .55f);
                c.drawText(b, 0, b.length(), px(x + 9 * d), px(top + bh / 2 + brandP.getTextSize() * .36f), brandP);
            }
        } else {
            drawIcon(c, pad, top, 30 * d, f.type == MEDIA || whiteGlyph);
        }
        if (f.badge != null) {
            if (big) drawBadge(c, f.badge, w - pad, top);
            else if (f.playing) {
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(w - pad - 4 * d, top + 5 * d, 4 * d, fill);
            }
        }

        // Footer, laid out from the bottom up.
        float y = h - pad;
        float textW = w - 2 * pad;
        if (f.progress >= 0) {
            float bh = (big ? 4 : 3) * d;
            fill.setColor(0x55000000);
            c.drawRect(pad, y - bh, w - pad, y, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawRect(pad, y - bh, pad + (w - 2 * pad) * Math.min(1f, Math.max(0f, f.progress)), y, fill);
            y -= bh + (big ? 12 : 9) * d;
        }
        if (f.sub != null) {
            drawFitted(c, f.sub, pad, y, textW, subP);
            y -= subP.getTextSize() + 7 * d;
        }
        if (f.title != null) {
            TextPaint tp = big ? titleBig : titleP;
            drawFitted(c, f.title, pad, y, textW, tp);
            y -= tp.getTextSize() + (big ? 7 : 6) * d;
        }
        if (f.caption != null) {
            CharSequence cap = upper(f.caption, capP, textW);
            c.drawText(cap, 0, cap.length(), px(pad), px(y), capP);
        }
    }

    boolean isWordmark() {
        return glyphBounds != null && glyphBounds.width() > glyphBounds.height() * 2.2f;
    }

    /** Draws the logo (or system glyph) scaled to a box height; returns the x where it ends. */
    private float drawIcon(Canvas c, float x, float y, float boxH, boolean forceWhite) {
        if (glyph != null && glyphBounds != null) {
            float s = boxH / glyphBounds.height();
            float maxW = isWordmark() ? boxH * 6 : boxH * 1.4f;
            if (glyphBounds.width() * s > maxW) s = maxW / glyphBounds.width();
            float gw = glyphBounds.width() * s, gh = glyphBounds.height() * s;
            dst.set(x, y + (boxH - gh) / 2, x + gw, y + (boxH + gh) / 2);
            if (forceWhite && mono != null) c.drawBitmap(mono, glyphBounds, dst, white);
            else c.drawBitmap(glyph, glyphBounds, dst, forceWhite && whiteGlyph ? white : bmp);
            return x + gw;
        }
        if (sysGlyph != Glyphs.NONE) {
            line.setColor(0xFFFFFFFF);
            Glyphs.draw(c, sysGlyph, x + boxH / 2, y + boxH / 2, boxH * .86f, line, Math.max(2f * d, boxH / 16), 0);
            return x + boxH;
        }
        return x;
    }

    /** Centre-crops the art to the tile, then lays the scrim over it. */
    private void drawArt(Canvas c, Bitmap art, int w, int h) {
        int aw = art.getWidth(), ah = art.getHeight();
        if (aw * h > ah * w) {
            int cw = ah * w / h;
            artSrc.set((aw - cw) / 2, 0, (aw + cw) / 2, ah);
        } else {
            int ch = aw * h / w;
            artSrc.set(0, (ah - ch) / 2, aw, (ah + ch) / 2);
        }
        dst.set(0, 0, w, h);
        c.drawBitmap(art, artSrc, dst, bmp);
        c.drawRect(0, 0, w, h, scrim);
    }

    private void drawWatermark(Canvas c, int w, int h) {
        if (glyphBounds == null) return;
        float gh = h * (tall(h) ? 0.6f : 0.78f), s = gh / glyphBounds.height();
        if (glyphBounds.width() * s > w * 0.6f) s = w * 0.6f / glyphBounds.width();
        float gw = glyphBounds.width() * s;
        gh = glyphBounds.height() * s;
        float right = w + gw * 0.12f, bottom = h + gh * 0.14f;
        dst.set(right - gw, bottom - gh, right, bottom);
        c.drawBitmap(mono != null ? mono : glyph, glyphBounds, dst, mark);
    }

    private void drawBadge(Canvas c, String s, float right, float top) {
        CharSequence t = upper(s, badgeP, 200 * d);
        float tw = badgeP.measureText(t, 0, t.length()), px = 8 * d, bh = 20 * d;
        dst.set(right - tw - 2 * px, top, right, top + bh);
        fill.setColor(darken(color, 0.38f));
        c.drawRoundRect(dst, 3 * d, 3 * d, fill);
        c.drawText(t, 0, t.length(), px(right - tw - px), px(top + bh / 2 + badgeP.getTextSize() * .36f), badgeP);
    }

    static int darken(int c, float by) {
        return Color.rgb((int) (Color.red(c) * (1 - by)), (int) (Color.green(c) * (1 - by)), (int) (Color.blue(c) * (1 - by)));
    }

    static int lighten(int c, float by) {
        return Color.rgb((int) (Color.red(c) + (255 - Color.red(c)) * by), (int) (Color.green(c) + (255 - Color.green(c)) * by),
                (int) (Color.blue(c) + (255 - Color.blue(c)) * by));
    }

    private void drawFitted(Canvas c, String s, float x, float y, float max, TextPaint p) {
        if (s == null) return;
        CharSequence t = fitted.get(s);
        if (t == null) {
            t = TextUtils.ellipsize(s, p, max, TextUtils.TruncateAt.END);
            fitted.put(s, t);
        }
        c.drawText(t, 0, t.length(), px(x), px(y), p);
    }

    /** Upper-cased and ellipsized once, then reused. */
    private CharSequence upper(String s, TextPaint p, float max) {
        CharSequence t = fittedUpper.get(s);
        if (t == null) {
            t = TextUtils.ellipsize(s.toUpperCase(), p, max, TextUtils.TruncateAt.END);
            fittedUpper.put(s, t);
        }
        return t;
    }

    @Override
    protected void onFocusChanged(boolean f, int dir, Rect prev) {
        super.onFocusChanged(f, dir, prev);
        centrePivot();
        animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(150).start();
        // No elevation: its shadow is invisible on black and costs GPU time; the grid draws us last instead.
        if (getParent() instanceof View) ((View) getParent()).invalidate();
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofFloat(glow, f ? 1 : 0);
        glowAnim.setDuration(f ? 180 : 120);
        glowAnim.addUpdateListener(a -> { glow = (float) a.getAnimatedValue(); invalidate(); });
        glowAnim.start();
    }

    @Override
    public void setPressed(boolean p) {
        if (p != isPressed()) {
            // Windows 8 press feedback: the tile sinks in.
            centrePivot();
            float s = p ? 0.97f : (isFocused() ? 1.04f : 1f);
            animate().scaleX(s).scaleY(s).setDuration(90).start();
        }
        super.setPressed(p);
    }
}
