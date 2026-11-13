package com.dk.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
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
import android.view.KeyEvent;
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
import java.io.ByteArrayInputStream;
import android.speech.RecognizerIntent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.content.ActivityNotFoundException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.dk.home.remote.RemotePage;
import com.dk.home.truyentep.TruyenTepPage;

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
    private TextView homeTitle;
    private static final String DEFAULT_TITLE = "HẬU OS";
    private GridLayout grid;
    private ScrollView scroll;
    private View ccPanel, ccHeader, homeRoot, ytPanel;
    private ControlCenter cc;
    private WebView yt;
    /** Domain quảng cáo bị chặn ở tầng mạng cho trang YouTube (đọc từ assets/blocklist.txt). */
    private final Set<String> ytBlockedHosts = new HashSet<>();
    /** Mã xin quyền / nhận kết quả giọng nói cho nút micro của YouTube. */
    private static final int REQ_YT_VOICE = 7401, REQ_YT_MIC = 7402;
    private View tvPanel;
    private TvPage tv;
    private boolean tvActive;
    private View rcPanel;
    private RemotePage rc;
    private boolean rcActive;
    private View brPanel;
    // Thanh tìm kiếm Google (tiện ích mặc định của máy) ở cuối trang Tiện ích
    private static final int REQ_QSB_BIND = 7301, REQ_QSB_CFG = 7302;
    private static final String KEY_QSB = "qsb_id", KEY_QSB_ASKED = "qsb_asked";
    private AppWidgetHost qsbHost;
    private AppWidgetManager qsbMgr;
    private FrameLayout qsbHolder;
    private int qsbId = -1;
    private BrowserPage br;
    // "Duyệt web" kiểu Webandr (chép từ Mac-Os): thay cho BrowserPage sau khi bấm "Đổi trình duyệt" trong Cài đặt trình duyệt.
    private BrowserHost webandr;
    private static final String KEY_WEBANDR = "browser_webandr";
    private boolean useWebandr;
    // Trang "Truyền tệp" (chép từ truyentep): nằm bên TRÁI Duyệt web, chỉ kết nối khi đang mở trang này.
    private TruyenTepPage truyen;
    private boolean truyenActive;
    private boolean appPaused;
    private AiAssistant ai;
    private boolean brActive;
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
    private int cols = 1;
    private float cumScale = 1f;
    private boolean pinching, pinchJustStarted;
    private boolean scaleStepDone;   // mỗi cử chỉ chụm/mở chỉ đổi số cột 1 bậc
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
        cols = Math.max(1, Math.min(4, getSharedPreferences(PREFS, MODE_PRIVATE).getInt("cols", 1)));
        grid.setColumnCount(cols);
        scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                cumScale = 1f;
                scaleStepDone = false;
                pinching = true;
                pinchJustStarted = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (scaleStepDone) return true;   // đã đổi 1 bậc trong cử chỉ này, chờ cử chỉ mới
                cumScale *= d.getScaleFactor();
                if (cumScale > 1.25f && cols > 1) { setCols(cols - 1); scaleStepDone = true; }       // phóng to: ít cột, icon to hơn
                else if (cumScale < 0.8f && cols < 4) { setCols(cols + 1); scaleStepDone = true; }   // thu nhỏ: nhiều cột hơn
                return true;
            }
        });
        ccPanel = findViewById(R.id.ccPanel);
        ccHeader = findViewById(R.id.ccHeader);
        homeRoot = findViewById(R.id.homeRoot);
        ytPanel = findViewById(R.id.ytPanel);
        buildYoutube();
        tvPanel = findViewById(R.id.tvPanel);
        tv = new TvPage(this, e -> super.dispatchKeyEvent(e));
        ((ViewGroup) tvPanel).addView(tv, new FrameLayout.LayoutParams(-1, -1));
        rcPanel = findViewById(R.id.rcPanel);
        rc = new RemotePage(this);
        ((ViewGroup) rcPanel).addView(rc, new FrameLayout.LayoutParams(-1, -1));
        brPanel = findViewById(R.id.brPanel);
        br = new BrowserPage(this);
        ((ViewGroup) brPanel).addView(br, new FrameLayout.LayoutParams(-1, -1));
        truyen = new TruyenTepPage(this);
        ((ViewGroup) brPanel.getParent()).addView(truyen, new FrameLayout.LayoutParams(-1, -1));
        truyen.setVisibility(View.INVISIBLE);
        useWebandr = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WEBANDR, false);
        if (useWebandr) applyBrowserEngine();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        qsbHolder = findViewById(R.id.qsbHolder);
        try {
            qsbMgr = AppWidgetManager.getInstance(this);
            qsbHost = new AppWidgetHost(this, 1024);
            qsbId = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_QSB, -1);
            if (qsbId >= 0) showQsb(qsbId);
        } catch (Exception ignored) { }

        findViewById(R.id.clockPanel).setBackground(Ui.panel(this, Ui.GRID));
        ai = new AiAssistant(this);
        View aiBtn = findViewById(R.id.aiBtn);
        aiBtn.setBackground(Ui.panel(this, Ui.ACCENT, 8));
        aiBtn.setOnClickListener(v -> ai.start());
        findViewById(R.id.clock).setOnClickListener(v -> openClockApp());
        findViewById(R.id.date).setOnClickListener(v -> openCalendarApp());
        search.setBackground(Ui.panel(this, Ui.PLAIN));
        battery.setBackground(Ui.panel(this, Ui.HOT, 8));
        homeTitle = findViewById(R.id.homeTitle);
        homeTitle.setText(Ui.up(getSharedPreferences(PREFS, MODE_PRIVATE).getString("home_title", DEFAULT_TITLE)));
        homeTitle.setOnClickListener(v -> showRename());
        // Bấm vào nhãn PIN: mở trang Pin trong Cài đặt
        battery.setOnClickListener(v -> {
            List<Intent> t = new ArrayList<>();
            t.add(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
            t.add(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            t.add(new Intent(Settings.ACTION_SETTINGS));
            if (!launchFirst(t)) Toast.makeText(this, "Không mở được trang Pin", Toast.LENGTH_SHORT).show();
        });

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
    protected void onStart() {
        super.onStart();
        try { if (qsbHost != null) qsbHost.startListening(); } catch (Exception ignored) { }
    }

    @Override
    protected void onStop() {
        try { if (qsbHost != null) qsbHost.stopListening(); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appPaused = false;
        syncTruyen();
        if (yt != null) yt.onResume();
        if (tv != null) tv.onAppResume();
        if (rc != null) rc.setActive(rcActive);
        if (br != null) br.resume();
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (webandr != null && requestCode >= 2100 && requestCode < 2200) {
            webandr.handlePermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        if (requestCode == REQ_YT_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startYtVoice();
            else Toast.makeText(this, "Cần cấp quyền micro để tìm kiếm bằng giọng nói", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ai != null && ai.onPermissionResult(requestCode, grantResults)) return;
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ---------- Cho trợ lý AI giọng nói ----------

    /** Chuyển trang: -3 Điều khiển, -2 TV, -1 YouTube, 0 màn hình chính, 1 Tiện ích. */
    void aiPage(float p) {
        if (customView != null) hideCustom();
        resetSearch();
        animateTo(p);
    }

    /** Mở YouTube, có từ khóa thì tìm luôn. */
    void aiYoutube(String q) {
        if (q != null && !q.trim().isEmpty()) yt.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(q.trim()));
        aiPage(-1f);
    }

    /** Mở TV, có tên kênh thì phát luôn kênh đó. */
    void aiTv(final String q) {
        aiPage(-2f);
        if (q == null || q.trim().isEmpty()) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!tv.playByName(q)) Toast.makeText(this, "Không thấy kênh \"" + q + "\"", Toast.LENGTH_SHORT).show();
        }, 450);
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
        appPaused = true;
        syncTruyen();
        if (ai != null) ai.cancel();
        if (yt != null) yt.onPause();
        if (tv != null) tv.onAppPause();
        if (rc != null) rc.setActive(false);
        if (br != null) br.pause();
        if (webandr != null) { try { webandr.onPause(); } catch (Exception e) { } }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (ai != null) ai.cancel();
        if (yt != null) {
            ((ViewGroup) yt.getParent()).removeView(yt);
            yt.destroy();
        }
        if (tv != null) tv.destroy();
        if (rc != null) rc.destroy();
        if (br != null) br.destroy();
        if (webandr != null) { try { webandr.destroy(); } catch (Exception e) { } webandr = null; }
        cc.release();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == TruyenTepPage.REQ_PICK) {
            if (truyen != null) truyen.onPicked(data);
            return;
        }
        if (webandr != null && webandr.handleActivityResult(requestCode, resultCode, data)) return;
        if (requestCode == RemotePage.REQ_VOICE) {
            if (rc != null) rc.onVoiceResult(resultCode, data);
        } else if (requestCode == REQ_YT_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                String spoken = (results == null || results.isEmpty()) ? null : results.get(0);
                if (spoken != null && !spoken.trim().isEmpty() && yt != null) {
                    yt.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(spoken.trim()));
                }
            }
        } else if (requestCode == REQ_QSB_BIND) {
            if (resultCode == RESULT_OK && qsbId >= 0) afterQsbBound(qsbId);
            else dropQsb();
        } else if (requestCode == REQ_QSB_CFG) {
            if (resultCode == RESULT_OK && qsbId >= 0) showQsb(qsbId);
            else dropQsb();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (tvActive && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return tv.handleKey(e);
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustom(); return; }
        if (rcActive) { animateTo(0f); return; }   // trang Điều khiển: Back về màn hình chính
        if (tvActive) {
            if (!tv.onBack()) animateTo(0f);   // đang ở bảng chọn kênh: về màn hình chính
            return;
        }
        if (pos < 0f) {
            if (yt.canGoBack()) yt.goBack(); else animateTo(0f);
        } else if (pos > 2.5f) {
            animateTo(2f);   // Truyền tệp: Back về trình duyệt
        } else if (pos > 1f) {
            // trình duyệt: lùi trang, hết lịch sử thì về trang Tiện ích
            boolean handled = useWebandr && webandr != null ? webandr.back() : br.onBack();
            if (!handled) animateTo(1f);
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
        pos = Math.max(-3f, Math.min(3f, p));
        int w = screenW();
        homeRoot.setTranslationX(pos * w);
        ccPanel.setTranslationX((pos - 1f) * w);
        ytPanel.setTranslationX((pos + 1f) * w);
        tvPanel.setTranslationX((pos + 2f) * w);
        rcPanel.setTranslationX((pos + 3f) * w);
        brPanel.setTranslationX((pos - 2f) * w);
        if (truyen != null) truyen.setTranslationX((pos - 3f) * w);
        int cv = pos > 0f ? View.VISIBLE : View.INVISIBLE;
        int yv = pos < 0f ? View.VISIBLE : View.INVISIBLE;
        int tvv = pos < -1f ? View.VISIBLE : View.INVISIBLE;
        int rv = pos < -2f ? View.VISIBLE : View.INVISIBLE;
        if (ccPanel.getVisibility() != cv) ccPanel.setVisibility(cv);
        if (ytPanel.getVisibility() != yv) ytPanel.setVisibility(yv);
        if (tvPanel.getVisibility() != tvv) tvPanel.setVisibility(tvv);
        if (rcPanel.getVisibility() != rv) rcPanel.setVisibility(rv);
        int bv = pos > 1f ? View.VISIBLE : View.INVISIBLE;
        if (brPanel.getVisibility() != bv) brPanel.setVisibility(bv);
        if (truyen != null) {
            int truv = pos > 2f ? View.VISIBLE : View.INVISIBLE;
            if (truyen.getVisibility() != truv) truyen.setVisibility(truv);
        }
        boolean brAct = pos >= 1.98f;
        if (br != null && brAct != brActive) {
            brActive = brAct;
            br.setActive(brAct);
        }
        // Trang TV chỉ bật khi đã dừng hẳn ở trang đó (không bật giữa lúc đang vuốt, tránh khựng)
        boolean tvAct = Math.abs(pos + 2f) < 0.02f;
        if (tv != null && tvAct != tvActive) {
            tvActive = tvAct;
            if (tvAct && yt != null) yt.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();})", null);
            tv.setActive(tvAct);
        }
        // Trang Điều khiển: chỉ kết nối khi đã dừng hẳn ở trang đó
        boolean rcAct = pos <= -2.98f;
        if (rc != null && rcAct != rcActive) {
            rcActive = rcAct;
            rc.setActive(rcAct);
        }
        syncTruyen();
    }

    /** Trang Truyền tệp chỉ kết nối khi đang dừng hẳn ở trang đó và app đang hiện (không chạy nền). */
    private void syncTruyen() {
        if (truyen == null) return;
        boolean on = !appPaused && Math.abs(pos - 3f) < 0.02f;
        if (on != truyenActive) {
            truyenActive = on;
            truyen.setActive(on);
        }
    }

    /** Bật layer phần cứng khi đang vuốt/chuyển trang để trượt mượt, tắt lại khi xong. */
    private void layers(boolean on) {
        int t = on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (homeRoot.getLayerType() != t) homeRoot.setLayerType(t, null);
        if (ccPanel.getLayerType() != t) ccPanel.setLayerType(t, null);
        if (ytPanel.getLayerType() != t) ytPanel.setLayerType(t, null);
        if (tvPanel.getLayerType() != t) tvPanel.setLayerType(t, null);
        if (rcPanel.getLayerType() != t) rcPanel.setLayerType(t, null);
        if (brPanel.getLayerType() != t) brPanel.setLayerType(t, null);
        if (truyen != null && truyen.getLayerType() != t) truyen.setLayerType(t, null);
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
                if (pos == 1f) { cc.refresh(); ensureQsb(); }
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

    // ---------- Đổi trình duyệt: BrowserPage <-> "Duyệt web" kiểu Webandr ----------

    /** Gọi từ nút "Đổi trình duyệt" trong Cài đặt trình duyệt: từ nay dùng "Duyệt web" kiểu Webandr và nhớ lựa chọn. */
    void switchBrowser() {
        useWebandr = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WEBANDR, true).apply();
        applyBrowserEngine();
    }

    /** Đóng "Duyệt web" (Webandr) và mở lại trình duyệt cũ; lựa chọn được lưu. */
    void switchBackToOldBrowser() {
        useWebandr = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WEBANDR, false).apply();
        if (webandr != null) {
            View v = webandr.getRoot();
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            try { webandr.destroy(); } catch (Exception e) { }
            webandr = null;
        }
        applyBrowserEngine();
    }

    /** Hiện đúng trình duyệt đang chọn trong vùng trang trình duyệt (brPanel). */
    private void applyBrowserEngine() {
        if (useWebandr) {
            if (webandr == null) {
                webandr = new BrowserHost(this, new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                    @Override public kotlin.Unit invoke() {
                        switchBackToOldBrowser();   // nút X của "Duyệt web" (đã xác nhận): đóng và mở lại trình duyệt cũ
                        return kotlin.Unit.INSTANCE;
                    }
                });
                webandr.start(null);
                ((ViewGroup) brPanel).addView(webandr.getRoot(), new FrameLayout.LayoutParams(-1, -1));
            }
            br.setVisibility(View.GONE);
            webandr.getRoot().setVisibility(View.VISIBLE);
        } else {
            if (webandr != null) webandr.getRoot().setVisibility(View.GONE);
            br.setVisibility(View.VISIBLE);
        }
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
        loadYtBlocklist();
        yt.addJavascriptInterface(new AdBridge(), "HauOsAd");
        yt.addJavascriptInterface(new YtVoiceBridge(), "HauOsVoice");
        yt.setWebViewClient(new WebViewClient() {
            // Chặn quảng cáo ngay ở tầng mạng: request tới domain trong blocklist không bao giờ được tải.
            // Trả 404 (không phải 200 rỗng) để trình phát không bị treo chờ phản hồi quảng cáo.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                if (isYtHostBlocked(request.getUrl().getHost())) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Blocked",
                            Collections.<String, String>emptyMap(),
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(v, request);
            }

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

    /** Đọc assets/blocklist.txt (bỏ dòng trống và dòng chú thích bắt đầu bằng #). */
    private void loadYtBlocklist() {
        for (String line : readAsset("blocklist.txt").split("\n")) {
            String t = line.trim().toLowerCase();
            if (!t.isEmpty() && !t.startsWith("#")) ytBlockedHosts.add(t);
        }
    }

    /** Host có thuộc blocklist không (khớp đúng domain hoặc mọi subdomain của nó). */
    private boolean isYtHostBlocked(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String blocked : ytBlockedHosts) {
            if (h.equals(blocked) || h.endsWith("." + blocked)) return true;
        }
        return false;
    }

    /** Micro của YouTube: JS bắt cú bấm (assets/yt_voice.js) rồi gọi vào đây. */
    private class YtVoiceBridge {
        @JavascriptInterface
        public void startVoiceSearch() {
            runOnUiThread(MainActivity.this::startYtVoice);
        }
    }

    /** Mở nhận dạng giọng nói gốc của Android (WebView không chạy được Web Speech API). */
    private void startYtVoice() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_YT_MIC);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên YouTube");
        try {
            startActivityForResult(intent, REQ_YT_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Thiết bị này không hỗ trợ nhận dạng giọng nói", Toast.LENGTH_SHORT).show();
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
                themeJs = readAsset("yt_adskip.js") + readAsset("yt_adblock.js") + readAsset("yt_voice.js")
                        + "\n(function(){var id='hauos-theme',s=document.getElementById(id);"
                        + "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}"
                        + "s.textContent=" + JSONObject.quote(sb.toString()) + ";})();";
            }
            v.evaluateJavascript(themeJs, null);
        } catch (Exception ignored) { }
    }

    /** Toàn màn hình video cho trình duyệt (và YouTube dùng chung). */
    void showCustom(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) { cb.onCustomViewHidden(); return; }
        customView = view;
        customCb = cb;
        view.setBackgroundColor(Color.BLACK);
        ((ViewGroup) findViewById(android.R.id.content)).addView(view,
                new FrameLayout.LayoutParams(-1, -1));
    }

    void hideCustomPublic() {
        hideCustom();
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
        // Đang kéo ô trên trang Tiện ích để đổi chỗ: không chuyển trang / không chụm theo cử chỉ này
        if (ControlCenter.tileDragging) {
            tracking = false;
            boolean r = super.dispatchTouchEvent(e);
            int a0 = e.getActionMasked();
            if (a0 == MotionEvent.ACTION_UP || a0 == MotionEvent.ACTION_CANCEL) ControlCenter.tileDragging = false;
            return r;
        }
        // Chạm ra ngoài ô tìm kiếm: bỏ con trỏ (giữ nguyên chữ đã gõ) để vuốt chuyển trang được
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && search.hasFocus() && !hitBox(search, e.getRawX(), e.getRawY())) {
            dropSearchFocus();
        }
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
                else if (pos <= -2.5f) tracking = rc.canSwipeBackFrom(downX, downY);   // trang Điều khiển: vuốt từ mép trái hoặc từ vùng ngoài ô cảm ứng để quay lại
                else tracking = true;   // YouTube / TV: hướng vuốt được kiểm tra ở ACTION_MOVE
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !dragging) {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        boolean ok;
                        if (pos >= 3f) ok = dx < 0;              // Truyền tệp: vuốt trái về trình duyệt
                        else if (pos >= 2f) ok = dx > 0 || (dx < 0 && br.canSwipeBack(downX, downY, screenW()));   // trình duyệt: vuốt phải sang Truyền tệp, vuốt trái về Tiện ích
                        else if (pos >= 1f) ok = true;           // trang Tiện ích: vuốt phải mở trình duyệt, vuốt trái về chính
                        else if (pos <= -3f) ok = dx > 0;
                        else if (pos <= -2f) ok = true;          // trang TV: vuốt được cả hai chiều
                        else if (pos <= -1f) {
                            // YouTube: vuốt phải về màn hình chính (trừ khi đang cuộn ngang), vuốt trái sang TV
                            ok = dx > 0 ? (downX < dp(40) || !yt.canScrollHorizontally(-1)) : !yt.canScrollHorizontally(1);
                        }
                        else ok = true;
                        if (ok) {
                            dragging = true;
                            startPos = pos;
                            boolean positive = pos > 0f || (pos == 0f && dx > 0);
                            dragLo = positive ? (pos > 2.5f ? 2f : (pos > 1.5f ? 1f : 0f)) : -3f;
                            dragHi = positive ? (pos > 1.5f ? 3f : (pos > 0.5f ? 2f : 1f)) : 0f;
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
                    // mỗi lần thả tay chỉ chuyển tối đa 1 trang so với trang lúc bắt đầu vuốt
                    int startPage = Math.round(startPos);
                    float target;
                    if (Math.abs(vx) > dp(500)) {
                        // vuốt nhanh: chuyển trang theo hướng ngón tay
                        target = startPage + (vx < 0 ? -1 : 1);
                    } else {
                        float moved = pos - startPage;
                        target = Math.abs(moved) > 0.3f ? startPage + Math.signum(moved) : startPage;
                    }
                    target = Math.max(-3f, Math.min(3f, target));
                    animateTo(target, vx);
                    return true;
                }
                // Chạm giữa lúc đang trượt làm hoạt ảnh bị huỷ: không để trang kẹt lưng chừng
                if (pos != 0f && pos != 1f && pos != 2f && pos != 3f && pos != -1f && pos != -2f && pos != -3f) animateTo(Math.round(pos));
                break;
        }
        return super.dispatchTouchEvent(e);
    }

    // ---------- Màn hình chính ----------

    void go(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Không mở được ứng dụng này", Toast.LENGTH_SHORT).show();
        }
    }

    /** Thử lần lượt các cách mở; cách nào mở được thì dừng. */
    boolean launchFirst(List<Intent> tries) {
        for (Intent i : tries) {
            if (i == null) continue;
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private void addPkgs(List<Intent> tries, String... pkgs) {
        for (String p : pkgs) tries.add(pm.getLaunchIntentForPackage(p));
    }

    /** Chạm vào giờ: mở ứng dụng Đồng hồ có sẵn trên máy. */
    void openClockApp() {
        List<Intent> t = new ArrayList<>();
        t.add(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS));
        addPkgs(t, "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
                "com.coloros.alarmclock", "com.oplus.alarmclock", "com.oneplus.deskclock", "com.miui.clock",
                "com.huawei.deskclock", "com.vivo.alarmclock");
        if (!launchFirst(t)) Toast.makeText(this, "Không tìm thấy ứng dụng Đồng hồ", Toast.LENGTH_SHORT).show();
    }

    /** Chạm vào thứ, ngày, tháng: mở ứng dụng Lịch có sẵn trên máy. */
    void openCalendarApp() {
        List<Intent> t = new ArrayList<>();
        t.add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR));
        t.add(new Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(
                Uri.parse("content://com.android.calendar/time"), System.currentTimeMillis())));
        addPkgs(t, "com.google.android.calendar", "com.android.calendar", "com.samsung.android.calendar",
                "com.coloros.calendar", "com.oplus.calendar", "com.miui.calendar", "com.huawei.calendar");
        if (!launchFirst(t)) Toast.makeText(this, "Không tìm thấy ứng dụng Lịch", Toast.LENGTH_SHORT).show();
    }

    // ---------- Thanh tìm kiếm Google (tiện ích của máy) ----------

    private AppWidgetProviderInfo findGoogleSearch() {
        if (qsbMgr == null) return null;
        AppWidgetProviderInfo any = null;
        for (AppWidgetProviderInfo i : qsbMgr.getInstalledProviders()) {
            if (i.provider == null || !"com.google.android.googlequicksearchbox".equals(i.provider.getPackageName())) continue;
            String cls = i.provider.getClassName().toLowerCase();
            if (cls.contains("search") || cls.contains("qsb")) return i;
            if (any == null) any = i;
        }
        return any;
    }

    /** Lần đầu mở trang Tiện ích: xin gắn tiện ích Google (hệ thống hiện hộp thoại cho phép). Từ chối thì không hỏi lại. */
    private void ensureQsb() {
        if (qsbHost == null || qsbMgr == null) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (qsbId >= 0 || sp.getBoolean(KEY_QSB_ASKED, false)) return;
        AppWidgetProviderInfo info = findGoogleSearch();
        if (info == null) return;
        sp.edit().putBoolean(KEY_QSB_ASKED, true).apply();
        try {
            qsbId = qsbHost.allocateAppWidgetId();
            if (qsbMgr.bindAppWidgetIdIfAllowed(qsbId, info.provider)) {
                afterQsbBound(qsbId);
            } else {
                Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, qsbId)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
                startActivityForResult(i, REQ_QSB_BIND);
            }
        } catch (Exception e) {
            dropQsb();
        }
    }

    private void afterQsbBound(int id) {
        AppWidgetProviderInfo info = qsbMgr.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            try {
                qsbHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_QSB_CFG, null);
                return;
            } catch (Exception ignored) { }
        }
        showQsb(id);
    }

    private void showQsb(int id) {
        try {
            AppWidgetProviderInfo info = qsbMgr.getAppWidgetInfo(id);
            if (info == null) { dropQsb(); return; }
            AppWidgetHostView hv = qsbHost.createView(this, id, info);
            hv.setAppWidget(id, info);
            qsbHolder.removeAllViews();
            int h = Math.max(dp(56), info.minHeight);
            qsbHolder.addView(hv, new FrameLayout.LayoutParams(-1, h));
            qsbHolder.setVisibility(View.VISIBLE);
            qsbId = id;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_QSB, id).apply();
        } catch (Exception e) {
            dropQsb();
        }
    }

    private void dropQsb() {
        try { if (qsbHost != null && qsbId >= 0) qsbHost.deleteAppWidgetId(qsbId); } catch (Exception ignored) { }
        qsbId = -1;
        if (qsbHolder != null) { qsbHolder.removeAllViews(); qsbHolder.setVisibility(View.GONE); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_QSB).apply();
    }

    private boolean hitBox(View v, float rawX, float rawY) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return rawX >= l[0] && rawX <= l[0] + v.getWidth() && rawY >= l[1] && rawY <= l[1] + v.getHeight();
    }

    /** Bỏ con trỏ khỏi ô tìm kiếm và ẩn bàn phím, không xóa chữ. */
    private void dropSearchFocus() {
        homeRoot.setFocusableInTouchMode(true);   // để tiêu điểm chuyển sang đây, không quay lại ô tìm kiếm
        search.clearFocus();
        homeRoot.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
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

    /** Đổi số ứng dụng trên một hàng (1 đến 4) và lưu lại. Chỉnh kích thước tại chỗ, không dựng lại các ô. */
    private void setCols(int n) {
        n = Math.max(1, Math.min(4, n));
        if (n == cols) return;
        cols = n;
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("cols", cols).apply();
            restyleTiles();
            layoutGrid();
        } catch (Exception ex) {
            // Phòng khi bố cục lỗi: dựng lại từ đầu một lần
            try {
                signature = "";
                reloadApps();
            } catch (Exception ignored) { }
        }
    }

    private boolean rebuildPending;

    /**
     * Xếp lại các ô theo số cột hiện tại: gỡ hết ô rồi thêm lại với thông số mới (giống lúc mở app),
     * làm sau khi cử chỉ kết thúc (post) để không đụng vào lúc đang xử lý cảm ứng.
     */
    private void layoutGrid() {
        if (rebuildPending) return;
        rebuildPending = true;
        grid.post(() -> {
            rebuildPending = false;
            List<View> kids = new ArrayList<>();
            for (int i = 0; i < grid.getChildCount(); i++) kids.add(grid.getChildAt(i));
            grid.removeAllViews();
            grid.setColumnCount(cols);
            for (View v : kids) {
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                lp.width = 0;
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                grid.addView(v, lp);
            }
            grid.requestLayout();
        });
    }

    private void restyleTiles() {
        int icon = dp(iconDpFor(cols));
        int sp = textSpFor(cols);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            LinearLayout ll = (LinearLayout) v;
            if (ll.getChildCount() < 2) continue;
            View iv = ll.getChildAt(0);
            ViewGroup.LayoutParams lp = iv.getLayoutParams();
            if (lp != null) {
                lp.width = icon;
                lp.height = icon;
                iv.setLayoutParams(lp);
            }
            View tv = ll.getChildAt(1);
            if (tv instanceof TextView) ((TextView) tv).setTextSize(sp);
        }
        grid.requestLayout();
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
                x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                showAppMenu(e);
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

    /** Nhấn giữ một ứng dụng: chọn xem thông tin hoặc quăng sang / gỡ khỏi trang Tiện ích. */
    private void showAppMenu(final Entry e) {
        final boolean pinned = cc.isPinned(e.cn);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText(Ui.up(e.label));
        title.setTextColor(Ui.CYAN);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(6));
        Ui.style(title);
        box.addView(title);

        final AlertDialog d = new AlertDialog.Builder(this).setView(box).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.panel(this, Ui.GRID));

        String[] labels = {"Thông tin ứng dụng", pinned ? "Gỡ khỏi Tiện ích" : "Thêm vào Tiện ích"};
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            TextView row = new TextView(this);
            row.setText(Ui.up(labels[i]));
            row.setTextColor(i == 1 && pinned ? Ui.PINK : Color.WHITE);
            row.setTextSize(14);
            row.setPadding(dp(4), dp(14), dp(4), dp(14));
            Ui.style(row);
            row.setOnClickListener(v -> {
                d.dismiss();
                if (which == 0) {
                    go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + e.cn.getPackageName())));
                } else if (pinned) {
                    cc.unpin(e.cn);
                    Toast.makeText(this, "Đã gỡ khỏi Tiện ích", Toast.LENGTH_SHORT).show();
                } else {
                    cc.pin(e.cn);
                    Toast.makeText(this, "Đã thêm vào Tiện ích", Toast.LENGTH_SHORT).show();
                }
            });
            box.addView(row);
        }
        d.show();
    }

    /** Bấm vào tên ở đầu màn hình chính để đổi tên (để trống = về "HẬU OS"). */
    private void showRename() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("ĐỔI TÊN");
        title.setTextColor(Ui.CYAN);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(8));
        Ui.style(title);
        box.addView(title);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Ui.DIM);
        input.setHint(DEFAULT_TITLE);
        input.setText(homeTitle.getText());
        input.setSelectAllOnFocus(true);
        input.setBackground(Ui.panel(this, Ui.PLAIN, 8));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(10), 0, 0);
        TextView cancel = new TextView(this);
        cancel.setText("HỦY");
        cancel.setTextColor(Ui.DIM);
        cancel.setTextSize(14);
        cancel.setPadding(dp(14), dp(10), dp(14), dp(10));
        Ui.style(cancel);
        TextView ok = new TextView(this);
        ok.setText("LƯU");
        ok.setTextColor(Ui.CYAN);
        ok.setTextSize(14);
        ok.setPadding(dp(14), dp(10), dp(14), dp(10));
        Ui.style(ok);
        row.addView(cancel);
        row.addView(ok);
        box.addView(row);

        final AlertDialog d = new AlertDialog.Builder(this).setView(box).create();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(Ui.panel(this, Ui.GRID));
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            if (name.isEmpty() || name.equalsIgnoreCase(DEFAULT_TITLE)) {
                ed.remove("home_title");
                name = DEFAULT_TITLE;
            } else {
                ed.putString("home_title", name);
            }
            ed.apply();
            homeTitle.setText(Ui.up(name));
            d.dismiss();
        });
        input.setOnEditorActionListener((v, id, ev) -> {
            ok.performClick();
            return true;
        });
        d.show();
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
