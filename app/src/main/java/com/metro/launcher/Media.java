package com.metro.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tracks what each app is playing through the system media sessions (needs notification-listener
 * access), and remembers the last thing every app played so tiles can show "Previously playing".
 * Lives for the whole process: the listener service keeps it recording even while Start isn't open.
 */
final class Media implements MediaSessionManager.OnActiveSessionsChangedListener {
    interface Listener { void onMediaChanged(); }

    static final class Item {
        String pkg, title, sub;
        boolean playing, active;
        long when, duration, position, positionAt;
        float speed = 1;
        /** Cover art or video thumbnail, and the title it belongs to; read back from disk on demand. */
        Bitmap art;
        String artFor;
        int artPx;
        boolean artLoaded;

        /** Playback progress 0-1, or -1 when the app doesn't report a duration. */
        float progress() {
            if (duration <= 0) return -1;
            long pos = position;
            if (playing && positionAt > 0) pos += (long) ((android.os.SystemClock.elapsedRealtime() - positionAt) * speed);
            return Math.max(0, Math.min(1, pos / (float) duration));
        }
    }

    static final long KEEP_MS = 7 * 24 * 3600_000L;
    /** Art is kept small: a tile shows it at most ~450 px across, and RGB_565 halves the memory again. */
    static final int ART_PX = 320;

    private static Media inst;

    static Media get(Context c) {
        if (inst == null) inst = new Media(c.getApplicationContext());
        return inst;
    }

    final Context ctx;
    final SharedPreferences prefs;
    final Handler h = new Handler(Looper.getMainLooper());
    final Map<String, Item> items = new HashMap<>();
    final Map<MediaController, MediaController.Callback> sessions = new HashMap<>();
    Listener listener;
    boolean started;
    /** One thread for art, so deleting an old cover and writing the next one can't overtake each other. */
    final ExecutorService io = Executors.newSingleThreadExecutor();

    private Media(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("media", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        // Development builds kept covers here without knowing which title they went with.
        io.execute(() -> {
            File[] old = new File(c.getFilesDir(), "media").listFiles();
            if (old != null) for (File f : old) f.delete();
        });
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String[] f = String.valueOf(e.getValue()).split("\t", -1);
            if (f.length < 3) continue;
            Item it = new Item();
            it.pkg = e.getKey();
            it.when = Long.parseLong(f[0]);
            it.title = f[1];
            it.sub = f[2].isEmpty() ? null : f[2];
            if (f.length >= 5) {
                it.position = Long.parseLong(f[3]);
                it.duration = Long.parseLong(f[4]);
            }
            if (now - it.when > KEEP_MS) continue;
            // The saved cover, if any, belongs to the saved title.
            it.artFor = it.title;
            items.put(it.pkg, it);
        }
    }

    /** Safe to call repeatedly; starts tracking as soon as notification access has been granted. */
    boolean start() {
        if (started) return true;
        MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName cn = new ComponentName(ctx, MediaListener.class);
        try {
            m.addOnActiveSessionsChangedListener(this, cn, h);
            started = true;
            onActiveSessionsChanged(m.getActiveSessions(cn));
        } catch (SecurityException e) {
            started = false;
        }
        return started;
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> list) {
        for (Map.Entry<MediaController, MediaController.Callback> e : sessions.entrySet())
            e.getKey().unregisterCallback(e.getValue());
        sessions.clear();
        for (Item it : items.values()) { it.active = false; it.playing = false; }
        if (list != null) {
            for (final MediaController c : list) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata m) { update(c); }
                    @Override public void onPlaybackStateChanged(PlaybackState s) { update(c); }
                };
                c.registerCallback(cb, h);
                sessions.put(c, cb);
                update(c);
            }
        }
        changed();
    }

    void update(MediaController c) {
        String pkg = c.getPackageName();
        PlaybackState st = c.getPlaybackState();
        boolean playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
        MediaMetadata md = c.getMetadata();
        Item it = items.get(pkg);
        String title = md == null ? null : first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) {
            if (it != null) { it.active = true; it.playing = playing; position(it, st); changed(); }
            return;
        }
        if (it == null) {
            it = new Item();
            it.pkg = pkg;
            items.put(pkg, it);
        }
        boolean newTrack = !title.equals(it.title);
        it.title = title;
        if (newTrack) {
            it.art = null;
            it.artFor = null;
            it.artPx = 0;
            it.artLoaded = true;
            final File f = artFile(pkg);
            io.execute(f::delete);
        }
        art(it, md);
        it.sub = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        it.active = true;
        it.playing = playing;
        if (playing || newTrack) it.when = System.currentTimeMillis();
        it.duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        position(it, st);
        prefs.edit().putString(pkg, it.when + "\t" + clean(it.title) + "\t" + clean(it.sub) + "\t" + it.position + "\t" + it.duration).apply();
        changed();
    }

    /**
     * Apps hand over art as a bitmap, or as a URI. Only local URIs can be read: web ones would need
     * the internet permission, which this launcher deliberately doesn't have.
     */
    void art(final Item it, MediaMetadata md) {
        Bitmap b = null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            b = md.getBitmap(k);
            if (b != null) break;
        }
        String uri = null;
        if (b == null) for (String k : new String[]{MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String u = md.getString(k);
            if (u != null && (u.startsWith("content:") || u.startsWith("android.resource:") || u.startsWith("file:"))) { uri = u; break; }
        }
        if (b == null && uri == null) return;
        // Apps often send a small icon first and the full cover a moment later: take the bigger one.
        int size = b != null ? Math.min(b.getWidth(), b.getHeight()) : ART_PX;
        if (it.title.equals(it.artFor) && size <= it.artPx) return;
        final String title = it.title, pkg = it.pkg, src = uri;
        it.artFor = title;
        it.artPx = size;
        final Bitmap given = b;
        io.execute(() -> {
            Bitmap full = given;
            if (full == null) {
                try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(src))) {
                    full = BitmapFactory.decodeStream(in);
                } catch (Exception ignored) { }
            }
            if (full == null || full.getWidth() < 8 || full.getHeight() < 8) return;
            final Bitmap small = shrink(full);
            try (FileOutputStream out = new FileOutputStream(artFile(pkg))) {
                small.compress(Bitmap.CompressFormat.JPEG, 88, out);
            } catch (Exception ignored) { }
            h.post(() -> {
                if (!title.equals(it.title)) return;
                it.art = small;
                it.artLoaded = true;
                changed();
            });
        });
    }

    static Bitmap shrink(Bitmap b) {
        float s = Math.min(1f, ART_PX / (float) Math.min(b.getWidth(), b.getHeight()));
        Bitmap scaled = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
        Bitmap out = scaled.copy(Bitmap.Config.RGB_565, false);
        if (scaled != b) scaled.recycle();
        return out != null ? out : b;
    }

    /** The item's art, decoded from disk the first time it's needed. */
    Bitmap art(Item it) {
        if (!it.artLoaded) {
            it.artLoaded = true;
            File f = artFile(it.pkg);
            if (f.exists()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                it.art = BitmapFactory.decodeFile(f.getPath(), o);
            }
        }
        return it.art;
    }

    static void position(Item it, PlaybackState st) {
        if (st == null || st.getPosition() < 0) return;
        it.position = st.getPosition();
        it.positionAt = st.getLastPositionUpdateTime();
        it.speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1;
    }

    static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }

    static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    File artFile(String pkg) {
        File dir = new File(ctx.getFilesDir(), "art");
        dir.mkdirs();
        return new File(dir, pkg + ".jpg");
    }

    void changed() {
        if (listener != null) listener.onMediaChanged();
    }

    Item item(String pkg) {
        Item it = items.get(pkg);
        if (it == null || it.title == null) return null;
        if (!it.active && System.currentTimeMillis() - it.when > KEEP_MS) return null;
        return it;
    }

    /** What's playing now, or else whatever played most recently. */
    Item latest() {
        Item best = null;
        for (Item it : items.values()) {
            if (item(it.pkg) == null) continue;
            if (best == null || (it.playing && !best.playing) || (it.playing == best.playing && it.when > best.when)) best = it;
        }
        return best;
    }

    MediaController controller(String pkg) {
        for (MediaController c : sessions.keySet()) if (c.getPackageName().equals(pkg)) return c;
        return null;
    }

    void forget(String pkg) {
        items.remove(pkg);
        prefs.edit().remove(pkg).apply();
        final File f = artFile(pkg);
        io.execute(f::delete);
        changed();
    }

    List<Item> all() { return new ArrayList<>(items.values()); }
}
