package com.dk.home;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Dịch vụ hỗ trợ tiếp cận: chụp màn hình và tự bấm nút trong bảng cài đặt nhanh của hệ thống
 * (cho những nút mà Android không cho ứng dụng thường tự bật/tắt).
 */
public class ScreenshotService extends AccessibilityService {
    public static ScreenshotService instance;

    public interface Done { void run(boolean ok); }

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        h.removeCallbacksAndMessages(null);   // hủy các lần thử bấm nút còn chờ, không để Handler giữ dịch vụ
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    /** Mở bảng cài đặt nhanh, bấm nút có tên khớp một trong keys, rồi về màn hình chính. */
    public void clickTile(final String[] keys, final Done done) {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        h.postDelayed(() -> attempt(keys, 0, done), 800);
    }

    private void attempt(final String[] keys, final int n, final Done done) {
        if (tryClick(keys)) {
            h.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_HOME);
                if (done != null) done.run(true);
            }, 350);
            return;
        }
        if (n >= 6) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            if (done != null) done.run(false);
            return;
        }
        h.postDelayed(() -> attempt(keys, n + 1, done), 400);
    }

    private boolean tryClick(String[] keys) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        } catch (Exception ignored) { }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        String mine = getPackageName();
        for (String k : keys) {
            for (AccessibilityNodeInfo r : roots) {
                if (mine.equals(String.valueOf(r.getPackageName()))) continue;
                AccessibilityNodeInfo hit = find(r, k, 0);
                AccessibilityNodeInfo c = hit;
                for (int i = 0; i < 5 && c != null; i++) {
                    if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    c = c.getParent();
                }
            }
        }
        return false;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo n, String k, int depth) {
        if (n == null || depth > 25) return null;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null && t.toString().toLowerCase().contains(k))
                || (d != null && d.toString().toLowerCase().contains(k))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(n.getChild(i), k, depth + 1);
            if (r != null) return r;
        }
        return null;
    }
}
