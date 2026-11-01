package app.iole.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reminders set on the TV ("remind me at 8 to call Mum"). Kept in private preferences so they survive a
 * restart, and shown with Overlay over whatever is playing when they're due.
 */
public class Reminders extends BroadcastReceiver {
    static final String ACTION = "app.iole.tv.REMINDER";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("cleo_reminders", Context.MODE_PRIVATE); }

    static JSONArray all(Context c) {
        try { return new JSONArray(prefs(c).getString("list", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static void save(Context c, JSONArray a) { prefs(c).edit().putString("list", a.toString()).apply(); }

    private static PendingIntent pi(Context c, String id) {
        Intent i = new Intent(c, Reminders.class).setAction(ACTION).putExtra("id", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags);
    }

    private static void arm(Context c, String id, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = pi(c, id);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, p);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    static void set(Context c, String id, long at, String text) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || text == null) return;
        cancel(c, id);
        try {
            JSONArray a = all(c);
            a.put(new JSONObject().put("id", id).put("at", at).put("text", text.length() > 200 ? text.substring(0, 200) : text));
            save(c, a);
        } catch (Exception ignored) {}
        arm(c, id, at);
    }

    static void cancel(Context c, String id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi(c, id));
        JSONArray a = all(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && !id.equals(o.optString("id"))) out.put(o); }
        save(c, out);
    }

    /** After a reboot, alarms are gone: set them again, and show any that came due while the TV was off. */
    static void rearmAll(Context c) {
        JSONArray a = all(c), keep = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i); if (o == null) continue;
            long at = o.optLong("at");
            if (at > now) { keep.put(o); arm(c, o.optString("id"), at); }
            else if (now - at < 6 * 3600000L) Overlay.show(c, "Reminder", o.optString("text"), 20000);
        }
        save(c, keep);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String id = intent.getStringExtra("id");
        JSONArray a = all(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optString("id").equals(id)) { Overlay.show(c, "Reminder", o.optString("text"), 20000); break; }
        }
        if (id != null) cancel(c, id);
        MainActivity.notifyJs("iole.reminderFired(" + JSONObject.quote(id == null ? "" : id) + ")");
    }
}
