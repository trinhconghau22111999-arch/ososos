package com.dk.home;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Trung tâm điều khiển: các nút nhanh + thanh trượt, dựng bằng code. */
final class ControlCenter {
    private static final int CYAN = 0xFF00E5FF, DIM = 0xFF7F8BC0, WHITE = 0xFFFFFFFF;

    interface State { boolean on(); }

    private static class Tile {
        LinearLayout root;
        TextView glyph, title, sub;
    }

    private final Activity a;
    private final GridLayout grid;
    private final TextView info;
    private final Runnable closer;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final AudioManager audio;
    private final PowerManager power;
    private final WifiManager wifi;
    private final ConnectivityManager conn;
    private final TelephonyManager tele;
    private final LocationManager loc;
    private CameraManager cam;
    private CameraManager.TorchCallback torchCb;
    private String torchId;
    private boolean torchOn, askedWrite;
    private VSlider bright, volume;
    private int cell, margin;

    ControlCenter(Activity a, GridLayout grid, TextView info, Runnable closer) {
        this.a = a;
        this.grid = grid;
        this.info = info;
        this.closer = closer;
        audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        power = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        wifi = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        conn = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        tele = (TelephonyManager) a.getSystemService(Context.TELEPHONY_SERVICE);
        loc = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        build();
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private void go(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Máy không hỗ trợ mục này", Toast.LENGTH_SHORT).show();
        }
    }

    private void place(View v, int r, int c, int rs, int cs) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(r, rs), GridLayout.spec(c, cs));
        lp.width = cell * cs - 2 * margin;
        lp.height = cell * rs - 2 * margin;
        lp.setMargins(margin, margin, margin, margin);
        grid.addView(v, lp);
    }

    private Tile tile(String glyph, String title, String sub, boolean pill) {
        Tile t = new Tile();
        t.root = new LinearLayout(a);
        t.root.setBackgroundResource(R.drawable.bg_panel);
        t.glyph = new TextView(a);
        t.glyph.setText(glyph);
        t.glyph.setTextColor(WHITE);
        t.title = new TextView(a);
        t.title.setText(title);
        t.title.setTextColor(WHITE);
        t.title.setMaxLines(1);
        t.sub = new TextView(a);
        t.sub.setText(sub);
        t.sub.setTextColor(DIM);
        t.sub.setTextSize(12);
        t.sub.setMaxLines(1);
        if (pill) {
            t.root.setOrientation(LinearLayout.HORIZONTAL);
            t.root.setGravity(Gravity.CENTER_VERTICAL);
            t.root.setPadding(dp(14), 0, dp(10), 0);
            t.glyph.setTextSize(26);
            t.title.setTextSize(15);
            t.title.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);
            col.addView(t.title);
            col.addView(t.sub);
            t.root.addView(t.glyph);
            t.root.addView(col);
        } else {
            t.root.setOrientation(LinearLayout.VERTICAL);
            t.root.setGravity(Gravity.CENTER);
            t.glyph.setTextSize(24);
            t.title.setTextSize(10);
            t.title.setGravity(Gravity.CENTER);
            t.title.setPadding(dp(2), dp(2), dp(2), 0);
            t.root.addView(t.glyph);
            t.root.addView(t.title);
        }
        return t;
    }

    private void setActive(Tile t, boolean on) {
        t.root.setBackgroundResource(on ? R.drawable.bg_accent : R.drawable.bg_panel);
        t.glyph.setTextColor(on ? CYAN : WHITE);
    }

    private void bind(Tile t, State s, Runnable click) {
        t.root.setOnClickListener(v -> click.run());
        if (s != null) refreshers.add(() -> setActive(t, s.on()));
    }

    private void key(int code) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void askWrite() {
        if (askedWrite) return;
        askedWrite = true;
        Toast.makeText(a, "Cho phép DK Home chỉnh cài đặt hệ thống rồi quay lại", Toast.LENGTH_LONG).show();
        go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.getPackageName())));
    }

    private boolean cellular() {
        Network n = conn.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : conn.getNetworkCapabilities(n);
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    private boolean locationOn() {
        if (Build.VERSION.SDK_INT >= 28) return loc.isLocationEnabled();
        return loc.isProviderEnabled(LocationManager.GPS_PROVIDER) || loc.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void build() {
        int pad = dp(14);
        cell = (a.getResources().getDisplayMetrics().widthPixels - 2 * pad) / 4;
        margin = dp(4);

        // Hàng 1: Wi-Fi + dữ liệu di động
        Tile w = tile("📶", "Wi-Fi", "", true);
        bind(w, () -> wifi.isWifiEnabled(), () -> go(new Intent(
                Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIFI_SETTINGS)));
        refreshers.add(() -> w.sub.setText(wifi.isWifiEnabled() ? "Đang bật" : "Đã tắt"));
        place(w.root, 0, 0, 1, 2);

        Tile d = tile("⇅", "Dữ liệu di động", "", true);
        bind(d, this::cellular, () -> go(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)));
        refreshers.add(() -> {
            String op = tele.getNetworkOperatorName();
            d.title.setText(op == null || op.isEmpty() ? "Dữ liệu di động" : op);
            d.sub.setText(cellular() ? "Đang dùng" : "Không dùng");
        });
        place(d.root, 0, 2, 1, 2);

        // Nhạc (2x2)
        LinearLayout media = new LinearLayout(a);
        media.setOrientation(LinearLayout.VERTICAL);
        media.setBackgroundResource(R.drawable.bg_panel);
        media.setGravity(Gravity.CENTER);
        TextView mt = new TextView(a);
        mt.setText("Điều khiển nhạc");
        mt.setTextColor(DIM);
        mt.setTextSize(15);
        mt.setGravity(Gravity.CENTER);
        media.addView(mt, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout btns = new LinearLayout(a);
        String[] g = {"⏮", "⏯", "⏭"};
        int[] codes = {KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT};
        for (int i = 0; i < 3; i++) {
            final int code = codes[i];
            TextView b = new TextView(a);
            b.setText(g[i]);
            b.setTextSize(28);
            b.setTextColor(i == 1 ? CYAN : WHITE);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> key(code));
            btns.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }
        media.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        place(media, 1, 0, 2, 2);

        // Độ sáng + âm lượng
        bright = new VSlider(a, "☀", 0xFFFFAB00, 255);
        bright.setListener((v, user) -> {
            if (!user) return;
            if (!Settings.System.canWrite(a)) { askWrite(); return; }
            try {
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, v));
            } catch (Exception ignored) { }
        });
        place(bright, 1, 2, 2, 1);

        volume = new VSlider(a, "🔊", CYAN, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setListener((v, user) -> {
            if (user) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        });
        place(volume, 1, 3, 2, 1);

        // Hàng 3: Bluetooth, máy bay, điểm phát sóng, khóa xoay
        Tile bt = tile("🔷", "Bluetooth", "", false);
        bind(bt, null, () -> go(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        place(bt.root, 3, 0, 1, 1);

        Tile air = tile("✈", "Máy bay", "", false);
        bind(air, () -> Settings.Global.getInt(a.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
                () -> go(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));
        place(air.root, 3, 1, 1, 1);

        Tile hot = tile("◉", "Phát Wi-Fi", "", false);
        bind(hot, null, () -> {
            Intent i = new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings");
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            } catch (Exception e) {
                go(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            }
        });
        place(hot.root, 3, 2, 1, 1);

        Tile rot = tile("🔒", "Khóa xoay", "", false);
        bind(rot, () -> Settings.System.getInt(a.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0, () -> {
            if (!Settings.System.canWrite(a)) { askedWrite = false; askWrite(); return; }
            int cur = Settings.System.getInt(a.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
            Settings.System.putInt(a.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, cur == 1 ? 0 : 1);
            refresh();
        });
        place(rot.root, 3, 3, 1, 1);

        // Hàng 4: chụp màn hình, chế độ tối, tiết kiệm pin, đèn pin
        Tile shot = tile("✂", "Chụp màn hình", "", false);
        bind(shot, null, () -> {
            if (ScreenshotService.instance != null) {
                closer.run();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ScreenshotService.instance != null)
                        ScreenshotService.instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                }, 500);
            } else {
                Toast.makeText(a, "Bật \"DK Home - Chụp màn hình\" trong Hỗ trợ tiếp cận", Toast.LENGTH_LONG).show();
                go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        place(shot.root, 4, 0, 1, 1);

        Tile dark = tile("🌓", "Chế độ tối", "", false);
        bind(dark, () -> (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES, () -> go(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        place(dark.root, 4, 1, 1, 1);

        Tile saver = tile("🔋", "Tiết kiệm pin", "", false);
        bind(saver, () -> power.isPowerSaveMode(), () -> go(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));
        place(saver.root, 4, 2, 1, 1);

        Tile torch = tile("🔦", "Đèn pin", "", false);
        bind(torch, () -> torchOn, () -> {
            if (torchId == null) {
                Toast.makeText(a, "Máy không có đèn flash", Toast.LENGTH_SHORT).show();
                return;
            }
            try { cam.setTorchMode(torchId, !torchOn); } catch (Exception ignored) { }
        });
        place(torch.root, 4, 3, 1, 1);

        // Hàng 5: vị trí, bảo vệ mắt
        Tile pos = tile("📍", "Vị trí", "", false);
        bind(pos, this::locationOn, () -> go(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        place(pos.root, 5, 0, 1, 1);

        Tile eye = tile("👁", "Bảo vệ mắt", "", false);
        bind(eye, null, () -> go(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        place(eye.root, 5, 1, 1, 1);

        // Theo dõi trạng thái đèn pin
        try {
            cam = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cam.getCameraIdList()) {
                CameraCharacteristics c = cam.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
            torchCb = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(torchId)) {
                        torchOn = enabled;
                        refresh();
                    }
                }
            };
            cam.registerTorchCallback(torchCb, null);
        } catch (Exception ignored) { }
    }

    void refresh() {
        askedWrite = false;
        for (Runnable r : refreshers) {
            try { r.run(); } catch (Exception ignored) { }
        }
        try {
            volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volume.setValue(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            bright.setValue(Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128));
        } catch (Exception ignored) { }
        Intent bat = a.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String op = tele.getNetworkOperatorName();
        String s = (op == null || op.isEmpty()) ? "Không có SIM" : op;
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0) s += "   •   Pin " + (level * 100 / scale) + "%";
        }
        info.setText(s);
    }

    void release() {
        try {
            if (cam != null && torchCb != null) cam.unregisterTorchCallback(torchCb);
        } catch (Exception ignored) { }
    }
}
