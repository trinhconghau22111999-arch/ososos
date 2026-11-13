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
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private static class Entry {
        CharSequence label;
        Drawable icon;
        ComponentName cn;
    }

    private PackageManager pm;
    private EditText search;
    private TextView battery;
    private GridLayout grid;
    private ScrollView scroll;
    private View ccPanel, ccHeader, ccFooter;
    private ControlCenter cc;
    private float downY;
    private String signature = "";

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        pm = getPackageManager();
        search = findViewById(R.id.search);
        battery = findViewById(R.id.battery);
        grid = findViewById(R.id.appsGrid);
        scroll = findViewById(R.id.homeScroll);
        ccPanel = findViewById(R.id.ccPanel);
        ccHeader = findViewById(R.id.ccHeader);
        ccFooter = findViewById(R.id.ccFooter);

        findViewById(R.id.clockPanel).setBackground(Ui.panel(this, Ui.GRID));
        findViewById(R.id.ctlBtn).setBackground(Ui.panel(this, Ui.ACCENT));
        search.setBackground(Ui.panel(this, Ui.PLAIN));
        battery.setBackground(Ui.panel(this, Ui.HOT, 8));

        buildDock();

        cc = new ControlCenter(this, (GridLayout) findViewById(R.id.ccGrid),
                (TextView) findViewById(R.id.ccInfo), this::closeCc);
        findViewById(R.id.ctlBtn).setOnClickListener(v -> openCc());
        ccFooter.setOnClickListener(v -> closeCc());
        findViewById(R.id.ccSettings).setOnClickListener(v -> go(new Intent(Settings.ACTION_SETTINGS)));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { filter(s.toString()); }
            public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0) battery.setText("● PIN " + (level * 100 / scale) + "%");
        }
        cc.refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeCc();
        resetSearch();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    protected void onDestroy() {
        cc.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (ccPanel.getVisibility() == View.VISIBLE) closeCc();
        else resetSearch();
        // Là màn hình chính nên không thoát khi nhấn Back
    }

    // ---------- Trung tâm điều khiển ----------

    private void openCc() {
        cc.refresh();
        ccPanel.setVisibility(View.VISIBLE);
    }

    private void closeCc() {
        ccPanel.setVisibility(View.GONE);
    }

    private boolean hit(View v, float rawY) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return rawY >= l[1] && rawY <= l[1] + v.getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            downY = e.getRawY();
        } else if (e.getAction() == MotionEvent.ACTION_UP) {
            float dy = e.getRawY() - downY;
            if (ccPanel.getVisibility() == View.VISIBLE) {
                if (dy < -dp(90) && (hit(ccHeader, downY) || hit(ccFooter, downY))) closeCc();
            } else if (scroll.getScrollY() == 0 && !search.hasFocus()
                    && downY < getResources().getDisplayMetrics().heightPixels * 0.4f && dy > dp(100)) {
                openCc();
            }
        }
        return super.dispatchTouchEvent(e);
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

    private void resetSearch() {
        search.setText("");
        search.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private LinearLayout makeTile(CharSequence label, Drawable icon, int iconDp, int style) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(2), dp(10), dp(2), dp(8));
        v.setBackground(Ui.panel(this, style));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        v.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        TextView t = new TextView(this);
        t.setText(Ui.up(label));
        t.setTextColor(Color.WHITE);
        Ui.style(t);
        t.setTextSize(10);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(2), dp(4), dp(2), 0);
        v.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setTag(label.toString().toLowerCase());
        return v;
    }

    private Intent sel(String category) {
        return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
    }

    private void buildDock() {
        LinearLayout dock = findViewById(R.id.dock);
        Intent[] list = {
                new Intent(Intent.ACTION_DIAL),
                sel(Intent.CATEGORY_APP_MESSAGING),
                sel(Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        };
        for (final Intent i : list) {
            ResolveInfo ri = pm.resolveActivity(i, 0);
            if (ri == null) continue;
            LinearLayout t = makeTile(ri.loadLabel(pm), ri.loadIcon(pm), 38, Ui.PLAIN);
            t.setOnClickListener(x -> go(new Intent(i)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            dock.addView(t, lp);
        }
    }

    /** Đưa toàn bộ ứng dụng lên màn hình chính. */
    private void reloadApps() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<Entry> apps = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            Entry e = new Entry();
            e.label = ri.loadLabel(pm);
            e.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            e.icon = ri.loadIcon(pm);
            apps.add(e);
            sig.append(e.cn.flattenToShortString()).append(';');
        }
        String s = sig.toString();
        if (s.equals(signature) && grid.getChildCount() > 0) return;
        signature = s;

        Collections.sort(apps, new Comparator<Entry>() {
            public int compare(Entry x, Entry y) {
                return x.label.toString().compareToIgnoreCase(y.label.toString());
            }
        });
        grid.removeAllViews();
        for (final Entry e : apps) {
            LinearLayout t = makeTile(e.label, e.icon, 38, Ui.PLAIN);
            t.setOnClickListener(x -> go(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.cn)));
            t.setOnLongClickListener(x -> {
                go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + e.cn.getPackageName())));
                return true;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(t, lp);
        }
        filter(search.getText().toString());
    }

    private void filter(String q) {
        String k = q.trim().toLowerCase();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            boolean ok = k.isEmpty() || String.valueOf(v.getTag()).contains(k);
            v.setVisibility(ok ? View.VISIBLE : View.GONE);
        }
    }
}
