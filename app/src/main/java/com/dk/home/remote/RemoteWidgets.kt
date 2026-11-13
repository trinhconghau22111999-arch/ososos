package com.dk.home.remote

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Bang mau Gaming: nen den xanh dam, vien neon cyan / tim / hong, goc vat cheo. */
object Neon {
    const val BG = 0xFF0A0C12.toInt()
    const val PANEL = 0xFF131826.toInt()
    const val PANEL_HI = 0xFF1D2540.toInt()
    const val EDGE = 0xFF2C3658.toInt()
    const val EDGE_HI = 0xFF3F5190.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val MAGENTA = 0xFFFF2D95.toInt()
    const val VIOLET = 0xFF8A5CFF.toInt()
    const val AMBER = 0xFFFFB300.toInt()
    const val GREEN = 0xFF00E676.toInt()
    const val RED = 0xFFFF3B5C.toInt()
    const val BLUE = 0xFF2D8CFF.toInt()
    const val TEXT = 0xFFE8ECF8.toInt()
    const val DIM = 0xFF8590B0.toInt()
    const val INK = 0xFF05070C.toInt()
}

private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

/** Tron mau a sang mau b theo ty le t (0..1). */
private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
)

/** Hinh chu nhat vat cheo 2 goc (tren-trai va duoi-phai) - dac trung kieu gaming. */
private fun chamferPath(l: Float, t: Float, r: Float, b: Float, cut: Float, out: Path): Path {
    out.reset()
    out.moveTo(l + cut, t)
    out.lineTo(r, t)
    out.lineTo(r, b - cut)
    out.lineTo(r - cut, b)
    out.lineTo(l, b)
    out.lineTo(l, t + cut)
    out.close()
    return out
}

/** Nen khung vat goc: to mau + vien neon (co the co vang sang va luoi ke o). */
class NeonDrawable(
    private val fillColor: Int,
    private val edgeColor: Int,
    private val cutDp: Float = 10f,
    private val strokeDp: Float = 1.6f,
    private val glow: Boolean = true,
    private val grid: Boolean = false
) : Drawable() {
    private val d = Resources.getSystem().displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val inset = 3f * d
        val l = b.left + inset; val t = b.top + inset
        val r = b.right - inset; val bt = b.bottom - inset
        if (r <= l || bt <= t) return
        val cut = min(cutDp * d, min(r - l, bt - t) / 2.5f)
        chamferPath(l, t, r, bt, cut, path)

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, t, 0f, bt, mix(fillColor, Color.WHITE, 0.07f), fillColor, Shader.TileMode.CLAMP)
        c.drawPath(path, paint)
        paint.shader = null

        if (grid) {
            c.save()
            c.clipPath(path)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = withAlpha(edgeColor, 26)
            val step = 26f * d
            var x = l + step
            while (x < r) { c.drawLine(x, t, x, bt, paint); x += step }
            var y = t + step
            while (y < bt) { c.drawLine(l, y, r, y, paint); y += step }
            c.restore()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        if (glow) {
            paint.strokeWidth = 5f * d
            paint.color = withAlpha(edgeColor, 55)
            c.drawPath(path, paint)
        }
        paint.strokeWidth = strokeDp * d
        paint.color = edgeColor
        c.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Nut vat goc vien neon, icon tu ve (khong phu thuoc font nen khong bi o vuong loi).
 * Mau thuong: vien + icon cyan. Co [tint]: vien theo mau, nen pha mau, icon trang. [inverted]: dang khoa (hong).
 */
class IconButton(ctx: Context, private val kind: Int) : View(ctx) {
    var inverted = false
        set(v) { field = v; invalidate() }
    /** Mau nhan rieng (0 = mac dinh cyan). */
    var tint = 0
        set(v) { field = v; invalidate() }
    /** true: nen vat goc (icon ve theo nua canh ngan), false: nen tron co vong neon. */
    var rect = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init { isClickable = true }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) Haptics.tick(this)
        return super.onTouchEvent(e)
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 3f * d

        val edge: Int
        val bgColor: Int
        val iconColor: Int
        when {
            inverted -> { edge = Neon.MAGENTA; bgColor = mix(Neon.PANEL, Neon.MAGENTA, if (isPressed) 0.55f else 0.32f); iconColor = Neon.TEXT }
            tint != 0 -> { edge = tint; bgColor = mix(Neon.PANEL, tint, if (isPressed) 0.55f else 0.28f); iconColor = Neon.TEXT }
            else -> { edge = Neon.CYAN; bgColor = if (isPressed) Neon.PANEL_HI else Neon.PANEL; iconColor = Neon.CYAN }
        }

        fill.shader = null
        fill.color = bgColor
        ring.color = edge
        if (rect) {
            val inset = 3f * d
            chamferPath(inset, inset, width - inset, height - inset, 10f * d, path)
            fill.shader = LinearGradient(0f, inset, 0f, height - inset, mix(bgColor, Color.WHITE, 0.07f), bgColor, Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
            ring.strokeWidth = 5f * d; ring.color = withAlpha(edge, if (isPressed) 90 else 50)
            c.drawPath(path, ring)
            ring.strokeWidth = 1.8f * d; ring.color = edge
            c.drawPath(path, ring)
        } else {
            c.drawCircle(cx, cy, r, fill)
            ring.strokeWidth = 5f * d; ring.color = withAlpha(edge, if (isPressed) 90 else 50)
            c.drawCircle(cx, cy, r, ring)
            ring.strokeWidth = 1.8f * d; ring.color = edge
            c.drawCircle(cx, cy, r, ring)
        }

        line.color = iconColor
        line.strokeWidth = 2.8f * d
        if (kind == HOME) {
            // Nut ve man hinh chinh: hinh ngoi nha (mai + than + cua)
            val w = r * 0.46f
            c.drawPath(Path().apply {
                moveTo(cx - w * 1.25f, cy - w * 0.05f); lineTo(cx, cy - w * 1.15f); lineTo(cx + w * 1.25f, cy - w * 0.05f)
            }, line)
            c.drawPath(Path().apply {
                moveTo(cx - w * 0.85f, cy - w * 0.1f); lineTo(cx - w * 0.85f, cy + w * 0.95f)
                lineTo(cx + w * 0.85f, cy + w * 0.95f); lineTo(cx + w * 0.85f, cy - w * 0.1f)
            }, line)
            c.drawLine(cx, cy + w * 0.95f, cx, cy + w * 0.3f, line)
        } else if (kind == POWER) {
            val ri = r * 0.38f
            c.drawArc(RectF(cx - ri, cy - ri, cx + ri, cy + ri), -60f, 300f, false, line)
            c.drawLine(cx, cy - r * 0.46f, cx, cy - r * 0.04f, line)
        } else if (kind == REW || kind == FWD) {
            // Tua 10 giay: 2 tam giac cung huong + so "10" ben duoi
            fill.color = line.color
            val dir = if (kind == FWD) 1f else -1f
            val t = r * 0.22f
            for (k in 0..1) {
                val x = cx + dir * (k - 0.5f) * t * 1.7f
                val tri = Path().apply {
                    moveTo(x + dir * t, cy - r * 0.16f)
                    lineTo(x - dir * t * 0.8f, cy - r * 0.16f - t)
                    lineTo(x - dir * t * 0.8f, cy - r * 0.16f + t)
                    close()
                }
                c.drawPath(tri, fill)
            }
            fill.textSize = r * 0.40f; fill.textAlign = Paint.Align.CENTER
            fill.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            c.drawText("10", cx, cy + r * 0.62f, fill)
        } else if (kind == PLAY) {
            // Phat / dung trong 1 nut: tam giac + 2 vach
            fill.color = line.color
            val t = r * 0.30f
            val tx = cx - r * 0.24f
            c.drawPath(Path().apply {
                moveTo(tx + t, cy); lineTo(tx - t * 0.8f, cy - t * 1.1f); lineTo(tx - t * 0.8f, cy + t * 1.1f); close()
            }, fill)
            val bw = r * 0.10f
            c.drawRect(cx + r * 0.22f, cy - t * 1.0f, cx + r * 0.22f + bw, cy + t * 1.0f, fill)
            c.drawRect(cx + r * 0.46f, cy - t * 1.0f, cx + r * 0.46f + bw, cy + t * 1.0f, fill)
        } else if (kind == NEXT) {
            // Bai/video ke tiep: tam giac + vach dung
            fill.color = line.color
            val t = r * 0.30f
            val tx = cx - r * 0.08f
            c.drawPath(Path().apply {
                moveTo(tx + t, cy); lineTo(tx - t * 0.9f, cy - t * 1.1f); lineTo(tx - t * 0.9f, cy + t * 1.1f); close()
            }, fill)
            c.drawRect(cx + r * 0.34f, cy - t * 1.1f, cx + r * 0.34f + r * 0.11f, cy + t * 1.1f, fill)
        } else if (kind == FULL) {
            // Phong to: 4 goc khung huong ra ngoai
            val o = r * 0.50f; val l = r * 0.24f
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                val px = cx + sx * o; val py = cy + sy * o
                c.drawLine(px, py, px - sx * l, py, line)
                c.drawLine(px, py, px, py - sy * l, line)
            }
        } else if (kind == TOUCH) {
            // Cham: cham tron + 2 vong lan toa; dang khoa (inverted) them net gach cheo
            fill.color = line.color
            c.drawCircle(cx, cy, r * 0.10f, fill)
            line.strokeWidth = 2.4f * d
            c.drawCircle(cx, cy, r * 0.30f, line)
            c.drawCircle(cx, cy, r * 0.52f, line)
            if (inverted) {
                line.strokeWidth = 3.2f * d
                c.drawLine(cx - r * 0.58f, cy + r * 0.58f, cx + r * 0.58f, cy - r * 0.58f, line)
            }
        } else {
            val cap = RectF(cx - r * 0.14f, cy - r * 0.50f, cx + r * 0.14f, cy + r * 0.06f)
            c.drawRoundRect(cap, r * 0.14f, r * 0.14f, line)
            c.drawArc(RectF(cx - r * 0.30f, cy - r * 0.34f, cx + r * 0.30f, cy + r * 0.26f), 0f, 180f, false, line)
            c.drawLine(cx, cy + r * 0.26f, cx, cy + r * 0.46f, line)
            c.drawLine(cx - r * 0.16f, cy + r * 0.46f, cx + r * 0.16f, cy + r * 0.46f, line)
        }
    }

    companion object { const val POWER = 0; const val MIC = 1; const val TOUCH = 2; const val FULL = 3; const val REW = 4; const val FWD = 5; const val PLAY = 6; const val NEXT = 7; const val HOME = 8 }
}


/** 5 phim dieu huong tren khung chuot: nen toi vat goc vien neon, mui ten cyan; OK to gradient cyan-tim. */
class DPadButton(ctx: Context, private val kind: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.INK; textAlign = Paint.Align.CENTER; textSize = 22f * d
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.08f
    }
    private val path = Path()

    init { isClickable = true }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) Haptics.tick(this)
        return super.onTouchEvent(e)
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(c: Canvas) {
        val inset = 3f * d
        val cut = (if (kind == OK) 16f else 11f) * d
        chamferPath(inset, inset, width - inset, height - inset, cut, path)

        val edge: Int
        if (kind == OK) {
            fill.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Neon.CYAN, Neon.VIOLET, Shader.TileMode.CLAMP)
            fill.alpha = if (isPressed) 190 else 255
            edge = Neon.CYAN
        } else {
            fill.shader = null
            fill.color = if (isPressed) Neon.PANEL_HI else Neon.PANEL
            edge = if (isPressed) Neon.CYAN else Neon.EDGE_HI
        }
        c.drawPath(path, fill)
        fill.shader = null

        stroke.strokeWidth = 5f * d; stroke.color = withAlpha(edge, if (isPressed || kind == OK) 70 else 30)
        c.drawPath(path, stroke)
        stroke.strokeWidth = 1.8f * d; stroke.color = edge
        c.drawPath(path, stroke)

        val cx = width / 2f; val cy = height / 2f
        if (kind == OK) {
            c.drawText("OK", cx, cy - (text.descent() + text.ascent()) / 2f, text)
            return
        }
        ink.color = if (isPressed) Color.WHITE else Neon.CYAN
        val a = min(width, height) * 0.22f // nua canh tam giac
        val p = Path()
        when (kind) {
            UP -> { p.moveTo(cx, cy - a); p.lineTo(cx - a, cy + a * 0.7f); p.lineTo(cx + a, cy + a * 0.7f) }
            DOWN -> { p.moveTo(cx, cy + a); p.lineTo(cx - a, cy - a * 0.7f); p.lineTo(cx + a, cy - a * 0.7f) }
            LEFT -> { p.moveTo(cx - a, cy); p.lineTo(cx + a * 0.7f, cy - a); p.lineTo(cx + a * 0.7f, cy + a) }
            else -> { p.moveTo(cx + a, cy); p.lineTo(cx - a * 0.7f, cy - a); p.lineTo(cx - a * 0.7f, cy + a) }
        }
        p.close()
        c.drawPath(p, ink)
    }

    companion object { const val UP = 0; const val DOWN = 1; const val LEFT = 2; const val RIGHT = 3; const val OK = 4 }
}
