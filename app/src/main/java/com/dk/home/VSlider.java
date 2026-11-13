package com.dk.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Thanh trượt dọc kiểu Trung tâm điều khiển. */
public class VSlider extends View {
    public interface Listener { void onChange(int value, boolean fromUser); }

    private int max;
    private int value;
    private final String glyph;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private Listener listener;

    public VSlider(Context c, String glyph, int color, int max) {
        super(c);
        this.glyph = glyph;
        this.max = Math.max(1, max);
        float d = c.getResources().getDisplayMetrics().density;
        bg.setColor(0xFF121A33);
        fill.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * d);
        stroke.setColor(0xFF2C3A78);
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
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = w / 2.2f, s = stroke.getStrokeWidth() / 2;
        rect.set(s, s, w - s, h - s);
        canvas.drawRoundRect(rect, r, r, bg);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        float top = h * (1f - (float) value / max);
        canvas.drawRect(0, top, w, h, fill);
        canvas.restore();
        canvas.drawRoundRect(rect, r, r, stroke);
        text.setColor((float) value / max > 0.15f ? 0xFF0D1224 : 0xFFFFFFFF);
        canvas.drawText(glyph, w / 2, h - w * 0.35f, text);
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
