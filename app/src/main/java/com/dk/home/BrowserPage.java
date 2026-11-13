package com.dk.home;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Trình duyệt nhiều tab (trang bên trái trang Tiện ích): thanh tab, ô địa chỉ, dấu trang. */
final class BrowserPage extends LinearLayout {
    private static final String PREFS = "hauos";
    private static final String HOME = "https://www.google.com/";
    private static final int MAX_TABS = 20;
    private static final int STAR_BLUE = 0xFF2EB8FF;
    // Bảng màu giống trình duyệt mẫu: nền đen, tab đang mở xanh đậm chữ xanh sáng, ô địa chỉ xám đậm
    private static final int BG = 0xFF000000, TAB_ON = 0xFF0B4A73, TAB_OFF = 0xFF1C1C1E;
    private static final int TXT_ON = 0xFF4DB6FF, TXT_OFF = 0xFF9AA0A6, LINK = 0xFF3EA6FF, URL_BG = 0xFF1E1E1E;

    private static final class Tab {
        WebView web;       // tạo khi chọn tab lần đầu
        String url;
        String title = "";
        TextView chip;
    }

    private static final class Mark {
        String title, url;
    }

    private static final class Hist {
        String title, url;
        long at;
    }

    private static final int MAX_HIST = 300, MAX_SEARCH = 100;
    private final List<Hist> hist = new ArrayList<>();
    private final List<String> searches = new ArrayList<>();
    /** Bật: chạm vào liên kết thì tự mở tab mới. Tắt: mở ngay trong tab hiện tại, chỉ nhấn giữ mới mở tab khác. */
    private boolean newTabOnTap = true;   // true = cho phép mở liên kết (bình thường); false = chạm vào liên kết không mở gì
    private ArrayAdapter<String> suggestAd;

    private final MainActivity act;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<Mark> marks = new ArrayList<>();
    private int cur = 0;

    private final HorizontalScrollView tabScroll;
    private final LinearLayout tabRow;
    private final AutoCompleteTextView urlBox;
    private final Icon starBtn;
    private final View progress;
    private final FrameLayout holder;
    private boolean active;

    BrowserPage(MainActivity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        setClickable(true);

        // --- thanh tab ---
        tabScroll = new HorizontalScrollView(a);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        tabRow = new LinearLayout(a);
        tabRow.setOrientation(HORIZONTAL);
        tabRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        tabScroll.addView(tabRow, new FrameLayout.LayoutParams(-2, -1));   // cao bằng thanh tab, tab không bị cắt/lệch lên trên
        LinearLayout tabBar = new LinearLayout(a);
        tabBar.setOrientation(HORIZONTAL);
        TextView gear = new TextView(a);
        gear.setText("⚙");
        gear.setTextColor(TXT_ON);
        gear.setTextSize(20);
        gear.setGravity(Gravity.CENTER);
        gear.setOnClickListener(v -> showSettings());
        tabBar.addView(gear, new LayoutParams(dp(34), -1));
        tabBar.addView(tabScroll, new LayoutParams(0, -1, 1f));
        addView(tabBar, new LayoutParams(-1, dp(36)));

        // --- hàng ô địa chỉ ---
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(4), dp(8));

        urlBox = new AutoCompleteTextView(a);
        urlBox.setSingleLine(true);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setHintTextColor(Ui.DIM);
        urlBox.setHint("Nhập địa chỉ hoặc từ khóa");
        urlBox.setTextSize(17);
        urlBox.setSelectAllOnFocus(true);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable ub = new GradientDrawable();
        ub.setColor(URL_BG);
        ub.setCornerRadius(dp(2));
        urlBox.setBackground(ub);
        urlBox.setPadding(dp(12), 0, dp(10), 0);
        urlBox.setEllipsize(TextUtils.TruncateAt.END);
        urlBox.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go();
                return true;
            }
            return false;
        });
        urlBox.setThreshold(1);
        urlBox.setDropDownBackgroundDrawable(new ColorDrawable(0xFF1E1E1E));
        suggestAd = new ArrayAdapter<String>(a, android.R.layout.simple_dropdown_item_1line, new ArrayList<String>()) {
            @Override
            public View getView(int position, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, cv, parent);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(15);
                tv.setSingleLine(true);
                return tv;
            }
        };
        urlBox.setAdapter(suggestAd);
        urlBox.setOnItemClickListener((pa, vw, pos, id) -> go());
        urlBox.setOnFocusChangeListener((vw, has) -> { if (has) refreshSuggest(); });
        bar.addView(urlBox, new LayoutParams(0, dp(44), 1f));

        Icon search = new Icon(a, Icon.SEARCH, Color.WHITE);
        search.setOnClickListener(v -> go());
        bar.addView(search, new LayoutParams(dp(44), dp(44)));

        starBtn = new Icon(a, Icon.STAR_OUTLINE, Color.WHITE);
        starBtn.setOnClickListener(v -> toggleMark());
        bar.addView(starBtn, new LayoutParams(dp(44), dp(44)));

        Icon list = new Icon(a, Icon.STAR_FILLED, STAR_BLUE);
        list.setOnClickListener(v -> showMarks());
        bar.addView(list, new LayoutParams(dp(44), dp(44)));
        addView(bar, new LayoutParams(-1, -2));

        // --- thanh tiến trình + vùng trang ---
        progress = new View(a);
        progress.setBackgroundColor(LINK);
        progress.setPivotX(0f);
        progress.setScaleX(0f);
        progress.setVisibility(INVISIBLE);
        addView(progress, new LayoutParams(-1, dp(2)));

        holder = new FrameLayout(a);
        addView(holder, new LayoutParams(-1, 0, 1f));

        load();
        if (tabs.isEmpty()) newTab(HOME, false);
        cur = Math.max(0, Math.min(cur, tabs.size() - 1));
        rebuildChips();
        select(cur);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- tab ----------

    private Tab current() {
        return tabs.get(cur);
    }

    private Tab newTab(String url, boolean selectIt) {
        Tab t = new Tab();
        t.url = url == null ? HOME : url;
        tabs.add(t);
        if (selectIt) {
            rebuildChips();
            select(tabs.size() - 1);
        }
        return t;
    }

    private void select(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab old = tabs.isEmpty() || cur >= tabs.size() ? null : tabs.get(cur);
        if (old != null && old.web != null) holder.removeView(old.web);
        cur = i;
        Tab t = current();
        if (t.web == null) {
            t.web = makeWeb(t);
            if (t.url != null && !t.url.isEmpty()) t.web.loadUrl(t.url);
        }
        if (t.web.getParent() == null) holder.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
        if (active) t.web.onResume();
        urlBox.clearFocus();
        urlBox.setText(t.web.getUrl() != null ? t.web.getUrl() : t.url);
        progress.setVisibility(INVISIBLE);
        refreshChips();
        refreshStar();
        final TextView chip = t.chip;
        if (chip != null) tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(40)), 0));
        save();
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.remove(i);
        if (t.web != null) {
            holder.removeView(t.web);
            t.web.destroy();
        }
        if (tabs.isEmpty()) {
            Tab n = new Tab();
            n.url = HOME;
            tabs.add(n);
            cur = 0;
        } else if (i < cur) {
            cur--;
        } else if (i == cur) {
            cur = Math.min(cur, tabs.size() - 1);
        }
        rebuildChips();
        // chọn lại tab hiện tại (web của nó có thể chưa gắn vào khung)
        int keep = cur;
        cur = Math.max(0, Math.min(keep, tabs.size() - 1));
        Tab nt = current();
        if (nt.web == null) {
            nt.web = makeWeb(nt);
            nt.web.loadUrl(nt.url);
        }
        if (nt.web.getParent() == null) holder.addView(nt.web, new FrameLayout.LayoutParams(-1, -1));
        if (active) nt.web.onResume();
        urlBox.setText(nt.web.getUrl() != null ? nt.web.getUrl() : nt.url);
        refreshChips();
        refreshStar();
        save();
    }

    private void rebuildChips() {
        tabRow.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            TextView c = new TextView(act);
            c.setSingleLine(true);
            c.setEllipsize(TextUtils.TruncateAt.END);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setTextSize(13);
            c.setPadding(dp(10), 0, dp(4), 0);
            c.setMaxWidth(dp(130));
            c.setMinWidth(dp(70));
            c.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            c.setOnClickListener(v -> {
                if (idx != cur) select(idx);
            });
            // nhấn giữ hoặc bấm ✕ để đóng tab
            c.setOnLongClickListener(v -> {
                closeTab(idx);
                return true;
            });
            t.chip = c;
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.addView(c, new LayoutParams(-2, -1));
            TextView x = new TextView(act);
            x.setText("✕");
            x.setTextSize(12);
            x.setGravity(Gravity.CENTER);
            x.setPadding(dp(6), 0, dp(10), 0);
            x.setOnClickListener(v -> closeTab(idx));
            cell.addView(x, new LayoutParams(-2, -1));
            cell.setTag(x);
            LayoutParams lp = new LayoutParams(-2, -1);
            lp.rightMargin = dp(4);
            tabRow.addView(cell, lp);
        }
        TextView add = new TextView(act);
        add.setText("+ Tab");
        add.setTextColor(LINK);
        add.setTextSize(14);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(12), 0, dp(10), 0);
        add.setOnClickListener(v -> {
            if (tabs.size() >= MAX_TABS) {
                Toast.makeText(act, "Tối đa " + MAX_TABS + " tab", Toast.LENGTH_SHORT).show();
                return;
            }
            newTab(HOME, true);
        });
        tabRow.addView(add, new LayoutParams(-2, -1));
        refreshChips();
    }

    private void refreshChips() {
        for (int i = 0; i < tabs.size() && i < tabRow.getChildCount() - 1; i++) {
            Tab t = tabs.get(i);
            boolean on = i == cur;
            LinearLayout cell = (LinearLayout) tabRow.getChildAt(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? TAB_ON : TAB_OFF);
            bg.setCornerRadius(dp(3));
            cell.setBackground(bg);
            String title = t.title == null || t.title.isEmpty()
                    ? (t.web != null && t.web.getTitle() != null ? t.web.getTitle() : hostOf(t.url))
                    : t.title;
            t.chip.setText(title);
            t.chip.setTextColor(on ? TXT_ON : TXT_OFF);
            ((TextView) cell.getTag()).setTextColor(on ? TXT_ON : TXT_OFF);
        }
    }

    private static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h != null) return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception ignored) { }
        return "Tab mới";
    }

    // ---------- WebView ----------

    private WebView makeWeb(final Tab t) {
        WebView w = new WebView(act);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(0xFF121212);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                String u = r.getUrl().toString();
                String low = u.toLowerCase();
                if (!newTabOnTap && r.isForMainFrame() && r.hasGesture() && !r.isRedirect()
                        && (low.startsWith("http://") || low.startsWith("https://"))) {
                    WebView.HitTestResult h = v.getHitTestResult();
                    int ty = h == null ? 0 : h.getType();
                    // Đã tắt "cho phép mở liên kết": chạm vào liên kết không làm gì cả (chặn hẳn), chỉ nhấn giữ mới mở được
                    if (ty == WebView.HitTestResult.SRC_ANCHOR_TYPE || ty == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return true;
                }
                return handleScheme(u);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                t.url = url;
                if (t == current()) {
                    if (!urlBox.hasFocus()) urlBox.setText(url);
                    progress.setVisibility(VISIBLE);
                    progress.setScaleX(0.05f);
                    refreshStar();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                t.url = url;
                if (v.getTitle() != null) t.title = v.getTitle();
                addHistory(v.getTitle(), url);
                noteSearch(url);
                if (t == current()) {
                    if (!urlBox.hasFocus()) urlBox.setText(url);
                    progress.setVisibility(INVISIBLE);
                    refreshStar();
                }
                refreshChips();
                save();
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (t == current()) {
                    progress.setVisibility(p >= 100 ? INVISIBLE : VISIBLE);
                    progress.setScaleX(Math.max(0.05f, p / 100f));
                }
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                t.title = title == null ? "" : title;
                refreshChips();
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message msg) {
                // Đã tắt "cho phép mở liên kết": chặn hẳn cửa sổ/tab mà trang tự bật lên (quảng cáo, popup...), không mở gì cả
                if (!newTabOnTap) return false;
                if (tabs.size() >= MAX_TABS) return false;
                Tab n = newTab("", true);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) msg.obj;
                tr.setWebView(n.web);
                msg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView v) {
                for (int i = 0; i < tabs.size(); i++) {
                    if (tabs.get(i).web == v) {
                        closeTab(i);
                        return;
                    }
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                act.showCustom(view, cb);
            }

            @Override
            public void onHideCustomView() {
                act.hideCustomPublic();
            }
        });
        // Nhấn giữ liên kết / tiêu đề / ảnh / video: hộp thoại "Mở trong tab mới" (mở nền, vẫn ở trang hiện tại)
        w.setOnLongClickListener(v -> {
            final WebView.HitTestResult r = ((WebView) v).getHitTestResult();
            if (r == null) return false;
            final String extra = r.getExtra();
            switch (r.getType()) {
                case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                    if (extra == null || extra.isEmpty()) return false;
                    showOpenInTab(extra, null);
                    return true;
                case WebView.HitTestResult.IMAGE_TYPE:
                    if (extra == null || extra.isEmpty()) return false;
                    showOpenInTab(null, extra);
                    return true;
                case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: {
                    final String img = extra;
                    Handler h = new Handler(Looper.getMainLooper()) {
                        @Override
                        public void handleMessage(Message m) {
                            String link = m.getData() == null ? null : m.getData().getString("url");
                            showOpenInTab(link, img);
                        }
                    };
                    ((WebView) v).requestFocusNodeHref(h.obtainMessage());
                    return true;
                }
                default:
                    return false;
            }
        });
        w.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                rq.setMimeType(mime);
                rq.addRequestHeader("User-Agent", ua);
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) rq.addRequestHeader("Cookie", ck);
                rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
                Toast.makeText(act, "Đang tải xuống…", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                try {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ex) {
                    Toast.makeText(act, "Không tải được tệp này", Toast.LENGTH_SHORT).show();
                }
            }
        });
        return w;
    }

    /** Hộp thoại sau khi nhấn giữ: bấm để mở trong tab mới ở nền, người dùng vẫn ở trang hiện tại. */
    private void showOpenInTab(final String link, final String image) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        TextView title = new TextView(act);
        title.setText(link != null && !link.isEmpty() ? link : image);
        title.setTextColor(Ui.DIM);
        title.setTextSize(12);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, 0, 0, dp(6));
        box.addView(title);
        final AlertDialog d = new AlertDialog.Builder(act).setView(box).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.panel(act, Ui.GRID));
        if (link != null && !link.isEmpty()) {
            box.addView(menuRow("Mở trong tab mới", () -> { d.dismiss(); openBackground(link); }));
        }
        if (image != null && !image.isEmpty() && !image.equals(link)) {
            box.addView(menuRow(link != null && !link.isEmpty() ? "Mở ảnh trong tab mới" : "Mở trong tab mới",
                    () -> { d.dismiss(); openBackground(image); }));
        }
        if (box.getChildCount() < 2) return;
        d.show();
    }

    private TextView menuRow(String label, Runnable onClick) {
        TextView row = new TextView(act);
        row.setText(Ui.up(label));
        row.setTextColor(Color.WHITE);
        row.setTextSize(14);
        row.setPadding(dp(4), dp(14), dp(4), dp(14));
        Ui.style(row);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /** Mở một tab mới nhưng không chuyển sang đó. */
    private void openBackground(String url) {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(act, "Tối đa " + MAX_TABS + " tab", Toast.LENGTH_SHORT).show();
            return;
        }
        Tab t = new Tab();
        t.url = url;
        tabs.add(t);
        rebuildChips();
        save();
        Toast.makeText(act, "Đã mở tab mới", Toast.LENGTH_SHORT).show();
    }

    /** true nếu đã xử lý (mở app khác); false để WebView tự tải trang http(s). */
    private boolean handleScheme(String url) {
        String low = url.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("about:")
                || low.startsWith("file:") || low.startsWith("data:") || low.startsWith("javascript:")
                || low.startsWith("blob:")) return false;
        try {
            Intent i = low.startsWith("intent:") ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(act, "Không có ứng dụng để mở liên kết này", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
        return true;
    }

    // ---------- ô địa chỉ ----------

    private void go() {
        String q = urlBox.getText().toString().trim();
        if (q.isEmpty()) return;
        String url;
        if (q.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) url = q;
        else if (!q.contains(" ") && (q.contains(".") || q.startsWith("localhost")) ) url = "https://" + q;
        else url = "https://www.google.com/search?q=" + Uri.encode(q);
        if (url.startsWith("https://www.google.com/search?q=")) addSearch(q);
        hideKeyboard();
        urlBox.clearFocus();
        Tab t = current();
        t.url = url;
        if (t.web == null) {
            t.web = makeWeb(t);
            holder.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
        }
        t.web.loadUrl(url);
    }

    // ---------- lịch sử + cài đặt ----------

    private void addSearch(String q) {
        if (q == null) return;
        q = q.trim();
        if (q.isEmpty()) return;
        searches.remove(q);
        searches.add(0, q);
        while (searches.size() > MAX_SEARCH) searches.remove(searches.size() - 1);
    }

    /** Trang kết quả tìm kiếm (Google/Bing/DuckDuckGo/YouTube): ghi lại từ khóa dù gõ ở ô địa chỉ hay ngay trong trang. */
    private void noteSearch(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            String q = null;
            if (h.contains("google.") && path.equals("/search")) q = u.getQueryParameter("q");
            else if (h.contains("bing.com") && path.equals("/search")) q = u.getQueryParameter("q");
            else if (h.contains("duckduckgo.com")) q = u.getQueryParameter("q");
            else if (h.contains("youtube.com") && path.equals("/results")) q = u.getQueryParameter("search_query");
            if (q != null) addSearch(q);
        } catch (Exception ignored) { }
    }

    private void addHistory(String title, String url) {
        if (url == null) return;
        String low = url.toLowerCase();
        if (!(low.startsWith("http://") || low.startsWith("https://"))) return;
        if (!hist.isEmpty() && hist.get(0).url.equals(url)) {
            if (title != null && !title.isEmpty()) hist.get(0).title = title;
            return;
        }
        Hist e = new Hist();
        e.url = url;
        e.title = title == null || title.isEmpty() ? hostOf(url) : title;
        e.at = System.currentTimeMillis();
        hist.add(0, e);
        while (hist.size() > MAX_HIST) hist.remove(hist.size() - 1);
    }

    /** Gợi ý khi gõ ở ô địa chỉ: từ khóa đã tìm + địa chỉ trang đã vào (bỏ https://). */
    private void refreshSuggest() {
        suggestAd.clear();
        for (String q : searches) suggestAd.add(q);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Hist h : hist) {
            String u = h.url.replaceFirst("^https?://(www\\.)?", "");
            if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (seen.add(u)) suggestAd.add(u);
            if (seen.size() >= 100) break;
        }
        suggestAd.notifyDataSetChanged();
    }

    private void showSettings() {
        hideKeyboard();
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        TextView title = new TextView(act);
        title.setText("CÀI ĐẶT TRÌNH DUYỆT");
        title.setTextColor(Ui.CYAN);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(8));
        Ui.style(title);
        box.addView(title);
        final AlertDialog d = new AlertDialog.Builder(act).setView(box).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.panel(act, Ui.GRID));
        CheckBox cb = new CheckBox(act);
        cb.setText("Cho phép mở liên kết");
        cb.setTextColor(Color.WHITE);
        cb.setTextSize(14);
        cb.setButtonTintList(ColorStateList.valueOf(Ui.CYAN));
        cb.setChecked(newTabOnTap);
        cb.setOnCheckedChangeListener((b, on) -> { newTabOnTap = on; save(); });
        box.addView(cb);
        TextView note = new TextView(act);
        note.setText("Đang bật: chạm vào liên kết mở bình thường trong trang, và cửa sổ/tab mà trang tự bật lên cũng được mở.\nBỏ chọn: chạm vào liên kết sẽ không mở gì cả (kể cả tab/cửa sổ trang tự bật). Muốn mở thì nhấn giữ liên kết, chọn \"Mở trong tab mới\".");
        note.setTextColor(Ui.DIM);
        note.setTextSize(12);
        note.setPadding(dp(4), dp(2), dp(4), dp(8));
        box.addView(note);
        box.addView(menuRow("Lịch sử trang", () -> { d.dismiss(); showHistory(); }));
        box.addView(menuRow("Lịch sử tìm kiếm", () -> { d.dismiss(); showSearches(); }));
        box.addView(menuRow("Đổi trình duyệt", () -> { d.dismiss(); act.switchBrowser(); }));
        box.addView(menuRow("Xóa toàn bộ lịch sử", () -> {
            hist.clear();
            searches.clear();
            save();
            d.dismiss();
            Toast.makeText(act, "Đã xóa lịch sử", Toast.LENGTH_SHORT).show();
        }));
        d.show();
    }

    private void showHistory() {
        final SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        List<String[]> rows = new ArrayList<>();
        for (Hist h : hist) rows.add(new String[]{h.title, h.url, f.format(new Date(h.at))});
        showList("LỊCH SỬ TRANG", rows, "Chưa có lịch sử trang.", i -> open(hist.get(i).url), i -> hist.remove(i));
    }

    private void showSearches() {
        List<String[]> rows = new ArrayList<>();
        for (String q : searches) rows.add(new String[]{q, "", ""});
        showList("LỊCH SỬ TÌM KIẾM", rows, "Chưa có lịch sử tìm kiếm.", i -> {
            urlBox.setText(searches.get(i));
            go();
        }, i -> searches.remove(i));
    }

    /** Danh sách cuộn: bấm để mở, nhấn giữ để xóa một dòng. */
    private void showList(String head, List<String[]> rows, String empty, final Consumer<Integer> onClick, final Consumer<Integer> onDelete) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        TextView title = new TextView(act);
        title.setText(head);
        title.setTextColor(Ui.CYAN);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(6));
        Ui.style(title);
        box.addView(title);
        final AlertDialog d = new AlertDialog.Builder(act).setView(box).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.panel(act, Ui.GRID));
        if (rows.isEmpty()) {
            TextView e = new TextView(act);
            e.setText(empty);
            e.setTextColor(Ui.DIM);
            e.setTextSize(14);
            e.setPadding(dp(4), dp(10), dp(4), dp(10));
            box.addView(e);
        } else {
            ScrollView sv = new ScrollView(act);
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(VERTICAL);
            sv.addView(col);
            for (int i = 0; i < rows.size(); i++) {
                final int idx = i;
                String[] r = rows.get(i);
                TextView row = new TextView(act);
                String txt = r[0];
                if (!r[1].isEmpty()) txt += "\n" + r[1];
                if (!r[2].isEmpty()) txt += "\n" + r[2];
                row.setText(txt);
                row.setTextColor(Color.WHITE);
                row.setTextSize(14);
                row.setMaxLines(3);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));
                row.setOnClickListener(v -> { d.dismiss(); onClick.accept(idx); });
                row.setOnLongClickListener(v -> {
                    onDelete.accept(idx);
                    save();
                    d.dismiss();
                    Toast.makeText(act, "Đã xóa", Toast.LENGTH_SHORT).show();
                    return true;
                });
                col.addView(row);
            }
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
            box.addView(sv, new LayoutParams(-1, maxH));
            TextView hint = new TextView(act);
            hint.setText("Nhấn giữ một dòng để xóa");
            hint.setTextColor(Ui.DIM);
            hint.setTextSize(12);
            hint.setPadding(dp(4), dp(8), dp(4), 0);
            box.addView(hint);
        }
        d.show();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
    }

    // ---------- dấu trang ----------

    private String pageUrl() {
        Tab t = current();
        String u = t.web != null ? t.web.getUrl() : null;
        return u != null ? u : t.url;
    }

    private int markIndex(String url) {
        if (url == null) return -1;
        for (int i = 0; i < marks.size(); i++) if (marks.get(i).url.equals(url)) return i;
        return -1;
    }

    private void refreshStar() {
        boolean on = markIndex(pageUrl()) >= 0;
        starBtn.setType(on ? Icon.STAR_FILLED : Icon.STAR_OUTLINE, on ? Ui.AMBER : Color.WHITE);
    }

    private void toggleMark() {
        String u = pageUrl();
        if (u == null || u.isEmpty() || u.equals("about:blank")) return;
        int i = markIndex(u);
        if (i >= 0) {
            marks.remove(i);
            Toast.makeText(act, "Đã bỏ dấu trang", Toast.LENGTH_SHORT).show();
        } else {
            Mark m = new Mark();
            m.url = u;
            Tab t = current();
            m.title = t.web != null && t.web.getTitle() != null && !t.web.getTitle().isEmpty() ? t.web.getTitle() : hostOf(u);
            marks.add(0, m);
            Toast.makeText(act, "Đã thêm dấu trang", Toast.LENGTH_SHORT).show();
        }
        refreshStar();
        save();
    }

    private void showMarks() {
        hideKeyboard();
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        TextView title = new TextView(act);
        title.setText("DẤU TRANG");
        title.setTextColor(Ui.CYAN);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(6));
        Ui.style(title);
        box.addView(title);
        final AlertDialog d = new AlertDialog.Builder(act).setView(box).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.panel(act, Ui.GRID));
        if (marks.isEmpty()) {
            TextView e = new TextView(act);
            e.setText("Chưa có dấu trang. Bấm ngôi sao để thêm trang hiện tại.");
            e.setTextColor(Ui.DIM);
            e.setTextSize(14);
            e.setPadding(dp(4), dp(10), dp(4), dp(10));
            box.addView(e);
        }
        for (final Mark m : new ArrayList<>(marks)) {
            TextView row = new TextView(act);
            row.setText(m.title + "\n" + m.url);
            row.setTextColor(Color.WHITE);
            row.setTextSize(14);
            row.setMaxLines(2);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            row.setOnClickListener(v -> {
                d.dismiss();
                open(m.url);
            });
            row.setOnLongClickListener(v -> {
                marks.remove(m);
                save();
                refreshStar();
                d.dismiss();
                Toast.makeText(act, "Đã xóa dấu trang", Toast.LENGTH_SHORT).show();
                return true;
            });
            box.addView(row);
        }
        if (!marks.isEmpty()) {
            TextView hint = new TextView(act);
            hint.setText("Nhấn giữ để xóa");
            hint.setTextColor(Ui.DIM);
            hint.setTextSize(12);
            hint.setPadding(dp(4), dp(8), dp(4), 0);
            box.addView(hint);
        }
        d.show();
    }

    private void open(String url) {
        Tab t = current();
        t.url = url;
        if (t.web == null) {
            t.web = makeWeb(t);
            holder.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
        }
        urlBox.setText(url);
        t.web.loadUrl(url);
    }

    // ---------- cho MainActivity ----------

    void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (!on) {
            hideKeyboard();
            urlBox.clearFocus();
        }
    }

    /** Nút Back: true nếu trang đã tự xử lý (đóng bàn phím / lùi lịch sử). */
    boolean onBack() {
        if (urlBox.hasFocus()) {
            hideKeyboard();
            urlBox.clearFocus();
            return true;
        }
        Tab t = current();
        if (t.web != null && t.web.canGoBack()) {
            t.web.goBack();
            return true;
        }
        return false;
    }

    /** Vuốt trái (về trang Tiện ích) chỉ khi chạm trong vùng trang và trang không cuộn ngang được, hoặc từ sát mép phải. */
    boolean canSwipeBack(float rawX, float rawY, int screenW) {
        if (rawX > screenW - dp(40)) return true;
        int[] l = new int[2];
        holder.getLocationOnScreen(l);
        if (rawY < l[1] || rawY > l[1] + holder.getHeight()) return false;
        Tab t = current();
        return t.web == null || !t.web.canScrollHorizontally(1);
    }

    void pause() {
        for (Tab t : tabs) if (t.web != null) t.web.onPause();
        save();
    }

    void resume() {
        Tab t = tabs.isEmpty() ? null : current();
        if (t != null && t.web != null) t.web.onResume();
    }

    void destroy() {
        for (Tab t : tabs) {
            if (t.web != null) {
                if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
                t.web.destroy();
            }
        }
    }

    // ---------- lưu / nạp ----------

    private void save() {
        try {
            JSONArray ta = new JSONArray();
            for (Tab t : tabs) {
                String u = t.web != null && t.web.getUrl() != null ? t.web.getUrl() : t.url;
                ta.put(new JSONObject().put("u", u == null ? HOME : u).put("t", t.title == null ? "" : t.title));
            }
            JSONArray ha = new JSONArray();
            for (Hist h : hist) ha.put(new JSONObject().put("u", h.url).put("t", h.title).put("a", h.at));
            JSONArray ma = new JSONArray();
            for (Mark m : marks) ma.put(new JSONObject().put("u", m.url).put("t", m.title));
            act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("br_tabs", ta.toString())
                    .putInt("br_cur", cur)
                    .putString("br_marks", ma.toString())
                    .putString("br_hist", ha.toString())
                    .putString("br_search", new JSONArray(searches).toString())
                    .putBoolean("br_newtab", newTabOnTap)
                    .apply();
        } catch (Exception ignored) { }
    }

    private void load() {
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray ta = new JSONArray(p.getString("br_tabs", "[]"));
            for (int i = 0; i < ta.length() && i < MAX_TABS; i++) {
                JSONObject o = ta.getJSONObject(i);
                Tab t = new Tab();
                t.url = o.optString("u", HOME);
                t.title = o.optString("t", "");
                tabs.add(t);
            }
            cur = p.getInt("br_cur", 0);
            newTabOnTap = p.getBoolean("br_newtab", true);
            JSONArray ha = new JSONArray(p.getString("br_hist", "[]"));
            for (int i = 0; i < ha.length() && i < MAX_HIST; i++) {
                JSONObject o = ha.getJSONObject(i);
                Hist h = new Hist();
                h.url = o.optString("u", "");
                h.title = o.optString("t", "");
                h.at = o.optLong("a", 0L);
                if (!h.url.isEmpty()) hist.add(h);
            }
            JSONArray sa = new JSONArray(p.getString("br_search", "[]"));
            for (int i = 0; i < sa.length() && i < MAX_SEARCH; i++) searches.add(sa.getString(i));
            JSONArray ma = new JSONArray(p.getString("br_marks", "[]"));
            for (int i = 0; i < ma.length(); i++) {
                JSONObject o = ma.getJSONObject(i);
                Mark m = new Mark();
                m.url = o.optString("u", "");
                m.title = o.optString("t", "");
                if (!m.url.isEmpty()) marks.add(m);
            }
        } catch (Exception ignored) { }
    }

    // ---------- biểu tượng vẽ tay ----------

    private static final class Icon extends View {
        static final int SEARCH = 0, STAR_OUTLINE = 1, STAR_FILLED = 2;
        private int type, color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int type, int color) {
            super(c);
            this.type = type;
            this.color = color;
            setClickable(true);
        }

        void setType(int type, int color) {
            this.type = type;
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setColor(color);
            p.setStrokeWidth(2f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            if (type == SEARCH) {
                p.setStyle(Paint.Style.STROKE);
                float r = 7f * d;
                cv.drawCircle(cx - 2f * d, cy - 2f * d, r, p);
                cv.drawLine(cx + 3f * d, cy + 3f * d, cx + 9f * d, cy + 9f * d, p);
            } else {
                path.reset();
                float R = 11f * d, r = 4.3f * d;   // tỉ lệ ~0.38: ngôi sao 5 cánh chuẩn, không bị mập
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float rr = i % 2 == 0 ? R : r;
                    float x = cx + (float) Math.cos(a) * rr, y = cy + 1f * d + (float) Math.sin(a) * rr;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStyle(type == STAR_FILLED ? Paint.Style.FILL : Paint.Style.STROKE);   // sao đặc chỉ tô màu, không viền (viền làm sao bị dày/nhòe)
                cv.drawPath(path, p);
            }
        }
    }
}
