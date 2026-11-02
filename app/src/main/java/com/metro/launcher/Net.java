package com.metro.launcher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything Metro sends over the network goes through here, and nothing does unless the user has
 * turned on Internet access in Personalize: every online feature checks {@link #allowed} first.
 */
final class Net {
    /** One background thread for all downloads, so they never compete for the TV's little bandwidth. */
    static final ExecutorService io = Executors.newSingleThreadExecutor();

    interface Progress { void at(long done, long total); }

    static boolean allowed(Context c) {
        return c.getSharedPreferences("metro", Context.MODE_PRIVATE).getBoolean("internet", false);
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MetroLauncher (Android TV)");
        c.setRequestProperty("Accept", "application/json, image/*, */*");
        return c;
    }

    /** A small text response (JSON), or an IOException. */
    static String text(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(in, out, null, -1);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** Downloads to a temporary file and only then renames it, so a cut-off download never looks complete. */
    static void download(String url, File dest, Progress p) throws IOException {
        File part = new File(dest.getPath() + ".part");
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            long total = c.getContentLength();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                copy(in, out, p, total);
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(dest)) throw new IOException("Couldn't save " + dest.getName());
    }

    static void copy(InputStream in, OutputStream out, Progress p, long total) throws IOException {
        byte[] buf = new byte[16384];
        long done = 0, lastReport = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            done += n;
            if (p != null && done - lastReport > 32768) { p.at(done, total); lastReport = done; }
        }
        if (p != null) p.at(done, total);
    }

    private Net() { }
}
