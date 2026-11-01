package app.iole.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyPair;

/** The phone's side of the link: its key pair, the token from cleo.ac, and the TVs' public keys. */
final class Link {
    private Link() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("cleo_link", Context.MODE_PRIVATE); }

    static String[] keys(Context c) {
        SharedPreferences p = prefs(c);
        String pub = p.getString("pub", null), priv = p.getString("priv", null);
        if (pub == null || priv == null) {
            try {
                KeyPair kp = Envelope.newKeyPair();
                pub = Envelope.publicB64(kp); priv = Envelope.privateB64(kp);
                p.edit().putString("pub", pub).putString("priv", priv).apply();
            } catch (Exception e) { return null; }
        }
        return new String[]{pub, priv};
    }

    static boolean linked(Context c) { return prefs(c).getString("token", null) != null; }
    static String token(Context c) { return prefs(c).getString("token", null); }

    static JSONObject post(String path, JSONObject body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.WORKER + "/id/" + path).openConnection();
        try {
            c.setRequestMethod("POST"); c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (token != null) c.setRequestProperty("X-Cleo-Notify", token);
            byte[] b = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream(); os.write(b); os.close();
            int st = c.getResponseCode();
            InputStream is = st >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) { byte[] buf = new byte[4096]; int n; while ((n = is.read(buf)) > 0) bo.write(buf, 0, n); }
            JSONObject j = bo.size() > 0 ? new JSONObject(bo.toString("UTF-8")) : new JSONObject();
            if (st == 401) { unlink(null); throw new IllegalStateException("unlinked"); }
            if (st >= 400) throw new IllegalStateException(j.optString("error", "HTTP " + st));
            return j;
        } finally { c.disconnect(); }
    }

    private static Context appCtx;
    static void init(Context c) { appCtx = c.getApplicationContext(); }
    static void unlink(Context c) {
        Context x = c != null ? c : appCtx; if (x == null) return;
        prefs(x).edit().remove("token").remove("tvs").remove("name").apply();
    }

    /** Swaps a 6-character code from cleo.ac for this phone's own token. */
    static JSONObject claim(Context c, String code) throws Exception {
        String[] k = keys(c);
        JSONObject r = post("notify/claim", new JSONObject().put("code", code).put("key", k[0]).put("label", android.os.Build.MODEL), null);
        prefs(c).edit().putString("token", r.getString("token")).putString("name", r.optString("name"))
                .putString("tvs", r.optJSONArray("tvs") != null ? r.getJSONArray("tvs").toString() : "[]").putLong("tvsAt", System.currentTimeMillis()).apply();
        return r;
    }

    static JSONArray tvs(Context c) {
        SharedPreferences p = prefs(c);
        if (System.currentTimeMillis() - p.getLong("tvsAt", 0) > 3600000L && token(c) != null) {
            try { JSONObject r = post("notify/keys", new JSONObject(), token(c)); p.edit().putString("tvs", r.getJSONArray("tvs").toString()).putLong("tvsAt", System.currentTimeMillis()).apply(); } catch (Exception ignored) {}
        }
        try { return new JSONArray(p.getString("tvs", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** Sends one notification, encrypted separately for each linked TV. Returns how many TVs it went to. */
    static int send(Context c, JSONObject payload) throws Exception {
        String t = token(c); if (t == null) return 0;
        payload.put("pk", keys(c)[0]);
        JSONArray list = tvs(c); JSONObject env = new JSONObject();
        for (int i = 0; i < list.length(); i++) {
            JSONObject tv = list.getJSONObject(i);
            String key = tv.optString("key", "");
            if (key.length() > 40) env.put(tv.getString("id"), new JSONObject(Envelope.seal(key, payload.toString())));
        }
        if (env.length() == 0) throw new IllegalStateException("Open Cleo on your TV once, so it can share its key");
        return post("notify", new JSONObject().put("env", env), t).optInt("sent");
    }
}
