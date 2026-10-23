package com.killchain.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** The map: a full-screen view redrawn every frame, with drag-to-pan, pinch-to-zoom and taps. */
@SuppressLint("ViewConstructor")
class GameView(ctx: Context, private val game: Game) : View(ctx), Choreographer.FrameCallback {
    private val density = resources.displayMetrics.density
    private var running = false
    private var lastNs = 0L

    companion object {
        /** Tests drive frames themselves. */
        var autoRun = true
    }
    fun start() { if (!autoRun) return; if (!running) { running = true; lastNs = 0; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNs == 0L) 0.016 else ((frameTimeNanos - lastNs) / 1e9).coerceIn(0.0, 0.1)
        lastNs = frameTimeNanos
        game.frame(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { game.resize(w / density, h / density) }
    override fun onDraw(canvas: Canvas) { game.draw(canvas) }

    // ---------- touch: tap, drag-pan, pinch-zoom ----------
    private var x0 = 0f; private var y0 = 0f; private var lx = 0f; private var ly = 0f
    private var moved = false; private var multi = false
    private var pd = 0f; private var pcx = 0f; private var pcy = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / density; val y = e.y / density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = x; y0 = y; lx = x; ly = y; moved = false; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { multi = true; pinchStart(e) }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val ax = e.getX(0) / density; val ay = e.getY(0) / density; val bx = e.getX(1) / density; val by = e.getY(1) / density
                    val d = hypot(ax - bx, ay - by); val cx = (ax + bx) / 2; val cy = (ay + by) / 2
                    if (pd > 0) { game.R.pan(cx - pcx, cy - pcy); game.R.zoomAt(cx, cy, (d / pd).toDouble()) }
                    pd = d; pcx = cx; pcy = cy
                } else if (!multi) {
                    if (hypot(x - x0, y - y0) > 8) moved = true
                    if (moved) game.R.pan(x - lx, y - ly)
                    lx = x; ly = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount == 2) {
                    // One finger stays down: continue as a drag from where it is.
                    val keep = if (e.actionIndex == 0) 1 else 0
                    lx = e.getX(keep) / density; ly = e.getY(keep) / density; pd = 0f
                } else pinchStart(e)
            }
            MotionEvent.ACTION_UP -> { if (!moved && !multi) game.onTap(x, y); pd = 0f }
            MotionEvent.ACTION_CANCEL -> pd = 0f
        }
        return true
    }
    private fun pinchStart(e: MotionEvent) {
        val ax = e.getX(0) / density; val ay = e.getY(0) / density; val bx = e.getX(1) / density; val by = e.getY(1) / density
        pd = hypot(ax - bx, ay - by); pcx = (ax + bx) / 2; pcy = (ay + by) / 2
    }
}
