package com.dk.home;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import java.util.Locale;

/** Bảng thiết kế gaming: màu, khung, kiểu chữ. */
final class Ui {
    static final int CYAN = 0xFF00E5FF, PINK = 0xFFFF2D8A, AMBER = 0xFFFFAB00, DIM = 0xFF7F8BC0, INK = 0xFF0D1224;
    static final int PLAIN = 0, ACCENT = 1, HOT = 2, BLUE = 3, HERO = 4, GRID = 5;

    private Ui() { }

    static Drawable panel(Context c, int style) {
        return panel(c, style, style == GRID ? 18 : 10);
    }

    static Drawable panel(Context c, int style, float cutDp) {
        switch (style) {
            case ACCENT: return new Chamfer(c, 0xFF0A3B4D, CYAN, cutDp, false, true, 0, 0);
            case HOT: return new Chamfer(c, 0xFF4A1730, PINK, cutDp, false, true, 0, 0);
            case BLUE: return new Chamfer(c, 0xFF1B2F66, 0xFF2F6BFF, cutDp, false, true, 0, 0);
            case HERO: return new Chamfer(c, 0xFF2EB8FF, CYAN, cutDp, false, true, 0xFF2EB8FF, 0xFF7B6CFF);
            case GRID: return new Chamfer(c, 0xFF0C1430, CYAN, cutDp, true, true, 0, 0);
            default: return new Chamfer(c, 0xFF121A33, 0xFF2C3A78, cutDp, false, false, 0, 0);
        }
    }

    static String up(CharSequence s) {
        return s == null ? "" : s.toString().toUpperCase(new Locale("vi"));
    }

    static void style(TextView t) {
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setLetterSpacing(0.08f);
    }
}
