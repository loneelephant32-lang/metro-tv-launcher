package com.metro.launcher;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Updates Metro from its GitHub releases. A download is installed only when it is Metro and is signed
 * with the same certificate as the copy already installed, and Android still asks the user to confirm.
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/loneelephant32-lang/metro-tv-launcher/releases/latest";
    static final String ASSET = "metro-launcher.apk";
    static final long WEEK_MS = 7 * 24 * 3600_000L;

    static final class Release {
        String version, notes, url;
        long size;
    }

    interface Checked { void done(Release newer, String error); }

    interface Done { void done(String error); }

    private static final Handler main = new Handler(Looper.getMainLooper());

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("metro", Context.MODE_PRIVATE); }

    /** Read from the package manager: BuildConfig isn't generated (AGP 8 turns it off by default). */
    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compares dotted versions number by number: 1.10.0 is newer than 1.9.2. */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[^0-9]+"), y = b.replaceFirst("^[vV]", "").split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    /** Asks GitHub for the latest release; {@code newer} is null when Metro is up to date. */
    static void check(Context ctx, Checked cb) {
        final Context c = ctx.getApplicationContext();
        Net.io.execute(() -> {
            Release r = null;
            String error = null;
            try {
                JSONObject o = new JSONObject(Net.text(LATEST));
                String tag = o.getString("tag_name");
                prefs(c).edit().putLong("update_checked", System.currentTimeMillis()).apply();
                if (compare(tag, installedVersion(c)) > 0) {
                    JSONArray assets = o.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (!ASSET.equals(a.optString("name"))) continue;
                        r = new Release();
                        r.version = tag.replaceFirst("^[vV]", "");
                        r.notes = o.optString("body", "").trim();
                        r.url = a.getString("browser_download_url");
                        r.size = a.optLong("size");
                    }
                    if (r == null) error = "Version " + tag + " has no " + ASSET + " to install";
                }
            } catch (Exception e) {
                error = "Couldn't reach GitHub";
            }
            final Release found = r;
            final String err = error;
            main.post(() -> cb.done(found, err));
        });
    }

    /** The weekly check, when it's switched on: a toast points to Personalize › About. */
    static void autoCheck(Context ctx, Done notify) {
        Context c = ctx.getApplicationContext();
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("update_auto", false) || !Net.allowed(c)) return;
        if (System.currentTimeMillis() - p.getLong("update_checked", 0) < WEEK_MS) return;
        check(c, (r, err) -> { if (r != null) notify.done(r.version); });
    }

    static File apk(Context c) { return new File(c.getCacheDir(), "update.apk"); }

    static void download(Context ctx, Release r, Net.Progress p, Done done) {
        final Context c = ctx.getApplicationContext();
        Net.io.execute(() -> {
            String error = null;
            try {
                apk(c).delete();
                Net.download(r.url, apk(c), (d, t) -> main.post(() -> p.at(d, t > 0 ? t : r.size)));
                error = verify(c, apk(c));
                if (error != null) apk(c).delete();
            } catch (Exception e) {
                error = "Download failed. Check the connection and try again.";
            }
            final String err = error;
            main.post(() -> done.done(err));
        });
    }

    /** Null when the file is Metro, signed like the installed copy; otherwise why it won't be installed. */
    static String verify(Context c, File f) {
        PackageManager pm = c.getPackageManager();
        PackageInfo got = pm.getPackageArchiveInfo(f.getPath(), signingFlags());
        if (got == null) return "The download isn't a valid app";
        if (!c.getPackageName().equals(got.packageName)) return "The download isn't Metro (" + got.packageName + ")";
        try {
            Set<String> mine = digests(pm.getPackageInfo(c.getPackageName(), signingFlags()));
            Set<String> theirs = digests(got);
            if (mine.isEmpty() || theirs.isEmpty()) return "Couldn't check who signed the download";
            if (!mine.equals(theirs)) {
                return "This update is signed with a different key than the Metro on this TV, so it can't replace it. "
                        + "Uninstall Metro and install the release from GitHub once; later updates will then work here.";
            }
        } catch (Exception e) {
            return "Couldn't check who signed the download";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> digests(PackageInfo info) throws Exception {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            sigs = info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        }
        if (sigs == null) sigs = info.signatures;
        Set<String> out = new HashSet<>();
        if (sigs == null) return out;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature s : sigs) {
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(s.toByteArray())) hex.append(String.format("%02x", b));
            out.add(hex.toString());
        }
        return out;
    }

    /**
     * Hands the verified download to Android's installer through a PackageInstaller session (no
     * FileProvider needed). The result arrives at {@link InstallReceiver}, which shows Android's
     * confirmation screen.
     */
    @SuppressLint("WrongConstant")
    static void install(Context ctx, Done done) {
        final Context c = ctx.getApplicationContext();
        Net.io.execute(() -> {
            String error = null;
            PackageInstaller.Session s = null;
            try {
                File f = apk(c);
                if (verify(c, f) != null) throw new SecurityException(verify(c, f));
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(c.getPackageName());
                sp.setSize(f.length());
                int id = pi.createSession(sp);
                s = pi.openSession(id);
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("metro.apk", 0, f.length())) {
                    Net.copy(in, out, null, f.length());
                    s.fsync(out);
                }
                // Explicit and not exported, so it may be mutable: the installer fills in the result.
                Intent result = new Intent(c, InstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, id, result, flags).getIntentSender());
            } catch (SecurityException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "Couldn't start the installer";
                if (s != null) s.abandon();
            } finally {
                if (s != null) s.close();
            }
            final String err = error;
            main.post(() -> done.done(err));
        });
    }

    private Updater() { }
}
