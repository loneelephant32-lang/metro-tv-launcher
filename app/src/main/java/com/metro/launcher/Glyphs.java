package com.metro.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Line-art system icons in the spirit of the Windows 8 glyphs, drawn with whatever colour the paint has. */
final class Glyphs {
    static final int NONE = 0, CLOSE = 1, RECENT = 2, PERSONALIZE = 3, APPS = 4, MUSIC = 5, SETTINGS = 6,
            WIFI = 7, ETHERNET = 8, NO_NETWORK = 9, MEMORY = 10;

    private static final Path path = new Path();
    private static final RectF oval = new RectF();

    /** @param s the glyph's box size; @param level Wi-Fi bars 0-3 (ignored for other glyphs) */
    static void draw(Canvas c, int g, float cx, float cy, float s, Paint p, float strokeW, int level) {
        float r = s / 2;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeW);
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (g) {
            case CLOSE:
                c.drawCircle(cx, cy, r, p);
                c.drawLine(cx - r * .38f, cy - r * .38f, cx + r * .38f, cy + r * .38f, p);
                c.drawLine(cx + r * .38f, cy - r * .38f, cx - r * .38f, cy + r * .38f, p);
                break;
            case RECENT:
                c.drawRect(cx - r * .55f, cy - r * .8f, cx + r, cy + r * .3f, p);
                c.drawRect(cx - r, cy - r * .3f, cx + r * .45f, cy + r * .8f, p);
                break;
            case PERSONALIZE:
                for (int i = 0; i < 3; i++) {
                    float y = cy - r * .62f + i * r * .62f, k = cx + (i == 1 ? r * .38f : -r * .32f);
                    c.drawLine(cx - r, y, cx + r, y, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(k, y, r * .22f, p);
                    p.setStyle(Paint.Style.STROKE);
                }
                break;
            case APPS:
                c.drawCircle(cx, cy, r, p);
                c.drawLine(cx, cy - r * .45f, cx, cy + r * .42f, p);
                c.drawLine(cx - r * .36f, cy + r * .06f, cx, cy + r * .42f, p);
                c.drawLine(cx + r * .36f, cy + r * .06f, cx, cy + r * .42f, p);
                break;
            case MUSIC:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - r * .35f, cy + r * .55f, r * .32f, p);
                c.drawRect(cx - r * .1f, cy - r * .85f, cx + r * .06f, cy + r * .55f, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx, cy - r * .8f, cx + r * .55f, cy - r * .45f, p);
                break;
            case SETTINGS: {
                // A gear: eight teeth around a ring, with the hole punched out.
                path.reset();
                int teeth = 8;
                for (int i = 0; i < teeth * 4; i++) {
                    double a = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2;
                    float rr = (i % 4 == 0 || i % 4 == 1) ? r : r * .74f;
                    float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                path.addCircle(cx, cy, r * .32f, Path.Direction.CW);
                path.setFillType(Path.FillType.EVEN_ODD);
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            }
            case WIFI:
            case NO_NETWORK: {
                float by = cy + r * .7f;
                int alpha = p.getAlpha();
                for (int i = 1; i <= 3; i++) {
                    float rr = r * (0.38f + 0.34f * (i - 1)) * 1.15f;
                    oval.set(cx - rr, by - rr, cx + rr, by + rr);
                    p.setAlpha(g == WIFI && i <= level ? alpha : alpha / 3);
                    c.drawArc(oval, 225, 90, false, p);
                }
                p.setAlpha(alpha);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, by - r * .05f, strokeW * .9f, p);
                if (g == NO_NETWORK) {
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx - r * .8f, cy - r * .8f, cx + r * .8f, cy + r * .8f, p);
                }
                break;
            }
            case ETHERNET: {
                float q = r * .34f;
                c.drawRect(cx - q, cy - r, cx + q, cy - r + q * 1.6f, p);
                c.drawLine(cx, cy - r + q * 1.6f, cx, cy + r * .1f, p);
                c.drawLine(cx - r * .75f, cy + r * .1f, cx + r * .75f, cy + r * .1f, p);
                for (float x : new float[]{cx - r * .75f, cx + r * .75f}) {
                    c.drawLine(x, cy + r * .1f, x, cy + r * .4f, p);
                    c.drawRect(x - q * .8f, cy + r * .4f, x + q * .8f, cy + r, p);
                }
                break;
            }
            case MEMORY: {
                float q = r * .62f;
                c.drawRect(cx - q, cy - q, cx + q, cy + q, p);
                for (int i = -1; i <= 1; i++) {
                    float o = i * q * .55f;
                    c.drawLine(cx - q, cy + o, cx - r, cy + o, p);
                    c.drawLine(cx + q, cy + o, cx + r, cy + o, p);
                    c.drawLine(cx + o, cy - q, cx + o, cy - r, p);
                    c.drawLine(cx + o, cy + q, cx + o, cy + r, p);
                }
                break;
            }
            default:
                break;
        }
        p.setStyle(Paint.Style.STROKE);
    }

    private Glyphs() { }
}
