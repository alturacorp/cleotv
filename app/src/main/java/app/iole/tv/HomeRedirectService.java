package app.iole.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Make Home open Cleo". Fire OS won't let another app become the home screen, so this watches only
 * for the system home screen coming to the front and opens Cleo on top of it. It reads which app is
 * in front (package and screen name), never what is on screen or typed.
 */
public class HomeRedirectService extends AccessibilityService {
    static final String PREFS = "cleo";
    private Set<String> homePackages = new HashSet<>();
    private long lastRedirect = 0;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE); }
    static boolean wanted(Context c) { return prefs(c).getBoolean("redirect", false); }
    static void pause(Context c, long ms) { prefs(c).edit().putLong("pauseUntil", System.currentTimeMillis() + ms).apply(); }

    static boolean running(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return flat != null && flat.contains(c.getPackageName() + "/" + HomeRedirectService.class.getName());
        } catch (Exception e) { return false; }
    }

    /** Every app that answers the HOME intent except Cleo itself: on Fire TV, Amazon's home. */
    static Set<String> findHomePackages(Context c) {
        Set<String> out = new HashSet<>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : list) {
                String p = ri.activityInfo.packageName;
                if (!p.equals(c.getPackageName()) && !"android".equals(p) && !p.contains("settings")) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    @Override protected void onServiceConnected() { homePackages = findHomePackages(this); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (!homePackages.contains(pkg)) return;
        if (!wanted(this)) return;
        if (System.currentTimeMillis() < prefs(this).getLong("pauseUntil", 0)) return;
        CharSequence cls = event.getClassName();
        String c = cls == null ? "" : cls.toString().toLowerCase();
        // Leave Amazon Kids and Amazon's own settings screens alone, so nobody gets locked in.
        if (c.contains("kids") || c.contains("freetime") || c.contains("tahoe") || c.contains("setting") || c.contains("profile")) return;
        long now = System.currentTimeMillis();
        if (now - lastRedirect < 1500) return;
        lastRedirect = now;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    @Override public void onInterrupt() {}
}
