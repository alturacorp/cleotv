package app.iole.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A small Cleo card in the top corner, shown over whatever app is playing (reminders, break nudges).
 * Needs "display over other apps" (granted with adb on Fire TV); without it, falls back to a long toast.
 * It never takes focus or input, so the app underneath keeps the remote.
 */
final class Overlay {
    private Overlay() {}
    private static View current;

    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static void show(final Context ctx, final String title, final String text, final long ms) {
        final Context c = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (!allowed(c)) {
                    try { Toast.makeText(c, title + ": " + text, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                    return;
                }
                try {
                    final WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                    hide(wm);
                    float d = c.getResources().getDisplayMetrics().density;
                    LinearLayout box = new LinearLayout(c);
                    box.setOrientation(LinearLayout.VERTICAL);
                    int p = (int) (18 * d);
                    box.setPadding(p, (int) (14 * d), p, (int) (14 * d));
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xEB1B1916); bg.setCornerRadius(18 * d); bg.setStroke((int) Math.max(1, d), 0x26FFFFFF);
                    box.setBackground(bg);
                    TextView t = new TextView(c);
                    t.setText("✦  " + title);
                    t.setTextColor(0xFFC4B5FD); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); t.setTypeface(Typeface.DEFAULT_BOLD);
                    TextView b = new TextView(c);
                    b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                    b.setMaxWidth((int) (360 * d)); b.setPadding(0, (int) (4 * d), 0, 0);
                    box.addView(t); box.addView(b);
                    int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.END;
                    lp.x = (int) (28 * d); lp.y = (int) (24 * d);
                    box.setAlpha(0f);
                    wm.addView(box, lp);
                    current = box;
                    box.animate().alpha(1f).setDuration(350).start();
                    final View shown = box;
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() {
                            if (current != shown) return;
                            shown.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() { @Override public void run() { hide(wm); } }).start();
                        }
                    }, Math.max(4000, ms));
                } catch (Exception e) {
                    try { Toast.makeText(c, title + ": " + text, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private static void hide(WindowManager wm) {
        if (current == null) return;
        try { wm.removeView(current); } catch (Exception ignored) {}
        current = null;
    }
}
