package app.iole.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.List;

/**
 * Tracks the active media session (Netflix, Spotify, Plex…) so the home screen can show
 * "Now playing" with controls. Needs notification-listener access for MediaListener,
 * which Fire OS only grants via adb (see README).
 */
class NowPlaying {
    private final Context ctx;
    private final Runnable onChange;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName component;
    private MediaSessionManager msm;
    private volatile MediaController controller;
    private volatile int artVersion = 0;
    private boolean listening = false;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) { pick(list); }
            };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { onChange.run(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { artVersion++; onChange.run(); }
        @Override public void onSessionDestroyed() {
            if (msm != null) {
                try { pick(msm.getActiveSessions(component)); } catch (Exception e) { setController(null); }
            }
        }
    };

    NowPlaying(Context ctx, Runnable onChange) {
        this.ctx = ctx.getApplicationContext();
        this.onChange = onChange;
        this.component = MainActivity.listenerComponent(ctx);
    }

    boolean hasAccess() {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(component.flattenToString());
        } catch (Exception e) {
            return false;
        }
    }

    void start() {
        if (listening) return;
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler);
            listening = true;
            pick(msm.getActiveSessions(component));
        } catch (SecurityException e) {
            listening = false; // no access yet
        } catch (Exception e) {
            listening = false;
        }
    }

    void stop() {
        if (!listening || msm == null) return;
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {}
        setController(null);
        listening = false;
    }

    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null) best = c;
            }
        }
        setController(best);
        onChange.run();
    }

    private void setController(MediaController c) {
        MediaController old = controller;
        if (old != null && c != null && old.getSessionToken().equals(c.getSessionToken())) return;
        if (old != null) { try { old.unregisterCallback(callback); } catch (Exception ignored) {} }
        controller = c;
        artVersion++;
        if (c != null) { try { c.registerCallback(callback, handler); } catch (Exception ignored) {} }
    }

    String json() {
        MediaController c = controller;
        if (c == null) return "null";
        try {
            MediaMetadata md = c.getMetadata();
            PlaybackState st = c.getPlaybackState();
            JSONObject o = new JSONObject();
            String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null && md != null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null) return "null";
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            long pos = 0;
            if (st != null) {
                pos = st.getPosition();
                if (playing && st.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                }
            }
            o.put("title", title);
            o.put("artist", artist == null ? "" : artist);
            o.put("app", appLabel(c.getPackageName()));
            o.put("pkg", c.getPackageName());
            o.put("playing", playing);
            o.put("position", Math.max(0, pos));
            o.put("duration", md.getLong(MediaMetadata.METADATA_KEY_DURATION));
            o.put("art", art() != null ? artVersion : 0);
            return o.toString();
        } catch (Exception e) {
            return "null";
        }
    }

    boolean isPlaying() {
        MediaController c = controller;
        PlaybackState st = c == null ? null : c.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    Bitmap art() {
        MediaController c = controller;
        if (c == null) return null;
        MediaMetadata md = c.getMetadata();
        if (md == null) return null;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    void action(String what) {
        MediaController c = controller;
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        if ("pause".equals(what)) t.pause();
        else if ("play".equals(what)) t.play();
        else if ("next".equals(what)) t.skipToNext();
        else if ("previous".equals(what)) t.skipToPrevious();
        else {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }
}
