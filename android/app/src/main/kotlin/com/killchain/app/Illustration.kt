package com.killchain.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small animated pictures for the first-run guide, drawn with the game's own symbols. */
@SuppressLint("ViewConstructor")
class Illustration(ctx: Context, private val g: Game, private val page: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val fl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = g.ui.mono; textSize = 10f }
    private val t0 = SystemClock.uptimeMillis()

    private fun col(c: Int, a: Double) = (c and 0xffffff) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
    private fun label(c: Canvas, s: String, x: Float, y: Float, color: Int, size: Float = 10f, align: Paint.Align = Paint.Align.LEFT) {
        tx.textSize = size; tx.textAlign = align; tx.color = color; c.drawText(s, x, y, tx)
    }

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000.0
        val w = width / d; val h = height / d
        c.save(); c.scale(d, d)
        val cx = w / 2; val cy = h / 2; val R = min(w, h) * 0.42f
        // Scope background
        fl.shader = RadialGradient(cx, cy, R * 1.2f, Color.parseColor("#082030"), Color.parseColor("#01050a"), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 1.15f, fl); fl.shader = null
        st.pathEffect = null
        for (k in listOf(1f, 0.66f, 0.33f)) { st.color = col(C.CYAN, if (k == 1f) 0.45 else 0.18); c.drawCircle(cx, cy, R * k, st) }
        val R2 = R
        when (page) {
            0 -> {
                sweep(c, cx, cy, R2, t, C.IN)
                val a1 = t * 0.35; val a2 = -t * 0.28 + 2.5
                g.R.glyph("air", "fighter", cx + cos(a1).toFloat() * R * 0.55f, cy + sin(a1).toFloat() * R * 0.55f, a1 + PI, C.IN, glow = true, type = "RAFALE", size = 1.6f, cv = c)
                g.R.glyph("air", "fighter", cx + cos(a2).toFloat() * R * 0.75f, cy + sin(a2).toFloat() * R * 0.75f, a2, C.PK, hollow = true, glow = true, type = "J10C", size = 1.6f, cv = c)
                label(c, "INDIA", cx - R, cy + R + 14, C.IN); label(c, "PAKISTAN", cx + R, cy + R + 14, C.PK, align = Paint.Align.RIGHT)
            }
            1 -> {
                val bx = cx - R * 0.8f; val by = cy + R * 0.35f
                st.color = C.IN; c.drawRect(bx - 5, by - 5, bx + 5, by + 5, st); fl.color = C.IN; c.drawRect(bx - 3, by - 3, bx + 3, by + 3, fl)
                label(c, "AMBALA", bx, by + 18, C.IN, 9f, Paint.Align.CENTER)
                val tx0 = cx + R * 0.7f; val ty0 = cy - R * 0.45f
                val pulse = (0.5 + 0.5 * sin(t * 3)).toFloat()
                st.color = C.RED; c.drawCircle(tx0, ty0, 14 + pulse * 3, st)
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) c.drawLine(tx0 + dx * 10, ty0 + dy * 10, tx0 + dx * 22, ty0 + dy * 22, st)
                label(c, "TARGET", tx0, ty0 - 28, C.RED, 9f, Paint.Align.CENTER)
                val k = ((t % 6) / 6).toFloat()
                val hdg = Math.atan2((tx0 - bx).toDouble(), -(ty0 - by).toDouble())
                val jx = bx + (tx0 - bx) * k; val jy = by + (ty0 - by) * k
                st.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), (-t * 16 % 8).toFloat()); st.color = col(C.RED, 0.6)
                c.drawLine(jx, jy, tx0, ty0, st); st.pathEffect = null
                for ((i, o) in listOf(0f to 0f, -14f to 12f, 14f to 9f).withIndex()) {
                    val fx = sin(hdg).toFloat(); val fy = -cos(hdg).toFloat()
                    g.R.glyph("air", "fighter", jx - fy * o.first - fx * o.second, jy + fx * o.first - fy * o.second, hdg, C.IN, glow = true, type = if (i == 0) "RAFALE" else "RAFALE", size = 1.3f, cv = c)
                }
                label(c, "GROUND ATTACK + 2 ESCORTS", cx, cy + R + 14, C.HOT, 10f, Paint.Align.CENTER)
            }
            2 -> {
                val ax = cx - R * 0.55f; val ay = cy - R * 0.1f
                sweep(c, ax, ay, R * 0.8f, t, C.PK)
                g.R.glyph("air", "aew", ax, ay, t * 0.2, C.PK, cv = c)
                label(c, "AEW&C", ax, ay + 22, C.PK, 9f, Paint.Align.CENTER)
                val on = Pair(cx + R * 0.45f, cy - R * 0.45f); val off = Pair(cx + R * 0.55f, cy + R * 0.35f)
                st.pathEffect = DashPathEffect(floatArrayOf(2f, 4f), (-t * 12 % 6).toFloat()); st.color = col(C.PK, 0.5)
                c.drawLine(ax, ay, on.first, on.second, st); c.drawLine(ax, ay, off.first, off.second, st); st.pathEffect = null
                // Radar-on jet with its cone
                val h1 = -PI / 2
                fl.shader = RadialGradient(on.first, on.second, R * 0.6f, col(C.PK, 0.3), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawArc(on.first - R * 0.6f, on.second - R * 0.6f, on.first + R * 0.6f, on.second + R * 0.6f, 180f - 60f + 0f, 120f, true, fl); fl.shader = null
                g.R.glyph("air", "fighter", on.first, on.second, h1, C.PK, glow = true, type = "J10C", size = 1.5f, cv = c)
                g.R.glyph("air", "fighter", off.first, off.second, h1, C.PK, hollow = true, type = "J10C", size = 1.5f, cv = c)
                label(c, "RADAR ON", on.first, on.second + 22, C.PK, 9f, Paint.Align.CENTER)
                label(c, "SILENT", off.first, off.second + 22, C.PK, 9f, Paint.Align.CENTER)
                label(c, "DATA LINK", cx, cy + R + 14, C.TEXT, 10f, Paint.Align.CENTER)
            }
            3 -> {
                val k = ((t % 7) / 7).toFloat()
                val jx = cx + R * (0.15f + 0.55f * k); val jy = cy - R * 0.1f
                val mx = cx - R * 0.8f + R * 1.25f * (1 - (1 - k) * (1 - k)) * 0.75f; val my = cy - R * 0.1f + sin(k * 6).toFloat() * 4
                val lethal = k < 0.45f
                st.color = if (lethal) C.RED else C.DIM; st.strokeWidth = if (lethal) 1.5f else 1f
                st.pathEffect = if (lethal) null else DashPathEffect(floatArrayOf(3f, 4f), 0f)
                c.drawLine(mx, my, jx, jy, st); st.pathEffect = null; st.strokeWidth = 1.2f
                for (i in 0 until 12) { st.color = col(C.PK, 0.06 * i); c.drawLine(mx - (12 - i) * 5f, my, mx - (11 - i) * 5f, my, st) }
                fl.color = C.WHITE; c.drawCircle(mx, my, 2.2f, fl)
                g.R.glyph("air", "fighter", jx, jy, PI / 2, C.IN, glow = true, type = "RAFALE", size = 1.6f, cv = c)
                val mach = 4.2 - 2.6 * k
                label(c, "PL-15E M${"%.1f".format(mach)} ${if (lethal) "CLOSING" else "LOSING ENERGY"}", mx, my + 18, if (lethal) C.RED else C.DIM, 9.5f)
                label(c, if (k > 0.6f) "DEFEATED" else "BREAK!", cx, cy + R + 14, if (k > 0.6f) C.PK else C.HOT, 11f, Paint.Align.CENTER)
            }
            else -> {
                val pulse = (0.5 + 0.5 * sin(t * 3)).toFloat()
                st.color = C.RED; c.drawCircle(cx, cy - 8, 16 + pulse * 2, st)
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) c.drawLine(cx + dx * 12, cy - 8 + dy * 12, cx + dx * 24, cy - 8 + dy * 24, st)
                val hits = ((t % 6) / 1.5).toInt().coerceAtMost(3)
                for (i in 0 until 3) {
                    val px = cx - 12 + i * 9f
                    st.color = C.RED; c.drawRect(px, cy - 42, px + 6, cy - 36, st)
                    if (i < hits) { fl.color = C.RED; c.drawRect(px, cy - 42, px + 6, cy - 36, fl) }
                }
                label(c, if (hits >= 3) "DESTROYED +10" else "$hits / 3 HITS", cx, cy + 32, if (hits >= 3) C.PK else C.RED, 10f, Paint.Align.CENTER)
                val esc = ((t % 6) / 6 * 70 + 10).toFloat()
                val bw = R * 1.3f; val bx = cx - bw / 2; val by = cy + R * 0.7f
                st.color = C.LINE_HI; c.drawRect(bx, by, bx + bw, by + 6, st)
                fl.color = if (esc > 60) C.HOT else C.PK; c.drawRect(bx, by, bx + bw * esc / 100, by + 6, fl)
                label(c, "ESCALATION ${esc.toInt()}%  (100% = BOTH LOSE)", cx, by + 20, C.TEXT, 9.5f, Paint.Align.CENTER)
            }
        }
        c.restore()
        postInvalidateOnAnimation()
    }

    private fun sweep(c: Canvas, x: Float, y: Float, r: Float, t: Double, color: Int) {
        val a = (t / 4 * 360) % 360
        for (i in 0 until 12) {
            fl.color = col(color, 0.12 * (1 - i / 12.0))
            c.drawArc(x - r, y - r, x + r, y + r, (a - (i + 1) * 4).toFloat(), 4.2f, true, fl)
        }
        st.color = col(color, 0.7)
        c.drawLine(x, y, x + cos(Math.toRadians(a)).toFloat() * r, y + sin(Math.toRadians(a)).toFloat() * r, st)
    }
}
