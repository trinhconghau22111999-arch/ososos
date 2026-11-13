package com.dk.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Trang xem truyền hình (lấy từ app Xem_tivi), đặt ở phía bên phải trang YouTube.
 * Điều khiển bằng remote: lên/xuống đổi kênh, trái/phải đổi đài, số để chọn kênh, OK mở bảng chọn.
 */
public class TvPage extends FrameLayout {

    /** Chuyển phím cho hệ thống xử lý (ví dụ điều hướng trong danh sách). */
    public interface KeyPassThrough {
        boolean dispatch(KeyEvent e);
    }

    static class Ch {
        final int num;
        final String name, url, group;

        Ch(int num, String name, String url, String group) {
            this.num = num;
            this.name = name;
            this.url = url;
            this.group = group;
        }

        String label() {
            return num + "   " + name;
        }
    }

    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
            "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
            "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"));

    private static boolean isAdHost(String h) {
        String x = h;
        while (true) {
            if (AD_HOSTS.contains(x)) return true;
            int i = x.indexOf('.');
            if (i < 0) return false;
            x = x.substring(i + 1);
        }
    }

    /** Phóng video phủ kín khung, ẩn phần còn lại của trang và cố gắng phát ngay. */
    private static final String TV_FULL_JS = """
(function(){
  if (!window.__tvFullStyle) {
    window.__tvFullStyle = true;
    var st = document.createElement('style');
    st.textContent = 'html,body{overflow:hidden!important;background:#000!important}video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  function go(){
    if (window.__tvPaused) return;
    var v = document.querySelector('video');
    if (v) { try { v.muted = false; var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e){} }
  }
  go();
  if (!window.__tvFullObs) {
    window.__tvFullObs = true;
    var t = null;
    new MutationObserver(function(){ if (t) return; t = setTimeout(function(){ t = null; go(); }, 500); })
      .observe(document.documentElement, {childList:true, subtree:true});
  }
})();
""";

    /** Chạy lại TV_FULL_JS rồi trả về true nếu video đang thật sự chạy. */
    private static final String CHECK_JS = TV_FULL_JS
            + ";(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&v.readyState>2&&v.currentTime>0);})()";

    /** Tắt tiếng + dừng video ngay. */
    private static final String PAUSE_JS = "window.__tvPaused=true;Array.prototype.forEach.call(document.querySelectorAll('video,audio'),function(v){try{v.muted=true;v.pause();}catch(e){}});";

    /** Cho phép phát lại. */
    private static final String RESUME_JS = "window.__tvPaused=false;";

    private static final int GOLD = 0xFFFFD27A;
    private static final int GREEN = 0xFF7CFC9A;
    private static final int BLUE = 0xFF1E90FF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final KeyPassThrough pass;
    private final float density;

    // ---- dữ liệu ----
    private final ArrayList<Ch> all = new ArrayList<>();
    private final ArrayList<String> groupNames = new ArrayList<>();
    private final ArrayList<List<Ch>> groupChs = new ArrayList<>();
    private Ch cur, pending;
    private List<Ch> shownList = new ArrayList<>();
    private int leftSel = 0;

    // ---- giao diện ----
    private WebView web;
    private FrameLayout fullLayer, cover;
    private TextView osd, osdTime, msg, coverName, coverNum, rightTitle;
    private LinearLayout overlay, leftCol, rightCol;
    private boolean narrow;
    private ListView groupList, chList;
    private RowAdapter leftAd, rightAd;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCb;

    private int countdown = 0;
    private int stuck = 0;
    private boolean active = false;
    private final StringBuilder numBuf = new StringBuilder();

    public TvPage(Context c, KeyPassThrough pass) {
        super(c);
        this.pass = pass;
        this.density = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        loadChannels(c);
        buildUi(c);
        showOverlay(true);
        showWelcome();
    }

    // ---------------------------------------------------------------- vòng đời (gọi từ MainActivity)

    /** Trang TV hiện hay ẩn. Ẩn thì tắt tiếng, hiện thì phát lại kênh đang xem. */
    public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) activate();
        else deactivate();
    }

    /** App bị đưa xuống nền: tắt tiếng để không phát ngầm. */
    public void onAppPause() {
        if (active) deactivate();
    }

    /** App quay lại: nếu trang TV đang hiện thì phát tiếp. */
    public void onAppResume() {
        if (active) activate();
    }

    public void destroy() {
        ui.removeCallbacksAndMessages(null);
        removeAllViews();
        web.destroy();
    }

    private void activate() {
        web.onResume();
        if (cur != null && !overlayOn()) {
            String u = web.getUrl();
            if (u == null || u.startsWith("about:")) {
                reloadCur();
            } else {
                web.evaluateJavascript(RESUME_JS, null);
                web.evaluateJavascript(TV_FULL_JS, null);
            }
        }
        if (overlayOn()) (shownList.isEmpty() ? groupList : chList).requestFocus();
        if (cur != null) startWatch();
    }

    private void deactivate() {
        silence();
        ui.removeCallbacks(watch);
        ui.removeCallbacks(zapRun);
        ui.removeCallbacks(retryRun);
    }

    // ---------------------------------------------------------------- phím và nút Back (gọi từ MainActivity)

    /** Xử lý phím remote khi trang TV đang hiện. Trả về true nếu đã xử lý. */
    public boolean handleKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (overlayOn()) {
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_GUIDE) {
                if (down && cur != null) hideOverlay(true);
                return true;
            }
            if (down && channelsColumnOnly() && k == KeyEvent.KEYCODE_DPAD_LEFT) {
                showColumn(false);
                groupList.requestFocus();
                return true;
            }
            if (down && groupList.hasFocus()) {
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {          // sang cột Kênh: chắc chắn đúng đài
                    syncGroup();
                    if (shownList.isEmpty()) return true;        // đài 1 kênh: không có gì bên phải
                    if (narrow) {
                        showColumn(true);
                        chList.requestFocus();
                        return true;
                    }
                }
                boolean r = pass.dispatch(e);
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                        || k == KeyEvent.KEYCODE_PAGE_UP || k == KeyEvent.KEYCODE_PAGE_DOWN) {
                    ui.post(this::syncGroup);
                }
                return r;
            }
            return pass.dispatch(e);
        }

        // đang xem: remote điều khiển trực tiếp
        int dg = digitOf(k);
        if (dg < 0 && !isMapped(k)) return pass.dispatch(e);
        if (!down) return true;
        if (dg >= 0) {
            if (e.getRepeatCount() == 0) digit(dg);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(+1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_UP:
                zapGroup(+1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                zapGroup(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_TV:
                if (e.getRepeatCount() == 0) showOverlay(false);
                break;
            case KeyEvent.KEYCODE_INFO:
                if (cur != null) showOsd(cur.label() + "   •   " + cur.group);
                break;
            case KeyEvent.KEYCODE_PROG_YELLOW:
                if (e.getRepeatCount() == 0) {
                    showMsg("Tải lại kênh…", 3000);
                    reloadCur();
                }
                break;
            case KeyEvent.KEYCODE_PROG_BLUE:
                if (e.getRepeatCount() == 0) showOverlay(false);
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * Nút Back khi đang ở trang TV. Trả về true nếu đã xử lý trong trang;
     * false nghĩa là đang ở bảng chọn kênh, nên cho phép chuyển về màn hình chính.
     */
    public boolean onBack() {
        if (fullView != null) {
            hideFull();
            return true;
        }
        if (overlayOn()) {
            if (channelsColumnOnly()) {      // đang ở danh sách kênh của một đài: về danh sách đài
                showColumn(false);
                groupList.requestFocus();
                return true;
            }
            return false;
        }
        showOverlay(true);
        return true;
    }

    private static int digitOf(int k) {
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return k - KeyEvent.KEYCODE_0;
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) return k - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private static boolean isMapped(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_PROG_BLUE:
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- dữ liệu

    private void loadChannels(Context c) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getAssets().open("channels.json"), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("groups");
            int n = 1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.getJSONObject(i);
                String gname = g.getString("name");
                JSONArray chs = g.getJSONArray("channels");
                List<Ch> list = new ArrayList<>();
                for (int j = 0; j < chs.length(); j++) {
                    JSONObject o = chs.getJSONObject(j);
                    Ch ch = new Ch(n++, o.getString("name"), o.getString("url"), gname);
                    list.add(ch);
                    all.add(ch);
                }
                if (!list.isEmpty()) {
                    groupNames.add(gname);
                    groupChs.add(list);
                }
            }
        } catch (Exception e) {
            // không đọc được danh sách kênh: trang vẫn mở, chỉ không có kênh
        }
    }

    // ---------------------------------------------------------------- giao diện

    private int dp(int v) {
        return Math.round(v * density);
    }

    private FrameLayout.LayoutParams flp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private TextView pill(float sp) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xB0000000);
        g.setCornerRadius(dp(10));
        t.setBackground(g);
        t.setVisibility(View.GONE);
        return t;
    }

    private TextView title(String text) {
        TextView t = new TextView(getContext());
        t.setText(Ui.up(text));
        t.setTextSize(20f);
        t.setTextColor(0xFF00E5FF);
        Ui.style(t);
        t.setLetterSpacing(0.14f);
        t.setPadding(dp(4), 0, 0, dp(8));
        return t;
    }

    /** Đường kẻ gradient dưới tiêu đề, giống các trang khác. */
    private View rule() {
        View v = new View(getContext());
        v.setBackgroundResource(R.drawable.bg_rule);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(2));
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private ListView mkList() {
        ListView l = new ListView(getContext());
        l.setId(View.generateViewId());
        l.setDivider(null);
        l.setDividerHeight(dp(4));
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(0xFF0A3B4D);
        sel.setStroke(dp(2), 0xFF00E5FF);
        sel.setCornerRadius(dp(10));
        l.setSelector(sel);
        l.setVerticalScrollBarEnabled(false);
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return l;
    }

    private TextView newRowText() {
        TextView t = new TextView(getContext());
        t.setTextSize(22f);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setMinHeight(dp(52));
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private abstract class RowAdapter extends BaseAdapter {
        abstract int size();

        abstract void bind(TextView t, int p);

        @Override
        public int getCount() {
            return size();
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView : newRowText();
            bind(t, position);
            return t;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildUi(Context c) {
        web = new WebView(c);
        setupWeb(c);
        addView(web, new FrameLayout.LayoutParams(-1, -1));

        // Chạm 2 lần nhanh để mở bảng chọn kênh. Chạm 1 lần không làm gì.
        GestureDetector gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!overlayOn()) showOverlay(false);
                return true;
            }
        });
        View tapLayer = new View(c);
        tapLayer.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            return true;
        });
        addView(tapLayer, new FrameLayout.LayoutParams(-1, -1));

        fullLayer = new FrameLayout(c);
        fullLayer.setVisibility(View.GONE);
        fullLayer.setBackgroundColor(Color.BLACK);
        addView(fullLayer, new FrameLayout.LayoutParams(-1, -1));

        osd = pill(30);
        addView(osd, flp(-2, -2, Gravity.TOP | Gravity.START, 32, 24, 0, 0));
        osdTime = pill(24);
        addView(osdTime, flp(-2, -2, Gravity.TOP | Gravity.END, 0, 24, 32, 0));
        msg = pill(22);
        addView(msg, flp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 40));

        // màn che lúc đang tải kênh: đếm ngược 5..1 rồi tự biến mất
        cover = new FrameLayout(c);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        coverName = new TextView(c);
        coverName.setTextSize(34f);
        coverName.setTextColor(Color.WHITE);
        coverName.setTypeface(Typeface.DEFAULT_BOLD);
        coverName.setGravity(Gravity.CENTER);
        coverNum = new TextView(c);
        coverNum.setTextSize(110f);
        coverNum.setTextColor(BLUE);
        coverNum.setTypeface(Typeface.DEFAULT_BOLD);
        coverNum.setGravity(Gravity.CENTER);
        coverNum.setPadding(0, dp(12), 0, dp(12));
        TextView coverSub = new TextView(c);
        coverSub.setText("Đang tải kênh…");
        coverSub.setTextSize(20f);
        coverSub.setTextColor(0xFFAAAAAA);
        coverSub.setGravity(Gravity.CENTER);
        box.addView(coverName);
        box.addView(coverNum);
        box.addView(coverSub);
        cover.addView(box, flp(-2, -2, Gravity.CENTER, 0, 0, 0, 0));
        addView(cover, new FrameLayout.LayoutParams(-1, -1));

        // bảng chọn kênh: trái = đài, phải = kênh
        overlay = new LinearLayout(c);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setBackgroundColor(0xFF0D1224);
        overlay.setPadding(dp(40), dp(28), dp(40), dp(16));
        overlay.setVisibility(View.GONE);

        groupList = mkList();
        chList = mkList();
        groupList.setNextFocusRightId(chList.getId());
        chList.setNextFocusLeftId(groupList.getId());

        leftAd = new RowAdapter() {
            @Override
            int size() {
                return groupNames.size();
            }

            @Override
            void bind(TextView t, int p) {
                List<Ch> l = groupChs.get(p);
                // đài 1 kênh: hiện số kênh; đài nhiều kênh: hiện dải số (vd 5–13)
                t.setText(l.size() == 1
                        ? l.get(0).num + "   " + groupNames.get(p)
                        : l.get(0).num + "–" + l.get(l.size() - 1).num + "   " + groupNames.get(p));
                t.setTextColor(p == leftSel ? GOLD : Color.WHITE);
                t.setTypeface(p == leftSel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        };
        rightAd = new RowAdapter() {
            @Override
            int size() {
                return shownList.size();
            }

            @Override
            void bind(TextView t, int p) {
                Ch ch = shownList.get(p);
                t.setText(ch.label());
                t.setTextColor(ch == cur ? GREEN : Color.WHITE);
                t.setTypeface(ch == cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        };
        groupList.setAdapter(leftAd);
        chList.setAdapter(rightAd);

        groupList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != leftSel) setGroup(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        // đài chỉ có 1 kênh: bấm OK là xem luôn
        groupList.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < 0 || pos >= groupChs.size()) return;
            List<Ch> l = groupChs.get(pos);
            if (l.size() == 1) {
                play(l.get(0));
            } else {
                setGroup(pos);
                showColumn(true);
                chList.setSelection(0);
                chList.requestFocus();
            }
        });
        chList.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos >= 0 && pos < shownList.size()) play(shownList.get(pos));
        });

        narrow = c.getResources().getConfiguration().screenWidthDp < 600;
        LinearLayout left = new LinearLayout(c);
        leftCol = left;
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(title("TV của tôi"));
        left.addView(rule());
        left.addView(groupList, new LinearLayout.LayoutParams(-1, 0, 1f));
        rightTitle = title("");
        LinearLayout right = new LinearLayout(c);
        rightCol = right;
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(narrow ? 0 : dp(28), 0, 0, 0);
        right.addView(rightTitle);
        right.addView(rule());
        right.addView(chList, new LinearLayout.LayoutParams(-1, 0, 1f));
        // màn hình hẹp (điện thoại dọc): chỉ hiện 1 cột tại một thời điểm để thấy đủ tên kênh
        overlay.addView(left, new LinearLayout.LayoutParams(0, -1, narrow ? 1f : 3f));
        overlay.addView(right, new LinearLayout.LayoutParams(0, -1, narrow ? 1f : 7f));
        if (narrow) {
            overlay.setPadding(dp(16), dp(28), dp(16), dp(16));
            showColumn(false);
        }
        addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb(Context c) {
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // UA như Chrome thật (bỏ "; wv" và "Version/4.0")
        s.setUserAgentString(WebSettings.getDefaultUserAgent(c)
                .replace("; wv)", ")").replace(" Version/4.0", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.BLACK);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null || request.getUrl().getScheme() == null) return false;
                String sc = request.getUrl().getScheme().toLowerCase(Locale.ROOT);
                return !sc.equals("https") && !sc.equals("http");   // không mở app ngoài
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null || request.getUrl().getHost() == null) return null;
                String h = request.getUrl().getHost().toLowerCase(Locale.ROOT);
                return isAdHost(h)
                        ? new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]))
                        : null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || url.startsWith("about:")) return;
                view.evaluateJavascript(TV_FULL_JS, null);
                hideMsg();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                String u = request.getUrl() == null ? null : request.getUrl().toString();
                if (u != null && u.startsWith("about:")) return;
                showMsg("Không tải được kênh — tự thử lại sau 5 giây", 6000);
                ui.removeCallbacks(retryRun);
                ui.postDelayed(retryRun, 5000);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (view == null) return;
                if (fullView != null) {
                    if (callback != null) callback.onCustomViewHidden();
                    return;
                }
                fullView = view;
                fullCb = callback;
                view.setBackgroundColor(Color.BLACK);
                fullLayer.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullLayer.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                hideFull();
            }
        });
    }

    private void hideFull() {
        View v = fullView;
        if (v == null) return;
        fullLayer.removeView(v);
        fullLayer.setVisibility(View.GONE);
        fullView = null;
        if (fullCb != null) fullCb.onCustomViewHidden();
        fullCb = null;
    }

    // ---------------------------------------------------------------- phát kênh

    private final Runnable retryRun = new Runnable() {
        @Override
        public void run() {
            reloadCur();
        }
    };

    private void load(String url) {
        showMsg("Đang tải kênh…", 0);
        web.loadUrl(url);
    }

    private void reloadCur() {
        if (cur != null) load(cur.url);
    }

    private static String normName(String t) {
        String n = java.text.Normalizer.normalize(t == null ? "" : t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{Mn}+", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase(java.util.Locale.ROOT);
        return n.replaceAll("[^a-z0-9]+", "");
    }

    /** Trợ lý AI: phát kênh có tên khớp nhất với câu nói. Trả về false nếu không thấy. */
    public boolean playByName(String name) {
        String q = normName(name).replace("kenh", "").replace("dai", "");
        if (q.length() < 2) return false;
        Ch best = null;
        int bestScore = 0, bestDiff = Integer.MAX_VALUE;
        for (Ch c : all) {
            String n = normName(c.name);
            int score = n.equals(q) ? 3 : (n.contains(q) || q.contains(n)) && n.length() >= 2 ? 2 : 0;
            int diff = Math.abs(n.length() - q.length());
            if (score > bestScore || (score == bestScore && score > 0 && diff < bestDiff)) {
                best = c; bestScore = score; bestDiff = diff;
            }
        }
        if (best == null) return false;
        play(best);
        return true;
    }

    private void play(Ch c) {
        ui.removeCallbacks(zapRun);
        ui.removeCallbacks(retryRun);
        pending = null;
        cur = c;
        hideOverlay(false);
        web.onResume();
        showCover(c.label());
        startWatch();
        load(c.url);
    }

    // lướt kênh: chỉ cập nhật tên kênh ngay, trang chỉ tải sau 0,6 giây không bấm nữa
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            countdown--;
            if (countdown <= 0) {
                hideCover();
                if (cur != null) showOsd(cur.label());
            } else {
                coverNum.setText(String.valueOf(countdown));
                ui.postDelayed(this, 1000);
            }
        }
    };

    private void showCover(String name) {
        coverName.setText(name);
        countdown = 5;
        coverNum.setText("5");
        cover.setVisibility(View.VISIBLE);
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000);
    }

    private void hideCover() {
        ui.removeCallbacks(tick);
        cover.setVisibility(View.GONE);
    }

    private final Runnable zapRun = new Runnable() {
        @Override
        public void run() {
            if (pending != null) play(pending);
        }
    };

    private void preview(Ch c) {
        pending = c;
        showOsd(c.label());
        ui.removeCallbacks(zapRun);
        ui.postDelayed(zapRun, 600);
    }

    private void zap(int delta) {
        if (all.isEmpty()) return;
        Ch base = pending != null ? pending : cur != null ? cur : all.get(0);
        int i = Math.max(0, all.indexOf(base));
        preview(all.get(Math.floorMod(i + delta, all.size())));
    }

    private void zapGroup(int delta) {
        if (groupNames.isEmpty()) return;
        Ch base = pending != null ? pending : cur != null ? cur : all.get(0);
        int gi = Math.max(0, groupNames.indexOf(base.group));
        preview(groupChs.get(Math.floorMod(gi + delta, groupNames.size())).get(0));
    }

    // giám sát: sau 15 giây rồi cứ 10 giây kiểm tra video có chạy không; ~40 giây không chạy thì tải lại
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (cur == null || !active) return;
            if (overlayOn()) {
                ui.postDelayed(this, 10000);
                return;
            }
            web.evaluateJavascript(CHECK_JS, r -> {
                if ("true".equals(r)) {
                    stuck = 0;
                } else {
                    stuck++;
                    if (stuck >= 4) {
                        stuck = 0;
                        reloadCur();
                    }
                }
            });
            ui.postDelayed(this, 10000);
        }
    };

    private void startWatch() {
        stuck = 0;
        ui.removeCallbacks(watch);
        ui.postDelayed(watch, 15000);
    }

    // ---------------------------------------------------------------- bảng chọn kênh

    private boolean overlayOn() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    /** Màn hẹp: true = chỉ hiện cột Kênh, false = chỉ hiện cột Đài. Màn rộng: luôn hiện cả hai. */
    private void showColumn(boolean channels) {
        if (!narrow) return;
        leftCol.setVisibility(channels ? View.GONE : View.VISIBLE);
        rightCol.setVisibility(channels ? View.VISIBLE : View.GONE);
    }

    private boolean channelsColumnOnly() {
        return narrow && rightCol.getVisibility() == View.VISIBLE && leftCol.getVisibility() == View.GONE;
    }

    private void setGroup(int gi) {
        if (groupNames.isEmpty()) return;
        leftSel = Math.max(0, Math.min(groupNames.size() - 1, gi));
        List<Ch> l = groupChs.get(leftSel);
        // đài chỉ có 1 kênh: bản thân đài là kênh, cột bên phải để trống
        shownList = l.size() == 1 ? new ArrayList<Ch>() : l;
        leftAd.notifyDataSetChanged();
        rightAd.notifyDataSetChanged();
        rightTitle.setText(l.size() == 1 ? "" : groupNames.get(leftSel));
        chList.setSelection(0);
    }

    /** Lần đầu mở: bảng bên phải hiện "Tivi của Bắp". */
    private void showWelcome() {
        shownList = new ArrayList<>();
        rightAd.notifyDataSetChanged();
        rightTitle.setText("Tivi của Bắp");
    }

    /** Đồng bộ cột Kênh với đài đang được tô sáng ở cột Đài. */
    private void syncGroup() {
        int p = groupList.getSelectedItemPosition();
        if (p >= 0 && p != leftSel) setGroup(p);
    }

    private void showOverlay(boolean focusGroups) {
        ui.removeCallbacks(zapRun);
        pending = null;
        hideCover();
        Ch c = cur;
        int gi = c == null ? 0 : Math.max(0, groupNames.indexOf(c.group));
        setGroup(gi);
        overlay.setVisibility(View.VISIBLE);
        if (c != null) silence();
        groupList.setSelection(leftSel);
        chList.setSelection(c != null ? Math.max(0, shownList.indexOf(c)) : 0);
        showColumn(!(focusGroups || shownList.isEmpty()));
        if (focusGroups || shownList.isEmpty()) groupList.requestFocus();
        else chList.requestFocus();
    }

    /** Về bảng chọn kênh là tắt tiếng ngay. */
    private void silence() {
        web.evaluateJavascript(PAUSE_JS, null);
        web.onPause();
    }

    /** resume = true: quay lại xem đúng kênh cũ (bật tiếng lại). */
    private void hideOverlay(boolean resume) {
        boolean wasOn = overlayOn();
        overlay.setVisibility(View.GONE);
        requestFocus();
        if (wasOn && resume && cur != null) {
            web.onResume();
            String u = web.getUrl();
            if (u == null || u.startsWith("about:")) {
                startWatch();
                reloadCur();
            } else {
                web.evaluateJavascript(RESUME_JS, null);
                web.evaluateJavascript(TV_FULL_JS, null);
            }
        }
    }

    // ---------------------------------------------------------------- OSD

    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osd.setVisibility(View.GONE);
            osdTime.setVisibility(View.GONE);
        }
    };

    private void showOsd(String text) {
        Calendar cal = Calendar.getInstance();
        osdTime.setText(String.format(Locale.US, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));
        osd.setText(text);
        osd.setVisibility(View.VISIBLE);
        osdTime.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        ui.postDelayed(hideOsd, 3500);
    }

    private final Runnable hideMsgRun = new Runnable() {
        @Override
        public void run() {
            msg.setVisibility(View.GONE);
        }
    };

    private void showMsg(String t, long autoHideMs) {
        msg.setText(t);
        msg.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideMsgRun);
        if (autoHideMs > 0) ui.postDelayed(hideMsgRun, autoHideMs);
    }

    private void hideMsg() {
        ui.removeCallbacks(hideMsgRun);
        msg.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- phím số

    private final Runnable numRun = new Runnable() {
        @Override
        public void run() {
            commitNum();
        }
    };

    private void digit(int d) {
        if (numBuf.length() >= 3) numBuf.setLength(0);
        numBuf.append(d);
        showOsd("Kênh: " + numBuf);
        ui.removeCallbacks(numRun);
        if (numBuf.length() >= 3) commitNum();
        else ui.postDelayed(numRun, 1800);
    }

    private void commitNum() {
        ui.removeCallbacks(numRun);
        Integer n = null;
        try {
            n = Integer.parseInt(numBuf.toString());
        } catch (NumberFormatException ignored) {
        }
        numBuf.setLength(0);
        Ch found = null;
        if (n != null) {
            for (Ch c : all) {
                if (c.num == n) {
                    found = c;
                    break;
                }
            }
        }
        if (found != null) play(found);
        else showOsd("Không có kênh số " + (n == null ? "" : n));
    }
}
