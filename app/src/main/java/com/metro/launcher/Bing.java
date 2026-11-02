package com.metro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bing's images of the day, for the background and the screensaver. The last eight are kept as
 * screen-sized JPEGs in the app's files, and the list is fetched again once it's 12 hours old.
 */
final class Bing {
    static final class Image {
        String file, description, credit;
    }

    static final int KEEP = 8;
    static final long STALE_MS = 12 * 3600_000L;
    /** Rotation choices in minutes; the last means "today's image", changing when Bing's does. */
    static final int[] EVERY = {15, 30, 60, 1440};
    static final String[] EVERY_NAMES = {"15 minutes", "30 minutes", "1 hour", "Daily"};

    private static final Pattern COPYRIGHT = Pattern.compile("^(.*?)\\s*\\(\\s*©\\s*(.*?)\\s*\\)\\s*$");
    private static boolean fetching;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "bing");
        d.mkdirs();
        return d;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("metro", Context.MODE_PRIVATE); }

    static int every(Context c) { return prefs(c).getInt("bing_every", 60); }

    /** Newest first. */
    static List<Image> list(Context c) {
        List<Image> out = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(new File(dir(c), "list.json"))) {
            byte[] b = new byte[(int) in.getChannel().size()];
            int n = in.read(b);
            JSONArray a = new JSONArray(new String(b, 0, Math.max(0, n), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Image im = new Image();
                im.file = o.getString("file");
                im.description = o.optString("description");
                im.credit = o.optString("credit");
                if (new File(dir(c), im.file).exists()) out.add(im);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The image to show right now for the chosen interval, or null before anything has downloaded. */
    static Image current(Context c) {
        List<Image> l = list(c);
        if (l.isEmpty()) return null;
        int every = every(c);
        if (every >= 1440) return l.get(0);
        long slot = System.currentTimeMillis() / (every * 60_000L);
        return l.get((int) (slot % l.size()));
    }

    static boolean stale(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("bing_fetched", 0) > STALE_MS;
    }

    static Bitmap load(Context c, Image im) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(new File(dir(c), im.file).getPath(), o);
    }

    /**
     * Fetches the list when it's stale, downloads what's new and drops what's no longer listed. Runs on
     * the network thread; {@code done} runs on the main thread, with true when something changed.
     */
    static void refresh(Context ctx, Runnable done) {
        final Context c = ctx.getApplicationContext();
        if (!Net.allowed(c) || fetching || !stale(c)) return;
        fetching = true;
        Net.io.execute(() -> {
            boolean changed = false;
            try {
                Locale l = Locale.getDefault();
                String mkt = l.getLanguage() + "-" + (l.getCountry().isEmpty() ? "US" : l.getCountry());
                JSONArray images = new JSONObject(Net.text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n="
                        + KEEP + "&mkt=" + mkt)).getJSONArray("images");
                DisplayMetrics m = c.getResources().getDisplayMetrics();
                int sw = Math.max(m.widthPixels, m.heightPixels), sh = Math.min(m.widthPixels, m.heightPixels);
                JSONArray keep = new JSONArray();
                Set<String> names = new HashSet<>();
                for (int i = 0; i < images.length() && keep.length() < KEEP; i++) {
                    JSONObject o = images.getJSONObject(i);
                    String base = o.optString("urlbase");
                    if (base.isEmpty()) continue;
                    String file = o.optString("startdate", "img" + i) + ".jpg";
                    File dest = new File(dir(c), file);
                    if (!dest.exists()) {
                        File raw = new File(c.getCacheDir(), "bing-raw.jpg");
                        try {
                            Net.download("https://www.bing.com" + base + "_1920x1080.jpg", raw, null);
                            if (!fit(raw, dest, sw, sh)) continue;
                            changed = true;
                        } finally {
                            raw.delete();
                        }
                    }
                    String[] dc = split(o.optString("copyright"));
                    keep.put(new JSONObject().put("file", file).put("description", dc[0]).put("credit", dc[1]));
                    names.add(file);
                }
                if (keep.length() > 0) {
                    try (FileOutputStream out = new FileOutputStream(new File(dir(c), "list.json"))) {
                        out.write(keep.toString().getBytes("UTF-8"));
                    }
                    File[] old = dir(c).listFiles();
                    if (old != null) for (File f : old) {
                        if (f.getName().endsWith(".jpg") && !names.contains(f.getName()) && f.delete()) changed = true;
                    }
                    prefs(c).edit().putLong("bing_fetched", System.currentTimeMillis()).apply();
                }
            } catch (Exception ignored) {
                // Offline or Bing changed its format: keep showing what's cached and try again next time.
            }
            final boolean any = changed;
            new Handler(Looper.getMainLooper()).post(() -> {
                fetching = false;
                if (any && done != null) done.run();
            });
        });
    }

    /** "Description (© Credit)" → {description, credit}; Bing occasionally leaves the brackets out. */
    static String[] split(String copyright) {
        Matcher mt = COPYRIGHT.matcher(copyright == null ? "" : copyright);
        if (mt.matches()) return new String[]{mt.group(1), mt.group(2)};
        return new String[]{copyright == null ? "" : copyright, ""};
    }

    static String caption(Image im) {
        if (im.credit.isEmpty()) return im.description;
        return im.description.isEmpty() ? "© " + im.credit : im.description + "  ·  © " + im.credit;
    }

    /**
     * Decodes {@code src} no bigger than needed, centre-crops it to exactly sw×sh and saves an RGB_565
     * JPEG, so what's stored and what's held in memory are both screen-sized.
     */
    static boolean fit(File src, File dest, int sw, int sh) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getPath(), o);
            int target = Math.max(sw, sh), sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeFile(src.getPath(), o);
            if (raw == null) return false;
            float scale = Math.max(sw / (float) raw.getWidth(), sh / (float) raw.getHeight());
            Bitmap bm = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565);
            Canvas cv = new Canvas(bm);
            cv.translate((sw - raw.getWidth() * scale) / 2, (sh - raw.getHeight() * scale) / 2);
            cv.scale(scale, scale);
            cv.drawBitmap(raw, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            raw.recycle();
            File part = new File(dest.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            bm.recycle();
            return part.renameTo(dest);
        } catch (Exception e) {
            return false;
        }
    }

    private Bing() { }
}
