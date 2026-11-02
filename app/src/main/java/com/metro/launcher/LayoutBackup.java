package com.metro.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Start's layout saved as a PNG: a picture of the tiles, with the settings themselves in a text chunk.
 * On Android 11 and later an app can only read back other files in shared storage if they are media,
 * so a picture is what lets a backup be restored after a reinstall or on another TV (copied over on a
 * USB drive). It's saved to Pictures/Metro.
 */
final class LayoutBackup {
    static final String KEY = "metro-layout";

    /** The settings that make up a layout: tiles, their sizes and colours, accent, background, animations. */
    static String export(SharedPreferences p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", 1);
        o.put("pins", p.getString("pins", ""));
        if (p.contains("pos")) o.put("pos", p.getString("pos", ""));
        o.put("accent", p.getInt("accent", 0));
        int bg = p.getInt("bg", 0);
        // A custom image lives only on this TV; restore the rest and leave the background alone then.
        if (bg != StartActivity.BG_IMAGE) o.put("bg", bg);
        o.put("anim", p.getBoolean("anim", true));
        JSONObject sizes = new JSONObject(), colors = new JSONObject();
        for (String k : p.getAll().keySet()) {
            if (k.startsWith("size_")) sizes.put(k.substring(5), p.getInt(k, 1));
            if (k.startsWith("color_")) colors.put(k.substring(6), p.getInt(k, 0));
        }
        o.put("sizes", sizes);
        o.put("colors", colors);
        return o.toString();
    }

    /** Replaces the current layout; tiles for apps this TV doesn't have are skipped when Start is built. */
    static void restore(SharedPreferences p, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("size_") || k.startsWith("color_")) e.remove(k);
        e.putString("pins", o.getString("pins"));
        // Backups from before tiles had cell positions are laid out afresh.
        if (o.has("pos")) e.putString("pos", o.getString("pos")); else e.remove("pos");
        e.putInt("accent", o.optInt("accent", 0));
        if (o.has("bg")) e.putInt("bg", o.getInt("bg"));
        e.putBoolean("anim", o.optBoolean("anim", true));
        JSONObject sizes = o.optJSONObject("sizes"), colors = o.optJSONObject("colors");
        if (sizes != null) for (Iterator<String> it = sizes.keys(); it.hasNext(); ) { String k = it.next(); e.putInt("size_" + k, sizes.getInt(k)); }
        if (colors != null) for (Iterator<String> it = colors.keys(); it.hasNext(); ) { String k = it.next(); e.putInt("color_" + k, colors.getInt(k)); }
        e.apply();
    }

    /** Saves the picture with the settings inside; returns where it went, for the toast. */
    @SuppressWarnings("deprecation")
    static String save(Context c, Bitmap preview, String json) throws Exception {
        byte[] png = withText(preview, json);
        String name = "Metro layout " + new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new Date()) + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Metro");
            Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("No storage");
            try (OutputStream out = c.getContentResolver().openOutputStream(uri)) { out.write(png); }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Metro");
            dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, name))) { out.write(png); }
        }
        return "Pictures/Metro/" + name;
    }

    /** PNG bytes with a tEXt chunk right after the header, where it's quick to find again. */
    static byte[] withText(Bitmap bm, String text) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, raw);
        byte[] b = raw.toByteArray();
        int afterHeader = 8 + 4 + 4 + 13 + 4;   // signature, then the IHDR chunk
        byte[] data = (KEY + "\0" + text).getBytes("ISO-8859-1");
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + data.length + 12);
        out.write(b, 0, afterHeader);
        writeInt(out, data.length);
        byte[] type = "tEXt".getBytes("ISO-8859-1");
        out.write(type);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
        out.write(b, afterHeader, b.length - afterHeader);
        return out.toByteArray();
    }

    /** The settings stored in a backup picture, or null if it isn't one. */
    static String read(File f) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            if ((sig[1] & 0xFF) != 'P' || (sig[2] & 0xFF) != 'N' || (sig[3] & 0xFF) != 'G') return null;
            for (int i = 0; i < 8; i++) {
                int len = in.readInt();
                byte[] type = new byte[4];
                in.readFully(type);
                String t = new String(type, "ISO-8859-1");
                if (t.equals("IDAT") || len < 0 || len > 1 << 20) return null;
                byte[] data = new byte[len];
                in.readFully(data);
                in.readInt();
                if (t.equals("tEXt")) {
                    String s = new String(data, "ISO-8859-1");
                    if (s.startsWith(KEY + "\0")) return s.substring(KEY.length() + 1);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    /** For the restore list: "11 tiles" from a backup's settings. */
    static String summary(String json) {
        try {
            String pins = new JSONObject(json).getString("pins");
            int n = pins.isEmpty() ? 0 : pins.split(",").length;
            return n + (n == 1 ? " tile" : " tiles");
        } catch (Exception e) {
            return "";
        }
    }

    private LayoutBackup() { }
}
