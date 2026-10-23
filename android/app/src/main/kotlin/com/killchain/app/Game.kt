package com.killchain.app

import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.killchain.app.Ui.Companion.esc
import com.killchain.app.Ui.Companion.html
import com.killchain.core.Carry
import com.killchain.core.Data
import com.killchain.core.ENEMY
import com.killchain.core.Ent
import com.killchain.core.Geo
import com.killchain.core.GameEvent
import com.killchain.core.Prompt
import com.killchain.core.Sim
import com.killchain.core.SimConfig
import com.killchain.core.Unit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The player's view of the game: who they command, what is selected, what they are planning. */
class UiState {
    var side = "IN"
    var sel: Any? = null
    var plan: Plan? = null
    val marks = mutableListOf<Mark>()
    var speed = 5
    var lastSpeed = 5
    var training = false
    val deployN = HashMap<String, Int>()
}

/** Campaign progress (saved between sessions). */
class Campaign(val side: String) {
    var night = 0
    var carry: Carry? = null
    val totals = hashMapOf("IN" to 0, "PK" to 0)
    val results = mutableListOf<IntArray>()

    fun toJson(): String = JSONObject().apply {
        put("side", side); put("night", night)
        put("totals", JSONObject().put("IN", totals["IN"]).put("PK", totals["PK"]))
        put("results", JSONArray().apply { for (r in results) put(JSONArray().put(r[0]).put(r[1]).put(r[2])) })
        carry?.let { c ->
            fun mm(m: Map<String, Map<String, Int>>) = JSONObject().apply { for ((k, v) in m) put(k, JSONObject().apply { for ((a, b) in v) put(a, b) }) }
            put("carry", JSONObject().apply {
                put("pools", mm(c.pools)); put("stocks", mm(c.stocks))
                put("damage", JSONObject().apply { for ((k, v) in c.damage) put(k, v) })
                put("dead", JSONArray(c.deadStatics)); put("esc", c.esc)
            })
        }
    }.toString()

    companion object {
        fun fromJson(s: String?): Campaign? = try {
            val o = JSONObject(s!!)
            Campaign(o.getString("side")).apply {
                night = o.getInt("night")
                val t = o.getJSONObject("totals"); totals["IN"] = t.getInt("IN"); totals["PK"] = t.getInt("PK")
                val r = o.getJSONArray("results"); for (i in 0 until r.length()) { val a = r.getJSONArray(i); results.add(intArrayOf(a.getInt(0), a.getInt(1), a.getInt(2))) }
                o.optJSONObject("carry")?.let { c ->
                    fun mm(j: JSONObject): Map<String, Map<String, Int>> {
                        val out = LinkedHashMap<String, Map<String, Int>>()
                        for (k in j.keys()) { val v = j.getJSONObject(k); out[k] = LinkedHashMap<String, Int>().apply { for (a in v.keys()) put(a, v.getInt(a)) } }
                        return out
                    }
                    val dmg = c.getJSONObject("damage"); val dead = c.getJSONArray("dead")
                    carry = Carry(mm(c.getJSONObject("pools")), mm(c.getJSONObject("stocks")),
                        LinkedHashMap<String, Int>().apply { for (k in dmg.keys()) put(k, dmg.getInt(k)) },
                        List(dead.length()) { dead.getString(it) }, c.getDouble("esc"))
                }
            }
        } catch (e: Exception) { null }
    }
}

/**
 * The game controller and in-game HUD (a port of js/ui.js): game loop, event feed and sounds,
 * decision cards, the selection panel and mission planning, missions list, campaign and replay.
 */
class Game(val act: MainActivity, val ui: Ui, val sound: Sound) {
    val R = Renderer(ui.density, ui.mono)
    val st = UiState()
    var sim: Sim? = null; private set
    val root = FrameLayout(act)
    lateinit var screens: Screens
    var training: Training? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("kc", android.content.Context.MODE_PRIVATE)

    private var evSeen = 0
    private var panelTimer = 0.0
    var debriefShown = false
    var campaign: Campaign? = null
    var current: Current? = null
    class Current(val nightIdx: Int, val side: String, val campaign: Boolean)
    private var replay: Replay? = null
    private class Replay { var i = 0; var playing = true; var acc = 0.0 }
    private var attract: Sim? = null
    private var attractCam = 0.0
    private val rnd = java.util.Random()

    val campNights = Data.NIGHTS.count { !it.scenario }

    companion object {
        val THEATRE = doubleArrayOf(28.4, 70.6, 35.0, 77.6)
        val SUBCONTINENT = doubleArrayOf(5.0, 60.0, 38.5, 97.0)
        val SIDE_NAME = mapOf("IN" to "India", "PK" to "Pakistan")
        val SHORT = mapOf("IN" to "IND", "PK" to "PAK")
        val NET = mapOf("IN" to "IAF data link (ODL/IACCS)", "PK" to "Link-17 network")
        private val ATTRACT_SHOTS = listOf(
            doubleArrayOf(29.5, 71.5, 34.8, 77.2), doubleArrayOf(31.0, 72.5, 34.6, 76.0), doubleArrayOf(5.0, 60.0, 38.5, 97.0),
            doubleArrayOf(28.6, 70.0, 32.6, 75.5), doubleArrayOf(27.5, 67.5, 36.0, 79.5),
        )
        fun fmtT(s: Double): String { val v = max(0.0, s); return "${floor(v / 60).toInt()}:${String.format("%02d", floor(v % 60).toInt())}" }
    }

    fun nightCfg(idx: Int, carry: Carry? = null): SimConfig {
        val n = Data.NIGHTS[idx]
        return SimConfig("${n.label}: ${n.title}", n.startH, n.startM, n.duration, n, carry)
    }

    // ---------- views ----------
    private val topBar = LinearLayout(act)
    private val clock = ui.text("----Z", 13f, C.WHITE)
    private val speedBtns = ArrayList<Pair<Int, TextView>>()
    private val score = ui.text("", 11f)
    private val btnMissions = iconBtn("▤ 0") { toggleDrawer("missions") }
    private val btnSound = iconBtn(if (sound.muted) "🔇" else "🔊") { sound.setMuted(!sound.muted); syncSound() }
    private val btnSensors = iconBtn("◎") { R.showSensors = !R.showSensors; styleIcon(it, R.showSensors) }
    private val btnLog = iconBtn("≡") { toggleDrawer("log") }
    private val btnMenu = iconBtn("❚❚ MENU") { pauseMenu() }
    private val ticker = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val panelScroll = ScrollView(act)
    private val panel = ui.col()
    private val panelInfo = ui.text("", 12f)
    private val panelHead = LinearLayout(act)
    private val panelBtns = ui.col()
    private val hint = ui.text("", 12f, C.WHITE)
    private val card = ui.col()
    private val drawer = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val drawerTitle = ui.text("", 13f, C.CYAN)
    private val drawerBody = ui.col()
    private var drawerKind: String? = null
    private val logLines = ArrayList<GameEvent>()
    private val replayBar = LinearLayout(act)
    private val rpPlay = ui.button("II") { replay?.let { it.playing = !it.playing } }
    private val rpSlider = SeekBar(act)
    private val rpTime = ui.text("", 12f)
    val coach = ui.col()
    private val hud = FrameLayout(act)

    private fun iconBtn(label: String, onClick: (TextView) -> kotlin.Unit): TextView {
        lateinit var t: TextView
        t = ui.button(label, size = 13f) { sound.play("click"); onClick(t) }
        t.minWidth = ui.dp(38); t.minHeight = ui.dp(32); t.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        return t
    }
    private fun styleIcon(t: TextView, on: Boolean) {
        t.background = ui.box(if (on) (C.CYAN and 0x00ffffff) or 0x33000000 else 0x1a0a1a24, if (on) C.CYAN else C.LINE_HI, 5f)
    }

    fun build() {
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        // Top bar
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.background = ui.box(C.PANEL, C.LINE, 0f)
        topBar.setPadding(ui.dp(8), ui.dp(4), ui.dp(6), ui.dp(4))
        val brand = ui.text(html("KILL<font color='${C.hex(C.RED)}'>CHAIN</font>"), 14f, C.CYAN).apply { letterSpacing = 0.12f }
        topBar.addView(brand)
        topBar.addView(clock, ui.lp(-2, -2).apply { leftMargin = ui.dp(10) })
        val seg = LinearLayout(act)
        for (v in listOf(0, 1, 5, 15, 40)) {
            val b = ui.button(if (v == 0) "II" else "$v×", size = 12f) { sound.play("click"); setSpeed(v) }
            b.minWidth = ui.dp(36); b.minHeight = ui.dp(32); b.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
            speedBtns.add(v to b)
            seg.addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(3) })
        }
        topBar.addView(seg, ui.lp(-2, -2).apply { leftMargin = ui.dp(8) })
        score.maxLines = 2; score.textSize = 10.5f; score.setLineSpacing(0f, 1f); score.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(score, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(10); rightMargin = ui.dp(6) })
        for (b in listOf(btnMissions, btnLog, btnSensors, btnSound, btnMenu)) topBar.addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(4) })
        styleIcon(btnSensors, true); syncSound()
        hud.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // Ticker
        hud.addView(ticker, ui.flp(ui.dp(420), -2, Gravity.BOTTOM or Gravity.START, 10, 0, 0, 10))
        // Panel (right)
        panel.background = ui.box(C.PANEL, C.LINE_HI, 8f)
        panel.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12))
        panelHead.orientation = LinearLayout.HORIZONTAL; panelHead.gravity = Gravity.CENTER_VERTICAL
        panel.addView(panelHead)
        panel.addView(panelInfo, ui.lp(top = 4))
        panel.addView(panelBtns, ui.lp(top = 8))
        panelScroll.addView(panel)
        panelScroll.isVerticalScrollBarEnabled = false
        panelScroll.visibility = View.GONE
        hud.addView(panelScroll, ui.flp(ui.dp(330), -2, Gravity.END or Gravity.TOP, 0, 50, 8, 8))
        // Hint
        hint.background = ui.box(0xee0a1824.toInt(), C.CYAN, 6f)
        hint.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); hint.gravity = Gravity.CENTER
        hint.visibility = View.GONE
        hud.addView(hint, ui.flp(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 80, 0, 80, 64))
        // Decision card
        card.background = ui.box(0xf2081420.toInt(), C.HOT, 8f, 1.5f)
        card.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); card.visibility = View.GONE
        hud.addView(card, ui.flp(ui.dp(380), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 52, 0, 0))
        // Drawer: log or missions
        drawer.background = ui.box(C.PANEL, C.LINE_HI, 8f)
        drawer.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        val dh = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        dh.addView(drawerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        dh.addView(ui.button("✕", size = 13f) { toggleDrawer(null) }.apply { minWidth = ui.dp(36); minHeight = ui.dp(30); setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)) })
        drawer.addView(dh)
        drawer.addView(ScrollView(act).apply { addView(drawerBody) }, ui.lp(h = -1, top = 6))
        drawer.visibility = View.GONE
        hud.addView(drawer, ui.flp(ui.dp(360), -1, Gravity.START or Gravity.TOP, 8, 50, 0, 8))
        // Replay bar
        replayBar.orientation = LinearLayout.HORIZONTAL; replayBar.gravity = Gravity.CENTER_VERTICAL
        replayBar.background = ui.box(C.PANEL, C.LINE_HI, 8f); replayBar.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        replayBar.addView(ui.text("REPLAY", 12f, C.HOT))
        replayBar.addView(rpPlay, ui.lp(-2, -2).apply { leftMargin = ui.dp(8) })
        rpSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) replay?.let { it.i = p; it.acc = 0.0 } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        replayBar.addView(rpSlider, LinearLayout.LayoutParams(0, -2, 1f))
        replayBar.addView(rpTime)
        replayBar.addView(ui.button("EXIT") { exitReplay(); showDebrief() }, ui.lp(-2, -2).apply { leftMargin = ui.dp(8) })
        replayBar.visibility = View.GONE
        hud.addView(replayBar, ui.flp(-1, -2, Gravity.BOTTOM, 60, 0, 60, 12))
        // Training coach
        coach.background = ui.box(0xf2081420.toInt(), C.CYAN, 8f, 1.5f)
        coach.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); coach.visibility = View.GONE
        hud.addView(coach, ui.flp(ui.dp(420), -2, Gravity.BOTTOM or Gravity.START, 10, 0, 0, 10))
        hud.visibility = View.GONE
        R.onMapReady = { act.view.invalidate() }
    }

    /** Keeps the HUD clear of notches and rounded corners. */
    fun setInsets(l: Int, t: Int, r: Int, b: Int) { hud.setPadding(l, t, r, b) }

    fun syncSound() { btnSound.text = if (sound.muted) "🔇" else "🔊"; styleIcon(btnSound, !sound.muted); screens.syncSound() }

    private var sized = false
    fun resize(w: Float, h: Float) {
        R.resize(w, h)
        if (!sized) { sized = true; R.fit(SUBCONTINENT, 10.0) }
        val narrow = w < 900
        topBar.getChildAt(0).visibility = if (w < 820) View.GONE else View.VISIBLE
        btnMenu.text = if (narrow) "❚❚" else "❚❚ MENU"
        // Phones in landscape: narrower side panels so more of the map stays visible.
        (panelScroll.layoutParams as FrameLayout.LayoutParams).width = ui.dp(if (narrow) 300 else 340)
        (coach.layoutParams as FrameLayout.LayoutParams).width = ui.dp(if (narrow) 360 else 420)
        (ticker.layoutParams as FrameLayout.LayoutParams).width = ui.dp(if (narrow) 380 else 460)
        (drawer.layoutParams as FrameLayout.LayoutParams).width = ui.dp(if (narrow) 320 else 380)
        hud.requestLayout()
    }

    // ---------- lifecycle ----------
    fun start(side: String, cfg: SimConfig, fitTo: DoubleArray? = null, isTraining: Boolean = false): Sim {
        st.side = side; st.sel = null; st.plan = null; st.training = isTraining
        exitReplay()
        R.forget()
        val s = Sim(cfg, side)
        sim = s
        evSeen = 0
        debriefShown = false
        ticker.removeAllViews(); logLines.clear(); drawerBody.removeAllViews()
        closeCard()
        toggleDrawer(null)
        screens.hideAll()
        hud.visibility = View.VISIBLE
        if (fitTo == null) R.fit(SUBCONTINENT, 10.0)
        R.flyTo(R.viewFor(fitTo ?: THEATRE, 16.0), if (fitTo != null) 1800.0 else 2600.0)
        attract = null
        setSpeed(0)
        renderPanel(true)
        return s
    }
    private fun Renderer.viewFor(r: DoubleArray, pad: Double) = viewFor(r[0], r[1], r[2], r[3], pad)

    fun startNight(idx: Int, side: String, camp: Boolean) {
        current = Current(idx, side, camp)
        if (!camp) campaign = null
        start(side, nightCfg(idx, if (camp) campaign?.carry else null))
        screens.briefing(idx, side)
    }

    /** After the briefing: AEW&C orbits fly themselves for both sides. */
    fun begin(idx: Int, side: String) {
        val s = sim ?: return
        val plan = Data.NIGHTS[idx].ai[side]
        for (a in plan?.aew ?: emptyList()) {
            val b = s.base(a.base) ?: continue
            s.createMission(com.killchain.core.MissionSpec("aew", side, a.base, b.home ?: "", 1, "AEW", at = Geo.project(a.lat, a.lon)))
        }
        renderMissions()
        setSpeed(5)
        hintMsg("Tap one of your air bases (□ with a number) and choose a mission")
    }

    fun showMenu() {
        st.training = false
        training?.stop()
        exitReplay(); closeCard()
        sim = null
        R.forget()
        attractCam = 0.0
        hud.visibility = View.GONE
        toggleDrawer(null)
        screens.menuHome()
    }

    fun loadCampaign(): Campaign? = Campaign.fromJson(prefs.getString("campaign", null))
    fun saveCampaign() { campaign?.let { prefs.edit().putString("campaign", it.toJson()).apply() } }

    // ---------- speed ----------
    fun setSpeed(v: Int) {
        if (v > 0) st.lastSpeed = v
        st.speed = v
        for ((k, b) in speedBtns) {
            b.background = ui.box(if (k == v) C.CYAN else 0x1a0a1a24, if (k == v) C.CYAN else C.LINE_HI, 5f)
            b.setTextColor(if (k == v) C.BG else C.CYAN)
        }
        updateTop()
    }
    fun togglePause() { if (replay != null) replay!!.playing = !replay!!.playing else setSpeed(if (st.speed > 0) 0 else st.lastSpeed) }

    private var resumeSpeed: Int? = null
    fun pauseMenu() {
        if (sim == null) return
        resumeSpeed = if (st.speed > 0) st.speed else resumeSpeed
        setSpeed(0)
        screens.pause { resumeSpeed?.let { setSpeed(it) }; resumeSpeed = null }
    }
    fun pauseForBackground() { if (sim != null && st.speed > 0 && replay == null) { resumeSpeed = st.speed; setSpeed(0) } }

    // ---------- hint ----------
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(250).withEndAction { hint.visibility = View.GONE }.start() }
    fun hintMsg(text: String) {
        hint.text = text
        hint.alpha = 1f; hint.visibility = View.VISIBLE
        (hint.layoutParams as FrameLayout.LayoutParams).apply {
            // Stay clear of the coach card during training.
            bottomMargin = ui.dp(if (coach.visibility == View.VISIBLE) 12 else 64)
            rightMargin = ui.dp(if (panelScroll.visibility == View.VISIBLE) 350 else 80)
            hint.layoutParams = this
        }
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, 3600)
    }

    // ---------- events ----------
    private fun evColor(cls: String) = when (cls) {
        "warn", "loss" -> C.RED; "kill" -> C.WHITE; "impact" -> C.HOT; "shot" -> C.CYAN; "good" -> C.PK; "intel" -> Color.parseColor("#d5b8ff"); else -> C.TEXT
    }
    private fun evLine(s: Sim, e: GameEvent, size: Float): TextView {
        val t = ui.text(html("<font color='${C.hex(C.DIM)}'>${s.clock(e.t).substring(0, 4)}</font>&nbsp;&nbsp;${esc(e.text)}"), size, evColor(e.cls))
        if (e.cls == "loss") t.paint.isFakeBoldText = true
        if (e.cls == "intel") { t.background = ui.box(0x33b48cff, 0xffb48cff.toInt(), 3f); t.setPadding(ui.dp(5), ui.dp(2), ui.dp(5), ui.dp(2)) }
        return t
    }

    private fun pumpEvents() {
        val s = sim ?: return
        for (e in s.events) {
            if (e.n <= evSeen) continue
            evSeen = e.n
            if (e.side != st.side && e.side != "ALL") continue
            soundFor(e)
            logLines.add(0, e)
            if (logLines.size > 300) logLines.removeAt(logLines.size - 1)
            if (drawerKind == "log") { drawerBody.addView(evLine(s, e, 11.5f), 0, ui.lp(top = 3)); if (drawerBody.childCount > 300) drawerBody.removeViewAt(drawerBody.childCount - 1) }
            if (e.cls != "info" || !Regex("New track|PITBULL|refuelled").containsMatchIn(e.text)) {
                if (st.training && e.cls == "info") continue
                val line = evLine(s, e, 12f)
                line.setShadowLayer(3f, 1f, 1f, Color.BLACK)
                ticker.addView(line, ui.lp(top = 2))
                main.postDelayed({ line.animate().alpha(0f).setDuration(400).withEndAction { ticker.removeView(line) }.start() }, 8000)
                while (ticker.childCount > 5) ticker.removeViewAt(0)
            }
        }
    }
    private fun soundFor(e: GameEvent) {
        val t = e.text
        when {
            e.cls == "intel" -> sound.play("intel")
            Regex("STRIKER (INBOUND|CLOSING)").containsMatchIn(t) -> sound.play("alarm")
            t.contains("LAUNCH WARNING") -> sound.play("rwr")
            t.contains("MISSILE WARNING") || t.contains("MISSILE ACTIVE") -> sound.play("active")
            t.contains("airborne from") || t.contains("scrambles") -> sound.play("takeoff", 0.8)
            t.contains("PITBULL") -> sound.play("lock")
            e.cls == "impact" -> sound.play("impact", if (e.side == "ALL") 0.9 else 1.0)
            t.contains(" LOST,") -> sound.play("loss")
            e.cls == "kill" || t.contains("DAMAGED") || t.contains("DESTROYED") -> sound.play("hit", 0.8)
            e.cls == "shot" && Regex("FOX|BIRD AWAY|launched").containsMatchIn(t) -> sound.play("launch", if (t.contains("BIRD AWAY")) 0.7 else 1.0)
        }
    }

    private fun updateTop() {
        val s = sim ?: return
        clock.text = s.clock() + if (st.speed == 0) " ❚❚" else ""
        if (st.training) { score.text = html(Ui.col(C.IN, "TRAINING")); return }
        val me = st.side
        val rem = max(0.0, (s.night?.ceasefireAt ?: s.duration) - s.t)
        val escv = s.esc.roundToInt()
        val escCol = if (escv >= 80) C.RED else if (escv >= 50) C.HOT else C.PK
        val jammed = s.ew[ENEMY[me]]!!.jam.until > s.t
        val sc = s.score[me]!!
        score.text = html(
            Ui.col(C.IN, "IND <b>${s.points("IN")}</b>") + " · " + Ui.col(C.PK, "<b>${s.points("PK")}</b> PAK") +
                "&nbsp;&nbsp;${if (s.night?.ceasefireAt != null) "CEASEFIRE" else "END"} <b>${fmtT(rem)}</b>" +
                (if (jammed) "&nbsp;&nbsp;" + Ui.warn("⚡ LINK JAMMED") else "") + "<br>" +
                Ui.col(escCol, "ESC ${bar(escv)} $escv%") +
                "&nbsp;&nbsp;AIR <b>${s.airborne(me)}/${Data.AIR_CAP[me]}</b>" +
                "&nbsp;&nbsp;LOST <b>${sc.lost + sc.groundLost}</b>",
        )
    }
    private fun bar(pct: Int): String { val n = (pct / 10).coerceIn(0, 10); return "▮".repeat(n) + Ui.k("▯".repeat(10 - n)) }

    // ---------- decision cards ----------
    private class CardState(val p: Prompt, var t0: Double, val ttl: Double, val prevSpeed: Int)
    private var cardSt: CardState? = null
    private val cardBar = View(act)
    private val cardTime = ui.text("", 12f, C.HOT)
    private fun now() = SystemClock.uptimeMillis().toDouble()

    private fun showCard(p: Prompt) {
        cardSt = CardState(p, now(), 10000.0, st.speed)
        if (st.speed > 1) setSpeed(1)
        card.removeAllViews()
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.text("DECISION", 12f, C.HOT).apply { letterSpacing = 0.15f }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(cardTime)
        card.addView(head)
        card.addView(ui.text(p.text, 12.5f, C.WHITE), ui.lp(top = 6))
        val row = LinearLayout(act)
        for ((i, o) in p.options.withIndex()) {
            val b = ui.button(o.label, o.text, accent = if (o.key == p.def) C.HOT else C.CYAN, on = o.key == p.def, size = 12f) { sound.play("click"); answerCard(o.key) }
            row.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = ui.dp(6) })
        }
        card.addView(row, ui.lp(top = 8))
        cardBar.background = ui.box(C.HOT, 0, 1f)
        card.addView(cardBar, LinearLayout.LayoutParams(-1, ui.dp(3)).apply { topMargin = ui.dp(8) })
        card.visibility = View.VISIBLE
        val ref = p.ref
        if (ref != null && (ref.kind == "air" || ref.kind == "base")) {
            if (ref.kind == "air" && ref.side == st.side) select(ref)
            R.flyTo(View2(ref.x, ref.y, max(R.view.scale, 0.9)), 700.0)
        }
    }
    private fun answerCard(k: String) {
        val c = cardSt ?: return
        sim?.answer(c.p, k)
        val prev = c.prevSpeed
        closeCard()
        if (prev > 1 && st.speed == 1) setSpeed(prev)
        renderPanel(true)
    }
    private fun closeCard() { cardSt = null; card.visibility = View.GONE }
    private fun tickCard(dt: Double) {
        val s = sim
        if (s == null || st.training || s.over || debriefShown) { if (cardSt != null) closeCard(); return }
        val c = cardSt
        if (c == null) { s.prompts.firstOrNull { !it.done }?.let { showCard(it) }; return }
        if (c.p.done) { closeCard(); return }
        if (st.speed == 0) { c.t0 += dt * 1000; return }
        val left = c.ttl - (now() - c.t0)
        val f = max(0.0, left / c.ttl)
        val lp = cardBar.layoutParams as LinearLayout.LayoutParams
        lp.width = ((card.width - card.paddingLeft - card.paddingRight) * f).toInt().coerceAtLeast(1); cardBar.layoutParams = lp
        cardTime.text = "${kotlin.math.ceil(max(0.0, left) / 1000).toInt()}s"
        if (left <= 0) answerCard(c.p.def)
    }

    // ---------- selection & panel ----------
    fun select(ref: Any?) { st.sel = ref; renderPanel(true) }
    private var lastSig = ""
    private val describer by lazy { Panel(this) }

    fun renderPanel(force: Boolean) {
        val s = sim
        val model: PanelModel?
        if (s != null && st.plan != null) model = describer.plan(s)
        else {
            val ref = st.sel
            val gone = ref == null || (ref is Unit && ref.kind == "air" && ref.state != "air") ||
                (ref is Ent && ref.kind == "cruise" && ref.dead) ||
                (ref is Ent && ref.side != st.side && ref.kind != "site" && ref.kind != "base" && s?.trackFor(st.side, ref) == null)
            if (s == null || gone) { if (st.sel != null) st.sel = null; model = null }
            else model = describer.describe(s, ref!!)
        }
        if (model == null) {
            panelScroll.visibility = View.GONE; R.hudInset = 0f; lastSig = ""
            return
        }
        panelScroll.visibility = View.VISIBLE
        panelHead.removeAllViews()
        panelHead.addView(ui.text(html(model.title), 14f, model.color).apply { paint.isFakeBoldText = true }, LinearLayout.LayoutParams(0, -2, 1f))
        if (model.tag != null) panelHead.addView(ui.text(html(model.tag), 10f, C.HOT).apply { background = ui.box(0, C.LINE_HI, 3f); setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1)) })
        panelHead.addView(ui.button("✕", size = 13f) { sound.play("click"); st.plan = null; select(null) }.apply {
            minWidth = ui.dp(34); minHeight = ui.dp(30); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)); background = null; setTextColor(C.DIM)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(6) })
        panelInfo.text = html(model.info)
        val sig = model.sig()
        if (force || sig != lastSig) {
            lastSig = sig
            panelBtns.removeAllViews()
            for (it in model.items) panelBtns.addView(describer.view(it), ui.lp(top = 6))
        }
        panelBtns.visibility = if (model.items.isEmpty()) View.GONE else View.VISIBLE
    }

    fun followers(u: Unit): List<Unit> = sim!!.units.filter { it.lead === u && it.state == "air" && it.inForm }

    // ---------- picking & commands ----------
    class Hit(val ref: Ent, val kind: String, val d: Double)
    fun pick(sx: Float, sy: Float): Hit? {
        val s = sim ?: return null
        val cands = ArrayList<Hit>()
        fun add(ref: Ent, x: Double, y: Double, kind: String, bias: Double) {
            val p = (if (ref.kind == "air") R.drawnAt(ref) else null) ?: R.toScreen(x, y)
            val d = hypot((p.x - sx).toDouble(), (p.y - sy).toDouble())
            if (d < 30) cands.add(Hit(ref, kind, d - bias))
        }
        for (u in s.units) {
            if (u.side != st.side) continue
            if (u.kind == "air" && u.state != "air") continue
            add(u, u.x, u.y, "own", if (u.kind == "air") 4.0 else 0.0)
        }
        for (tr in s.tracks[st.side]!!.values) {
            val e = tr.ref
            if (e.kind == "cruise" && !R.missileVisible(e as com.killchain.core.Missile, st.side)) continue
            val p: com.killchain.core.XY = if (tr.isStatic) com.killchain.core.Pt(tr.x, tr.y) else if (e.kind == "cruise") e else s.trackPos(tr)
            add(e, p.x, p.y, "enemy", if (e.kind == "air") 6.0 else 1.0)
        }
        for (x in s.sites) add(x, x.x, x.y, "site", 2.0)
        for (b in s.bases) add(b, b.x, b.y, if (b.side == st.side) "base" else "ebase", if (b.side == st.side) -2.0 else -1.0)
        return cands.minByOrNull { it.d }
    }

    fun onTap(sx: Float, sy: Float) {
        val s = sim ?: return
        if (replay != null) return
        val w = R.toWorld(sx, sy)
        st.marks.add(Mark(w.x, w.y, now() / 1000))
        if (st.marks.size > 8) st.marks.removeAt(0)
        val hit = pick(sx, sy)
        val pl = st.plan
        if (pl != null && pl.addingWp) {
            if (pl.via.size < 6) pl.via.add(w)
            pl.addingWp = false
            hintMsg("Waypoint ${pl.via.size} set. Add another, or LAUNCH.")
            renderPanel(true)
            return
        }
        if (pl != null) {
            val ok = describer.planTarget(s, pl, hit)
            if (!ok && pl.kind == "chase" && hit != null && hit.ref.kind == "air" && s.isDrone(hit.ref)) { hintMsg("Fighters can't stop a drone swarm: only SAMs can shoot drones down."); return }
            if (!ok) {
                hintMsg(when (pl.kind) { "chase" -> "Tap an enemy aircraft"; "escort" -> "Tap one of your ground-attack flights"; else -> "Tap an enemy site, radar, SAM or air base" })
                return
            }
            if (pl.kind == "strike" || pl.kind == "attack" || pl.kind == "feint") {
                if (pl.kind == "strike" && pl.escortN == null) pl.escortN = min(1, describer.escortRoom(s, pl))
                renderPanel(true)
                return
            }
            describer.launchPlan(s)
            return
        }
        val sel = st.sel as? Unit
        val ownAir = sel != null && sel.kind == "air" && sel.side == st.side && sel.state == "air" && !sel.ghost && sel.d.role != "aew"
        if (ownAir && hit != null && hit.ref !== sel) {
            for (kind in listOf("chase", "strike", "attack", "escort")) {
                val can = when (kind) { "attack" -> s.isDrone(sel!!); "strike" -> s.strikeWeapon(sel!!) != null; else -> sel!!.d.role == "fighter" && s.hasAAM(sel) }
                if (!can) continue
                val trial = Plan(kind, sel)
                if (describer.planTarget(s, trial, hit)) { st.plan = trial; describer.launchPlan(s); return }
            }
        }
        if (hit != null) { if (st.sel === hit.ref) select(null) else select(hit.ref); sound.play("click"); return }
        select(null)
    }

    // ---------- missions list & log drawer ----------
    fun toggleDrawer(kind: String?) {
        drawerKind = if (kind == null || kind == drawerKind) null else kind
        drawer.visibility = if (drawerKind == null) View.GONE else View.VISIBLE
        styleIcon(btnMissions, drawerKind == "missions"); styleIcon(btnLog, drawerKind == "log")
        if (drawerKind == "log") {
            drawerTitle.text = "EVENT LOG"
            drawerBody.removeAllViews()
            val s = sim
            if (s != null) for (e in logLines) drawerBody.addView(evLine(s, e, 11.5f), ui.lp(top = 3))
        } else if (drawerKind == "missions") { drawerTitle.text = "MISSIONS"; missionsSig = ""; renderMissions() }
    }
    private var missionsSig = ""
    fun renderMissions() {
        val s = sim ?: return
        val list = s.missions.filter { it.side == st.side && it.status != "done" }
        btnMissions.text = "▤ ${list.size}"
        if (drawerKind != "missions") return
        val sig = list.joinToString("|") { "${it.id}:${it.status}:${it.repeat}" }
        if (sig == missionsSig) return
        missionsSig = sig
        drawerBody.removeAllViews()
        if (list.isEmpty()) { drawerBody.addView(ui.text("No missions yet. Tap one of your air bases to plan one.", 12f, C.DIM)); return }
        for (m in list) {
            val tgt = m.target?.let { " → ${it.id}" } ?: if (m.kind == "escort") " → ${s.missions.find { x -> x.id == m.escortOf }?.name ?: ""}" else ""
            val whenTxt = if (m.status == "scheduled") " (${s.clock(m.launchAt).substring(0, 4)}Z)" else ""
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; background = ui.box(0x14000000, C.LINE, 5f); setPadding(ui.dp(8), ui.dp(6), ui.dp(6), ui.dp(6)) }
            val info = ui.text(html("<b>${m.name}</b> ${m.n}× ${Data.AIRCRAFT[m.type]?.name ?: m.type} · ${m.base}$tgt<br>${Ui.k(m.status + whenTxt + if (m.repeat) " · relief on" else "")}"), 11.5f)
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            info.setOnClickListener {
                val u = m.unit?.takeIf { it.state == "air" }
                val focus: Ent? = u ?: s.base(m.base)
                if (focus != null) { select(focus); R.flyTo(View2(focus.x, focus.y, max(R.view.scale, 0.8)), 600.0) }
            }
            if (m.kind == "cap" || m.kind == "aew") row.addView(ui.button("⟳", on = m.repeat, size = 13f) { m.repeat = !m.repeat; missionsSig = ""; renderMissions() }.apply { minWidth = ui.dp(36) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(4) })
            row.addView(ui.button("✕", accent = C.RED, size = 13f) { s.cancelMission(m); missionsSig = ""; renderMissions() }.apply { minWidth = ui.dp(36) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(4) })
            drawerBody.addView(row, ui.lp(top = 5))
        }
    }

    // ---------- debrief ----------
    fun showDebrief() {
        debriefShown = true
        setSpeed(0)
        closeCard()
        val s = sim ?: return
        val sum = s.summary()
        val cur = current
        val camp = campaign
        if (cur != null && cur.campaign && camp != null && camp.night == cur.nightIdx) {
            val pi = if (sum.crisis) 0 else sum.sides["IN"]!!.points; val pp = if (sum.crisis) 0 else sum.sides["PK"]!!.points
            camp.results.add(intArrayOf(s.night!!.n, pi, pp))
            camp.totals["IN"] = camp.totals["IN"]!! + pi; camp.totals["PK"] = camp.totals["PK"]!! + pp
            camp.carry = s.carryOut()
            camp.night = cur.nightIdx + 1
            saveCampaign()
        }
        screens.debrief(s, sum)
    }

    // ---------- replay ----------
    fun enterReplay() {
        val s = sim ?: return
        if (s.replay.isEmpty()) return
        replay = Replay()
        setSpeed(15)
        replayBar.visibility = View.VISIBLE
        topBarReplay(true)
        rpSlider.max = s.replay.size - 1; rpSlider.progress = 0
        select(null)
        screens.hideAll()
        R.flyTo(R.viewFor(THEATRE, 16.0), 1000.0)
    }
    private fun topBarReplay(on: Boolean) { score.visibility = if (on) View.INVISIBLE else View.VISIBLE; btnMissions.visibility = if (on) View.GONE else View.VISIBLE }
    fun exitReplay() {
        if (replay == null) return
        replay = null
        replayBar.visibility = View.GONE
        topBarReplay(false)
    }
    private fun stepReplay(dt: Double) {
        val r = replay ?: return
        val s = sim ?: return
        if (r.playing) {
            r.acc += dt * st.speed
            while (r.acc >= 3 && r.i < s.replay.size - 1) { r.acc -= 3; r.i++ }
            if (r.i >= s.replay.size - 1) r.playing = false
        }
        rpSlider.progress = r.i
        rpPlay.text = if (r.playing) "II" else "▶"
        val f = s.replay[r.i]
        rpTime.text = "  ${s.clock(f.t)} · ESC ${f.esc}%  "
        clock.text = s.clock(f.t)
    }

    // ---------- main loop ----------
    fun frame(dt: Double) {
        val s = sim
        if (replay != null && s != null) { stepReplay(dt); return }
        if (s == null) {
            stepAttract(dt)
            return
        }
        if (!s.over) s.update(dt * st.speed)
        pumpEvents()
        tickCard(dt)
        panelTimer -= dt
        if (panelTimer <= 0) { if (st.plan == null) renderPanel(false); updateTop(); renderMissions(); panelTimer = 0.25 }
        if (st.training) training?.tick()
        if (s.over && !debriefShown && !st.training) { renderPanel(false); updateTop(); showDebrief() }
    }

    fun draw(c: Canvas) {
        val s = sim
        val r = replay
        if (r != null && s != null) { R.drawReplay(c, s, s.replay[r.i]); return }
        if (s == null) { val a = attract; R.draw(c, a, spectatorUi.also { it.side = "ALL" }); return }
        R.draw(c, s, st)
    }
    private val spectatorUi = UiState()

    private fun stepAttract(dt: Double) {
        var a = attract
        if (a == null || a.over || a.t > 40 * 60) {
            a = Sim(nightCfg(rnd.nextInt(Data.NIGHTS.size)), null)
            a.update(60.0 * 9)
            attract = a
            R.forget()
        }
        a.update(dt * 6)
        attractCam -= dt
        if (attractCam <= 0) {
            val shot = ATTRACT_SHOTS[rnd.nextInt(ATTRACT_SHOTS.size)]
            R.flyTo(R.viewFor(shot, 10.0), 6000.0)
            attractCam = 11.0
        }
    }

    /** Android back: close what is open, else pause. Returns false to leave the app. */
    fun back(): Boolean {
        if (screens.back()) return true
        if (drawerKind != null) { toggleDrawer(null); return true }
        if (st.plan != null) { st.plan = null; renderPanel(true); return true }
        if (st.sel != null) { select(null); return true }
        if (replay != null) { exitReplay(); showDebrief(); return true }
        if (sim != null) { pauseMenu(); return true }
        return false
    }
}
