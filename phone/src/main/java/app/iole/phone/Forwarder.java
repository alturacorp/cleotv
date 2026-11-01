package app.iole.phone;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends incoming calls and messages to Cleo TV, encrypted for the TV. Nothing else is read or sent.
 * After a message it checks for a couple of minutes whether a quick reply was chosen on the TV, and sends it
 * through the original app's own reply button.
 */
public class Forwarder extends NotificationListenerService {
    private static final String[] MESSAGING = { "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca",
            "com.instagram.android", "com.discord", "com.microsoft.teams", "com.Slack", "com.snapchat.android", "com.viber.voip" };
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, StatusBarNotification> recent = new LinkedHashMap<String, StatusBarNotification>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, StatusBarNotification> e) { return size() > 40; }
    };
    private long pollUntil = 0;
    private final Map<String, Long> lastSent = new LinkedHashMap<>();

    @Override public void onCreate() { super.onCreate(); Link.init(this); }

    private boolean isMessaging(String pkg) { for (String m : MESSAGING) if (m.equals(pkg)) return true; return false; }
    private String label(String pkg) {
        try { PackageManager pm = getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return ""; }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!Link.linked(this) || sbn.getPackageName().equals(getPackageName())) return;
        SharedPreferences p = Link.prefs(this);
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        boolean msg = !call && (Notification.CATEGORY_MESSAGE.equals(n.category) || isMessaging(sbn.getPackageName()));
        if (call && !p.getBoolean("calls", true)) return;
        if (msg && !p.getBoolean("messages", true)) return;
        if (!call && !msg) return;
        if (!call && sbn.isOngoing()) return;
        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (title == null) title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null) return;
        String key = sbn.getKey(), dedupe = key + "|" + text;
        Long last = lastSent.get(dedupe);
        if (last != null && System.currentTimeMillis() - last < (call ? 30000 : 5000)) return; // apps repost the same notification
        lastSent.put(dedupe, System.currentTimeMillis()); if (lastSent.size() > 60) lastSent.remove(lastSent.keySet().iterator().next());
        recent.put(key, sbn);
        final JSONObject payload = new JSONObject();
        try {
            payload.put("kind", call ? "call" : "message").put("from", title.toString()).put("text", text == null ? "" : text.toString())
                    .put("app", label(sbn.getPackageName())).put("key", key);
        } catch (Exception e) { return; }
        io.execute(new Runnable() { @Override public void run() { try { Link.send(Forwarder.this, payload); } catch (Exception ignored) {} } });
        if (!call) startPolling();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!Link.linked(this)) return;
        if (Notification.CATEGORY_CALL.equals(sbn.getNotification().category)) {
            final JSONObject payload = new JSONObject();
            try { payload.put("kind", "callend").put("key", sbn.getKey()).put("from", ""); } catch (Exception e) { return; }
            io.execute(new Runnable() { @Override public void run() { try { Link.send(Forwarder.this, payload); } catch (Exception ignored) {} } });
        }
    }

    /* ---------- quick replies chosen on the TV ---------- */
    private void startPolling() {
        boolean idle = System.currentTimeMillis() > pollUntil;
        pollUntil = System.currentTimeMillis() + 120000;
        if (idle) ui.postDelayed(poll, 4000);
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (System.currentTimeMillis() > pollUntil || !Link.linked(Forwarder.this)) return;
            io.execute(new Runnable() { @Override public void run() {
                try {
                    JSONArray replies = Link.post("notify/poll", new JSONObject(), Link.token(Forwarder.this)).optJSONArray("replies");
                    String priv = Link.keys(Forwarder.this)[1];
                    for (int i = 0; replies != null && i < replies.length(); i++) {
                        JSONObject env = replies.getJSONObject(i).getJSONObject("env");
                        final JSONObject r = new JSONObject(Envelope.open(priv, env.getString("epk"), env.getString("iv"), env.getString("ct")));
                        ui.post(new Runnable() { @Override public void run() { act(r); } });
                    }
                } catch (Exception ignored) {}
            } });
            ui.postDelayed(this, 4000);
        }
    };

    private void act(JSONObject r) {
        StatusBarNotification sbn = recent.get(r.optString("key"));
        if (sbn == null) return;
        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions == null) return;
        try {
            if ("decline".equals(r.optString("action"))) {
                for (Notification.Action a : actions) {
                    String t = String.valueOf(a.title).toLowerCase();
                    if (t.contains("decline") || t.contains("reject") || t.contains("dismiss")) { a.actionIntent.send(); return; }
                }
                return;
            }
            for (Notification.Action a : actions) {
                RemoteInput[] ins = a.getRemoteInputs();
                if (ins == null || ins.length == 0) continue;
                Intent fill = new Intent(); Bundle res = new Bundle();
                for (RemoteInput in : ins) res.putCharSequence(in.getResultKey(), r.optString("text"));
                RemoteInput.addResultsToIntent(ins, fill, res);
                a.actionIntent.send(this, 0, fill);
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {}
    }
}
