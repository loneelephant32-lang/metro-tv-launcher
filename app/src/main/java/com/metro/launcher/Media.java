package com.metro.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        /** Playback progress 0-1, or -1 when the app doesn't report a duration. */
        float progress() {
            if (duration <= 0) return -1;
            long pos = position;
            if (playing && positionAt > 0) pos += (long) ((android.os.SystemClock.elapsedRealtime() - positionAt) * speed);
            return Math.max(0, Math.min(1, pos / (float) duration));
        }
    }

    static final long KEEP_MS = 7 * 24 * 3600_000L;

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

    private Media(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("media", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
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
        return new File(new File(ctx.getFilesDir(), "media"), pkg + ".jpg");
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
        artFile(pkg).delete();
        changed();
    }

    List<Item> all() { return new ArrayList<>(items.values()); }
}
