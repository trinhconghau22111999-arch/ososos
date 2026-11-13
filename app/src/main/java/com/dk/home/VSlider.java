package com.dk.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Thanh trượt dọc vát góc, tô gradient neon. */
public class VSlider extends View {
    public interface Listener { void onChange(int value, boolean fromUser); }

    private int max;
    private int value;
    private final String glyph;
    private final int c1, c2;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Listener listener;

    public VSlider(Context c, String glyph, int c1, int c2, int max) {
        super(c);
        this.glyph = glyph;
        this.c1 = c1;
        this.c2 = c2;
        this.max = Math.max(1, max);
        float d = c.getResources().getDisplayMetrics().density;
        bg.setColor(0xFF0C1430);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * d);
        stroke.setColor(c1);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(5f * d);
        glow.setColor((c1 & 0x00FFFFFF) | 0x33000000);
        text.setTextSize(26 * d);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener l) { listener = l; }

    public void setMax(int m) { max = Math.max(1, m); invalidate(); }

    public void setValue(int v) {
        value = Math.max(0, Math.min(max, v));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = stroke.getStrokeWidth() * 1.6f, cut = w * 0.3f;
        path.reset();
        path.moveTo(s + cut, s);
        path.lineTo(w - s, s);
        path.lineTo(w - s, h - s - cut);
        path.lineTo(w - s - cut, h - s);
        path.lineTo(s, h - s);
        path.lineTo(s, s + cut);
        path.close();
        fill.setShader(new LinearGradient(0, h, 0, 0, c1, c2, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        canvas.drawPath(path, glow);
        canvas.drawPath(path, bg);
        canvas.save();
        canvas.clipPath(path);
        canvas.drawRect(0, h * (1f - (float) value / max), w, h, fill);
        canvas.restore();
        canvas.drawPath(path, stroke);
        text.setColor(0xFFFFFFFF);
        canvas.drawText(glyph, w / 2, h - w * 0.4f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            float frac = 1f - e.getY() / Math.max(1, getHeight());
            value = Math.max(0, Math.min(max, Math.round(frac * max)));
            invalidate();
            if (listener != null) listener.onChange(value, true);
            return true;
        }
        return a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
    }
}
