package com.killchain.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Html
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Palette (matches the web version's CSS and map colours). */
object C {
    val BG = Color.parseColor("#01050a")
    val PANEL = Color.parseColor("#e6061018")
    val PANEL_SOLID = Color.parseColor("#06101a")
    val LINE = Color.parseColor("#1d3a48")
    val LINE_HI = Color.parseColor("#2c6a80")
    val TEXT = Color.parseColor("#9fc4d3")
    val DIM = Color.parseColor("#4b6b78")
    val WHITE = Color.parseColor("#e8f6ff")
    val CYAN = Color.parseColor("#5fe0ff")
    val IN = Color.parseColor("#ff9a3c")
    val PK = Color.parseColor("#33e58c")
    val RED = Color.parseColor("#ff4d6d")
    val HOT = Color.parseColor("#ffd166")
    val PURPLE = Color.parseColor("#c792ff")
    fun side(s: String) = if (s == "IN") IN else if (s == "PK") PK else CYAN
    fun hex(c: Int) = String.format("#%06x", c and 0xffffff)
}

/** Small helpers for building the UI in code (the app has no XML layouts). */
class Ui(val ctx: Context) {
    val density = ctx.resources.displayMetrics.density
    val mono: Typeface = try { Typeface.createFromAsset(ctx.assets, "fonts/ShareTechMono-Regular.ttf") } catch (e: Exception) { Typeface.MONOSPACE }
    fun dp(v: Float) = (v * density + 0.5f).toInt()
    fun dp(v: Int) = dp(v.toFloat())

    fun box(fill: Int, stroke: Int = 0, radius: Float = 6f, strokeW: Float = 1f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius * density
        if (stroke != 0) setStroke((strokeW * density).toInt().coerceAtLeast(1), stroke)
    }

    fun text(s: CharSequence, size: Float = 13f, color: Int = C.TEXT, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        typeface = mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) paint.isFakeBoldText = true
        setLineSpacing(0f, 1.12f)
        includeFontPadding = false
    }

    /** A pressable button: [label] in bold, optional sub-line, tinted by [accent]. */
    fun button(label: CharSequence, sub: CharSequence? = null, accent: Int = C.CYAN, on: Boolean = false, filled: Boolean = false,
               size: Float = 13f, onClick: () -> Unit): TextView {
        val t = text(if (sub != null) html("<b>$label</b><br><small><font color='${C.hex(C.TEXT)}'>$sub</font></small>") else label, size,
            if (filled) C.BG else accent)
        t.paint.isFakeBoldText = sub == null
        val normal = box(if (filled) accent else if (on) (accent and 0x00ffffff) or 0x33000000 else 0x1a0a1a24, if (on || filled) accent else C.LINE_HI, 5f)
        val pressed = box((accent and 0x00ffffff) or 0x55000000, accent, 5f)
        t.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
        t.setPadding(dp(10), dp(if (sub != null) 7 else 8), dp(10), dp(if (sub != null) 7 else 8))
        t.gravity = if (sub != null) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        t.isClickable = true
        t.setOnClickListener { onClick() }
        t.minHeight = dp(36)
        return t
    }

    fun row(vararg kids: View, gap: Int = 6): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, k) in kids.withIndex()) addView(k, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(gap) })
    }
    fun col(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }
    fun flp(w: Int, h: Int, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    companion object {
        @Suppress("DEPRECATION")
        fun html(s: String): Spanned = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun col(c: Int, s: String) = "<font color='${C.hex(c)}'>$s</font>"
        fun k(s: String) = col(C.DIM, s)
        fun warn(s: String) = col(C.RED, s)
        fun warm(s: String) = col(C.HOT, s)
    }
}
