package com.dk.home;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    static class App {
        CharSequence label;
        Drawable icon;
        ComponentName cn;
    }

    private PackageManager pm;
    private AudioManager audio;
    private View drawer;
    private EditText search;
    private TextView battery, volLabel;
    private SeekBar vol;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private AppAdapter adapter;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        pm = getPackageManager();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        drawer = findViewById(R.id.drawer);
        search = findViewById(R.id.search);
        battery = findViewById(R.id.battery);
        volLabel = findViewById(R.id.volLabel);
        vol = findViewById(R.id.vol);

        findViewById(R.id.searchBar).setOnClickListener(v -> openDrawer());
        buildQuick();
        buildFavorites();
        buildDock();
        buildVolume();

        adapter = new AppAdapter();
        GridView grid = findViewById(R.id.appGrid);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            App a = shown.get(pos);
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn);
            go(i);
            closeDrawer();
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + shown.get(pos).cn.getPackageName())));
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { filter(s.toString()); }
            public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0) battery.setText("Pin " + (level * 100 / scale) + "%");
        }
        syncVolume();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        // Là màn hình chính nên không thoát khi nhấn Back
    }

    // ---------- Màn hình chính ----------

    private void go(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Không mở được ứng dụng này", Toast.LENGTH_SHORT).show();
        }
    }

    private void buildQuick() {
        LinearLayout row = findViewById(R.id.quickRow);
        addQuick(row, "📶", "Wi-Fi", Settings.ACTION_WIFI_SETTINGS);
        addQuick(row, "🔷", "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS);
        addQuick(row, "🔊", "Âm thanh", Settings.ACTION_SOUND_SETTINGS);
        addQuick(row, "☀", "Màn hình", Settings.ACTION_DISPLAY_SETTINGS);
    }

    private void addQuick(LinearLayout row, String glyph, String label, final String action) {
        TextView t = new TextView(this);
        t.setText(glyph + "\n" + label);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setBackgroundResource(R.drawable.bg_panel);
        t.setPadding(0, dp(12), 0, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> go(new Intent(action)));
        row.addView(t);
    }

    private Intent sel(String category) {
        return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
    }

    private View tile(final Intent intent, boolean accent) {
        ResolveInfo ri = pm.resolveActivity(intent, 0);
        if (ri == null) return null;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(2), dp(10), dp(2), dp(8));
        v.setBackgroundResource(accent ? R.drawable.bg_accent : R.drawable.bg_panel);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(ri.loadIcon(pm));
        v.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView t = new TextView(this);
        t.setText(ri.loadLabel(pm));
        t.setTextColor(Color.WHITE);
        t.setTextSize(11);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(2), dp(4), dp(2), 0);
        v.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setOnClickListener(x -> go(new Intent(intent)));
        return v;
    }

    private void buildFavorites() {
        GridLayout g = findViewById(R.id.favGrid);
        Intent[] list = {
                sel(Intent.CATEGORY_APP_GALLERY),
                sel(Intent.CATEGORY_APP_CONTACTS),
                new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                sel(Intent.CATEGORY_APP_CALENDAR),
                sel(Intent.CATEGORY_APP_CALCULATOR),
                sel(Intent.CATEGORY_APP_MAPS),
                sel(Intent.CATEGORY_APP_EMAIL),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent i : list) {
            View t = tile(i, false);
            if (t == null) continue;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            g.addView(t, lp);
        }
    }

    private void buildDock() {
        LinearLayout dock = findViewById(R.id.dock);
        Intent[] list = {
                new Intent(Intent.ACTION_DIAL),
                sel(Intent.CATEGORY_APP_MESSAGING),
                sel(Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        };
        for (Intent i : list) {
            View t = tile(i, false);
            if (t == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            dock.addView(t, lp);
        }
        TextView all = new TextView(this);
        all.setText("⋮⋮⋮\nTất cả");
        all.setGravity(Gravity.CENTER);
        all.setTextColor(0xFF00E5FF);
        all.setTextSize(12);
        all.setTypeface(null, android.graphics.Typeface.BOLD);
        all.setBackgroundResource(R.drawable.bg_accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        all.setOnClickListener(v -> openDrawer());
        dock.addView(all, lp);
    }

    private void buildVolume() {
        vol.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        vol.setProgressTintList(ColorStateList.valueOf(0xFF00E5FF));
        vol.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
                volLabel.setText("Âm lượng  " + (p * 100 / Math.max(1, s.getMax())));
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
    }

    private void syncVolume() {
        vol.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    // ---------- Tất cả ứng dụng ----------

    private void loadApps() {
        all.clear();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            App a = new App();
            a.label = ri.loadLabel(pm);
            a.icon = ri.loadIcon(pm);
            a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            all.add(a);
        }
        Collections.sort(all, new Comparator<App>() {
            public int compare(App x, App y) {
                return x.label.toString().compareToIgnoreCase(y.label.toString());
            }
        });
    }

    private void filter(String q) {
        shown.clear();
        String k = q.trim().toLowerCase();
        for (App a : all) {
            if (k.isEmpty() || a.label.toString().toLowerCase().contains(k)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
    }

    private void openDrawer() {
        loadApps();
        search.setText("");
        filter("");
        drawer.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.setVisibility(View.GONE);
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private class AppAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int p) { return shown.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int p, View convert, ViewGroup parent) {
            LinearLayout v;
            ImageView iv;
            TextView t;
            if (convert == null) {
                v = new LinearLayout(MainActivity.this);
                v.setOrientation(LinearLayout.VERTICAL);
                v.setGravity(Gravity.CENTER);
                v.setPadding(dp(2), dp(8), dp(2), dp(8));
                iv = new ImageView(MainActivity.this);
                v.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
                t = new TextView(MainActivity.this);
                t.setTextColor(Color.WHITE);
                t.setTextSize(11);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, dp(4), 0, 0);
                v.addView(t);
            } else {
                v = (LinearLayout) convert;
                iv = (ImageView) v.getChildAt(0);
                t = (TextView) v.getChildAt(1);
            }
            App a = shown.get(p);
            iv.setImageDrawable(a.icon);
            t.setText(a.label);
            return v;
        }
    }
}
