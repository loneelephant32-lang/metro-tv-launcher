package com.metro.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * Screensaver: Bing's images of the day, crossfading one into the next with a slow drift, and the
 * time and date at the bottom left like the Windows 8 lock screen. Switched on from Personalize, which
 * enables this service; Android's screensaver settings choose it.
 */
public class BingDream extends DreamService {
    static final long SHOW_MS = 25_000, FADE_MS = 1500;

    private final Handler h = new Handler();
    private ImageView[] views;
    private FrameLayout photos;
    private int front, index;
    private TextView time, date, caption;
    private List<Bing.Image> images;
    private float d;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // The two photos crossfade inside their own layer, so the shade and text always stay on top.
        photos = new FrameLayout(this);
        views = new ImageView[]{new ImageView(this), new ImageView(this)};
        for (ImageView v : views) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            photos.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(photos, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        time = text(64, Fonts.REG);
        date = text(18, Fonts.REG);
        clock.addView(time);
        clock.addView(date);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        cl.setMargins(px(56), 0, 0, px(44));
        root.addView(clock, cl);
        caption = text(11.5f, Fonts.REG);
        caption.setTextColor(0xD9FFFFFF);
        caption.setMaxWidth(px(420));
        caption.setGravity(Gravity.END);
        FrameLayout.LayoutParams capl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        capl.setMargins(0, 0, px(56), px(48));
        root.addView(caption, capl);
        setContentView(root);

        images = Bing.list(this);
        if (images.isEmpty()) {
            caption.setText("Bing images appear here once Metro has downloaded them (Internet access must be on).");
            Bing.refresh(this, () -> { images = Bing.list(this); if (!images.isEmpty()) next(); });
        } else {
            index = new Random().nextInt(images.size());
            Bing.refresh(this, () -> images = Bing.list(this));
            next();
        }
        tick.run();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            time.setText(DateFormat.getTimeFormat(BingDream.this).format(now));
            date.setText(android.text.format.DateFormat.format("EEEE, d MMMM", now));
            h.postDelayed(this, 10_000);
        }
    };

    private final Runnable rotate = this::next;

    /** Crossfades to the next image and starts its slow drift; the old one fades out underneath. */
    private void next() {
        h.removeCallbacks(rotate);
        if (images.isEmpty()) return;
        Bing.Image im = images.get(index++ % images.size());
        Bitmap bm = Bing.load(this, im);
        if (bm == null) { h.postDelayed(rotate, 1000); return; }
        ImageView out = views[front], in = views[1 - front];
        front = 1 - front;
        Bitmap old = in.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) in.getDrawable()).getBitmap() : null;
        in.animate().cancel();
        in.setImageBitmap(bm);
        if (old != null && old != bm) old.recycle();
        in.bringToFront();
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setAlpha(0f);
        // Fade and drift run on separate timers: the fade is quick, the drift lasts the whole showing.
        android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(in, View.ALPHA, 0f, 1f).setDuration(FADE_MS);
        android.animation.ObjectAnimator drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(in,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)).setDuration(SHOW_MS + 2 * FADE_MS);
        drift.setInterpolator(null);
        fade.start();
        drift.start();
        out.animate().alpha(0f).setStartDelay(FADE_MS / 2).setDuration(FADE_MS).start();
        caption.setText(Bing.caption(im));
        h.postDelayed(rotate, SHOW_MS);
    }

    @Override
    public void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private TextView text(float sp, android.graphics.Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(px(4), 0, px(1), 0xAA000000);
        return t;
    }

    private int px(float dp) { return Math.round(dp * d); }
}
