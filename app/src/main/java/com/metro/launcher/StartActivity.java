package com.metro.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class StartActivity extends Activity implements Media.Listener {
    /** The Windows 8 start-screen accent palette. */
    static final int[] PALETTE = {
            0xFF1BA1E2, 0xFF00ABA9, 0xFF60A917, 0xFFF09609, 0xFFD80073, 0xFF0050EF, 0xFFA20025, 0xFF6A00FF,
            0xFF008A00, 0xFFFA6800, 0xFFAA00FF, 0xFFE51400, 0xFFA4C400, 0xFFF472D0, 0xFF825A2C, 0xFFE3C800,
            0xFF647687, 0xFF76608A, 0xFF6D8764, 0xFF87794E};
    /** Accent value meaning "every tile takes its app's own colour". */
    static final int APP_COLOURS = 0;
    static final int BG_BLACK = 0, BG_WHITE = 1, BG_IMAGE = 2, BG_MIDNIGHT = 3, BG_AURORA = 4, BG_EMBER = 5, BG_GRAPHITE = 6;
    /** Dark themes: a subtle diagonal fade, top-left to bottom-right. */
    static final int[][] THEME_GRADIENTS = {
            {0xFF101B3A, 0xFF080C1C, 0xFF030407},   // Midnight: deep navy into black
            {0xFF07302C, 0xFF0B0F24, 0xFF22102E},   // Aurora: teal, indigo, plum
            {0xFF3A0E12, 0xFF180809, 0xFF060505},   // Ember: maroon into charcoal
            {0xFF262B35, 0xFF15181E, 0xFF090A0C}};  // Graphite: blue-grey slate
    static final String[] THEME_NAMES = {"Midnight", "Aurora", "Ember", "Graphite"};

    static boolean isGradient(int mode) { return mode >= BG_MIDNIGHT && mode <= BG_GRAPHITE; }

    static android.graphics.drawable.GradientDrawable gradient(int mode) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, THEME_GRADIENTS[mode - BG_MIDNIGHT]);
        g.setDither(true);
        return g;
    }
    static final String SYS_SYSTEM = "sys_system";
    /** First-run Start screen, in order; matched against package names. */
    static final String[] DEFAULT_PINS = {"netflix", "youtube", SYS_SYSTEM, "spotify", "amazonvideo", "hotstar",
            "crunchyroll", "vlc", "com.android.vending"};
    static final String[] DEFAULT_WIDE = {"netflix", "spotify", "crunchyroll", "plex", "disney"};
    /** Setup and service stubs that shouldn't even be listed in Apps. */
    static final String[] NOT_APPS = {"initsetup", "setupwizard", "rcu_service"};

    static class App {
        String pkg, label, activity, cat;
        Bitmap glyph;
        int color;
        boolean white, picture;

        Intent intent() {
            return new Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setComponent(new ComponentName(pkg, activity))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    /**
     * Scrolls only when the focused tile would leave the screen, and then brings it well into view.
     * On this TV a scrolling frame repaints the whole screen (~20 ms of GPU), while moving between
     * tiles that are already visible only repaints those two tiles, so avoiding needless scrolls is
     * what keeps navigation smooth.
     */
    static class CenteringScroller extends HorizontalScrollView {
        final int margin;

        CenteringScroller(Context c) {
            super(c);
            margin = Math.round(40 * c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            if (getChildCount() == 0) return 0;
            int left = getScrollX(), right = left + getWidth();
            if (r.left >= left + margin && r.right <= right - margin) return 0;
            int max = Math.max(0, getChildAt(0).getWidth() - getWidth());
            int target = Math.max(0, Math.min(max, r.centerX() - getWidth() / 2));
            return target - getScrollX();
        }
    }

    /** A round status-bar button holding one glyph; fills in when focused. */
    class StatusButton extends View {
        int glyph, level;
        final String name;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        StatusButton(int glyph, String name, View.OnClickListener click) {
            super(StartActivity.this);
            this.glyph = glyph;
            this.name = name;
            setFocusable(true);
            setClickable(true);
            setContentDescription(name);
            setOnClickListener(click);
            setOnFocusChangeListener((v, f) -> {
                invalidate();
                statusHint.setText(f ? name : "");
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            boolean light = prefs.getInt("bg", BG_BLACK) == BG_WHITE;
            int fg = light ? 0xFF1D1D1D : Color.WHITE;
            if (isFocused()) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(fg);
                c.drawCircle(cx, cy, getWidth() / 2f, p);
                fg = light ? Color.WHITE : 0xFF1D1D1D;
            }
            p.setColor(fg);
            Glyphs.draw(c, glyph, cx, cy, getWidth() * .46f, p, dp(1.8f), level);
        }
    }

    SharedPreferences prefs;
    ActivityManager am;
    Media media;
    final Handler h = new Handler();
    final Random rnd = new Random();
    float d;

    FrameLayout root, flyoutLayer;
    LinearLayout content;
    ImageView bgImage;
    TextView startButton, clock, date, statusHint;
    StatusButton wifiButton;
    HorizontalScrollView scroller;
    TileGrid grid;
    FrameLayout splash;
    ImageView splashIcon;
    TileView systemTile;
    final TileView.Face ramFace = TileView.Face.text("System", "", "", 0), diskFace = TileView.Face.text("Storage", "", "", 0);

    final Map<String, App> apps = new LinkedHashMap<>();
    final List<TileView> tiles = new ArrayList<>();
    final Set<String> wasPlaying = new HashSet<>();
    List<String> recentList = new ArrayList<>();
    /** True from the moment a flyout opens until it starts closing (its slide-out still shows it). */
    boolean flyoutOpen;
    boolean launching, entrancePending = true, scanning, resumed, launchedApp;
    String focusKey;
    View lastFocus;
    TileView lastFlipped;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("metro", MODE_PRIVATE);
        am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        d = getResources().getDisplayMetrics().density;
        media = Media.get(this);
        media.start();
        buildUi();
        applyBackground();
        // Draw straight from the icon cache so Home never waits on PackageManager; refresh behind it.
        if (loadCachedApps()) buildTiles();
        rescanAsync();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        // Let go of the other apps' resources that loading their icons pulled in.
        h.postDelayed(() -> { trimGlyphs(); Runtime.getRuntime().gc(); }, 4000);
    }

    final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { rescanAsync(); }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packages);
        media.listener = null;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        entrancePending = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // An app that only covered part of the screen (a side panel, an overlay) never stops us, so
        // onRestart doesn't run: Start must be brought back after any launch, not only after onStop.
        if (entrancePending || launchedApp) {
            entrancePending = false;
            launchedApp = false;
            restoreStart();
            playEntrance();
        } else if (launching) {
            restoreStart();
        }
        media.listener = this;
        media.start();
        onMediaChanged();
        refreshRecentsAsync();
        h.post(ticker);
        h.postDelayed(flipper, 2500);
        if (bgImage.getDrawable() == null && prefs.getInt("bg", BG_BLACK) == BG_IMAGE) applyBackground();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(ticker);
        h.removeCallbacks(flipper);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Start isn't visible: drop the background image until we come back.
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            bgImage.setImageDrawable(null);
            trimGlyphs();
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // Home always brings back a fully visible Start, even mid-launch.
        if (launching || launchedApp) {
            launchedApp = false;
            restoreStart();
        }
        // Home pressed while already on Start: close whatever is open and jump back to the first tile.
        if (closeFlyout()) return;
        scroller.smoothScrollTo(0, 0);
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    @Override
    public void onBackPressed() { closeFlyout(); }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && flyoutOpen) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof App) appMenu((App) f.getTag());
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU && !flyoutOpen) {
            View f = getCurrentFocus();
            if (f instanceof TileView) tileMenu((TileView) f);
            else if (f != null && f.getTag() instanceof App) appMenu((App) f.getTag());
            else showSettings();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshStats();
            refreshProgress();
            refreshNetwork();
            h.postDelayed(this, 2000);
        }
    };

    /** Every few seconds one live tile on screen peeks to its next face, as on Windows 8. */
    final Runnable flipper = new Runnable() {
        @Override public void run() {
            h.postDelayed(this, 3000 + rnd.nextInt(2000));
            if (!animations() || launching || flyoutOpen) return;
            List<TileView> live = new ArrayList<>();
            for (TileView t : tiles) if (t.canFlip() && t != lastFlipped && onScreen(t)) live.add(t);
            if (live.isEmpty()) return;
            lastFlipped = live.get(rnd.nextInt(live.size()));
            lastFlipped.flip();
        }
    };

    // ---------------------------------------------------------------- layout

    void buildUi() {
        root = new FrameLayout(this);
        bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bgImage, match());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        root.addView(content, match());

        FrameLayout header = new FrameLayout(this);
        header.setPadding(dp(42), dp(24), dp(52), 0);
        header.setClipChildren(false);
        // "Start" is the Start button: it opens the alphabetical Apps list.
        startButton = text("Start", 32, Fonts.REG);
        startButton.setPadding(dp(10), dp(4), dp(12), dp(6));
        startButton.setFocusable(true);
        startButton.setClickable(true);
        startButton.setOnClickListener(v -> openApps());
        header.addView(startButton, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        StatusButton personalize = new StatusButton(Glyphs.PERSONALIZE, "Personalize", v -> showSettings());
        StatusButton recent = new StatusButton(Glyphs.RECENT, "Recent apps", v -> showRecents());
        StatusButton settings = new StatusButton(Glyphs.SETTINGS, "Settings", v -> startSafe(new Intent(Settings.ACTION_SETTINGS)));
        wifiButton = new StatusButton(Glyphs.WIFI, "Network", v -> startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        for (View v : new View[]{personalize, recent, settings, wifiButton}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.leftMargin = dp(6);
            right.addView(v, lp);
        }
        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.END);
        clock = text("", 22, Fonts.REG);
        date = text("", 11.5f, Fonts.REG);
        date.setPadding(0, dp(4), 0, 0);
        time.addView(clock);
        time.addView(date);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(20);
        right.addView(time, tlp);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        rlp.topMargin = dp(4);
        header.addView(right, rlp);
        statusHint = text("", 11, Fonts.MED);
        statusHint.setLetterSpacing(0.06f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        hlp.topMargin = dp(52);
        hlp.rightMargin = dp(110);
        header.addView(statusHint, hlp);
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        scroller = new CenteringScroller(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setSmoothScrollingEnabled(true);
        grid = new TileGrid(this, 2, 160, 160, 14);
        grid.setPadding(dp(52), dp(26), dp(52), dp(26));
        scroller.addView(grid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1);
        slp.bottomMargin = dp(14);
        content.addView(scroller, slp);

        splash = new FrameLayout(this);
        splash.setVisibility(View.GONE);
        splashIcon = new ImageView(this);
        splash.addView(splashIcon, new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
        root.addView(splash, match());

        flyoutLayer = new FrameLayout(this);
        flyoutLayer.setVisibility(View.GONE);
        flyoutLayer.setOnClickListener(v -> closeFlyout());
        root.addView(flyoutLayer, match());

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            if (n != null && !isInside(n, flyoutLayer)) lastFocus = n;
        });
        setContentView(root);
    }

    static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    TextView text(String s, float sp, android.graphics.Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(Color.WHITE);
        t.setIncludeFontPadding(false);
        return t;
    }

    int dp(float v) { return (int) (v * d + 0.5f); }

    static FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }

    // ---------------------------------------------------------------- apps, icon cache

    File iconFile(String pkg) {
        File dir = new File(getCacheDir(), "ic");
        dir.mkdirs();
        return new File(dir, pkg + ".png");
    }

    /** Rebuild the app list from the last scan; false if there's nothing usable cached yet. */
    boolean loadCachedApps() {
        String all = prefs.getString("apps", "");
        if (all.isEmpty()) return false;
        for (String line : all.split("\u001e")) {
            String[] f = line.split("\u001f", -1);
            if (f.length < 4) continue;
            String[] meta = prefs.getString("ic_" + f[0], "").split(":");
            if (meta.length < 4) continue;
            if (!iconFile(f[0]).exists()) continue;
            App a = new App();
            a.pkg = f[0];
            a.activity = f[1];
            a.cat = f[2];
            a.label = f[3];
            a.color = Integer.parseInt(meta[2]);
            a.white = "1".equals(meta[3]);
            a.picture = meta.length > 4 && "1".equals(meta[4]);
            apps.put(a.pkg, a);
        }
        return !apps.isEmpty();
    }

    /** Logos are only kept in memory for what's on screen; everything else is decoded on demand. */
    Bitmap glyph(App a) {
        if (a.glyph == null) a.glyph = BitmapFactory.decodeFile(iconFile(a.pkg).getPath());
        return a.glyph;
    }

    /** Drop logos of apps that aren't pinned (they come back from the disk cache when needed). */
    void trimGlyphs() {
        Set<String> keep = new HashSet<>(pins());
        for (App a : apps.values()) if (!keep.contains(a.pkg)) a.glyph = null;
    }

    /** The app's highest-density icon (XXXHDPI art is far sharper than the TV-density one), else the normal icon. */
    static android.graphics.drawable.Drawable sharpIcon(PackageManager pm, ResolveInfo ri) {
        try {
            int id = ri.getIconResource();
            if (id != 0) {
                android.content.res.Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                android.graphics.drawable.Drawable hi = r.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
                if (hi != null) return hi;
            }
        } catch (Exception ignored) { }
        return ri.loadIcon(pm);
    }

    void rescanAsync() {
        if (scanning) return;
        scanning = true;
        final Map<String, App> old = new HashMap<>(apps);
        final PackageManager pm = getPackageManager();
        // Logos are drawn up to ~46 dp on wide tiles and as watermarks; extract with headroom so they stay sharp.
        final int px = dp(120);
        new Thread(() -> {
            Map<String, App> found = new LinkedHashMap<>();
            StringBuilder list = new StringBuilder();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                    App a = new App();
                    a.pkg = pkg;
                    a.activity = ri.activityInfo.name;
                    a.cat = cat;
                    long updated = 0;
                    try { updated = pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (Exception ignored) { }
                    String stamp = Icons.VERSION + ":" + updated;
                    String meta = prefs.getString("ic_" + pkg, "");
                    App prev = old.get(pkg);
                    // Loading a label loads that app's resources; reuse ours unless the app was updated.
                    a.label = prev != null && meta.startsWith(stamp + ":") ? prev.label : String.valueOf(ri.loadLabel(pm));
                    if (meta.startsWith(stamp + ":") && prev != null) {
                        a.glyph = prev.glyph;
                        a.color = prev.color;
                        a.white = prev.white;
                        a.picture = prev.picture;
                    } else {
                        boolean cached = meta.startsWith(stamp + ":") && iconFile(pkg).exists();
                        if (cached) {
                            String[] m = meta.split(":");
                            a.color = Integer.parseInt(m[2]);
                            a.white = "1".equals(m[3]);
                            a.picture = m.length > 4 && "1".equals(m[4]);
                        } else {
                            Icons ic = Icons.extract(sharpIcon(pm, ri), px);
                            a.glyph = ic.glyph;
                            a.color = ic.color;
                            a.white = ic.white;
                            a.picture = ic.picture;
                            try (FileOutputStream out = new FileOutputStream(iconFile(pkg))) {
                                ic.glyph.compress(Bitmap.CompressFormat.PNG, 100, out);
                            } catch (Exception ignored) { }
                            prefs.edit().putString("ic_" + pkg, stamp + ":" + a.color + ":" + (a.white ? 1 : 0) + ":" + (a.picture ? 1 : 0)).apply();
                        }
                    }
                    found.put(pkg, a);
                    if (list.length() > 0) list.append('\u001e');
                    list.append(pkg).append('\u001f').append(a.activity).append('\u001f').append(cat).append('\u001f')
                            .append(a.label.replace('\u001e', ' ').replace('\u001f', ' '));
                }
            }
            prefs.edit().putString("apps", list.toString()).apply();
            h.post(() -> {
                scanning = false;
                boolean same = found.size() == apps.size();
                if (same) {
                    for (App a : found.values()) {
                        App p = apps.get(a.pkg);
                        if (p == null || !p.label.equals(a.label) || p.color != a.color || p.white != a.white) { same = false; break; }
                        // Keep whatever logo is already loaded.
                        if (a.glyph == null) a.glyph = p.glyph;
                    }
                }
                if (same && !tiles.isEmpty()) return;
                apps.clear();
                apps.putAll(found);
                buildTiles();
            });
        }).start();
    }

    // ---------------------------------------------------------------- pins

    /** What's pinned to Start, in order. Until the user changes it, a sensible default set. */
    List<String> pins() {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString("pins", null);
        if (saved != null) {
            for (String k : saved.split(",")) if ((apps.containsKey(k) || k.equals(SYS_SYSTEM)) && !out.contains(k)) out.add(k);
            return out;
        }
        for (String want : DEFAULT_PINS) {
            if (want.equals(SYS_SYSTEM)) { out.add(SYS_SYSTEM); continue; }
            for (App a : apps.values()) if (a.pkg.contains(want) && !out.contains(a.pkg)) { out.add(a.pkg); break; }
        }
        return out;
    }

    void savePins(List<String> p) { prefs.edit().putString("pins", TextUtils.join(",", p)).apply(); }

    boolean pinned(String key) { return pins().contains(key); }

    void pin(String key, boolean on) {
        List<String> p = pins();
        p.remove(key);
        if (on) p.add(key);
        savePins(p);
        if (on) focusKey = key;
        buildTiles();
    }

    static boolean listed(App a) {
        for (String s : NOT_APPS) if (a.pkg.contains(s)) return false;
        return !a.label.equalsIgnoreCase("Initial Setup");
    }

    int accent() { return prefs.getInt("accent", APP_COLOURS); }

    /** Own colour if the user picked one, else the accent, else the app's (or built-in tile's) colour. */
    int tileColor(String key, int natural) {
        int own = prefs.getInt("color_" + key, 0);
        if (own != 0) return own;
        return accent() != APP_COLOURS ? accent() : natural;
    }

    /** Logos go white whenever the tile isn't wearing the app's own colour. */
    boolean whiteGlyph(App a) {
        if (a.picture) return false;
        return a.white || accent() != APP_COLOURS || prefs.getInt("color_" + a.pkg, 0) != 0;
    }

    /** 1 = small square (1x1), 2 = wide (2x1), 3 = large square (2x2). */
    int tileSize(String key) {
        int def = 1;
        for (String w : DEFAULT_WIDE) if (key.contains(w)) def = 2;
        return prefs.getInt("size_" + key, def);
    }

    static TileGrid.LP sizeLp(int size) {
        return size == 3 ? new TileGrid.LP(2, 2) : new TileGrid.LP(size == 2 ? 2 : 1, 1);
    }

    void buildTiles() {
        View cur = getCurrentFocus();
        if (cur instanceof TileView) focusKey = key((TileView) cur);
        grid.removeAllViews();
        tiles.clear();
        systemTile = null;

        for (String k : pins()) {
            TileView t = new TileView(this);
            if (k.equals(SYS_SYSTEM)) {
                t.tag2 = k;
                t.label = "System";
                t.sysGlyph = Glyphs.MEMORY;
                t.setColor(tileColor(k, 0xFF00ABA9));
                t.setFaces(ramFace, diskFace);
                t.setOnClickListener(v -> showRecents());
                systemTile = t;
            } else {
                App a = apps.get(k);
                t.tag2 = a;
                t.label = a.label;
                t.brand = a.label;
                t.setGlyph(glyph(a), whiteGlyph(a));
                t.setColor(tileColor(k, a.color));
                t.setOnClickListener(v -> launch(v, (App) ((TileView) v).tag2));
            }
            t.setOnLongClickListener(v -> { tileMenu((TileView) v); return true; });
            t.setOnFocusChangeListener((v, f) -> { if (f) focusKey = key((TileView) v); });
            grid.addView(t, sizeLp(tileSize(k)));
            tiles.add(t);
        }
        applyBackgroundColors();
        refreshStats();
        wasPlaying.clear();
        onMediaChanged();
        View restore = tiles.isEmpty() ? startButton : tiles.get(0);
        if (focusKey != null) for (TileView t : tiles) if (focusKey.equals(key(t))) restore = t;
        if (!flyoutOpen) restore.requestFocus();
    }

    static String key(TileView t) { return t.tag2 instanceof App ? ((App) t.tag2).pkg : (String) t.tag2; }

    TileView tileFor(String key) {
        for (TileView t : tiles) if (key.equals(key(t))) return t;
        return null;
    }

    // ---------------------------------------------------------------- live tiles

    @Override
    public void onMediaChanged() {
        for (TileView t : tiles) {
            if (!(t.tag2 instanceof App)) continue;
            Media.Item it = media.item(((App) t.tag2).pkg);
            if (it == null) { if (t.faces.length > 1) t.setFaces(TileView.Face.icon()); continue; }
            TileView.Face f = new TileView.Face();
            f.type = TileView.MEDIA;
            f.live = true;
            f.playing = it.playing;
            f.caption = it.playing ? "Now playing" : "Recently played";
            f.title = it.title;
            f.sub = it.sub;
            f.badge = it.playing ? "Playing" : "Resume";
            f.progress = it.progress();
            t.setFaces(TileView.Face.icon(), f);
            String k = key(t);
            if (it.playing && !wasPlaying.contains(k)) {
                // Jump straight to the live face the moment something starts playing.
                wasPlaying.add(k);
                if (t.face != 1) { if (resumed && animations()) t.flip(); else t.showFace(1); }
            } else if (!it.playing) {
                wasPlaying.remove(k);
            }
        }
    }

    void refreshProgress() {
        for (TileView t : tiles) {
            if (!(t.tag2 instanceof App) || t.faces.length < 2) continue;
            Media.Item it = media.item(((App) t.tag2).pkg);
            if (it == null || !it.playing) continue;
            t.faces[1].progress = it.progress();
            t.invalidate();
        }
    }

    void refreshStats() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
        ramFace.caption = "Memory";
        ramFace.title = Math.round(100f * used / mi.totalMem) + "% used";
        ramFace.sub = gb(mi.availMem) + " free of " + gb(mi.totalMem);
        ramFace.progress = used / (float) mi.totalMem;
        diskFace.caption = "Storage";
        diskFace.title = Math.round(100f * (total - free) / total) + "% used";
        diskFace.sub = gb(free) + " free of " + gb(total);
        diskFace.progress = (total - free) / (float) total;
        if (systemTile != null) systemTile.invalidate();

        Date now = new Date();
        setIfChanged(clock, DateFormat.getTimeFormat(this).format(now));
        setIfChanged(date, DateFormat.format("EEEE, d MMMM", now));
    }

    void refreshNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        int g = Glyphs.NO_NETWORK, level = 0;
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                g = Glyphs.ETHERNET;
            } else {
                g = Glyphs.WIFI;
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                level = wm == null ? 3 : Math.max(1, WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4));
            }
        }
        if (g != wifiButton.glyph || level != wifiButton.level) {
            wifiButton.glyph = g;
            wifiButton.level = level;
            wifiButton.invalidate();
        }
    }

    /** Re-setting identical text still triggers a full layout pass of Start; skip it. */
    static void setIfChanged(TextView t, CharSequence s) {
        if (!TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    static String gb(long b) {
        if (b < 1L << 30) return (b >> 20) + " MB";
        return String.format(java.util.Locale.US, "%.1f GB", b / (float) (1L << 30));
    }

    // ---------------------------------------------------------------- recents & closing apps

    /** Usage stats are a binder round-trip per query, so keep them off the UI thread. */
    void refreshRecentsAsync() {
        final Set<String> known = new HashSet<>(apps.keySet());
        new Thread(() -> {
            List<String> r = recents(known);
            h.post(() -> recentList = r);
        }).start();
    }

    List<String> recents(Set<String> known) {
        List<String> out = usageRecents(known);
        if (out != null) return out;
        out = new ArrayList<>();
        for (String s : prefs.getString("recent", "").split(",")) {
            int c = s.indexOf(':');
            if (c > 0 && known.contains(s.substring(0, c))) out.add(s);
        }
        return out;
    }

    /** Apps used in the last 12 h from the system's usage stats, or null when usage access isn't granted. */
    List<String> usageRecents(Set<String> known) {
        android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
                != android.app.AppOpsManager.MODE_ALLOWED) return null;
        android.app.usage.UsageStatsManager um = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis(), since = now - 12 * 3600_000L;
        long closedAt = prefs.getLong("closedAt", 0);
        final Map<String, Long> last = new HashMap<>();
        for (android.app.usage.UsageStats u : um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, since, now)) {
            String pkg = u.getPackageName();
            long t = u.getLastTimeUsed();
            if (t < since || !known.contains(pkg) || u.getTotalTimeInForeground() <= 0) continue;
            // Hide apps the user closed from here, until they're used again.
            if (t <= Math.max(closedAt, prefs.getLong("closed_" + pkg, 0))) continue;
            Long prev = last.get(pkg);
            if (prev == null || t > prev) last.put(pkg, t);
        }
        List<String> pk = new ArrayList<>(last.keySet());
        Collections.sort(pk, (a, b) -> Long.compare(last.get(b), last.get(a)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pk.size() && i < 10; i++) out.add(pk.get(i) + ":" + last.get(pk.get(i)));
        return out;
    }

    void pushRecent(String pkg) {
        List<String> r = new ArrayList<>();
        for (String s : prefs.getString("recent", "").split(",")) if (s.indexOf(':') > 0 && !s.startsWith(pkg + ":")) r.add(s);
        r.add(0, pkg + ":" + System.currentTimeMillis());
        while (r.size() > 10) r.remove(r.size() - 1);
        prefs.edit().putString("recent", TextUtils.join(",", r)).apply();
    }

    void closeApp(String pkg) {
        am.killBackgroundProcesses(pkg);
        List<String> r = new ArrayList<>();
        for (String s : prefs.getString("recent", "").split(",")) if (s.indexOf(':') > 0 && !s.startsWith(pkg + ":")) r.add(s);
        prefs.edit().putLong("closed_" + pkg, System.currentTimeMillis()).putString("recent", TextUtils.join(",", r)).apply();
        for (int i = recentList.size() - 1; i >= 0; i--) if (recentList.get(i).startsWith(pkg + ":")) recentList.remove(i);
    }

    void closeAll() {
        final long before = availMem();
        for (String pkg : apps.keySet()) am.killBackgroundProcesses(pkg);
        prefs.edit().remove("recent").putLong("closedAt", System.currentTimeMillis()).apply();
        recentList = new ArrayList<>();
        // Memory is returned asynchronously; measure after the processes have died.
        h.postDelayed(() -> {
            long freed = Math.max(0, availMem() - before);
            refreshStats();
            toast("Closed all apps · " + gb(freed) + " freed");
        }, 900);
    }

    long availMem() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    void toast(String s) {
        final TextView t = text(s, 14, Fonts.REG);
        t.setBackgroundColor(0xEE1D1D1D);
        t.setPadding(dp(18), dp(11), dp(18), dp(11));
        root.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        ((FrameLayout.LayoutParams) t.getLayoutParams()).setMargins(0, 0, dp(52), dp(24));
        t.setTranslationX(dp(40));
        t.setAlpha(0);
        t.animate().translationX(0).alpha(1).setDuration(250).setInterpolator(new DecelerateInterpolator()).start();
        h.postDelayed(() -> t.animate().alpha(0).setDuration(250).withEndAction(() -> root.removeView(t)).start(), 2600);
    }

    // ---------------------------------------------------------------- Windows 8 animations

    boolean animations() { return prefs.getBoolean("anim", true); }

    /** Pivot each tile on the left edge of the screen so the whole start screen swings like a turnstile. */
    void pivotOnScreenEdge(View t) {
        int[] loc = new int[2];
        t.getLocationOnScreen(loc);
        t.setPivotX(-loc[0] + t.getWidth() * (t.getScaleX() - 1) / 2);
        t.setPivotY(t.getHeight() / 2f);
        t.setCameraDistance(9000 * d);
    }

    float screenFraction(View t) {
        int[] loc = new int[2];
        t.getLocationOnScreen(loc);
        return Math.max(0, Math.min(1, loc[0] / (float) root.getWidth()));
    }

    boolean onScreen(View t) {
        int[] loc = new int[2];
        t.getLocationOnScreen(loc);
        return loc[0] + t.getWidth() > 0 && loc[0] < root.getWidth();
    }

    /** Windows 8 start screen entrance: tiles swing in from the right, column by column. */
    void playEntrance() {
        if (!animations()) return;
        root.post(() -> {
            fadeHeader(true);
            for (TileView t : tiles) {
                t.animate().cancel();
                float s = t.isFocused() ? 1.04f : 1f;
                t.setScaleX(s);
                t.setScaleY(s);
                if (!onScreen(t)) { t.setRotationY(0); t.setAlpha(1); t.setTranslationX(0); t.centrePivot(); continue; }
                pivotOnScreenEdge(t);
                t.setRotationY(-55);
                t.setTranslationX(dp(90));
                t.setAlpha(0);
                t.animate().rotationY(0).translationX(0).alpha(1)
                        .setStartDelay((long) (60 + 220 * screenFraction(t)))
                        .setDuration(380).setInterpolator(new DecelerateInterpolator(2.2f))
                        .withEndAction(t::centrePivot).start();
            }
        });
    }

    /** Undo every launch-animation state at once, so nothing on Start can be left invisible or rotated. */
    void restoreStart() {
        h.removeCallbacks(launchWatchdog);
        launching = false;
        splash.animate().cancel();
        splash.setVisibility(View.GONE);
        View header = content.getChildAt(0);
        header.animate().cancel();
        header.setAlpha(1);
        header.setTranslationX(0);
        content.animate().cancel();
        content.setAlpha(1);
        content.setTranslationY(0);
        for (TileView t : tiles) {
            t.animate().cancel();
            t.setRotationY(0);
            t.setAlpha(1);
            t.setTranslationX(0);
            t.centrePivot();
            float sc = t.isFocused() ? 1.04f : 1f;
            t.setScaleX(sc);
            t.setScaleY(sc);
        }
    }

    /** If the app never actually took over the screen, don't leave Start hidden behind a dead launch. */
    final Runnable launchWatchdog = () -> {
        if (resumed && launching) {
            launchedApp = false;
            restoreStart();
            playEntrance();
        }
    };

    void fadeHeader(boolean in) {
        View header = content.getChildAt(0);
        header.animate().cancel();
        if (in) { header.setAlpha(0); header.setTranslationX(dp(40)); }
        header.animate().alpha(in ? 1 : 0).translationX(in ? 0 : -dp(30)).setStartDelay(in ? 40 : 0)
                .setDuration(in ? 380 : 180).setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * Windows 8 app launch: Start flips away (tiles turnstile out, or the Apps list slides off), then
     * the app's coloured splash zooms up from where it was selected.
     */
    void launch(View from, App a) {
        if (launching) return;
        pushRecent(a.pkg);
        launchedApp = true;
        if (!animations()) {
            startApp(a.intent(), null);
            return;
        }
        launching = true;
        {
            fadeHeader(false);
            for (TileView t : tiles) {
                if (!onScreen(t)) continue;
                pivotOnScreenEdge(t);
                long delay = t == from ? 70 : (long) (60 * screenFraction(t));
                t.animate().rotationY(80).alpha(0).setStartDelay(delay).setDuration(160)
                        .setInterpolator(new AccelerateInterpolator(1.6f)).start();
            }
        }
        boolean white = from instanceof TileView ? ((TileView) from).whiteGlyph : whiteGlyph(a);
        splash.setBackgroundColor(from instanceof TileView ? ((TileView) from).color : tileColor(a.pkg, a.color));
        BitmapDrawable icon = new BitmapDrawable(getResources(), glyph(a));
        if (white) icon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        splashIcon.setImageDrawable(icon);
        // Start the splash exactly over what was selected: with scale s, a pivot of L / (1 - s) keeps the left edge at L.
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        float sx = from.getWidth() / (float) root.getWidth(), sy = from.getHeight() / (float) root.getHeight();
        splash.setPivotX(loc[0] / (1 - sx));
        splash.setPivotY(loc[1] / (1 - sy));
        splash.setScaleX(sx);
        splash.setScaleY(sy);
        splash.setAlpha(0);
        splash.setVisibility(View.VISIBLE);
        splash.animate().scaleX(1).scaleY(1).alpha(1).setStartDelay(70).setDuration(190)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> startApp(a.intent(), ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.hold).toBundle()))
                .start();
    }

    void startApp(Intent i, Bundle opts) {
        try {
            startActivity(i, opts);
            h.removeCallbacks(launchWatchdog);
            h.postDelayed(launchWatchdog, 2500);
        } catch (Exception e) {
            launchedApp = false;
            restoreStart();
            playEntrance();
            toast("Couldn't open app");
        }
    }

    void startSafe(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { toast("Settings unavailable"); }
        }
    }

    // ---------------------------------------------------------------- Apps list (the Start button)

    /** Every app, alphabetically under letter headings, in the side panel. Menu on one pins or unpins it. */
    void openApps() {
        LinearLayout b = openFlyout("Apps");
        List<App> all = new ArrayList<>();
        for (App a : apps.values()) if (listed(a)) all.add(a);
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
        Set<String> pins = new HashSet<>(pins());
        String letter = null;
        for (App a : all) {
            char c0 = Character.toUpperCase(a.label.isEmpty() ? '#' : a.label.charAt(0));
            String l = Character.isLetter(c0) ? String.valueOf(c0) : "#";
            if (!l.equals(letter)) {
                letter = l;
                caption(b, l);
            }
            boolean on = pins.contains(a.pkg);
            View row = itemWith(b, appSquare(a), a.label, on ? "On Start" : null, null);
            row.setTag(a);
            row.setLongClickable(true);
            row.setOnClickListener(v -> {
                View icon = ((ViewGroup) v).getChildAt(0);
                closeFlyout();
                launch(icon, a);
            });
            row.setOnLongClickListener(v -> { appMenu(a); return true; });
        }
        focusFirst();
    }

    /** The app's logo on its tile colour, as a small square for lists. */
    View appSquare(App a) {
        final int tile = tileColor(a.pkg, a.color);
        final boolean white = whiteGlyph(a);
        return new View(this) {
            final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            final RectF r = new RectF();
            @Override protected void onDraw(Canvas c) {
                p.setColorFilter(null);
                p.setColor(tile);
                c.drawRect(0, 0, getWidth(), getHeight(), p);
                float in = getWidth() * .18f;
                r.set(in, in, getWidth() - in, getHeight() - in);
                if (white) p.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN));
                Bitmap g = glyph(a);
                if (g != null) c.drawBitmap(g, null, r, p);
            }
        };
    }

    void appMenu(App a) {
        LinearLayout b = openFlyout(a.label);
        boolean isPinned = pinned(a.pkg);
        item(b, null, "Open", null, v -> {
            closeFlyout();
            launch(startButton, a);
        });
        item(b, null, isPinned ? "Unpin from Start" : "Pin to Start", isPinned ? null : "Adds it as a tile at the end of Start", v -> {
            pin(a.pkg, !isPinned);
            closeFlyout();
            toast(isPinned ? "Unpinned " + a.label : "Pinned " + a.label + " to Start");
        });
        item(b, null, "Close app", "Stop it if it's running in the background", v -> {
            closeApp(a.pkg);
            closeFlyout();
            toast("Closed " + a.label);
        });
        item(b, null, "App info", null, v -> {
            closeFlyout();
            startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
        });
        focusFirst();
    }

    // ---------------------------------------------------------------- flyouts (charms-style side panels)

    LinearLayout flyoutBody;

    LinearLayout openFlyout(String heading) {
        flyoutLayer.animate().cancel();
        flyoutLayer.removeAllViews();
        flyoutLayer.setBackgroundColor(0x66000000);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF1D1D1D);
        sv.setOnClickListener(v -> { });
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(26), dp(30), dp(26), dp(30));
        TextView t = text(heading, 24, Fonts.REG);
        t.setPadding(dp(10), 0, 0, dp(16));
        body.addView(t);
        sv.addView(body);
        flyoutLayer.addView(sv, new FrameLayout.LayoutParams(dp(370), -1, Gravity.END));
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        flyoutLayer.setVisibility(View.VISIBLE);
        flyoutOpen = true;
        flyoutLayer.setAlpha(0);
        flyoutLayer.animate().alpha(1).setDuration(150).start();
        sv.setTranslationX(dp(370));
        sv.animate().translationX(0).setDuration(300).setInterpolator(new DecelerateInterpolator(2.5f)).start();
        flyoutBody = body;
        return body;
    }

    void focusFirst() {
        h.post(() -> {
            for (int i = 0; i < flyoutBody.getChildCount(); i++) {
                View v = flyoutBody.getChildAt(i);
                if (!v.isFocusable() && v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) v = ((ViewGroup) v).getChildAt(0);
                if (v.isFocusable()) { v.requestFocus(); return; }
            }
        });
    }

    boolean closeFlyout() {
        if (!flyoutOpen) return false;
        flyoutOpen = false;
        View panel = flyoutLayer.getChildAt(0);
        panel.animate().translationX(dp(370)).setDuration(200).setInterpolator(new AccelerateInterpolator()).start();
        flyoutLayer.animate().alpha(0).setDuration(200).withEndAction(() -> {
            if (flyoutOpen) return; // a new flyout opened meanwhile
            flyoutLayer.setVisibility(View.GONE);
            flyoutLayer.removeAllViews();
        }).start();
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View target = lastFocus != null && lastFocus.isAttachedToWindow() && lastFocus.isShown() ? lastFocus : null;
        if (target == null && focusKey != null) target = tileFor(focusKey);
        if (target == null) target = !tiles.isEmpty() ? tiles.get(0) : startButton;
        target.requestFocus();
        return true;
    }

    View item(LinearLayout parent, Bitmap icon, String label, String sub, View.OnClickListener click) {
        ImageView iv = null;
        if (icon != null) {
            iv = new ImageView(this);
            iv.setImageBitmap(icon);
        }
        return itemWith(parent, iv, label, sub, click);
    }

    View itemWith(LinearLayout parent, View icon, String label, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(focusBg());
        if (icon != null) {
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
            ilp.rightMargin = dp(14);
            row.addView(icon, ilp);
        }
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(label, 15.5f, Fonts.REG));
        if (sub != null) {
            TextView s = text(sub, 11.5f, Fonts.REG);
            s.setTextColor(0xFFA8A8A8);
            s.setPadding(0, dp(4), 0, 0);
            txt.addView(s);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(click);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    void caption(LinearLayout parent, String s) {
        TextView t = text(s.toUpperCase(), 10.5f, Fonts.MED);
        t.setTextColor(0xFF9A9A9A);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(10), dp(18), 0, dp(8));
        parent.addView(t);
    }

    android.graphics.drawable.StateListDrawable focusBg() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        int acc = accent() == APP_COLOURS ? PALETTE[0] : accent();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0xFF555555));
        s.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(acc));
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    interface ColorPick { void pick(int c); }

    /** Accent swatch grid, five per row. Colour 0 is the "app colours" swatch. */
    void swatches(LinearLayout parent, int[] colors, int selected, ColorPick pick) {
        LinearLayout row = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(this);
                parent.addView(row);
            }
            final int c = colors[i];
            View sw = new View(this) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas cv) {
                    int w = getWidth(), hh = getHeight(), in = dp(5);
                    if (isFocused()) { p.setColor(Color.WHITE); cv.drawRect(0, 0, w, hh, p); }
                    if (c == APP_COLOURS) {
                        int[] q = {0xFFE51400, 0xFF1BA1E2, 0xFF60A917, 0xFFF09609};
                        for (int k = 0; k < 4; k++) {
                            p.setColor(q[k]);
                            float x0 = in + (k % 2) * (w - 2 * in) / 2f, y0 = in + (k / 2) * (hh - 2 * in) / 2f;
                            cv.drawRect(x0, y0, x0 + (w - 2 * in) / 2f, y0 + (hh - 2 * in) / 2f, p);
                        }
                    } else { p.setColor(c); cv.drawRect(in, in, w - in, hh - in, p); }
                    if (c == selected) {
                        p.setColor(Color.WHITE);
                        p.setStrokeWidth(dp(3));
                        cv.drawLine(w * .3f, hh * .52f, w * .45f, hh * .67f, p);
                        cv.drawLine(w * .45f, hh * .67f, w * .72f, hh * .36f, p);
                    }
                }
            };
            sw.setFocusable(true);
            sw.setClickable(true);
            sw.setOnFocusChangeListener((v, f) -> v.invalidate());
            sw.setOnClickListener(v -> pick.pick(c));
            row.addView(sw, new LinearLayout.LayoutParams(dp(60), dp(54)));
        }
    }

    // ---------------------------------------------------------------- settings

    void showSettings() {
        LinearLayout b = openFlyout("Personalize");
        caption(b, "Background");
        int bg = prefs.getInt("bg", BG_BLACK);
        item(b, null, "Black" + (bg == BG_BLACK ? "  ✓" : ""), null, v -> setBg(BG_BLACK));
        item(b, null, "White" + (bg == BG_WHITE ? "  ✓" : ""), null, v -> setBg(BG_WHITE));
        for (int m = BG_MIDNIGHT; m <= BG_GRAPHITE; m++) {
            final int mode = m;
            View sw = new View(this);
            sw.setBackground(gradient(mode));
            itemWith(b, sw, THEME_NAMES[mode - BG_MIDNIGHT] + (bg == mode ? "  ✓" : ""), "Dark gradient", v -> setBg(mode));
        }
        item(b, null, "Custom image…" + (bg == BG_IMAGE ? "  ✓" : ""), "From Pictures, Download or USB storage", v -> pickImage());

        caption(b, "Tile colours");
        TextView hint = text(accent() == APP_COLOURS ? "Each tile uses its app's own colour. Pick an accent to theme every tile, with white logos."
                : "All tiles use this accent with white logos. The first swatch goes back to app colours.", 11.5f, Fonts.REG);
        hint.setTextColor(0xFFA8A8A8);
        hint.setPadding(dp(10), 0, dp(10), dp(10));
        hint.setLineSpacing(dp(2), 1);
        b.addView(hint);
        int[] cols = new int[PALETTE.length + 1];
        cols[0] = APP_COLOURS;
        System.arraycopy(PALETTE, 0, cols, 1, PALETTE.length);
        swatches(b, cols, accent(), c -> {
            SharedPreferences.Editor e = prefs.edit().putInt("accent", c);
            for (String k : prefs.getAll().keySet()) if (k.startsWith("color_")) e.remove(k);
            e.apply();
            buildTiles();
            closeFlyout();
            showSettings();
        });

        caption(b, "Start screen");
        item(b, null, "Animations: " + (animations() ? "On" : "Off"), "Windows 8 tile, live tile and app transitions", v -> {
            prefs.edit().putBoolean("anim", !animations()).apply();
            closeFlyout();
            showSettings();
        });
        boolean sys = pinned(SYS_SYSTEM);
        item(b, null, "System tile: " + (sys ? "On" : "Off"), "Live RAM and storage on Start", v -> {
            pin(SYS_SYSTEM, !sys);
            closeFlyout();
            showSettings();
        });
        if (!media.start()) {
            item(b, null, "Turn on live media", "Allow notification access so tiles can show what's playing", v -> {
                closeFlyout();
                startSafe(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            });
        }
        item(b, null, "Reset Start", "Default tiles, sizes and colours", v -> {
            SharedPreferences.Editor e = prefs.edit().remove("pins");
            for (String k : prefs.getAll().keySet()) if (k.startsWith("color_") || k.startsWith("size_")) e.remove(k);
            e.apply();
            focusKey = null;
            buildTiles();
            closeFlyout();
        });
        focusFirst();
    }

    void setBg(int mode) {
        prefs.edit().putInt("bg", mode).apply();
        applyBackground();
        closeFlyout();
        showSettings();
    }

    void applyBackground() {
        int mode = prefs.getInt("bg", BG_BLACK);
        bgImage.setImageDrawable(null);
        File f = new File(getFilesDir(), "bg.jpg");
        if (mode == BG_IMAGE && f.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bgImage.setImageBitmap(BitmapFactory.decodeFile(f.getPath(), o));
            // Dim slightly, like the Windows 8 start backgrounds, so tiles and text stay readable.
            bgImage.setColorFilter(0x55000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
        }
        bgImage.setVisibility(bgImage.getDrawable() == null ? View.GONE : View.VISIBLE);
        getWindow().setBackgroundDrawable(isGradient(mode) ? gradient(mode) : new ColorDrawable(mode == BG_WHITE ? Color.WHITE : Color.BLACK));
        applyBackgroundColors();
    }

    void applyBackgroundColors() {
        int mode = prefs.getInt("bg", BG_BLACK);
        boolean light = mode == BG_WHITE;
        int fg = light ? 0xFF1D1D1D : Color.WHITE;
        float shadow = mode == BG_IMAGE ? dp(3) : 0;
        for (TextView t : new TextView[]{startButton, clock, date, statusHint}) {
            t.setTextColor(fg);
            t.setShadowLayer(shadow, 0, dp(1), 0xCC000000);
        }
        date.setTextColor(light ? 0xFF555555 : 0xFFC8C8C8);
        // The Start button highlights like any other focusable item.
        android.graphics.drawable.StateListDrawable sb = new android.graphics.drawable.StateListDrawable();
        sb.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(light ? 0x221D1D1D : 0x33FFFFFF));
        sb.addState(new int[]{}, new ColorDrawable(0));
        startButton.setBackground(sb);
        for (TileView t : tiles) { t.ringColor = fg; t.invalidate(); }
        if (wifiButton != null) for (View v : new View[]{wifiButton}) v.invalidate();
    }

    static final int REQ_IMAGES = 7;

    void pickImage() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_IMAGES);
            return;
        }
        List<File> found = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        for (String dir : new String[]{"Pictures", "Download", "DCIM", "Wallpapers", ""}) roots.add(new File(ext, dir));
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) if (!v.getName().equals("emulated") && !v.getName().equals("self")) roots.add(v);
        for (File r : roots) scanImages(r, 2, found);

        LinearLayout b = openFlyout("Background image");
        if (found.isEmpty()) {
            TextView t = text("No images found.\n\nCopy a .jpg or .png to the Pictures or Download folder (or plug in a USB drive) and try again.", 13.5f, Fonts.REG);
            t.setTextColor(0xFFBBBBBB);
            t.setPadding(dp(10), 0, dp(10), 0);
            t.setLineSpacing(dp(4), 1);
            b.addView(t);
            item(b, null, "Back", null, v -> { closeFlyout(); showSettings(); });
        }
        for (File f : found) {
            String where = f.getParentFile().equals(ext) ? "Internal storage" : f.getParentFile().getName();
            item(b, null, f.getName(), where, v -> {
                if (saveBackground(f)) setBg(BG_IMAGE);
                else toast("Couldn't read that image");
            });
        }
        focusFirst();
    }

    void scanImages(File dir, int depth, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null || out.size() >= 60) return;
        for (File f : fs) {
            String n = f.getName().toLowerCase();
            if (f.isDirectory()) { if (depth > 0 && !n.startsWith(".") && !n.equals("android")) scanImages(f, depth - 1, out); }
            else if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                if (!out.contains(f)) out.add(f);
            }
        }
    }

    /** Downsample to screen size and keep a private copy so the original can be deleted later. */
    boolean saveBackground(File src) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getPath(), o);
            int target = Math.max(root.getWidth(), root.getHeight());
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeFile(src.getPath(), o);
            if (raw == null) return false;
            // Centre-crop to exactly the screen so the stored copy (and what we keep in RAM) is screen-sized.
            int sw = root.getWidth(), sh = root.getHeight();
            float scale = Math.max(sw / (float) raw.getWidth(), sh / (float) raw.getHeight());
            Bitmap bm = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565);
            Canvas cv = new Canvas(bm);
            cv.translate((sw - raw.getWidth() * scale) / 2, (sh - raw.getHeight() * scale) / 2);
            cv.scale(scale, scale);
            cv.drawBitmap(raw, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            raw.recycle();
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "bg.jpg"))) {
                bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            bm.recycle();
            h.postDelayed(() -> Runtime.getRuntime().gc(), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_IMAGES && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pickImage();
        else if (req == REQ_IMAGES) toast("Storage permission is needed to pick an image");
    }

    // ---------------------------------------------------------------- recents flyout

    void showRecents() {
        LinearLayout b = openFlyout("Recent apps");
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        item(b, null, "✕  Close all apps", gb(mi.availMem) + " of " + gb(mi.totalMem) + " RAM free · stops background apps", v -> { closeFlyout(); closeAll(); });
        caption(b, recentList.isEmpty() ? "Nothing used recently" : "Select an app to close it");
        long now = System.currentTimeMillis();
        for (String s : recentList) {
            int c = s.indexOf(':');
            final String pkg = s.substring(0, c);
            App a = apps.get(pkg);
            if (a == null) continue;
            long mins = (now - Long.parseLong(s.substring(c + 1))) / 60000;
            String ago = mins < 1 ? "just now" : mins < 60 ? mins + " min ago" : (mins / 60) + " h ago";
            item(b, glyph(a), a.label, "Used " + ago + " · select to close", v -> {
                closeApp(pkg);
                toast("Closed " + a.label);
                refreshStats();
                closeFlyout();
                showRecents();
            });
        }
        focusFirst();
    }

    // ---------------------------------------------------------------- per-tile menu

    static final String[] SIZE_NAMES = {"", "Small (1\u00d71)", "Wide (2\u00d71)", "Large (2\u00d72)"};

    void tileMenu(TileView t) {
        final String k = key(t);
        final App a = t.tag2 instanceof App ? (App) t.tag2 : null;
        LinearLayout b = openFlyout(a != null ? a.label : t.label);
        if (a != null) {
            item(b, null, "Open", null, v -> { closeFlyout(); launch(t, a); });
            final MediaController mc = media.controller(a.pkg);
            if (mc != null && mc.getPlaybackState() != null) {
                final boolean playing = mc.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
                item(b, null, playing ? "Pause" : "Resume playback", null, v -> {
                    if (playing) mc.getTransportControls().pause(); else mc.getTransportControls().play();
                    closeFlyout();
                });
            }
            item(b, null, "Close app", "Stop it if it's running in the background", v -> {
                closeApp(a.pkg);
                closeFlyout();
                toast("Closed " + a.label);
            });
        }
        final int size = tileSize(k), next = size % 3 + 1;
        item(b, null, "Size: " + SIZE_NAMES[size], "Select for " + SIZE_NAMES[next], v -> {
            prefs.edit().putInt("size_" + k, next).apply();
            closeFlyout();
            buildTiles();
        });
        item(b, null, "Move left", null, v -> move(k, -1));
        item(b, null, "Move right", null, v -> move(k, 1));
        if (a != null && media.item(a.pkg) != null) item(b, null, "Clear what's playing", "Remove it from this live tile", v -> {
            media.forget(a.pkg);
            closeFlyout();
        });
        item(b, null, "Unpin from Start", a != null ? "It stays in Apps" : "Turn it back on in Personalize", v -> {
            focusKey = null;
            closeFlyout();
            pin(k, false);
        });
        if (a != null) item(b, null, "App info", null, v -> {
            closeFlyout();
            startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
        });
        caption(b, a != null ? "Tile colour (first swatch: app colour)" : "Tile colour");
        int[] cols = new int[PALETTE.length + 1];
        cols[0] = APP_COLOURS;
        System.arraycopy(PALETTE, 0, cols, 1, PALETTE.length);
        int own = prefs.getInt("color_" + k, 0);
        swatches(b, cols, own != 0 ? own : accent(), c -> {
            if (c == APP_COLOURS) prefs.edit().remove("color_" + k).apply();
            else prefs.edit().putInt("color_" + k, c).apply();
            closeFlyout();
            buildTiles();
        });
        focusFirst();
    }

    void move(String key, int dir) {
        List<String> o = pins();
        int i = o.indexOf(key), j = i + dir;
        if (i < 0 || j < 0 || j >= o.size()) return;
        Collections.swap(o, i, j);
        savePins(o);
        // Keep the menu open so the tile can be nudged several places in a row.
        buildTiles();
    }
}
