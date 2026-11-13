package com.dk.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
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
    private View ccPanel, ccHeader, ccFooter, homeRoot, ytPanel, ytHeader;
    private ControlCenter cc;
    private WebView yt;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private static final String YT_URL = "https://m.youtube.com/";
    /** Vị trí trang: -1 = YouTube (bên phải) hiện, 0 = màn hình chính, 1 = trung tâm điều khiển (bên trái) hiện. */
    private float pos, startPos, dragLo, dragHi;
    private float downX, downY;
    private boolean tracking, dragging;
    private int touchSlop;
    private ValueAnimator anim;
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
        homeRoot = findViewById(R.id.homeRoot);
        ytPanel = findViewById(R.id.ytPanel);
        ytHeader = findViewById(R.id.ytHeader);
        findViewById(R.id.ytClose).setOnClickListener(v -> animateTo(0f));
        buildYoutube();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

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
        if (yt != null) yt.onResume();
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
        if (ControlCenter.keepOpen) {
            ControlCenter.keepOpen = false;
            cc.refresh();
            return;
        }
        closeCc();
        resetSearch();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    protected void onPause() {
        if (yt != null) yt.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (yt != null) {
            ((ViewGroup) yt.getParent()).removeView(yt);
            yt.destroy();
        }
        cc.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustom(); return; }
        if (pos < 0f) {
            if (yt.canGoBack()) yt.goBack(); else animateTo(0f);
        } else if (pos > 0f) {
            animateTo(0f);
        } else {
            resetSearch();
        }
        // Là màn hình chính nên không thoát khi nhấn Back
    }

    // ---------- Trung tâm điều khiển ----------

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    /** p = 1: trung tâm điều khiển (trái) hiện hẳn; 0: màn hình chính; -1: YouTube (phải) hiện hẳn. */
    private void setPos(float p) {
        pos = Math.max(-1f, Math.min(1f, p));
        int w = screenW();
        homeRoot.setTranslationX(pos * w);
        ccPanel.setTranslationX((pos - 1f) * w);
        ytPanel.setTranslationX((pos + 1f) * w);
        ccPanel.setVisibility(pos > 0f ? View.VISIBLE : View.GONE);
        ytPanel.setVisibility(pos < 0f ? View.VISIBLE : View.GONE);
    }

    private void animateTo(final float target) {
        if (anim != null) anim.cancel();
        if (target > 0f) cc.refresh();
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(220);
        anim.addUpdateListener(va -> setPos((Float) va.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Rời khỏi màn hình YouTube thì tạm dừng video
                if (pos == 0f && yt != null) {
                    yt.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();})", null);
                }
            }
        });
        anim.start();
    }

    private void openCc() {
        animateTo(1f);
    }

    private void closeCc() {
        if (pos != 0f) animateTo(0f);
    }

    private boolean hit(View v, float rawY) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return rawY >= l[1] && rawY <= l[1] + v.getHeight();
    }

    // ---------- YouTube (màn hình bên phải) ----------

    private void buildYoutube() {
        FrameLayout holder = findViewById(R.id.ytHolder);
        yt = new WebView(this);
        WebSettings ws = yt.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        yt.setBackgroundColor(0xFF0D1224);
        yt.setWebViewClient(new WebViewClient());
        yt.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                view.setBackgroundColor(Color.BLACK);
                ((ViewGroup) findViewById(android.R.id.content)).addView(view,
                        new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }
        });
        holder.addView(yt, new FrameLayout.LayoutParams(-1, -1));
        yt.loadUrl(YT_URL);
    }

    private void hideCustom() {
        if (customView == null) return;
        ((ViewGroup) customView.getParent()).removeView(customView);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
    }

    /**
     * Vuốt từ trái sang phải: hiện trung tâm điều khiển (bên trái).
     * Vuốt từ phải sang trái: hiện YouTube (bên phải).
     * Đóng YouTube: vuốt trái sang phải ở thanh tiêu đề hoặc sát mép trái, hoặc bấm "ĐÓNG".
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (anim != null) anim.cancel();
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                if (customView != null) tracking = false;
                else if (pos == 0f) tracking = !search.hasFocus();
                else if (pos > 0f) tracking = true;
                else tracking = downX < dp(36) || hit(ytHeader, downY);
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !dragging) {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        boolean ok;
                        if (pos >= 1f) ok = dx < 0;
                        else if (pos <= -1f) ok = dx > 0;
                        else ok = true;
                        if (ok) {
                            dragging = true;
                            startPos = pos;
                            boolean positive = pos > 0f || (pos == 0f && dx > 0);
                            dragLo = positive ? 0f : -1f;
                            dragHi = positive ? 1f : 0f;
                            downX = e.getRawX();
                            MotionEvent c = MotionEvent.obtain(e);
                            c.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(c);
                            c.recycle();
                        } else {
                            tracking = false;
                        }
                    }
                }
                if (dragging) {
                    float p = startPos + (e.getRawX() - downX) / screenW();
                    setPos(Math.max(dragLo, Math.min(dragHi, p)));
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    float target;
                    if (Math.abs(startPos) < 0.5f) target = Math.abs(pos) > 0.3f ? Math.signum(pos) : 0f;
                    else target = Math.abs(pos) > 0.7f ? Math.signum(startPos) : 0f;
                    animateTo(target);
                    return true;
                }
                break;
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
