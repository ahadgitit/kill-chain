package com.killchain.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import com.killchain.core.DEG
import com.killchain.core.Data
import com.killchain.core.ENEMY
import com.killchain.core.EW
import com.killchain.core.Ent
import com.killchain.core.FORMATION
import com.killchain.core.Frame
import com.killchain.core.Geo
import com.killchain.core.Missile
import com.killchain.core.Pt
import com.killchain.core.SAM_RELOAD
import com.killchain.core.Sim
import com.killchain.core.Track
import com.killchain.core.Unit
import com.killchain.core.XY
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Map colours. */
object MC {
    val sea0 = Color.parseColor("#03101a"); val sea1 = Color.parseColor("#01050a")
    val landIN = Color.parseColor("#140e07"); val landPK = Color.parseColor("#06150f"); val landX = Color.parseColor("#08151c")
    val dots = Color.argb(51, 90, 170, 200); val dotsIN = Color.argb(33, 255, 154, 60); val dotsPK = Color.argb(31, 51, 229, 140)
    val grid = Color.argb(26, 70, 150, 190); val gridText = Color.argb(115, 110, 170, 200)
    val river = Color.parseColor("#0c3a55"); val coast = Color.parseColor("#2c9cc4"); val border = Color.parseColor("#2b5a6e")
    val front = Color.parseColor("#5fe0ff"); val city = Color.parseColor("#4f7383")
}

class SP(var x: Float, var y: Float)

/** What the renderer needs to know about the player's UI. */
class Plan(var kind: String, val unit: Unit? = null, val base: String? = null, val type: String? = null, val n: Int = 0) {
    var target: com.killchain.core.Target? = null
    var prey: Ent? = null
    var escortUnit: Unit? = null
    var via: MutableList<Pt> = mutableListOf()
    var addingWp = false
    var escortN: Int? = null
}
class Mark(val x: Double, val y: Double, val t: Double)
class View2(var cx: Double, var cy: Double, var scale: Double) { fun copy() = View2(cx, cy, scale) }

/**
 * Port of js/render.js to android.graphics. All drawing is in dp ("CSS pixels"), like the web
 * version; the static map is cached in a bitmap rendered off the UI thread once the camera settles.
 */
class Renderer(private val density: Float, private val mono: Typeface) {
    val view = View2(0.0, 0.0, 1.0)
    var w = 0f; var h = 0f
    var showSensors = true
    var frame = 0
    var hudInset = 0f
    private var flight: Flight? = null
    private class Flight(val from: View2, val to: View2, val t0: Double, val ms: Double)

    // Per-aircraft drawing state (formation easing, where each jet was drawn).
    class VS { var fw = 0.0; var fwT = 0.0; var jetOff: SP? = null; var scr: SP? = null; var scrF = -1 }
    private val vs = HashMap<Ent, VS>()
    fun vsOf(e: Ent) = vs.getOrPut(e) { VS() }
    fun forget() = vs.clear()
    fun drawnAt(e: Ent): SP? { val v = vs[e] ?: return null; return if (v.scrF == frame) v.scr else null }

    val bounds: RectF

    init {
        val b = Geo.bbox
        val lo0 = b[0]; val la0 = b[1]; val lo1 = b[2]; val la1 = b[3]
        val pts = ArrayList<Pt>()
        var la = la0; while (la <= la1) { pts.add(Geo.project(la, lo0)); pts.add(Geo.project(la, lo1)); la += 2 }
        var lo = lo0; while (lo <= lo1) { pts.add(Geo.project(la0, lo)); pts.add(Geo.project(la1, lo)); lo += 2 }
        bounds = RectF(pts.minOf { it.x }.toFloat(), pts.minOf { it.y }.toFloat(), pts.maxOf { it.x }.toFloat(), pts.maxOf { it.y }.toFloat())
    }

    private fun now() = SystemClock.uptimeMillis().toDouble()

    fun resize(wDp: Float, hDp: Float) { w = wDp; h = hDp; mapKey = "" }

    fun viewFor(lat0: Double, lon0: Double, lat1: Double, lon1: Double, pad: Double): View2 {
        val a = Geo.project(lat0, lon0); val b = Geo.project(lat1, lon1)
        val wk = abs(b.x - a.x); val hk = abs(b.y - a.y)
        return View2((a.x + b.x) / 2, (a.y + b.y) / 2, min((w - pad * 2) / wk, (h - pad * 2) / hk))
    }
    fun fit(r: DoubleArray, pad: Double) { if (w <= 0 || h <= 0) return; flight = null; val v = viewFor(r[0], r[1], r[2], r[3], pad); view.cx = v.cx; view.cy = v.cy; view.scale = v.scale }
    fun flyTo(target: View2, ms: Double = 2000.0) {
        if (!(view.scale > 0) || !(target.scale > 0)) { if (target.scale > 0) { view.cx = target.cx; view.cy = target.cy; view.scale = target.scale }; return }
        flight = Flight(view.copy(), target, now(), ms) }
    fun stopFlight() { flight = null }
    private fun stepFlight() {
        val f = flight ?: return
        val k = min(1.0, (now() - f.t0) / f.ms); val e = ease(k)
        val ls = ln(f.from.scale) + (ln(f.to.scale) - ln(f.from.scale)) * e
        view.cx = f.from.cx + (f.to.cx - f.from.cx) * e
        view.cy = f.from.cy + (f.to.cy - f.from.cy) * e
        view.scale = exp(ls)
        if (k >= 1) flight = null
    }
    private fun clampView() {
        val v = view; val b = bounds
        val minScale = min(w / b.width(), h / b.height()) * 0.95
        v.scale = max(minScale, min(14.0, v.scale))
        val hw = w / 2 / v.scale; val hh = h / 2 / v.scale
        v.cx = if (b.width() < hw * 2) (b.left + b.right) / 2.0 else max(b.left + hw, min(b.right - hw, v.cx))
        v.cy = if (b.height() < hh * 2) (b.top + b.bottom) / 2.0 else max(b.top + hh, min(b.bottom - hh, v.cy))
    }
    fun toScreen(x: Double, y: Double) = SP(((x - view.cx) * view.scale + w / 2).toFloat(), ((y - view.cy) * view.scale + h / 2).toFloat())
    fun toWorld(sx: Float, sy: Float) = Pt((sx - w / 2) / view.scale + view.cx, (sy - h / 2) / view.scale + view.cy)
    fun pan(dx: Float, dy: Float) { flight = null; view.cx -= dx / view.scale; view.cy -= dy / view.scale }
    fun zoomAt(sx: Float, sy: Float, f: Double) {
        flight = null
        val before = toWorld(sx, sy)
        view.scale = max(0.03, min(14.0, view.scale * f))
        val after = toWorld(sx, sy)
        view.cx += before.x - after.x; view.cy += before.y - after.y
    }

    // ---------- paints & helpers ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val path = Path()
    private lateinit var c: Canvas

    private fun a(col: Int, alpha: Double) = (col and 0x00ffffff) or ((alpha.coerceIn(0.0, 1.0) * ((col ushr 24) and 0xff)).toInt() shl 24)
    private fun dash(vararg d: Float, phase: Float = 0f) = DashPathEffect(d, phase)

    private fun strokeP(col: Int, alpha: Double = 1.0, width: Float = 1f, d: DashPathEffect? = null): Paint {
        stroke.color = a(col, alpha); stroke.strokeWidth = width; stroke.pathEffect = d; stroke.xfermode = null; stroke.maskFilter = null
        return stroke
    }
    private fun fillP(col: Int, alpha: Double = 1.0): Paint { fill.color = a(col, alpha); fill.shader = null; fill.xfermode = null; return fill }

    private fun circle(x: Double, y: Double, rKm: Double, col: Int, alpha: Double, d: FloatArray? = null, fillA: Double = 0.0) {
        val s = toScreen(x, y); val r = (rKm * view.scale).toFloat()
        if (fillA > 0) c.drawCircle(s.x, s.y, r, fillP(col, fillA))
        c.drawCircle(s.x, s.y, r, strokeP(col, alpha, 1f, d?.let { DashPathEffect(it, 0f) }))
    }

    private fun label(text: String, sx: Float, sy: Float, col: Int, align: Paint.Align = Paint.Align.LEFT, size: Float = 10f, alpha: Double = 1.0, cv: Canvas = c, p: Paint = txt) {
        p.textSize = size; p.textAlign = align
        val fm = p.fontMetrics
        val y = sy - (fm.ascent + fm.descent) / 2
        p.color = a(Color.BLACK, alpha * 0.65); cv.drawText(text, sx + 1, y + 1, p)
        p.color = a(col, alpha); cv.drawText(text, sx, y, p)
    }
    private fun spaced(cv: Canvas, p: Paint, text: String, x: Float, y: Float, size: Float, spacing: Float, col: Int, alpha: Double) {
        p.textSize = size; p.textAlign = Paint.Align.LEFT
        val widths = FloatArray(text.length); p.getTextWidths(text, widths)
        val total = widths.sum() + spacing * (text.length - 1)
        var px = x - total / 2
        val fm = p.fontMetrics; val yy = y - (fm.ascent + fm.descent) / 2
        p.color = a(col, alpha)
        for ((i, ch) in text.withIndex()) { cv.drawText(ch.toString(), px, yy, p); px += widths[i] + spacing }
    }

    // ---------- frame ----------
    /** Draws a live game ([sim] null: the map only). */
    /** Never draw from a broken camera (e.g. a fit made before the view had a size). */
    private fun sane() {
        if (!(view.scale > 0) || view.cx.isNaN() || view.cy.isNaN() || view.scale.isInfinite()) {
            flight = null; view.cx = 0.0; view.cy = 0.0; view.scale = 0.2
        }
    }
    fun draw(cv: Canvas, sim: Sim?, ui: UiState) {
        if (w <= 0 || h <= 0) return
        sane()
        frame++
        stepFlight(); clampView()
        c = cv
        cv.save()
        cv.scale(density, density)
        drawMapLayer(cv)
        if (sim == null) { drawHud(ui); cv.restore(); return }
        val side = ui.side
        val spectator = side == "ALL"
        drawSites(sim, ui)
        drawBases(sim, ui)
        drawJamming(sim)
        if (!spectator) drawObjectives(sim, ui)
        if (showSensors) for (s in if (spectator) listOf("IN", "PK") else listOf(side)) drawSensors(sim, s, ui, spectator)
        drawSweeps(sim, if (spectator) null else side)
        if (!spectator) drawOrders(sim, ui)
        drawMissiles(sim, side)
        for (s in if (spectator) listOf("IN", "PK") else listOf(side)) drawUnits(sim, s, ui)
        if (!spectator) drawTracks(sim, side, ui)
        drawFx(sim, ui)
        drawHud(ui)
        cv.restore()
    }

    // ---------- the static map ----------
    private var mapKey = ""
    private var movedAt = 0.0
    private var cache: Bitmap? = null
    private var cacheKey = ""
    @Volatile private var pending: String? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "map").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val fastMap = MapPainter(density, mono, false)
    private val fullMap = MapPainter(density, mono, true)
    var onMapReady: (() -> kotlin.Unit)? = null

    private fun drawMapLayer(cv: Canvas) {
        val key = "${"%.2f".format(view.cx)},${"%.2f".format(view.cy)},${"%.5f".format(view.scale)},$w,$h"
        val t = now()
        if (key != mapKey) { mapKey = key; movedAt = t }
        val bmp = cache
        if (bmp != null && cacheKey == key) {
            cv.save(); cv.scale(1 / density, 1 / density); cv.drawBitmap(bmp, 0f, 0f, null); cv.restore()
            return
        }
        fastMap.paint(cv, view.copy(), w, h)
        // Once the camera has been still for a moment, render the full-quality map in the background.
        if (t - movedAt > 140 && pending != key && w > 0) {
            pending = key
            val v = view.copy(); val ww = w; val hh = h
            val pw = (ww * density).toInt().coerceAtLeast(1); val ph = (hh * density).toInt().coerceAtLeast(1)
            worker.execute {
                if (pending != key) return@execute
                val out = try { Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null } ?: return@execute
                val bc = Canvas(out)
                bc.scale(density, density)
                fullMap.paint(bc, v, ww, hh)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (pending == key) { cache?.recycle(); cache = out; cacheKey = key; onMapReady?.invoke() } else out.recycle()
                }
            }
        }
    }
    fun release() { worker.shutdownNow() }

    // ---------- live layers ----------
    private fun drawSites(sim: Sim, ui: UiState) {
        val pulse = 0.5 + 0.5 * sin(now() / 400)
        for (s in sim.sites) {
            val p = toScreen(s.x, s.y)
            val sel = ui.sel === s
            path.reset(); path.moveTo(p.x, p.y - 6); path.lineTo(p.x + 6, p.y); path.lineTo(p.x, p.y + 6); path.lineTo(p.x - 6, p.y); path.close()
            c.drawPath(path, strokeP(C.RED, 1.0, if (sel) 2f else 1.3f))
            if (s.hits > 0) {
                c.drawPath(path, fillP(C.RED))
                c.drawCircle(p.x, p.y, (9 + pulse * 3).toFloat(), strokeP(C.RED, 0.25 + 0.25 * pulse, 1.3f))
            }
            if (view.scale > 0.25 || sel) label("${s.name}${if (s.hits > 0) " ×" + s.hits else ""}", p.x + 9, p.y, C.RED, size = 9f)
        }
    }

    private fun drawBases(sim: Sim, ui: UiState) {
        for (b in sim.bases) {
            val own = b.side == ui.side || ui.side == "ALL"
            val p = toScreen(b.x, b.y)
            if (p.x < -20 || p.x > w + 20 || p.y < -20 || p.y > h + 20) continue
            val col = C.side(b.side)
            val al = if (own) 0.9 else 0.45
            c.drawRect(p.x - 3.5f, p.y - 3.5f, p.x + 3.5f, p.y + 3.5f, strokeP(col, al))
            val ready = b.pools.values.sumOf { it.ready }
            if (own && ready > 0) c.drawRect(p.x - 2, p.y - 2, p.x + 2, p.y + 2, fillP(col, al))
            if (b.damage > 0) {
                val sp = strokeP(C.RED, 0.9, 2f)
                c.drawArc(p.x - 8, p.y - 8, p.x + 8, p.y + 8, -90f, (min(1.0, b.damage / 60) * 360).toFloat(), false, sp)
                if (b.damage >= 60) { c.drawLine(p.x - 6, p.y - 6, p.x + 6, p.y + 6, sp); c.drawLine(p.x + 6, p.y - 6, p.x - 6, p.y + 6, sp) }
            }
            if (view.scale > 0.7 || (own && view.scale > 0.3)) {
                label(b.name + if (b.damage >= 60) " · CLOSED" else "", p.x, p.y + 12, if (b.damage >= 60) C.RED else col, Paint.Align.CENTER, 9f, al)
                if (own && b.pools.isNotEmpty() && ui.side != "ALL") {
                    val t = b.pools.entries.joinToString(" ") { (ty, q) -> "${Data.AIRCRAFT[ty]!!.name.split(" ")[0]} ${q.ready}${if (q.rearm.isNotEmpty()) "+" + q.rearm.size else ""}" }
                    label(t, p.x + 9, p.y - 1, C.DIM, size = 9f, alpha = al)
                }
            }
            if (ui.sel === b) c.drawRect(p.x - 7, p.y - 7, p.x + 7, p.y + 7, strokeP(C.WHITE))
        }
    }

    private fun radial(x: Float, y: Float, r0: Float, r: Float, inner: Int, outer: Int): RadialGradient {
        val stop = if (r > 0) (r0 / r).coerceIn(0f, 0.99f) else 0f
        return RadialGradient(x, y, max(0.01f, r), intArrayOf(inner, inner, outer), floatArrayOf(0f, stop, 1f), Shader.TileMode.CLAMP)
    }

    private fun drawSensors(sim: Sim, side: String, ui: UiState, spectator: Boolean) {
        val col = C.side(side)
        for (u in sim.units) {
            if (u.side != side) continue
            if (u.kind == "radar") { if (ui.sel === u) circle(u.x, u.y, u.range, col, 0.4, floatArrayOf(2f, 6f), 0.03) }
            else if (u.kind == "sam") {
                if (!spectator && !u.dead && u.radarOn) circle(u.x, u.y, Data.WEAPONS[u.samDef!!.weapon]!!.range * 0.7, C.RED, 0.22, floatArrayOf(3f, 5f), 0.018)
            } else if (u.kind == "air" && u.state == "air") {
                val s = toScreen(u.x, u.y); val r = (u.d.radar * view.scale).toFloat()
                if (u.d.role == "aew") {
                    fill.shader = radial(s.x, s.y, r * 0.2f, r, Color.TRANSPARENT, a(col, 0.06)); fill.xfermode = null
                    c.drawCircle(s.x, s.y, r, fill); fill.shader = null
                    c.drawCircle(s.x, s.y, r, strokeP(col, 0.35, 1f, dash(4f, 4f)))
                } else if (u.radarOn) {
                    val a0 = Math.toDegrees(u.hdg - PI / 2 - 60 * DEG).toFloat()
                    path.reset(); path.moveTo(s.x, s.y); path.arcTo(s.x - r, s.y - r, s.x + r, s.y + r, a0, 120f, false); path.close()
                    fill.shader = RadialGradient(s.x, s.y, max(0.01f, r), a(col, 0.12), Color.TRANSPARENT, Shader.TileMode.CLAMP); fill.xfermode = null
                    c.drawPath(path, fill); fill.shader = null
                    c.drawPath(path, strokeP(col, 0.3))
                }
            }
        }
        if (spectator) return
        for (tr in sim.tracks[side]!!.values) {
            val e = tr.ref
            if (tr.kind == "sam" && !e.dead) circle(tr.x, tr.y, Data.WEAPONS[(e as Unit).samDef!!.weapon]!!.range * 0.7, C.RED, 0.35, floatArrayOf(3f, 5f), 0.03)
        }
    }

    private fun drawSweeps(sim: Sim, side: String?) {
        val t = now() / 1000
        for (u in sim.units) {
            if (side != null && u.side != side) continue
            val R: Double; val period: Double
            if (u.kind == "radar") { if (u.dead || !u.radarOn) continue; R = u.range; period = 6.0 }
            else if (u.kind == "air" && u.state == "air" && u.d.role == "aew") { R = u.d.radar; period = 5.0 }
            else continue
            val s = toScreen(u.x, u.y); val r = (R * view.scale).toFloat()
            if (r < 8 || s.x < -r || s.x > w + r || s.y < -r || s.y > h + r) continue
            val ang = ((t + u.id * 0.37) / period) * PI * 2
            val col = C.side(u.side)
            val slices = 10; val span = 0.7
            fill.xfermode = add; fill.shader = null
            for (i in 0 until slices) {
                val a1 = ang - (i.toDouble() / slices) * span; val a0 = ang - ((i + 1.0) / slices) * span
                fill.color = a(col, (if (u.kind == "radar") 0.05 else 0.08) * (1 - i.toDouble() / slices))
                c.drawArc(s.x - r, s.y - r, s.x + r, s.y + r, Math.toDegrees(a0).toFloat(), Math.toDegrees(a1 - a0).toFloat(), true, fill)
            }
            fill.xfermode = null
            val sp = strokeP(col, if (u.kind == "radar") 0.25 else 0.4); sp.xfermode = add
            c.drawLine(s.x, s.y, s.x + cos(ang).toFloat() * r, s.y + sin(ang).toFloat() * r, sp)
            sp.xfermode = null
        }
    }

    private fun march() = (-(now() / 60) % 8).toFloat()

    private fun route(from: SP, pts: List<XY>, end: XY?, col: Int) {
        path.reset(); path.moveTo(from.x, from.y)
        for (q in pts + listOfNotNull(end)) { val p = toScreen(q.x, q.y); path.lineTo(p.x, p.y) }
        c.drawPath(path, strokeP(col, 0.85, 1.2f, dash(4f, 4f, phase = march())))
        for ((i, q) in pts.withIndex()) {
            val p = toScreen(q.x, q.y)
            c.drawCircle(p.x, p.y, 4f, strokeP(col))
            label("${i + 1}", p.x + 7, p.y - 6, col, size = 9f)
        }
    }

    private fun drawOrders(sim: Sim, ui: UiState) {
        val pl = ui.plan
        if (pl != null && (pl.target != null || pl.via.isNotEmpty())) {
            val src: XY? = pl.unit ?: sim.base(pl.base)
            val t = pl.target?.let { sim.refFor(it.kind, it.id) }
            if (src != null) route(toScreen(src.x, src.y), pl.via, t, C.HOT)
        }
        val sel = ui.sel as? Unit ?: return
        if (sel.kind != "air" || sel.state != "air") return
        val o = sel.order
        val col = C.side(sel.side)
        val from = toScreen(sel.x, sel.y)
        fun dashTo(x: Double, y: Double, color: Int = col): SP {
            val p = toScreen(x, y)
            c.drawLine(from.x, from.y, p.x, p.y, strokeP(color, 0.8, 1f, dash(4f, 4f, phase = march())))
            return p
        }
        val tgt = sel.engage ?: if (o?.type == "attack") o.target else null
        if (tgt != null) {
            val tr = sim.trackFor(sel.side, tgt)
            if (tr != null) { val p = sim.trackPos(tr); dashTo(p.x, p.y, C.RED) }
        } else if (o != null && o.type == "orbit") {
            dashTo(o.x, o.y); circle(o.x, o.y, o.r, col, 0.5, floatArrayOf(2f, 3f))
        } else if (o != null && (o.type == "strike" || o.type == "drone" || o.type == "feint") && o.list.isNotEmpty()) {
            val s = sim.refFor(o.list[0].kind, o.list[0].id)
            val via = o.via
            if (s != null && via != null && via.isNotEmpty()) route(from, via, s, if (o.type == "feint") col else C.RED)
            else if (s != null && !(o.type == "feint" && o.mode == "drag")) dashTo(s.x, s.y, if (o.type == "feint") col else C.RED)
            if (s != null && o.type != "feint") {
                val wk = sim.strikeWeapon(sel)
                if (wk != null) circle(s.x, s.y, Data.WEAPONS[wk]!!.range * 0.9, C.RED, 0.4, floatArrayOf(6f, 6f))
            }
        } else if (o != null && o.type == "sead") {
            dashTo(o.x, o.y); circle(o.x, o.y, 45.0, C.RED, 0.35, floatArrayOf(2f, 4f))
        } else if (o != null && o.type == "rtb") {
            val b = sim.base(o.base ?: sel.base)
            if (b != null) dashTo(b.x, b.y)
        }
        var best: com.killchain.core.WeaponDef? = null
        for ((k, n) in sel.wpn) { val wd = Data.WEAPONS[k]!!; if (n > 0 && wd.kind == "aam" && (best == null || wd.range > best.range)) best = wd }
        if (best != null) {
            circle(sel.x, sel.y, best.range * 0.85, col, 0.35, floatArrayOf(1f, 5f))
            circle(sel.x, sel.y, best.range * 0.4, col, 0.5, floatArrayOf(8f, 4f))
            val p = toScreen(sel.x, sel.y - best.range * 0.4)
            if (view.scale > 0.4) label("NO-ESCAPE", p.x, p.y - 6, col, Paint.Align.CENTER, 8f)
        }
    }

    fun missileVisible(m: Missile, side: String): Boolean {
        if (side == "ALL" || m.side == side) return true
        if (m.kind == "cruise") return m.seen[side]?.any == true
        val t = m.target
        return t != null && t.side == side && m.warned
    }

    private fun drawMissiles(sim: Sim, side: String) {
        for (m in sim.missiles) {
            if (!missileVisible(m, side)) continue
            val col = C.side(m.side)
            val cruise = m.kind == "cruise"
            val p = if (cruise) toScreen(m.x, m.y) else missileAim(m)
            val tr = m.trail
            if (tr.isNotEmpty()) {
                var prev = toScreen(tr[0].x, tr[0].y)
                for (i in 1..tr.size) {
                    val q = if (i < tr.size) toScreen(tr[i].x, tr[i].y) else p
                    c.drawLine(prev.x, prev.y, q.x, q.y, strokeP(col, (if (cruise) 0.5 else 0.7) * (i.toDouble() / tr.size), if (cruise) 1f else 1.4f))
                    prev = q
                }
            }
            if (!cruise && sim.isAlive(m.target) && side != "ALL") drawThreatLine(sim, m, side, p)
            headGlow(p, if (cruise) col else C.WHITE, col, cruise)
            if (!cruise && m.lock) {
                val r = (4 + (now() / 120) % 4).toFloat()
                c.drawCircle(p.x, p.y, r, strokeP(col, 0.8))
            }
        }
    }
    private fun headGlow(p: SP, core: Int, col: Int, cruise: Boolean) {
        val R = if (cruise) 6f else 8f
        fill.shader = RadialGradient(p.x, p.y, R, if (cruise) col else C.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP); fill.xfermode = add
        c.drawCircle(p.x, p.y, R, fill); fill.shader = null; fill.xfermode = null
        c.drawCircle(p.x, p.y, if (cruise) 2f else 1.7f, fillP(core))
    }

    private fun missileAim(m: Missile): SP {
        val p = toScreen(m.x, m.y); val t = m.target ?: return p
        val o = vs[t]?.jetOff ?: return p
        val q = toScreen(t.x, t.y)
        val wgt = ease(max(0.0, min(1.0, 1 - (hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) - 4) / 110))).toFloat()
        return SP(p.x + o.x * wgt, p.y + o.y * wgt)
    }
    private fun targetPoint(t: Ent): SP {
        val q = toScreen(t.x, t.y); val o = vs[t]?.jetOff
        return if (o != null) SP(q.x + o.x, q.y + o.y) else q
    }

    private fun drawThreatLine(sim: Sim, m: Missile, side: String, p: SP) {
        val t = m.target!!
        val q = targetPoint(t)
        if (m.side == side) {
            c.drawLine(p.x, p.y, q.x, q.y, strokeP(C.side(side), 0.25, 1f, dash(2f, 4f)))
            return
        }
        val th = sim.missileThreat(m, false)
        val col = if (th.lethal) C.RED else C.DIM
        c.drawLine(p.x, p.y, q.x, q.y, strokeP(col, 0.9, if (th.lethal) 1.5f else 1f, if (th.lethal) null else dash(3f, 4f)))
        val t2 = "${m.w.name} M${"%.1f".format(th.mach)} ${"%.0f".format(th.d)}km ${if (th.lethal) (if (m.lock) "LOCKED" else "CLOSING") else "LOSING ENERGY"}"
        label(t2, p.x + 6, p.y + 10, col, size = 10f)
    }

    private fun jetPath(type: String?) {
        val pts = SHAPES[type] ?: SWEPT
        path.reset()
        for ((i, q) in pts.withIndex()) if (i == 0) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1])
        for (i in pts.indices.reversed()) if (pts[i][0] != 0f) path.lineTo(-pts[i][0], pts[i][1])
        path.close()
    }

    /** One aircraft or site symbol, as in the web version. */
    fun glyph(kind: String, role: String?, sx: Float, sy: Float, hdg: Double, col: Int, hollow: Boolean = false, glow: Boolean = false,
              dashed: Boolean = false, type: String? = null, size: Float = 1f, cv: Canvas = c) {
        cv.save(); cv.translate(sx, sy)
        val sp = strokeP(col, 1.0, 1.5f, if (dashed) dash(2f, 2f) else null)
        val fp = fillP(col)
        if (glow) { sp.setShadowLayer(8f, 0f, 0f, col); fp.setShadowLayer(8f, 0f, 0f, col) }
        val rot = Math.toDegrees(hdg).toFloat()
        if (kind == "air" && role == "drone") {
            cv.rotate(rot)
            cv.drawLine(-4f, -4f, 4f, 4f, sp); cv.drawLine(4f, -4f, -4f, 4f, sp); cv.drawLine(0f, -6f, 0f, -3f, sp)
        } else if (kind == "air" && role == "fighter") {
            cv.rotate(rot); cv.scale(size, size); sp.strokeWidth = 1.5f / size
            jetPath(type)
            cv.drawPath(path, if (hollow) sp else fp)
        } else if (kind == "air") {
            cv.drawCircle(0f, 0f, 6f, sp); cv.drawLine(-9f, 0f, 9f, 0f, sp)
            cv.rotate(rot); cv.drawLine(0f, -6f, 0f, -11f, sp)
        } else if (kind == "sam") {
            cv.drawRect(-5f, -5f, 5f, 5f, sp)
            path.reset(); path.moveTo(-3f, 3f); path.lineTo(0f, -3f); path.lineTo(3f, 3f); cv.drawPath(path, sp)
        } else if (kind == "radar") {
            cv.drawArc(-4f, -4f, 4f, 4f, 180f, 180f, false, sp); cv.drawLine(0f, 0f, 0f, 5f, sp)
        } else if (kind == "cruise") {
            cv.drawCircle(0f, 0f, 2.5f, fp)
        }
        sp.clearShadowLayer(); fp.clearShadowLayer()
        cv.restore()
    }

    // Wingmen in formation are drawn in their slot a few icon-widths off the lead (see render.js).
    private fun formPos(u: Unit, p: SP, q: SP?): SP {
        val v = vsOf(u)
        val t = now()
        val dt = min(0.1, (t - (if (v.fwT == 0.0) t else v.fwT)) / 1000)
        v.fwT = t
        v.fw += ((if (u.inForm) 1.0 else 0.0) - v.fw) * min(1.0, dt * 2.5)
        if (q == null || v.fw < 0.01) return p
        val L = u.lead!!
        val k = FORMATION[u.slot % FORMATION.size]; val SP_ = 13.0
        val fx = sin(L.hdg); val fy = -cos(L.hdg)
        val tx = q.x - fy * k[0] * SP_ - fx * k[1] * SP_; val ty = q.y + fx * k[0] * SP_ - fy * k[1] * SP_
        val gap = hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) - hypot(tx - q.x, ty - q.y)
        val wgt = ease(v.fw) * max(0.0, min(1.0, 1 - gap / 40))
        return SP((p.x + (tx - p.x) * wgt).toFloat(), (p.y + (ty - p.y) * wgt).toFloat())
    }
    private fun formCounts(sim: Sim): Map<Unit, Int> {
        val n = HashMap<Unit, Int>()
        for (u in sim.units) { val l = u.lead; if (u.kind == "air" && u.state == "air" && l != null && u.inForm) n[l] = (n[l] ?: 0) + 1 }
        return n
    }

    private fun drawUnits(sim: Sim, side: String, ui: UiState) {
        val showLabels = view.scale > 0.45
        val wing = formCounts(sim)
        val col = C.side(side)
        for (u in sim.units) {
            if (u.side != side) continue
            if (u.kind == "air" && u.state != "air") continue
            val real = toScreen(u.x, u.y)
            if (real.x < -60 || real.x > w + 60 || real.y < -60 || real.y > h + 60) continue
            val lead = u.lead
            val p = if (u.kind == "air" && lead != null) formPos(u, real, toScreen(lead.x, lead.y)) else real
            if (u.kind == "air") { val v = vsOf(u); v.jetOff = SP(p.x - real.x, p.y - real.y); v.scr = p; v.scrF = frame }
            val tucked = u.kind == "air" && lead != null && u.inForm
            val sel = ui.sel === u
            if (u.kind == "air" && u.defending && floor(sim.t * 2).toInt() % 2 == 0) c.drawCircle(p.x, p.y, 13f, strokeP(C.RED, 1.0, 1.5f))
            if (u.kind == "air" && !tucked) {
                val L = (10 + u.spd * 40).toFloat()
                val sx = sin(u.hdg).toFloat(); val sy = -cos(u.hdg).toFloat()
                c.drawLine(p.x + sx * 9, p.y + sy * 9, p.x + sx * (9 + L), p.y + sy * (9 + L), strokeP(col, 0.45))
            }
            if (u.dead) { wreck(p, u.callsign); continue }
            if (u.kind == "air" && u.d.role == "drone" && u.count > 1) swarm(u.hdg, p, col, u.count)
            else if (u.kind == "air") glyph("air", u.d.role, p.x, p.y, u.hdg, if (u.dmg > 0) C.HOT else col, hollow = !u.radarOn || u.dmg > 0, glow = !u.ghost, dashed = u.ghost, type = u.type)
            else glyph(u.kind, null, p.x, p.y, u.hdg, col, dashed = !u.radarOn)
            if (u.kind == "sam" && !u.dead) samBar(sim, u, p)
            val lx = p.x + 11
            if (u.kind == "air" && u.defending) label(if (u.defMode == "notch") "NOTCH" else "DRAG", p.x - 11, p.y + 14, C.RED, Paint.Align.RIGHT, 9f)
            else if (u.kind == "air" && u.exposed && !tucked) label("EXPOSED", p.x - 11, p.y + 14, C.RED, Paint.Align.RIGHT, 9f)
            else if (u.kind == "air" && u.low && !tucked) label("▼LOW", p.x - 11, p.y + 14, col, Paint.Align.RIGHT, 9f)
            if (sel) {
                val r = (12 + sin(now() / 250) * 1.5).toFloat()
                val sp = strokeP(C.WHITE)
                for ((dx, dy) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
                    path.reset()
                    path.moveTo(p.x + dx * r, p.y + dy * (r - 5)); path.lineTo(p.x + dx * r, p.y + dy * r); path.lineTo(p.x + dx * (r - 5), p.y + dy * r)
                    c.drawPath(path, sp)
                }
            }
            if (u.kind == "air" && (showLabels || sel) && (!tucked || sel)) {
                val n = u.count + (wing[u] ?: 0)
                label("${u.callsign}${if (n > 1) " ×$n" else ""}", lx, p.y - 5, if (u.ghost) C.DIM else col, size = 10f)
                if (sel || view.scale > 1.1) label(u.d.name, lx, p.y + 6, C.DIM, size = 9f)
            } else if (u.kind != "air" && (view.scale > 0.9 || sel)) {
                label(u.callsign, p.x + 9, p.y, C.DIM, size = 9f)
            }
        }
    }

    fun swarm(hdg: Double, p: SP, col: Int, count: Int, cv: Canvas = c) {
        val k = min(60, count)
        val R = min(22.0, 3 + sqrt(count.toDouble()) * 1.5); val t = now() / 700
        val fp = fillP(col)
        for (i in 0 until k) {
            val ang = i * 2.39996 + sin(t + i * 1.3) * 0.25 + hdg
            val r = R * sqrt((i + 0.5) / k) * (1 + sin(t * 1.7 + i) * 0.08)
            val x = (p.x + cos(ang) * r).toFloat(); val y = (p.y + sin(ang) * r).toFloat()
            cv.drawRect(x - 0.8f, y - 0.8f, x + 0.8f, y + 0.8f, fp)
        }
    }

    private fun samBar(sim: Sim, u: Unit, p: SP) {
        val bw = 22f; val x = p.x - bw / 2; val y = p.y + 9
        c.drawRect(x - 1, y - 1, x + bw + 1, y + 4, fillP(Color.BLACK, 0.6))
        val ra = u.reloadAt
        if (ra != null) {
            val f = max(0.0, min(1.0, 1 - (ra - sim.t) / SAM_RELOAD)).toFloat()
            c.drawRect(x, y, x + bw * f, y + 3, fillP(C.HOT))
            if (view.scale > 0.5) label("RELOADING ${ceil((ra - sim.t) / 60).toInt()}m", p.x, y + 10, C.HOT, Paint.Align.CENTER, 8f)
        } else if (u.dry) {
            if (view.scale > 0.5) label("EMPTY", p.x, y + 10, C.RED, Paint.Align.CENTER, 8f)
        } else {
            val f = (u.ammo.toDouble() / u.samDef!!.ammo).toFloat()
            c.drawRect(x, y, x + bw * f, y + 3, fillP(if (f > 0.5) C.side(u.side) else C.RED))
        }
    }

    private fun strikerMark(sim: Sim, tr: Track, p: SP) {
        val e = tr.ref as Unit
        val job = e.order?.list?.firstOrNull()
        val tgt = job?.let { sim.refFor(it.kind, it.id) }
        val pulse = 0.5 + 0.5 * sin(now() / 180)
        c.drawCircle(p.x, p.y, (14 + pulse * 4).toFloat(), strokeP(C.RED, 0.5 + 0.5 * pulse, 1.5f))
        if (tgt != null) {
            val q = toScreen(tgt.x, tgt.y)
            c.drawLine(p.x, p.y, q.x, q.y, strokeP(C.RED, 0.7, 1.5f, dash(6f, 5f, phase = (-(now() / 40) % 11).toFloat())))
        }
        label("STRIKER → ${tr.striker}", p.x + 11, p.y + 7, C.RED, size = 10f)
    }
    private fun wreck(p: SP, name: String) {
        val sp = strokeP(C.DIM, 1.0, 1.5f)
        c.drawLine(p.x - 5, p.y - 5, p.x + 5, p.y + 5, sp); c.drawLine(p.x + 5, p.y - 5, p.x - 5, p.y + 5, sp)
        if (view.scale > 0.6) label("$name (destroyed)", p.x + 9, p.y, C.DIM, size = 9f)
    }

    private fun drawJamming(sim: Sim) {
        for (side in listOf("IN", "PK")) {
            val e = sim.ew[side]!!.barrage
            if (!(e.until > sim.t)) continue
            val p = toScreen(e.x, e.y); val r = (EW.barrage.r * view.scale).toFloat()
            val t = now() / 1000
            val col = C.side(side)
            c.drawCircle(p.x, p.y, r, strokeP(col, 0.5, 1f, dash(3f, 6f, phase = (-t * 30 % 9).toFloat())))
            c.drawCircle(p.x, p.y, r, fillP(col, 0.08))
            val fp = fillP(col, 0.35)
            for (i in 0 until 40) {
                val ang = (i * 2.4 + t * 3) % (PI * 2); val d = r * (((i * 37) % 100) / 100f)
                val x = p.x + cos(ang).toFloat() * d; val y = p.y + sin(ang).toFloat() * d
                c.drawRect(x, y, x + 2, y + 1, fp)
            }
            label("JAMMING", p.x, p.y - r - 8, col, Paint.Align.CENTER, 9f)
        }
    }

    private fun drawObjectives(sim: Sim, ui: UiState) {
        val pulse = 0.5 + 0.5 * sin(now() / 350)
        for (o in sim.objectives[ui.side]!!) {
            val ref = o.ref ?: continue
            val p = toScreen(ref.x, ref.y)
            val r = (13 + pulse * 2).toFloat()
            val sp = strokeP(if (o.hit) C.DIM else C.RED, 0.9, 1.3f)
            c.drawCircle(p.x, p.y, r, sp)
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) c.drawLine(p.x + dx * (r - 4), p.y + dy * (r - 4), p.x + dx * (r + 5), p.y + dy * (r + 5), sp)
            hitPips(ref, p.x, p.y - r - 9, C.RED)
        }
        for (o in sim.objectives[ENEMY[ui.side]]!!) {
            val ref = o.ref ?: continue
            val p = toScreen(ref.x, ref.y)
            c.drawCircle(p.x, p.y, 15f, strokeP(if (o.hit) C.RED else C.HOT, 0.7, 1f, dash(4f, 3f)))
            if (view.scale > 0.5) label("DEFEND", p.x, p.y + 24, C.HOT, Paint.Align.CENTER, 8f)
            if (ref.hits > 0) hitPips(ref, p.x, p.y - 22, C.HOT)
        }
    }
    private fun hitPips(ref: Ent, x: Float, y: Float, col: Int) {
        val hh = min(3, ref.hits)
        for (i in 0 until 3) {
            val px = x - 10 + i * 7
            c.drawRect(px, y - 2.5f, px + 5, y + 2.5f, strokeP(col))
            if (i < hh) c.drawRect(px, y - 2.5f, px + 5, y + 2.5f, fillP(col))
        }
    }

    private fun drawTracks(sim: Sim, side: String, ui: UiState) {
        val col = C.side(ENEMY[side]!!)
        val table = sim.tracks[side]!!
        val wing = HashMap<Ent, Int>()
        for (tr in table.values) {
            val e = tr.ref as? Unit ?: continue
            val l = e.lead
            if (e.kind == "air" && l != null && e.inForm && tr.q != "esm" && table.containsKey(l.id)) wing[l] = (wing[l] ?: 0) + 1
        }
        for (tr in table.values) {
            if (tr.kind == "cruise") continue
            val pos: XY = if (tr.isStatic) Pt(tr.x, tr.y) else sim.trackPos(tr)
            val real = toScreen(pos.x, pos.y)
            if (real.x < -60 || real.x > w + 60 || real.y < -60 || real.y > h + 60) continue
            val stale = sim.t - tr.t > 3 && !tr.isStatic
            val e = tr.ref
            val al = if (stale) 0.45 else 1.0
            val esm = tr.q == "esm"
            val u = e as? Unit
            val ltr = if (u != null && u.kind == "air" && u.lead != null && !esm) table[u.lead!!.id] else null
            val p = if (ltr != null) { val lp = sim.trackPos(ltr); formPos(u!!, real, toScreen(lp.x, lp.y)) } else real
            if (e.kind == "air") { val v = vsOf(e); v.jetOff = SP(p.x - real.x, p.y - real.y); v.scr = p; v.scrF = frame }
            val tucked = ltr != null && u!!.inForm
            val tcol = a(col, al)
            if (e.kind == "air") {
                u!!
                if (u.d.role == "drone" && max(tr.count, u.count) > 1 && !esm) swarm(u.hdg, p, tcol, if (tr.count > 0) tr.count else u.count)
                else glyph("air", u.d.role, p.x, p.y, u.hdg, tcol, hollow = true, dashed = esm, glow = !esm && !stale, type = u.type)
                if (esm) c.drawCircle(p.x, p.y, 10f, strokeP(tcol, 1.0, 1f, dash(2f, 3f)))
            } else if (e.dead) { wreck(p, (e as Unit).callsign); continue }
            else glyph(e.kind, null, p.x, p.y, 0.0, tcol)
            if (e.kind == "sam" && !e.dead) samBar(sim, e as Unit, p)
            val sel = ui.sel === e
            if (sel) c.drawRect(p.x - 11, p.y - 11, p.x + 11, p.y + 11, strokeP(C.WHITE))
            if (e.kind == "air") {
                u!!
                val n = (if (tr.count > 0) tr.count else u.count) + (wing[e] ?: 0)
                val name = if (esm) "${u.d.name}?" else "${u.d.name}${if (n > 1) " ×$n" else ""}"
                if ((view.scale > 0.3 && !tucked) || sel) label("T${tr.num} $name", p.x + 11, p.y - 5, col, size = 10f, alpha = al)
                if (tr.striker != null && !stale && !tucked) strikerMark(sim, tr, p)
                if (u.low && !esm && !tucked) label("▼LOW", p.x - 11, p.y + 14, col, Paint.Align.RIGHT, 9f, al)
            } else if (view.scale > 0.9 || sel) label((e as Unit).callsign, p.x + 9, p.y, C.DIM, size = 9f, alpha = al)
        }
    }

    private fun drawFx(sim: Sim, ui: UiState) {
        for (f in sim.fx) {
            val age = sim.t - f.t
            val ref = f.ref
            if (ref != null && !f.offSet) { f.offSet = true; f.off = vs[ref]?.jetOff?.let { Pair(it.x, it.y) } }
            val p = toScreen(f.x, f.y)
            f.off?.let { p.x += it.first; p.y += it.second }
            explosion(f.kind, f.x, p, age)
        }
        val t = now() / 1000
        for (m in ui.marks) {
            val age = t - m.t
            if (age > 1) continue
            val p = toScreen(m.x, m.y)
            val sp = strokeP(C.WHITE, 1 - age)
            c.drawCircle(p.x, p.y, (4 + age * 14).toFloat(), sp)
            c.drawLine(p.x - 5, p.y, p.x + 5, p.y, sp); c.drawLine(p.x, p.y - 5, p.x, p.y + 5, sp)
        }
    }

    private fun explosion(kind: String, fx: Double, p: SP, age: Double) {
        if (kind == "kill" || kind == "impact") {
            val impact = kind == "impact"
            val col = if (impact) C.HOT else C.WHITE
            if (age < 2.5) {
                val fr = (26 * (1 - age / 2.5) + 6).toFloat()
                fill.shader = RadialGradient(p.x, p.y, fr,
                    intArrayOf(if (impact) Color.argb(242, 255, 210, 120) else Color.argb(242, 255, 255, 255), if (impact) Color.argb(128, 255, 77, 109) else Color.argb(102, 120, 220, 255), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                fill.xfermode = add
                c.drawCircle(p.x, p.y, fr, fill)
                fill.shader = null; fill.xfermode = null
            }
            for (k in doubleArrayOf(1.0, 0.55)) {
                val sp = strokeP(col, max(0.0, 1 - age / 12) * k, 1.4f); sp.xfermode = add
                c.drawCircle(p.x, p.y, ((4 + age * 3.2) * k + 2).toFloat(), sp); sp.xfermode = null
            }
            val fp = fillP(col, max(0.0, 1 - age / 6))
            for (i in 0 until 10) {
                val ang = (i / 10.0) * PI * 2 + (fx % 1)
                val d = age * (2 + (i % 3)) * 2.2
                val x = p.x + (cos(ang) * d).toFloat(); val y = p.y + (sin(ang) * d).toFloat()
                c.drawRect(x, y, x + 1.6f, y + 1.6f, fp)
            }
            if (age < 5) label(if (impact) "IMPACT" else "SPLASH", p.x, (p.y - 18 - age * 3).toFloat(), col, Paint.Align.CENTER, 11f, max(0.0, 1 - age / 5))
        } else if (kind == "miss" && age < 3) {
            c.drawCircle(p.x, p.y, (3 + age * 3).toFloat(), strokeP(C.DIM, 1 - age / 3))
        } else if (kind == "chaff" && age < 4) {
            for (i in 0 until 7) {
                val fp = fillP(C.WHITE, (1 - age / 4) * (0.5 + 0.5 * ((i * 7) % 3) / 2.0))
                val x = (p.x - 8 + i * 2.6 + age * (i - 3)).toFloat(); val y = (p.y + 4 + ((i * 5) % 4) + age * 2).toFloat()
                c.drawRect(x, y, x + 1.5f, y + 1.5f, fp)
            }
        }
    }

    private fun drawHud(ui: UiState) {
        val bottom = h - 26 - hudInset
        val right = w - 14
        val km = listOf(10, 20, 50, 100, 200, 500, 1000).firstOrNull { it * view.scale >= 70 } ?: 1000
        val px = (km * view.scale).toFloat()
        val sp = strokeP(MC.gridText)
        path.reset()
        path.moveTo(right - px, bottom - 4); path.lineTo(right - px, bottom); path.lineTo(right, bottom); path.lineTo(right, bottom - 4)
        path.moveTo(right - px / 2, bottom - 2); path.lineTo(right - px / 2, bottom)
        c.drawPath(path, sp)
        label("$km KM", right - px / 2, bottom - 10, MC.gridText, Paint.Align.CENTER, 9f)
    }

    // ---------- after-action replay ----------
    /** Draws one replay frame: everything from both sides, nothing hidden. */
    fun drawReplay(cv: Canvas, sim: Sim, f: Frame) {
        if (w <= 0 || h <= 0) return
        sane()
        frame++
        stepFlight(); clampView()
        c = cv
        cv.save(); cv.scale(density, density)
        drawMapLayer(cv)
        for (s in sim.sites) {
            val p = toScreen(s.x, s.y)
            path.reset(); path.moveTo(p.x, p.y - 6); path.lineTo(p.x + 6, p.y); path.lineTo(p.x, p.y + 6); path.lineTo(p.x - 6, p.y); path.close()
            c.drawPath(path, strokeP(C.RED, 1.0, 1.3f))
            if (view.scale > 0.25) label(s.name, p.x + 9, p.y, C.RED, size = 9f)
        }
        for ((k, b) in sim.bases.withIndex()) {
            val p = toScreen(b.x, b.y); val col = C.side(b.side)
            c.drawRect(p.x - 3.5f, p.y - 3.5f, p.x + 3.5f, p.y + 3.5f, strokeP(col, 0.9))
            val dmg = f.damage.getOrNull(k) ?: 0
            if (dmg > 0) c.drawArc(p.x - 8, p.y - 8, p.x + 8, p.y + 8, -90f, (min(1.0, dmg / 60.0) * 360).toFloat(), false, strokeP(C.RED, 0.9, 2f))
            if (view.scale > 0.7) label(b.name, p.x, p.y + 12, col, Paint.Align.CENTER, 9f)
        }
        for (m in f.missiles) {
            val p = toScreen(m.x, m.y)
            headGlow(p, if (m.cruise) C.side(m.side) else C.WHITE, C.side(m.side), m.cruise)
        }
        for (u in f.units) {
            val p = toScreen(u.x.toDouble(), u.y.toDouble())
            if (p.x < -60 || p.x > w + 60 || p.y < -60 || p.y > h + 60) continue
            val col = C.side(u.side)
            val def = Data.AIRCRAFT[u.type]
            if (def != null) {
                if (def.role == "drone" && u.count > 1) swarm(u.hdg, p, col, u.count)
                else glyph("air", def.role, p.x, p.y, u.hdg, col, hollow = !u.radar, glow = !u.ghost, dashed = u.ghost, type = u.type)
                if (view.scale > 0.45) label("${u.callsign}${if (u.count > 1) " ×${u.count}" else ""}", p.x + 11, p.y - 5, if (u.ghost) C.DIM else col, size = 10f)
            } else {
                val orig = sim.unit(u.id)
                if (u.count == 0) { wreck(p, u.callsign); continue }
                glyph(orig?.kind ?: "sam", null, p.x, p.y, 0.0, col, dashed = !u.radar)
            }
        }
        for (x in f.fx) explosion(x.kind, x.x.toDouble(), toScreen(x.x.toDouble(), x.y.toDouble()), 1.0)
        drawHud(UiState())
        cv.restore()
    }

    companion object {
        fun ease(t: Double) = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
        private fun pts(vararg v: Float): Array<FloatArray> = Array(v.size / 2) { floatArrayOf(v[it * 2], v[it * 2 + 1]) }
        val DELTA = pts(0f, -9f, 1.2f, -4f, 6.5f, 5f, 1.5f, 5f, 1f, 7f)
        val CANARD_DELTA = pts(0f, -9f, 1.2f, -5f, 3.2f, -2.8f, 1.3f, -2.4f, 6.5f, 5f, 1.5f, 5f, 1f, 7f)
        val SWEPT = pts(0f, -9f, 1.1f, -3f, 6f, 2f, 6f, 3.5f, 1.5f, 3f, 3.6f, 6.8f, 3.6f, 8f, 0.8f, 7.5f)
        val SU30 = pts(0f, -10.5f, 1.3f, -4.5f, 3.4f, -3f, 1.6f, -2.4f, 7.5f, 2.5f, 7.5f, 4f, 2f, 3.6f, 4.4f, 8f, 4.4f, 9.2f, 1f, 8.6f)
        val SHAPES = mapOf("RAFALE" to CANARD_DELTA, "J10C" to CANARD_DELTA, "M2000" to DELTA, "SU30" to SU30, "F16" to SWEPT,
            "JF17B3" to SWEPT, "JF17B2" to SWEPT, "MIG29" to SWEPT)
    }
}

/**
 * Paints the static map (sea, land, graticule, rivers, coasts, borders, frontier, labels) for a
 * view. The "full" painter adds the dot-matrix land texture and glows; the fast one is light
 * enough to redraw every frame while the camera moves. One instance per thread.
 */
class MapPainter(private val density: Float, mono: Typeface, private val full: Boolean) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val m = Matrix()
    private val tmp = Path()

    private fun build(lines: List<FloatArray>, close: Boolean): Path = Path().apply {
        for (a in lines) {
            if (a.size < 4) continue
            moveTo(a[0], a[1]); var i = 2
            while (i < a.size) { lineTo(a[i], a[i + 1]); i += 2 }
            if (close) close()
        }
    }
    private val land: List<Pair<Int, Path>> = Geo.countries.map { ct ->
        val tint = if (ct.name == "India") 0 else if (ct.name == "Pakistan") 1 else 2
        tint to build(ct.rings, true).apply { fillType = Path.FillType.EVEN_ODD }
    }
    private val lakes = build(Geo.lakes, true)
    private val rivers = build(Geo.rivers, false)
    private val coasts = build(Geo.coasts, false)
    private val borders = build(Geo.borders, false)
    private val ib = build(listOf(Geo.ib), false)
    private val loc = build(listOf(Geo.loc), false)
    private val patterns = HashMap<Int, BitmapShader>()

    private fun dotShader(col: Int): BitmapShader = patterns.getOrPut(col) {
        val n = round(5 * density).toInt().coerceAtLeast(2)
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(b).drawCircle(n / 2f, n / 2f, 0.7f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun a(col: Int, alpha: Double) = (col and 0x00ffffff) or ((alpha.coerceIn(0.0, 1.0) * ((col ushr 24) and 0xff)).toInt() shl 24)

    fun paint(c: Canvas, v: View2, w: Float, h: Float) {
        val s = v.scale
        fun sx(x: Double) = ((x - v.cx) * s + w / 2).toFloat()
        fun sy(y: Double) = ((y - v.cy) * s + h / 2).toFloat()
        m.reset(); m.setScale(s.toFloat(), s.toFloat()); m.postTranslate((w / 2 - v.cx * s).toFloat(), (h / 2 - v.cy * s).toFloat())
        fun drawP(p: Path, paint: Paint) { p.transform(m, tmp); c.drawPath(tmp, paint) }
        // Sea
        val cx = sx(0.0); val cy = sy(0.0)
        fill.shader = RadialGradient(cx, cy, max(1f, (max(w, h) * 1.1 + 2500 * s).toFloat()), MC.sea0, MC.sea1, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        // Land
        val n = round(5 * density).toInt().coerceAtLeast(2)
        val offX = (((cx * density) % n) + n) % n; val offY = (((cy * density) % n) + n) % n
        for ((tint, p) in land) {
            p.transform(m, tmp); tmp.fillType = Path.FillType.EVEN_ODD
            fill.color = if (tint == 0) MC.landIN else if (tint == 1) MC.landPK else MC.landX
            c.drawPath(tmp, fill)
            if (!full) continue
            val sh = dotShader(if (tint == 0) MC.dotsIN else if (tint == 1) MC.dotsPK else MC.dots)
            val lm = Matrix(); lm.setScale(1 / density, 1 / density); lm.postTranslate(offX / density, offY / density)
            sh.setLocalMatrix(lm)
            fill.shader = sh; fill.alpha = if (tint == 2) 204 else 255
            c.drawPath(tmp, fill)
            fill.shader = null; fill.alpha = 255
        }
        fill.color = MC.sea0; drawP(lakes, fill)
        // Graticule
        val step = if (s > 1.2) 1 else if (s > 0.35) 2 else 5
        val bb = Geo.bbox; val lo0 = bb[0]; val la0 = bb[1]; val lo1 = bb[2]; val la1 = bb[3]
        stroke.color = MC.grid; stroke.strokeWidth = 1f; stroke.pathEffect = null; stroke.clearShadowLayer()
        tmp.reset()
        var la = ceil(la0 / step) * step
        while (la <= la1) {
            var lo = lo0; var first = true
            while (lo <= lo1) { val p = Geo.project(la, lo); if (first) tmp.moveTo(sx(p.x), sy(p.y)) else tmp.lineTo(sx(p.x), sy(p.y)); first = false; lo += 1 }
            la += step
        }
        var lo = ceil(lo0 / step) * step
        while (lo <= lo1) {
            var la2 = la0; var first = true
            while (la2 <= la1) { val p = Geo.project(la2, lo); if (first) tmp.moveTo(sx(p.x), sy(p.y)) else tmp.lineTo(sx(p.x), sy(p.y)); first = false; la2 += 1 }
            lo += step
        }
        c.drawPath(tmp, stroke)
        // Rivers, coasts (glow), borders, frontier (glow)
        stroke.color = MC.river; stroke.strokeWidth = if (s > 0.8) 1.3f else 0.9f
        drawP(rivers, stroke)
        stroke.color = MC.coast; stroke.strokeWidth = 1.2f
        if (full) stroke.setShadowLayer(10f, 0f, 0f, Color.argb(140, 60, 190, 255))
        drawP(coasts, stroke); stroke.clearShadowLayer()
        stroke.color = MC.border; stroke.strokeWidth = 1f; stroke.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
        drawP(borders, stroke); stroke.pathEffect = null
        stroke.color = MC.front; stroke.strokeWidth = 1.8f
        if (full) stroke.setShadowLayer(12f, 0f, 0f, Color.argb(230, 95, 224, 255))
        drawP(ib, stroke)
        stroke.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
        drawP(loc, stroke)
        stroke.pathEffect = null; stroke.clearShadowLayer()
        // Labels
        for (L in Geo.labels) {
            val px = sx(L.x); val py = sy(L.y)
            if (px < -200 || px > w + 200 || py < -50 || py > h + 50) continue
            when (L.kind) {
                "IN", "PK" -> { val size = max(14.0, min(34.0, 9 + s * 12)).toFloat(); spaced(c, L.text, px, py, size, size * 0.55f, C.side(L.kind), 0.3) }
                "X" -> { if (!(L.rank == 3 && s < 0.18)) spaced(c, L.text, px, py, max(10.0, min(18.0, 7 + s * 7)).toFloat(), 4f, Color.parseColor("#5d8494"), 0.45) }
                "SEA" -> spaced(c, L.text, px, py, max(11.0, min(20.0, 8 + s * 8)).toFloat(), 6f, Color.parseColor("#2c7fa3"), 0.5)
                "TER" -> { if (!(s < 0.14 || s > 1.6)) spaced(c, L.text, px, py, 10f, 5f, Color.parseColor("#3b5f6c"), 0.4) }
                "LOC" -> { if (s > 0.9) spaced(c, L.text, px, py, 9f, 3f, MC.front, 0.55) }
            }
        }
        val minRank = if (s > 0.9) 3 else if (s > 0.28) 2 else 1
        for (ct in Geo.cities) {
            if (ct.rank > minRank) continue
            val px = sx(ct.x); val py = sy(ct.y)
            if (px < -50 || px > w + 50 || py < -20 || py > h + 20) continue
            fill.color = MC.city
            c.drawRect(px - 1.5f, py - 1.5f, px + 1.5f, py + 1.5f, fill)
            if (ct.rank == 1) { stroke.color = MC.city; stroke.strokeWidth = 1f; c.drawRect(px - 3.5f, py - 3.5f, px + 3.5f, py + 3.5f, stroke) }
            label(c, ct.name.uppercase(), px + 6, py, MC.city, if (ct.rank == 1) 10f else 9f)
        }
        // Graticule labels along the left and bottom edges.
        la = ceil(la0 / step) * step
        while (la <= la1) {
            val wl = Pt((14 - w / 2) / s + v.cx, (0 - h / 2) / s + v.cy)
            val ll = Geo.unproject(wl.x, wl.y)
            val p = Geo.project(la, ll.second)
            val py = sy(p.y)
            if (py > 60 && py < h - 10) label(c, "${la.toInt()}°N", 6f, py - 7, MC.gridText, 9f)
            la += step
        }
        lo = ceil(lo0 / step) * step
        while (lo <= lo1) {
            val wl = Pt((0 - w / 2) / s + v.cx, (h - 14 - h / 2) / s + v.cy)
            val ll = Geo.unproject(wl.x, wl.y)
            val p = Geo.project(ll.first, lo)
            val px = sx(p.x)
            if (px > 40 && px < w - 40) label(c, "${lo.toInt()}°E", px + 3, h - 9, MC.gridText, 9f)
            lo += step
        }
    }

    private fun label(c: Canvas, text: String, x: Float, y: Float, col: Int, size: Float) {
        txt.textSize = size; txt.textAlign = Paint.Align.LEFT
        val fm = txt.fontMetrics; val yy = y - (fm.ascent + fm.descent) / 2
        txt.color = a(Color.BLACK, 0.65); c.drawText(text, x + 1, yy + 1, txt)
        txt.color = col; c.drawText(text, x, yy, txt)
    }
    private fun spaced(c: Canvas, text: String, x: Float, y: Float, size: Float, spacing: Float, col: Int, alpha: Double) {
        txt.textSize = size; txt.textAlign = Paint.Align.LEFT
        val widths = FloatArray(text.length); txt.getTextWidths(text, widths)
        val total = widths.sum() + spacing * (text.length - 1)
        var px = x - total / 2
        val fm = txt.fontMetrics; val yy = y - (fm.ascent + fm.descent) / 2
        txt.color = a(col, alpha)
        for ((i, ch) in text.withIndex()) { c.drawText(ch.toString(), px, yy, txt); px += widths[i] + spacing }
    }
}
