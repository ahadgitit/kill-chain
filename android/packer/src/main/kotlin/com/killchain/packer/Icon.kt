package com.killchain.packer

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** The launcher icon, drawn in code: a radar scope with a delta jet locked in its sweep. */
object Icon {
    private val SEA0 = Color(0x03, 0x14, 0x22)
    private val SEA1 = Color(0x01, 0x05, 0x0a)
    private val CYAN = Color(0x5f, 0xe0, 0xff)
    private val ORANGE = Color(0xff, 0x9a, 0x3c)
    private val GREEN = Color(0x33, 0xe5, 0x8c)
    private val RED = Color(0xff, 0x4d, 0x6d)

    // Canard delta (Rafale / J-10) right half, nose up, as in the game's renderer.
    private val JET = arrayOf(0.0 to -9.0, 1.2 to -5.0, 3.2 to -2.8, 1.3 to -2.4, 6.5 to 5.0, 1.5 to 5.0, 1.0 to 7.0)

    private fun g2(img: BufferedImage): Graphics2D = img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    fun jetPath(cx: Double, cy: Double, k: Double, rot: Double): GeneralPath {
        val p = GeneralPath()
        val pts = ArrayList<Pair<Double, Double>>()
        for (q in JET) pts.add(q)
        for (i in JET.indices.reversed()) if (JET[i].first != 0.0) pts.add(-JET[i].first to JET[i].second)
        val c = Math.cos(rot); val s = Math.sin(rot)
        for ((i, q) in pts.withIndex()) {
            val x = cx + (q.first * c - q.second * s) * k; val y = cy + (q.first * s + q.second * c) * k
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.closePath()
        return p
    }

    fun background(g: Graphics2D, n: Int) {
        g.paint = RadialGradientPaint(Point2D.Double(n / 2.0, n / 2.0), n * 0.75f, floatArrayOf(0f, 1f), arrayOf(SEA0, SEA1),
            MultipleGradientPaint.CycleMethod.NO_CYCLE)
        g.fillRect(0, 0, n, n)
        // Faint dot-matrix texture, like the game's land fill.
        g.color = Color(90, 170, 200, 26)
        val step = n / 36.0
        var y = step / 2
        while (y < n) { var x = step / 2; while (x < n) { g.fill(Ellipse2D.Double(x - n / 400.0, y - n / 400.0, n / 200.0, n / 200.0)); x += step }; y += step }
    }

    /** Scope and jet. [n] is the layer size; [safe] the radius that must stay visible. */
    fun foreground(g: Graphics2D, n: Int, safe: Double) {
        val c = n / 2.0
        val R = safe * 0.92
        // Range rings
        g.stroke = BasicStroke((n / 160.0).toFloat())
        for (k in listOf(1.0, 0.66, 0.33)) {
            g.color = Color(CYAN.red, CYAN.green, CYAN.blue, if (k == 1.0) 150 else 70)
            g.draw(Ellipse2D.Double(c - R * k, c - R * k, 2 * R * k, 2 * R * k))
        }
        g.color = Color(CYAN.red, CYAN.green, CYAN.blue, 45)
        g.drawLine((c - R).toInt(), c.toInt(), (c + R).toInt(), c.toInt())
        g.drawLine(c.toInt(), (c - R).toInt(), c.toInt(), (c + R).toInt())
        // Sweep wedge trailing a bright line (radar sweep), in India orange fading out.
        val a0 = 35.0
        for (i in 0 until 24) {
            val alpha = (110 * (1 - i / 24.0)).toInt()
            g.color = Color(ORANGE.red, ORANGE.green, ORANGE.blue, alpha)
            g.fill(Arc2D.Double(c - R, c - R, 2 * R, 2 * R, a0 + i * 2.5, 2.6, Arc2D.PIE))
        }
        g.stroke = BasicStroke((n / 110.0).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = ORANGE
        val ar = Math.toRadians(a0)
        g.drawLine(c.toInt(), c.toInt(), (c + Math.cos(ar) * R).toInt(), (c - Math.sin(ar) * R).toInt())
        // The jet: glow, then a white silhouette, heading up-right.
        val rot = Math.toRadians(32.0)
        val k = safe / 19.0
        val jx = c + k * 1.5; val jy = c - k * 1.5
        for (i in 14 downTo 1) {
            g.color = Color(CYAN.red, CYAN.green, CYAN.blue, 9)
            g.stroke = BasicStroke((k * i * 0.45).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(jetPath(jx, jy, k, rot))
        }
        g.color = Color(0xe8, 0xf6, 0xff)
        g.fill(jetPath(jx, jy, k, rot))
        // A green enemy contact with a red lock box, low left: the other side of the kill chain.
        val ex = c - R * 0.5; val ey = c + R * 0.5
        g.color = GREEN
        g.fill(jetPath(ex, ey, k * 0.55, Math.toRadians(-140.0)))
        g.color = RED
        g.stroke = BasicStroke((n / 120.0).toFloat())
        val b = k * 6.5
        g.draw(java.awt.geom.Rectangle2D.Double(ex - b, ey - b, 2 * b, 2 * b))
    }

    fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    fun layer(n: Int, draw: (Graphics2D) -> Unit): ByteArray {
        val img = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        val g = g2(img); draw(g); g.dispose()
        return png(img)
    }

    /** Adaptive layers are 108 dp with a 66 dp safe circle; legacy icons are 48 dp squares. */
    fun adaptiveBg(px: Int) = layer(px) { background(it, px) }
    fun adaptiveFg(px: Int) = layer(px) { foreground(it, px, px * 33.0 / 108) }
    fun legacy(px: Int) = layer(px) { g ->
        val r = px * 0.09
        g.clip = java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, px.toDouble(), px.toDouble(), r * 2, r * 2)
        background(g, px); foreground(g, px, px * 0.46)
    }

    /** Play Store listing art: 512 px icon and a 1024x500 feature graphic. */
    fun storeArt(dir: File) {
        dir.mkdirs()
        File(dir, "icon-512.png").writeBytes(layer(512) { g -> background(g, 512); foreground(g, 512, 512 * 0.46) })
        val w = 1024; val h = 500
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(img)
        g.paint = GradientPaint(0f, 0f, SEA0, w.toFloat(), h.toFloat(), SEA1)
        g.fillRect(0, 0, w, h)
        val sub = BufferedImage(h, h, BufferedImage.TYPE_INT_ARGB)
        val gs = g2(sub); foreground(gs, h, h * 0.44); gs.dispose()
        g.drawImage(sub, w - h - 20, 0, null)
        g.font = Font(Font.MONOSPACED, Font.BOLD, 92)
        g.color = Color(0xe8, 0xf6, 0xff); g.drawString("KILL", 60, 220)
        g.color = ORANGE; g.drawString("CHAIN", 60 + g.fontMetrics.stringWidth("KILL "), 220)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 28)
        g.color = Color(0x9f, 0xc4, 0xd3)
        g.drawString("Air-war command on a live radar map", 62, 285)
        g.drawString("Detect. Share. Shoot. Survive.", 62, 330)
        g.dispose()
        ImageIO.write(img, "png", File(dir, "feature-1024x500.png"))
    }
}
