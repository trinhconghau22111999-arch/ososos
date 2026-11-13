package com.dk.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Khung vát góc (trên-trái và dưới-phải) với viền neon, kiểu HUD gaming. */
final class Chamfer extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float cut, sw, cell;
    private final boolean grid, glowOn;
    private final int g1, g2;

    Chamfer(Context c, int fillC, int strokeC, float cutDp, boolean grid, boolean glowOn, int g1, int g2) {
        float d = c.getResources().getDisplayMetrics().density;
        cut = cutDp * d;
        sw = 1.6f * d;
        cell = 22 * d;
        this.grid = grid;
        this.glowOn = glowOn;
        this.g1 = g1;
        this.g2 = g2;
        fill.setColor(fillC);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(sw);
        line.setColor(strokeC);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(sw * 3.2f);
        glow.setColor((strokeC & 0x00FFFFFF) | 0x33000000);
        gridP.setStyle(Paint.Style.STROKE);
        gridP.setStrokeWidth(d);
        gridP.setColor(0x1400E5FF);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float s = sw * 1.6f, l = s, t = s, r = b.width() - s, bt = b.height() - s;
        path.reset();
        path.moveTo(l + cut, t);
        path.lineTo(r, t);
        path.lineTo(r, bt - cut);
        path.lineTo(r - cut, bt);
        path.lineTo(l, bt);
        path.lineTo(l, t + cut);
        path.close();
        if (g1 != 0) fill.setShader(new LinearGradient(0, 0, b.width(), b.height(), g1, g2, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.save();
        canvas.translate(b.left, b.top);
        if (glowOn) canvas.drawPath(path, glow);
        canvas.drawPath(path, fill);
        if (grid) {
            canvas.save();
            canvas.clipPath(path);
            for (float x = cell; x < b.width(); x += cell) canvas.drawLine(x, 0, x, b.height(), gridP);
            for (float y = cell; y < b.height(); y += cell) canvas.drawLine(0, y, b.width(), y, gridP);
            canvas.restore();
        }
        canvas.drawPath(path, line);
        canvas.restore();
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
