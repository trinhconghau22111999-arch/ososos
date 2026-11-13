package com.dk.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.widget.BaseAdapter;
import android.widget.ListView;
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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
    private View ccPanel, ccHeader, homeRoot, ytPanel;
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
    private VelocityTracker vt;
    private ScaleGestureDetector scale;
    private int cols = 4;
    private float cumScale = 1f;
    private boolean pinching, pinchJustStarted;
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
        cols = Math.max(1, Math.min(4, getSharedPreferences(PREFS, MODE_PRIVATE).getInt("cols", 4)));
        grid.setColumnCount(cols);
        scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                cumScale = 1f;
                pinching = true;
                pinchJustStarted = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                cumScale *= d.getScaleFactor();
                if (cumScale > 1.25f && cols > 1) { setCols(cols - 1); cumScale = 1f; }       // phóng to: ít cột, icon to hơn
                else if (cumScale < 0.8f && cols < 4) { setCols(cols + 1); cumScale = 1f; }   // thu nhỏ: nhiều cột hơn
                return true;
            }
        });
        ccPanel = findViewById(R.id.ccPanel);
        ccHeader = findViewById(R.id.ccHeader);
        homeRoot = findViewById(R.id.homeRoot);
        ytPanel = findViewById(R.id.ytPanel);
        buildYoutube();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        findViewById(R.id.clockPanel).setBackground(Ui.panel(this, Ui.GRID));
        search.setBackground(Ui.panel(this, Ui.PLAIN));
        battery.setBackground(Ui.panel(this, Ui.HOT, 8));

        buildDock();

        cc = new ControlCenter(this, (GridLayout) findViewById(R.id.ccGrid),
                (TextView) findViewById(R.id.ccInfo), this::closeCc);
        findViewById(R.id.ccSettings).setOnClickListener(v -> go(new Intent(Settings.ACTION_SETTINGS)));

        setPos(0f);

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
        ensureDark();
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
        int cv = pos > 0f ? View.VISIBLE : View.INVISIBLE;
        int yv = pos < 0f ? View.VISIBLE : View.INVISIBLE;
        if (ccPanel.getVisibility() != cv) ccPanel.setVisibility(cv);
        if (ytPanel.getVisibility() != yv) ytPanel.setVisibility(yv);
    }

    /** Bật layer phần cứng khi đang vuốt/chuyển trang để trượt mượt, tắt lại khi xong. */
    private void layers(boolean on) {
        int t = on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (homeRoot.getLayerType() != t) homeRoot.setLayerType(t, null);
        if (ccPanel.getLayerType() != t) ccPanel.setLayerType(t, null);
        if (ytPanel.getLayerType() != t) ytPanel.setLayerType(t, null);
    }

    private void animateTo(final float target) {
        animateTo(target, 0f);
    }

    /** vx: vận tốc ngón tay (px/giây) lúc thả, dùng để chọn thời gian trượt cho tự nhiên. */
    private void animateTo(final float target, float vx) {
        if (anim != null) anim.cancel();
        layers(true);
        float dist = Math.abs(target - pos);
        long dur = 120 + (long) (140 * dist);
        if (Math.abs(vx) > 0f) {
            float need = dist * screenW();
            long byVel = (long) (need / Math.max(Math.abs(vx), 1f) * 1000f);
            dur = Math.max(100, Math.min(dur, byVel));
        }
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(dur);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(va -> setPos((Float) va.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                setPos(target);
                layers(false);
                if (pos == 0f && yt != null) {
                    yt.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();})", null);
                }
                if (pos == 1f) cc.refresh();
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
        yt.addJavascriptInterface(new AdBridge(), "HauOsAd");
        yt.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                injectTheme(v);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                injectTheme(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                injectTheme(v);
            }
        });
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

    private String themeJs;

    private String readAsset(String name) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(name), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Cầu nối JS -> app: trang báo toạ độ nút "Bỏ qua quảng cáo", app gửi cú chạm thật vào đó. */
    private class AdBridge {
        @JavascriptInterface
        public void skip(final float x, final float y, final float dpr) {
            runOnUiThread(() -> tapYoutube(x * dpr, y * dpr));
        }
    }

    private void tapYoutube(final float x, final float y) {
        if (yt == null || pos != -1f) return;           // chỉ khi màn hình YouTube đang hiện
        String u = yt.getUrl();
        if (u == null || !u.contains("youtube.com")) return;
        final long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        yt.dispatchTouchEvent(d);
        d.recycle();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            MotionEvent up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            yt.dispatchTouchEvent(up);
            up.recycle();
        }, 70);
    }

    /** Phủ giao diện gaming (assets/yt_theme.css) lên trang YouTube. */
    private void injectTheme(WebView v) {
        try {
            if (themeJs == null) {
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("yt_theme.css"), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                r.close();
                themeJs = readAsset("yt_adskip.js") + "\n(function(){var id='hauos-theme',s=document.getElementById(id);"
                        + "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}"
                        + "s.textContent=" + JSONObject.quote(sb.toString()) + ";})();";
            }
            v.evaluateJavascript(themeJs, null);
        } catch (Exception ignored) { }
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
     * Đóng YouTube: vuốt trái sang phải bắt đầu từ sát mép trái, hoặc bấm Back.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (vt != null) vt.addMovement(e);
        // Chụm / nở hai ngón tay trên màn hình chính để đổi số ứng dụng mỗi hàng
        if (pos == 0f && !dragging && customView == null) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) pinching = false;
            scale.onTouchEvent(e);
            if (pinching) {
                int act = e.getActionMasked();
                if (pinchJustStarted) {
                    pinchJustStarted = false;
                    MotionEvent c = MotionEvent.obtain(e);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(c);
                    c.recycle();
                }
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) pinching = false;
                tracking = false;
                return true;
            }
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!dragging) tracking = false;
                break;
            case MotionEvent.ACTION_DOWN:
                if (anim != null) anim.cancel();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                if (customView != null) tracking = false;
                else if (pos == 0f) tracking = !search.hasFocus();
                else if (pos > 0f) tracking = true;
                else tracking = downX < dp(40);
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
                            layers(true);
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
                    float vx = 0f;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                    }
                    float target;
                    if (Math.abs(vx) > dp(500)) {
                        // vuốt nhanh: chuyển trang theo hướng ngón tay
                        if (Math.abs(startPos) < 0.5f) target = Math.max(dragLo, Math.min(dragHi, vx > 0 ? 1f : -1f));
                        else target = (vx * startPos < 0) ? 0f : Math.signum(startPos);
                    } else if (Math.abs(startPos) < 0.5f) {
                        target = Math.abs(pos) > 0.3f ? Math.signum(pos) : 0f;
                    } else {
                        target = Math.abs(pos) > 0.7f ? Math.signum(startPos) : 0f;
                    }
                    animateTo(target, vx);
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
        return makeTile(label, icon, iconDp, style, 10);
    }

    private LinearLayout makeTile(CharSequence label, Drawable icon, int iconDp, int style, int textSp) {
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
        t.setTextSize(textSp);
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

    private static final String PREFS = "hauos";
    private Intent[] dockDefaults;

    private void buildDock() {
        LinearLayout dock = findViewById(R.id.dock);
        dock.removeAllViews();
        if (dockDefaults == null) {
            dockDefaults = new Intent[]{
                    new Intent(Intent.ACTION_DIAL),
                    sel(Intent.CATEGORY_APP_MESSAGING),
                    sel(Intent.CATEGORY_APP_BROWSER),
                    new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            };
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (int i = 0; i < dockDefaults.length; i++) {
            final int slot = i;
            Intent launch = null;
            CharSequence label = null;
            Drawable icon = null;
            String saved = sp.getString("dock" + i, null);
            if (saved != null) {
                ComponentName cn = ComponentName.unflattenFromString(saved);
                if (cn != null) {
                    try {
                        ActivityInfo ai = pm.getActivityInfo(cn, 0);
                        label = ai.loadLabel(pm);
                        icon = ai.loadIcon(pm);
                        launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn);
                    } catch (Exception ignored) { }
                }
            }
            if (launch == null) {
                ResolveInfo ri = pm.resolveActivity(dockDefaults[i], 0);
                if (ri != null) {
                    label = ri.loadLabel(pm);
                    icon = ri.loadIcon(pm);
                    launch = new Intent(dockDefaults[i]);
                } else {
                    label = "+";
                    icon = getResources().getDrawable(android.R.drawable.ic_input_add);
                }
            }
            final Intent fl = launch;
            LinearLayout t = makeTile(label, icon, 38, Ui.PLAIN);
            t.setOnClickListener(x -> { if (fl != null) go(new Intent(fl)); else showDockPicker(slot); });
            t.setOnLongClickListener(x -> {
                x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                showDockPicker(slot);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            dock.addView(t, lp);
        }
    }

    /** Nhấn giữ một ô ở thanh dưới: hiện danh sách mọi ứng dụng để thay vào ô đó. */
    private void showDockPicker(final int slot) {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<Entry> list = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            Entry e = new Entry();
            e.label = ri.loadLabel(pm);
            e.icon = ri.loadIcon(pm);
            e.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            list.add(e);
        }
        Collections.sort(list, new Comparator<Entry>() {
            public int compare(Entry x, Entry y) {
                return x.label.toString().compareToIgnoreCase(y.label.toString());
            }
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText(Ui.up("Chọn ứng dụng thay thế"));
        title.setTextColor(Ui.CYAN);
        title.setTextSize(16);
        Ui.style(title);
        box.addView(title);

        TextView reset = new TextView(this);
        reset.setText(Ui.up("↺  Trở về mặc định"));
        reset.setTextColor(Ui.PINK);
        reset.setTextSize(13);
        reset.setPadding(0, dp(10), 0, dp(10));
        Ui.style(reset);
        box.addView(reset);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return list.size(); }
            public Object getItem(int i) { return list.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(7), dp(4), dp(7));
                ImageView iv = new ImageView(MainActivity.this);
                iv.setImageDrawable(list.get(i).icon);
                row.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
                TextView tv = new TextView(MainActivity.this);
                tv.setText(Ui.up(list.get(i).label));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(14);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(14), 0, 0, 0);
                Ui.style(tv);
                row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        });
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        final AlertDialog d = new AlertDialog.Builder(this).setView(box).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.panel(this, Ui.GRID));
        lv.setOnItemClickListener((parent, v, pos2, id) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("dock" + slot, list.get(pos2).cn.flattenToString()).apply();
            buildDock();
            d.dismiss();
        });
        reset.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("dock" + slot).apply();
            buildDock();
            d.dismiss();
        });
        d.show();
    }

    private static boolean darkTried;

    /**
     * Mở app là đưa điện thoại về giao diện tối.
     * 1) Có quyền WRITE_SECURE_SETTINGS (cấp một lần bằng adb) thì đặt thẳng ui_night_mode = tối.
     * 2) Không có thì nhờ dịch vụ Hỗ trợ tiếp cận bấm nút "Chế độ tối" trong bảng nhanh (nếu dịch vụ đã bật).
     * Mỗi lần chạy app chỉ thử một lần để không lặp.
     */
    private void ensureDark() {
        if (darkTried) return;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) return;
        darkTried = true;
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) {
                Settings.Secure.putInt(getContentResolver(), "ui_night_mode", 2);
                return;
            }
        } catch (Exception ignored) { }
        ScreenshotService svc = ScreenshotService.instance;
        if (svc != null) {
            ControlCenter.keepOpen = true;
            svc.clickTile(new String[]{"chế độ tối", "giao diện tối", "dark mode", "dark"}, null);
        }
    }

    private int iconDpFor(int c) {
        return c >= 4 ? 38 : c == 3 ? 50 : c == 2 ? 66 : 84;
    }

    private int textSpFor(int c) {
        return c >= 4 ? 10 : c == 3 ? 12 : c == 2 ? 14 : 16;
    }

    /** Đổi số ứng dụng trên một hàng (1 đến 4) và lưu lại. */
    private void setCols(int n) {
        n = Math.max(1, Math.min(4, n));
        if (n == cols) return;
        cols = n;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("cols", cols).apply();
        grid.setColumnCount(cols);
        signature = "";
        reloadApps();
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
            LinearLayout t = makeTile(e.label, e.icon, iconDpFor(cols), Ui.PLAIN, textSpFor(cols));
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
