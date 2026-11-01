package app.iole.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Watches one Home Assistant doorbell entity while Cleo is running (including behind another app) and shows a
 * card with a camera snapshot over whatever is playing. Polls the local Home Assistant every 2 seconds; only
 * your Home Assistant is contacted.
 */
final class Doorbell {
    interface Ring { void rang(String name); }
    private final Context ctx;
    private final Ring ring;
    private volatile String base = "", token = "", entity = "", camera = "";
    private volatile Thread thread;
    private String lastState = null;

    Doorbell(Context c, Ring r) { ctx = c.getApplicationContext(); ring = r; }

    synchronized void configure(String url, String tok, String ent, String cam) {
        base = url == null ? "" : url.replaceAll("/+$", "");
        token = tok == null ? "" : tok; entity = ent == null ? "" : ent; camera = cam == null ? "" : cam;
        lastState = null;
        if (base.isEmpty() || token.isEmpty() || entity.isEmpty()) { stop(); return; }
        if (thread == null) {
            thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "cleo-doorbell");
            thread.setDaemon(true); thread.start();
        }
    }

    synchronized void stop() { Thread t = thread; thread = null; if (t != null) t.interrupt(); }

    private void loop() {
        int wait = 2000;
        while (thread == Thread.currentThread()) {
            try {
                JSONObject st = new JSONObject(new String(get("/api/states/" + entity), "UTF-8"));
                String state = st.optString("state");
                String name = st.optJSONObject("attributes") != null ? st.optJSONObject("attributes").optString("friendly_name", "Doorbell") : "Doorbell";
                boolean isEvent = entity.startsWith("event.");
                boolean rang = lastState != null && !state.equals(lastState) && (isEvent ? !"unavailable".equals(state) : "on".equals(state));
                lastState = state;
                if (rang) {
                    Bitmap snap = null;
                    if (!camera.isEmpty()) { try { byte[] b = get("/api/camera_proxy/" + camera); snap = BitmapFactory.decodeByteArray(b, 0, b.length); } catch (Exception ignored) {} }
                    Overlay.show(ctx, name, "Someone’s at the door", 25000, snap);
                    if (ring != null) ring.rang(name);
                }
                wait = 2000;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                wait = Math.min(30000, wait * 2); // Home Assistant unreachable: back off
            }
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
        }
    }

    private byte[] get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(4000); c.setReadTimeout(8000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            InputStream is = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 6_000_000) break; }
            return bo.toByteArray();
        } finally { c.disconnect(); }
    }
}
