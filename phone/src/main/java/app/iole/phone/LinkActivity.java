package app.iole.phone;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.Executors;

/** One screen: link with a code from cleo.ac, allow notification access, choose what goes to the TV. */
public class LinkActivity extends Activity {
    private LinearLayout box;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final int BG = 0xFF100F0D, CARD = 0xFF1C1A17, INK = 0xFFECE8E1, INK3 = 0xFF8F8A82, ACCENT = 0xFFC4B5FD;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Link.init(this);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24); box.setPadding(pad, dp(40), pad, pad);
        sv.addView(box); setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int sp, int color, boolean serif) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (serif) t.setTypeface(Typeface.SERIF); t.setLineSpacing(0, 1.2f); t.setPadding(0, dp(6), 0, dp(6)); return t;
    }
    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button btn = new Button(this); btn.setText(s); btn.setAllCaps(false); btn.setTextSize(16);
        btn.setTextColor(primary ? BG : INK);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(24)); g.setColor(primary ? INK : CARD); btn.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)); lp.topMargin = dp(12); btn.setLayoutParams(lp);
        btn.setOnClickListener(l); return btn;
    }
    private boolean hasAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, Forwarder.class).flattenToString());
    }

    private void render() {
        box.removeAllViews();
        box.addView(text("✦  Cleo", 15, ACCENT, false));
        box.addView(text("Phone link", 32, INK, true));
        if (!Link.linked(this)) {
            box.addView(text("On cleo.ac, open Settings, Cleo ID, Phone link, and choose Android. Type the code it shows.", 16, INK3, false));
            final EditText code = new EditText(this);
            code.setHint("K7Q4MP"); code.setTextColor(INK); code.setHintTextColor(INK3); code.setTextSize(28); code.setGravity(Gravity.CENTER);
            code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            code.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(7), new InputFilter.AllCaps() });
            box.addView(code);
            final TextView status = text("", 14, INK3, false);
            box.addView(button("Link", true, new View.OnClickListener() { @Override public void onClick(View v) {
                final String c = code.getText().toString().replaceAll("[^A-Za-z0-9]", "");
                if (c.length() != 6) { status.setText("The code has 6 letters and numbers."); return; }
                status.setText("Linking…");
                Executors.newSingleThreadExecutor().execute(new Runnable() { @Override public void run() {
                    String err = null;
                    try { Link.claim(LinkActivity.this, c); } catch (Exception e) { err = e.getMessage(); }
                    final String fe = err;
                    ui.post(new Runnable() { @Override public void run() { if (fe != null) status.setText(fe); else render(); } });
                } });
            } }));
            box.addView(status);
            return;
        }
        SharedPreferences p = Link.prefs(this);
        box.addView(text("Linked to " + p.getString("name", "your Cleo ID") + "’s TV.", 16, INK3, false));
        if (!hasAccess()) {
            box.addView(text("One more step: let Cleo see notifications, so it can pass calls and messages to your TV.", 16, INK, false));
            box.addView(button("Allow notification access", true, new View.OnClickListener() { @Override public void onClick(View v) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } }));
        }
        box.addView(toggle("Calls", "Who’s calling, and decline from the TV", "calls"));
        box.addView(toggle("Messages", "Texts, WhatsApp, Telegram, Signal and similar, with quick replies from the TV", "messages"));
        box.addView(text("What leaves this phone: the sender, the message and the app’s name, encrypted so only your TV can read them. Cleo’s server passes them on and doesn’t keep them. Turn this off any time here, or remove the phone on cleo.ac.", 13, INK3, false));
        final TextView st = text("", 14, INK3, false);
        box.addView(button("Send a test to the TV", false, new View.OnClickListener() { @Override public void onClick(View v) {
            st.setText("Sending…");
            Executors.newSingleThreadExecutor().execute(new Runnable() { @Override public void run() {
                String res;
                try { int n = Link.send(LinkActivity.this, new JSONObject().put("kind", "message").put("from", "Cleo").put("text", "Your phone is linked").put("app", "Cleo").put("key", "test")); res = n > 0 ? "Sent. Look at your TV." : "No TV got it. Is Cleo open on the TV?"; }
                catch (Exception e) { res = e.getMessage(); }
                final String fr = res; ui.post(new Runnable() { @Override public void run() { st.setText(fr); } });
            } });
        } }));
        box.addView(st);
        box.addView(button("Unlink this phone", false, new View.OnClickListener() { @Override public void onClick(View v) { Link.unlink(LinkActivity.this); render(); } }));
    }

    private View toggle(String title, String sub, final String key) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(4));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 18, INK, false)); col.addView(text(sub, 14, INK3, false));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final Switch sw = new Switch(this); sw.setChecked(Link.prefs(this).getBoolean(key, true));
        sw.setOnCheckedChangeListener((b, on) -> Link.prefs(LinkActivity.this).edit().putBoolean(key, on).apply());
        row.addView(sw);
        return row;
    }
}
