package app.iole.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cleo TV home screen. The whole UI lives in assets/web/index.html; this activity hosts it
 * in a WebView and exposes a small bridge (window.IoleTV) for apps, icons, media and HTTP.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/web/index.html";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Map<String, ActivityInfo> leanbackInfo = new HashMap<>();
    private final Map<String, ActivityInfo> launcherInfo = new HashMap<>();
    private final LruCache<String, byte[]> imageCache = new LruCache<String, byte[]>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };
    private NowPlaying nowPlaying;
    private boolean pageReady = false;

    // Read-aloud (the TV's own text-to-speech; nothing leaves the device)
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    // Sleep timer: pauses what's playing and brings Cleo back to wind down
    private long sleepAt = 0;
    private final Runnable sleepRun = new Runnable() {
        @Override public void run() {
            sleepAt = 0;
            try { if (nowPlaying != null) { nowPlaying.start(); nowPlaying.action("pause"); } } catch (Exception ignored) {}
            Intent i = new Intent(MainActivity.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra("sleep", true);
            try { startActivity(i); } catch (Exception e) { js("iole.sleepDone()"); }
        }
    };

    // Break nudge: a quiet toast after a long stretch away in other apps
    private long breakMinutes = 0;
    private String breakMessage = "";
    private final Runnable breakRun = new Runnable() {
        @Override public void run() {
            if (breakMinutes <= 0) return;
            boolean playing = true;
            try { if (nowPlaying != null) { nowPlaying.start(); if (nowPlaying.hasAccess()) playing = nowPlaying.isPlaying(); } } catch (Exception ignored) {}
            if (playing) {
                Overlay.show(MainActivity.this, "A little break?", breakMessage, 15000);
                ui.postDelayed(this, breakMinutes * 60000L);
            } else {
                ui.postDelayed(this, 5 * 60000L); // nothing playing right now; look again soon
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            imageCache.evictAll();
            js("iole.appsChanged()");
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#100f0d"));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        hideSystemUi();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/icon/", new IconHandler())
                .addPathHandler("/media/", new MediaArtHandler())
                .addPathHandler("/files/", new SharedFileHandler())
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The launcher never navigates away from its own page.
                return !HOST.equals(request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "IoleTV");
        live = new java.lang.ref.WeakReference<>(this);
        doorbell = new Doorbell(this, new Doorbell.Ring() { @Override public void rang(String name) { js("iole.doorbell(" + JSONObject.quote(name) + ")"); } });
        web.loadUrl(START_URL);
        web.requestFocus();

        nowPlaying = new NowPlaying(this, new Runnable() {
            @Override public void run() { js("iole.media()"); }
        });

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                int r = tts.setLanguage(Locale.getDefault());
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) r = tts.setLanguage(Locale.UK);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { js("iole.speaking(true)"); }
                    @Override public void onDone(String id) { js("iole.speaking(false)"); }
                    @Override public void onError(String id) { js("iole.speaking(false)"); }
                });
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        ui.removeCallbacks(breakRun);
        if (nowPlaying != null) nowPlaying.start();
        if (pageReady) js("iole.resume()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tts != null) tts.stop();
        ui.removeCallbacks(breakRun);
        if (breakMinutes > 0) ui.postDelayed(breakRun, breakMinutes * 60000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Fired when Lume is opened again while already running (Home on devices that allow it, Alexa, a remapped button).
        if (!pageReady) return;
        if (intent != null && intent.getBooleanExtra("sleep", false)) js("iole.sleepDone()");
        else js("iole.home()");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
        ui.removeCallbacks(sleepRun); ui.removeCallbacks(breakRun);
        if (nowPlaying != null) nowPlaying.stop();
        if (doorbell != null) doorbell.stop();
        if (tts != null) { tts.shutdown(); tts = null; }
        io.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            // A launcher never closes on Back; the page decides what Back means.
            if (event.getAction() == KeyEvent.ACTION_UP) js("iole.back()");
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) js("iole.menu()");
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static java.lang.ref.WeakReference<MainActivity> live;
    private Doorbell doorbell;
    /** Lets receivers tell the page about something (a reminder going off) when Cleo is running. */
    static void notifyJs(String code) { MainActivity a = live == null ? null : live.get(); if (a != null) a.js(code); }

    void js(final String code) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (web != null) web.evaluateJavascript("try{" + code + "}catch(e){}", null);
            }
        });
    }

    /* ------------------------------------------------------------------ apps */

    private List<ResolveInfo> query(String category) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(category);
        try { return getPackageManager().queryIntentActivities(i, 0); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private String buildAppList() {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, JSONObject> out = new LinkedHashMap<>();
        synchronized (leanbackInfo) {
            leanbackInfo.clear();
            for (ResolveInfo ri : query(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
                leanbackInfo.put(pkg, ri.activityInfo);
                boolean banner = ri.activityInfo.banner != 0 || ri.activityInfo.applicationInfo.banner != 0;
                out.put(pkg, appJson(pkg, String.valueOf(ri.loadLabel(pm)), banner));
            }
        }
        for (ResolveInfo ri : query(Intent.CATEGORY_LAUNCHER)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            synchronized (leanbackInfo) { if (!launcherInfo.containsKey(pkg)) launcherInfo.put(pkg, ri.activityInfo); }
            if (out.containsKey(pkg)) continue;
            out.put(pkg, appJson(pkg, String.valueOf(ri.loadLabel(pm)), false));
        }
        JSONArray arr = new JSONArray();
        for (JSONObject o : out.values()) arr.put(o);
        return arr.toString();
    }

    private JSONObject appJson(String pkg, String label, boolean banner) {
        JSONObject o = new JSONObject();
        try {
            o.put("pkg", pkg);
            o.put("label", label.trim());
            o.put("banner", banner);
        } catch (Exception ignored) {}
        return o;
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    /* ----------------------------------------------------------- image paths */

    private static byte[] toPng(Drawable d, int maxW, int maxH) {
        if (d == null) return null;
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) { w = maxW; h = maxH; }
        float scale = Math.min((float) maxW / w, (float) maxH / h);
        if (scale > 1f && !(d instanceof BitmapDrawable)) scale = Math.min(scale, 4f); // vectors/adaptive scale cleanly
        else if (scale > 1f) scale = 1f; // don't upscale bitmaps; the WebView does it smoother
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, bw, bh);
        d.draw(c);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    private static WebResourceResponse png(byte[] data) {
        if (data == null) return notFound();
        WebResourceResponse r = new WebResourceResponse("image/png", null, new ByteArrayInputStream(data));
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=86400");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    /** /icon/banner/<pkg> and /icon/icon/<pkg> */
    private class IconHandler implements WebViewAssetLoader.PathHandler {
        @Override
        public WebResourceResponse handle(String path) {
            try {
                int slash = path.indexOf('/');
                if (slash < 0) return notFound();
                String kind = path.substring(0, slash);
                String pkg = Uri.decode(path.substring(slash + 1));
                String key = kind + ":" + pkg;
                byte[] cached = imageCache.get(key);
                if (cached != null) return png(cached);

                PackageManager pm = getPackageManager();
                Drawable d = null;
                if ("banner".equals(kind)) {
                    ActivityInfo ai;
                    synchronized (leanbackInfo) { ai = leanbackInfo.get(pkg); }
                    if (ai != null) d = ai.loadBanner(pm);
                    if (d == null) d = pm.getApplicationBanner(pkg);
                    if (d == null) return notFound();
                    byte[] out = toPng(d, 640, 360);
                    if (out != null) imageCache.put(key, out);
                    return png(out);
                } else {
                    ActivityInfo ai;
                    synchronized (leanbackInfo) { ai = leanbackInfo.get(pkg); if (ai == null) ai = launcherInfo.get(pkg); }
                    d = AppArt.icon(pm, ai, pkg);
                    if (d == null) return notFound();
                    byte[] out = "card".equals(kind) ? AppArt.card(d) : toPng(d, 256, 256);
                    if (out != null) imageCache.put(key, out);
                    return png(out);
                }
            } catch (Exception e) {
                return notFound();
            }
        }
    }

    /** /media/art — artwork of whatever is playing. */
    private class MediaArtHandler implements WebViewAssetLoader.PathHandler {
        @Override
        public WebResourceResponse handle(String path) {
            if (path.startsWith("saved/")) { // artwork kept for Continue Watching
                File f = savedArtFile(path.substring(6));
                if (f == null || !f.exists()) return notFound();
                try { return new WebResourceResponse("image/jpeg", null, new FileInputStream(f)); } catch (Exception e) { return notFound(); }
            }
            Bitmap art = nowPlaying != null ? nowPlaying.art() : null;
            if (art == null) return notFound();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                art.compress(Bitmap.CompressFormat.PNG, 100, bos);
                return new WebResourceResponse("image/png", null, new ByteArrayInputStream(bos.toByteArray()));
            } catch (Exception e) {
                return notFound();
            }
        }
    }

    /** Photos sent from a phone with Send to TV. Kept in the app's private storage until removed. */
    private File sharedFile(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,48}\\.(jpg|png|webp)")) return null;
        File dir = new File(getFilesDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, name);
    }

    private class SharedFileHandler implements WebViewAssetLoader.PathHandler {
        @Override
        public WebResourceResponse handle(String path) {
            File f = sharedFile(path);
            if (f == null || !f.exists()) return notFound();
            String type = path.endsWith(".png") ? "image/png" : path.endsWith(".webp") ? "image/webp" : "image/jpeg";
            try { return new WebResourceResponse(type, null, new FileInputStream(f)); } catch (Exception e) { return notFound(); }
        }
    }

    private String[] deviceKeys() {
        android.content.SharedPreferences p = getSharedPreferences("cleo_keys", MODE_PRIVATE);
        String pub = p.getString("pub", null), priv = p.getString("priv", null);
        if (pub == null || priv == null) {
            try {
                java.security.KeyPair kp = Envelope.newKeyPair();
                pub = Envelope.publicB64(kp); priv = Envelope.privateB64(kp);
                p.edit().putString("pub", pub).putString("priv", priv).apply();
            } catch (Exception e) { return null; }
        }
        return new String[]{pub, priv};
    }

    private File updateFile() {
        File dir = new File(getCacheDir(), "update");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "cleo-tv.apk");
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    /** Continue Watching artwork lives in the app's private storage, named by a key from the page. */
    private File savedArtFile(String key) {
        if (key == null || !key.matches("[a-z0-9]{1,24}")) return null;
        File dir = new File(getFilesDir(), "art");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, key + ".jpg");
    }

    private void pruneSavedArt(long maxAgeMs) {
        File[] files = new File(getFilesDir(), "art").listFiles();
        if (files == null) return;
        long cut = System.currentTimeMillis() - maxAgeMs;
        for (File f : files) if (f.lastModified() < cut) f.delete();
    }

    /* ---------------------------------------------------------------- bridge */

    private class Bridge {
        @JavascriptInterface
        public String getApps() { return buildAppList(); }

        @JavascriptInterface
        public boolean launch(final String pkg) {
            final Intent i = launchIntent(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ui.post(new Runnable() {
                @Override public void run() {
                    try { startActivity(i); } catch (Exception ignored) {}
                }
            });
            return true;
        }

        @JavascriptInterface
        public void openAppInfo(String pkg) {
            startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        }

        @JavascriptInterface
        public void openSettings() { startSafely(new Intent(Settings.ACTION_SETTINGS)); }

        @JavascriptInterface
        public boolean openNotificationAccess() {
            return startSafely(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        }

        @JavascriptInterface
        public boolean hasMediaAccess() { return nowPlaying != null && nowPlaying.hasAccess(); }

        @JavascriptInterface
        public String getNowPlaying() { return nowPlaying != null ? nowPlaying.json() : "null"; }

        @JavascriptInterface
        public void media(final String action) {
            ui.post(new Runnable() { @Override public void run() { if (nowPlaying != null) nowPlaying.action(action); } });
        }

        /** Saves what's playing's artwork under key, so the Continue Watching card keeps it. */
        @JavascriptInterface
        public boolean saveArt(String key) {
            Bitmap art = nowPlaying != null ? nowPlaying.art() : null;
            File f = savedArtFile(key);
            if (art == null || f == null) return false;
            try {
                int w = art.getWidth(), h = art.getHeight();
                Bitmap small = w > 640 ? Bitmap.createScaledBitmap(art, 640, Math.max(1, h * 640 / w), true) : art;
                FileOutputStream out = new FileOutputStream(f);
                small.compress(Bitmap.CompressFormat.JPEG, 85, out);
                out.close();
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void dropArt(String key) { File f = savedArtFile(key); if (f != null) f.delete(); }

        @JavascriptInterface
        public void pruneArt(int days) { pruneSavedArt(days * 86400000L); }

        /** Downloads a shared photo into private storage; answers through iole.response with its /files/ path. */
        @JavascriptInterface
        public void fetchFile(final String id, final String url, final String headersJson, final String name) {
            io.execute(new Runnable() {
                @Override public void run() {
                    int status = 0; String result = "";
                    HttpURLConnection c = null;
                    File f = sharedFile(name);
                    try {
                        if (f == null || !url.startsWith("https://")) throw new IllegalArgumentException("bad file");
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000); c.setReadTimeout(60000);
                        JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                        Iterator<String> keys = h.keys();
                        while (keys.hasNext()) { String k = keys.next(); c.setRequestProperty(k, h.getString(k)); }
                        status = c.getResponseCode();
                        if (status == 200) {
                            InputStream is = c.getInputStream();
                            FileOutputStream out = new FileOutputStream(f);
                            byte[] buf = new byte[16384]; int n; long total = 0;
                            while ((n = is.read(buf)) > 0) { total += n; if (total > 12 * 1024 * 1024) throw new IllegalStateException("too big"); out.write(buf, 0, n); }
                            out.close(); is.close();
                            result = "/files/" + name;
                        }
                    } catch (Exception e) {
                        if (f != null) f.delete();
                        status = 0; result = e.getClass().getSimpleName();
                    } finally { if (c != null) c.disconnect(); }
                    js("iole.response(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(result) + ")");
                }
            });
        }

        @JavascriptInterface
        public void deleteFile(String name) { File f = sharedFile(name); if (f != null) f.delete(); }

        /** Opens a web link sent from a phone: YouTube links go to the YouTube app when it's installed. */
        @JavascriptInterface
        public boolean openUrl(String url) {
            if (url == null || !url.matches("(?i)^https?://.+")) return false;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            return startSafely(i);
        }

        @JavascriptInterface
        public void setReminder(String id, double at, String text) { Reminders.set(MainActivity.this, id, (long) at, text); }

        @JavascriptInterface
        public void cancelReminder(String id) { Reminders.cancel(MainActivity.this, id); }

        @JavascriptInterface
        public String reminders() { return Reminders.all(MainActivity.this).toString(); }

        @JavascriptInterface
        public boolean canOverlay() { return Overlay.allowed(MainActivity.this); }

        @JavascriptInterface
        public void showOverlay(String title, String text) { Overlay.show(MainActivity.this, title, text, 12000); }

        /* ---------- updates from GitHub releases ---------- */
        @JavascriptInterface
        public String updateInfo() {
            try {
                return new JSONObject().put("repo", BuildConfig.UPDATE_REPO).put("build", BuildConfig.BUILD_NUMBER)
                        .put("version", BuildConfig.VERSION_NAME).put("canInstall", canInstallPackages()).toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Downloads the new APK into private cache, reporting progress through iole.updateProgress. */
        @JavascriptInterface
        public void downloadUpdate(final String id, final String url) {
            io.execute(new Runnable() {
                @Override public void run() {
                    int status = 0; String result = "";
                    HttpURLConnection c = null;
                    File f = updateFile();
                    try {
                        if (!url.startsWith("https://github.com/") && !url.startsWith("https://objects.githubusercontent.com/")) throw new IllegalArgumentException("not a GitHub link");
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setInstanceFollowRedirects(true); c.setConnectTimeout(15000); c.setReadTimeout(60000);
                        c.setRequestProperty("User-Agent", "CleoTV/" + BuildConfig.VERSION_NAME);
                        status = c.getResponseCode();
                        if (status == 200) {
                            long total = c.getContentLength(), done = 0; int last = -1;
                            InputStream is = c.getInputStream();
                            FileOutputStream out = new FileOutputStream(f);
                            byte[] buf = new byte[32768]; int n;
                            while ((n = is.read(buf)) > 0) {
                                out.write(buf, 0, n); done += n;
                                if (done > 80L * 1024 * 1024) throw new IllegalStateException("too big");
                                int pct = total > 0 ? (int) (done * 100 / total) : -1;
                                if (pct != last && pct % 5 == 0) { last = pct; js("iole.updateProgress(" + pct + ")"); }
                            }
                            out.close(); is.close();
                            result = "ok";
                        }
                    } catch (Exception e) {
                        f.delete(); status = 0; result = e.getClass().getSimpleName();
                    } finally { if (c != null) c.disconnect(); }
                    js("iole.response(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(result) + ")");
                }
            });
        }

        /** Hands the downloaded APK to Android's installer. Returns "started", "permission" (asked the person to allow installs) or "missing". */
        @JavascriptInterface
        public String installUpdate() {
            File f = updateFile();
            if (!f.exists() || f.length() < 100000) return "missing";
            if (!canInstallPackages()) {
                Intent p = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                if (!startSafely(p)) startSafely(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                return "permission";
            }
            try {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return startSafely(i) ? "started" : "missing";
            } catch (Exception e) { return "missing"; }
        }

        /* ---------- keys for end-to-end encryption ---------- */
        @JavascriptInterface
        public String deviceKey() { String[] k = deviceKeys(); return k == null ? "" : k[0]; }

        /** Opens an envelope sealed for this TV; "" if it can't be opened. */
        @JavascriptInterface
        public String openEnvelope(String json) {
            try {
                String[] k = deviceKeys(); JSONObject e = new JSONObject(json);
                return Envelope.open(k[1], e.getString("epk"), e.getString("iv"), e.getString("ct"));
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String sealFor(String publicKey, String text) { try { return Envelope.seal(publicKey, text); } catch (Exception e) { return ""; } }

        /* ---------- Cleo Home: doorbell, and a check for the Bluetooth phone link ---------- */
        @JavascriptInterface
        public void setDoorbell(String url, String token, String entity, String camera) { doorbell.configure(url, token, entity, camera); }

        @JavascriptInterface
        public String bluetoothCheck() {
            try {
                JSONObject o = new JSONObject().put("sdk", Build.VERSION.SDK_INT);
                android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                o.put("bluetooth", ad != null);
                o.put("ble", getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
                boolean adv = false;
                try { adv = ad != null && ad.isMultipleAdvertisementSupported(); } catch (Exception ignored) {}
                o.put("advertise", adv);
                // iPhones only offer their notifications (ANCS) to accessories that ask for them in their
                // advertisement ("service solicitation"), which Android apps can do from Android 12.
                o.put("solicit", Build.VERSION.SDK_INT >= 31);
                o.put("possible", adv && Build.VERSION.SDK_INT >= 31);
                return o.toString();
            } catch (Exception e) { return "{\"error\":true}"; }
        }

        @JavascriptInterface
        public String homeRedirect() {
            try {
                JSONObject o = new JSONObject();
                o.put("wanted", HomeRedirectService.wanted(MainActivity.this));
                o.put("running", HomeRedirectService.running(MainActivity.this));
                o.put("homes", new JSONArray(HomeRedirectService.findHomePackages(MainActivity.this)));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void setHomeRedirect(boolean on) { HomeRedirectService.prefs(MainActivity.this).edit().putBoolean("redirect", on).apply(); }

        @JavascriptInterface
        public boolean openAccessibilitySettings() { return startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }

        /** Shows Fire OS's own home for a minute without the redirect bouncing back to Cleo. */
        @JavascriptInterface
        public void openAmazonHome() {
            HomeRedirectService.pause(MainActivity.this, 60000);
            for (String p : HomeRedirectService.findHomePackages(MainActivity.this)) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(p);
                if (startSafely(i)) return;
            }
        }

        @JavascriptInterface
        public double memoryMb() {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi.totalMem / 1048576.0;
            } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public boolean canSpeak() { return ttsReady; }

        @JavascriptInterface
        public void speak(final String text, final float rate) {
            ui.post(new Runnable() { @Override public void run() {
                if (tts == null || !ttsReady) return;
                tts.setSpeechRate(Math.max(0.6f, Math.min(1.5f, rate)));
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cleo-" + System.currentTimeMillis());
            } });
        }

        @JavascriptInterface
        public void stopSpeaking() { ui.post(new Runnable() { @Override public void run() { if (tts != null) tts.stop(); } }); }

        @JavascriptInterface
        public void sleepIn(final int minutes) {
            ui.post(new Runnable() { @Override public void run() {
                ui.removeCallbacks(sleepRun);
                if (minutes > 0) { sleepAt = System.currentTimeMillis() + minutes * 60000L; ui.postDelayed(sleepRun, minutes * 60000L); }
                else sleepAt = 0;
            } });
        }

        @JavascriptInterface
        public double sleepLeft() { return sleepAt == 0 ? 0 : Math.max(0, sleepAt - System.currentTimeMillis()); }

        @JavascriptInterface
        public void setBreakNudge(int minutes, String message) { breakMinutes = Math.max(0, minutes); breakMessage = message == null ? "" : message; }

        @JavascriptInterface
        public String deviceInfo() {
            return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        }

        /** Async HTTP so the page can reach the Lume Worker without CORS. Answers via iole.response(). */
        @JavascriptInterface
        public void request(final String id, final String method, final String url, final String headersJson, final String body) {
            io.execute(new Runnable() {
                @Override public void run() {
                    int status = 0;
                    String text;
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestMethod(method);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(90000);
                        JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                        Iterator<String> keys = h.keys();
                        while (keys.hasNext()) { String k = keys.next(); c.setRequestProperty(k, h.getString(k)); }
                        if (body != null && !body.isEmpty() && !"GET".equals(method)) {
                            c.setDoOutput(true);
                            byte[] b = body.getBytes("UTF-8");
                            c.setFixedLengthStreamingMode(b.length);
                            OutputStream os = c.getOutputStream();
                            os.write(b);
                            os.close();
                        }
                        status = c.getResponseCode();
                        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                        text = is == null ? "" : readAll(is);
                    } catch (Exception e) {
                        status = 0;
                        text = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    js("iole.response(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(text) + ")");
                }
            });
        }
    }

    private boolean startSafely(final Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) == null) return false;
        ui.post(new Runnable() {
            @Override public void run() { try { startActivity(i); } catch (Exception ignored) {} }
        });
        return true;
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    static ComponentName listenerComponent(Context ctx) {
        return new ComponentName(ctx, MediaListener.class);
    }
}
