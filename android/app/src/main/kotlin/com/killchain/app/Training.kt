package com.killchain.app

import android.view.View
import android.widget.LinearLayout
import com.killchain.app.Ui.Companion.html
import com.killchain.core.DEG
import com.killchain.core.Data
import com.killchain.core.FlightDef
import com.killchain.core.Geo
import com.killchain.core.Missile
import com.killchain.core.Order
import com.killchain.core.Sim
import com.killchain.core.SimConfig
import com.killchain.core.Unit
import com.killchain.core.dist
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Training: four scripted lessons on missile evasion and the kill chain (port of js/training.js). */
class Training(private val g: Game) {
    private val ui get() = g.ui
    private fun P(la: Double, lo: Double) = Geo.project(la, lo)
    private fun warnP(s: String) = "<font color='${C.hex(C.RED)}'>$s</font>"
    private fun goodP(s: String) = "<font color='${C.hex(C.PK)}'>$s</font>"
    private fun pc(m: Missile) = ((m.pk ?: 0.0) * 100).roundToInt()

    private fun scn(): SimConfig {
        fun f(side: String, cs: String, type: String, lo: String, base: String, la: Double, lon: Double, r: Double) =
            FlightDef(side, cs, type, 1, lo, base, P(la, lon), Order.orbit(P(la, lon).x, P(la, lon).y, r))
        return SimConfig("Training", 20, 0, 1e9, null, null, listOf(
            f("IN", "STUDENT", "RAFALE", "RAFALE_CAP", "ADAMPUR", 30.6, 75.2, 15.0),
            f("PK", "BANDIT", "J10C", "J10C", "RAFIQUI", 30.6, 74.2, 15.0),
            f("PK", "SKYEYE", "ERIEYE", "AEW", "RAFIQUI", 30.9, 70.5, 20.0),
        ), emptyBases = true, noStatics = true)
    }

    private fun place(u: Unit, lat: Double, lon: Double, hdgDeg: Double) {
        val p = P(lat, lon); val h = hdgDeg * DEG
        u.x = p.x; u.y = p.y; u.hdg = h; u.spd = u.d.speed; u.state = "air"; u.count = 1; u.threats = mutableListOf(); u.defending = false
        u.manualDef = null; u.engage = null; u.anchor = null; u.fuel = 9000.0; u.lastShot = -99.0
        u.wpn = LinkedHashMap(Data.LOADOUTS[u.loadoutKey] ?: emptyMap())
        u.order = Order.orbit(p.x + sin(h) * 400, p.y - cos(h) * 400, 15.0)
    }

    private class Lesson(val title: String, val intro: String, val fireAt: Double?, val warn: String?,
                         val setup: (Unit, Unit, Unit) -> kotlin.Unit, val result: (Missile, Unit, Set<String>, Unit) -> String)

    private val lessons = listOf(
        Lesson("LESSON 1 OF 4 · THE LONG SHOT",
            "A J-10CE (<b>BANDIT</b>, green) is 100 km west of you with its radar <b>ON</b>, locked on your Rafale. It will fire a <b>PL-15E</b>.<br><br>Because its radar is locked on you, your radar warning receiver will hear the launch. You get a <b>launch warning</b> right away.",
            3.0,
            "${warnP("<b>LAUNCH WARNING</b>, PL-15E, 100 km.")}<br><br>The motor burns for 12 seconds. After that the missile glides and keeps <b>slowing down</b>.<br><br>Tap <b>BREAK</b>: your jet turns its tail to the missile and runs at full speed. Watch the missile's label. Its Mach number drops, and the line turns <b>grey</b> once it can no longer catch you.",
            { s, b, aew -> place(s, 30.6, 75.25, 270.0); place(b, 30.6, 74.21, 90.0); place(aew, 30.9, 70.5, 0.0); b.radarOn = true; b.radarMode = "on"; s.radarOn = true; s.radarMode = "on" },
            { m, s, used, _ ->
                when (m.end) {
                    "energy" -> "${goodP("<b>DEFEATED.</b>")} The PL-15E ran out of energy at Mach ${"%.1f".format(m.spd / 0.295)}, ${dist(m, s).roundToInt()} km short of you.<br><br>A long-range shot against a jet that turns away usually fails. Pilots know this, so they fire closer, or fire <b>without warning</b> (lesson 3)."
                    "miss" -> "${goodP("<b>MISSED.</b>")} It reached you but missed (hit chance ${pc(m)}%). ${if (used.contains("drag")) "" else "Breaking earlier would have made it run out of energy before reaching you."}"
                    else -> "${warnP("<b>HIT.</b>")} The missile still had energy when it reached you${if (used.isNotEmpty()) "" else ", because the jet only started defending at 40 km"}. Try again and tap <b>BREAK</b> as soon as the warning sounds."
                }
            }),
        Lesson("LESSON 2 OF 4 · THE SHORT SHOT",
            "This time the J-10CE fires from only <b>35 km</b>. At this range the PL-15E reaches you while still flying above Mach 4. <b>You can't outrun it.</b>",
            2.0,
            "${warnP("<b>LAUNCH WARNING</b>, PL-15E, 35 km.")} The line stays <b>red</b>: running won't work.<br><br>Tap <b>NOTCH</b>: fly at 90° to the missile. A pulse-Doppler radar picks targets out of the ground clutter by their <b>closing speed</b>. At 90°, yours is close to zero, so you blend into the clutter. The seeker may lose its lock, and the hit chance drops sharply.",
            { s, b, aew -> place(s, 30.6, 74.95, 270.0); place(b, 30.6, 74.59, 90.0); place(aew, 30.9, 70.5, 0.0); b.radarOn = true; b.radarMode = "on"; s.radarOn = true; s.radarMode = "on" },
            { m, _, used, _ ->
                if (m.end == "hit") "${warnP("<b>HIT.</b>")} ${if (used.contains("notch")) "Notching lowers the hit chance to under half, but it does not make you safe." else "Running away does not work against a missile this fast."} At this range there are no good options. The best defence is not being there."
                else "${goodP("<b>SURVIVED.</b>")} ${if (used.contains("notch")) "Beaming the missile spoiled its radar seeker" else "You got lucky"}${if (m.pk != null) " (final hit chance ${pc(m)}%)" else ""}.<br><br>Notching is a gamble: it only works against radar-guided missiles, and the missile keeps closing fast. Against a heat-seeker, flares do the work."
            }),
        Lesson("LESSON 3 OF 4 · THE SILENT SHOT",
            "Now the J-10CE keeps its radar <b>OFF</b> and only switches it on at the moment it fires. You can't see it at all. The <b>Erieye</b> AEW&amp;C (circle with a bar, to the west) tracks you instead and shares that track over <b>Link-17</b>.<br><br>The J-10 fires a PL-15E using the Erieye's data, and the missile receives course updates over the data link. Your warning receiver hears nothing. Press START and watch for the warning.",
            3.0,
            "${warnP("<b>MISSILE WARNING, no launch warning.</b>")}<br><br>The missile's own radar just switched on, about 25 km away. It has been flying at you for about a minute and you had no idea. It's fast and close. Choose <b>BREAK</b> or <b>NOTCH</b>.",
            { s, b, aew ->
                place(s, 30.6, 75.0, 270.0); place(b, 30.6, 74.2, 90.0); place(aew, 30.7, 73.15, 0.0)
                aew.order = Order.orbit(P(30.7, 73.15).x, P(30.7, 73.15).y, 15.0)
                b.radarOn = false; b.radarMode = "off"; s.radarOn = true; s.radarMode = "on"
            },
            { m, _, _, _ ->
                (if (m.end == "hit") warnP("<b>HIT.</b>") else "${goodP("<b>SURVIVED</b>")}${if (m.pk != null) " (hit chance was ${pc(m)}%)" else ""}.") +
                    "<br><br>This is the <b>kill chain</b>: a sensor (Erieye), a silent shooter (J-10CE) and a data-link missile (PL-15E) working together. The target only finds out at the very end.<br><br>Counters: stay out of range, push back or shoot down the AEW&amp;C, or jam the link."
            }),
        Lesson("LESSON 4 OF 4 · YOUR SHOT",
            "Your turn. The J-10CE is about 75 km west, heading for you with its radar <b>on</b>, which means it can be locked. (A jet with its radar <b>off</b> can't be locked by radar missiles at all.)<br><br>1. Tap <b>STUDENT</b> to select it.<br>2. Tap <b>RADAR ON</b>.<br>3. Tap the green <b>J-10 track</b> to attack it.<br><br>Your Rafale fires a <b>Meteor</b> and then <b>cranks</b>, turning 50° off so its radar keeps guiding the missile. Because your radar is on, the J-10 gets a launch warning and will defend.",
            null, null,
            { s, b, aew -> place(s, 30.6, 75.0, 270.0); place(b, 30.6, 74.22, 90.0); place(aew, 30.9, 70.5, 0.0); b.radarOn = true; b.radarMode = "on"; s.radarOn = false; s.radarMode = "off"; s.wpn = linkedMapOf("METEOR" to 2) },
            { m, _, _, b ->
                when (m.end) {
                    "hit" -> "${goodP("<b>SPLASH ONE J-10CE.</b>")}<br><br>The Meteor's ramjet kept it fast to the end, so the J-10 could not run it out of energy."
                    "energy" -> "${warnP("<b>OUT OF ENERGY.</b>")} The J-10 ran and your missile could not catch it. Fire from closer, inside the inner dashed ring."
                    else -> "${warnP("<b>MISSED.</b>")} The J-10 ${if (b.defMode == "notch") "notched" else "dragged"} and the Meteor missed (hit chance ${pc(m)}%). Even good shots miss, which is why real pilots often fire two."
                }
            }),
    )

    private var idx = 0
    private var phase = "off"
    private var t0 = 0.0
    private var m: Missile? = null
    private var fired = false
    private var warned = false
    private val used = HashSet<String>()

    private fun units(s: Sim): Triple<Unit, Unit, Unit> {
        fun f(cs: String) = s.units.first { it.callsign == cs }
        return Triple(f("STUDENT"), f("BANDIT"), f("SKYEYE"))
    }

    private fun coach(title: String, body: String, buttons: List<Triple<String, Boolean, () -> kotlin.Unit>>) {
        val c = g.coach
        c.removeAllViews()
        c.addView(ui.text(title, 12f, C.CYAN).apply { letterSpacing = 0.12f; paint.isFakeBoldText = true })
        c.addView(ui.text(html(body), 12.5f, C.TEXT), ui.lp(top = 6))
        val row = LinearLayout(ui.ctx)
        for ((i, b) in buttons.withIndex()) row.addView(ui.button(b.first, accent = if (b.second) C.HOT else C.CYAN, filled = b.second, size = 12f) { g.sound.play("click"); b.third() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = ui.dp(6) })
        c.addView(row, ui.lp(top = 10))
        c.visibility = View.VISIBLE
    }
    private fun hideCoach() { g.coach.visibility = View.GONE }

    fun begin() {
        g.screens.hideAll()
        val s = g.start("IN", scn(), doubleArrayOf(30.05, 73.0, 31.15, 75.8), isTraining = true)
        val (_, b, aew) = units(s)
        b.ai = false; b.roe = "hold"
        aew.ai = true
        showIntro(0)
    }

    private fun showIntro(i: Int) {
        idx = i; phase = "intro"
        val L = lessons[i]
        val s = g.sim ?: return
        g.setSpeed(0)
        s.missiles.clear()
        val (st, b, aew) = units(s)
        L.setup(st, b, aew)
        st.roe = "hold"
        s.sweep()
        g.select(null)
        coach(L.title, L.intro, listOf(Triple("START", true) { run() }, Triple("QUIT", false) { quit() }))
    }

    private fun run() {
        phase = "run"; t0 = g.sim!!.t; m = null; fired = false; warned = false; used.clear()
        hideCoach()
        g.setSpeed(5)
    }
    private fun defend(mode: String?) {
        val s = g.sim ?: return
        s.cmdDefend(units(s).first, mode)
        phase = "run"
        hideCoach()
        g.setSpeed(5)
    }
    private fun quit() { phase = "off"; hideCoach(); g.showMenu() }
    fun stop() { phase = "off"; hideCoach() }

    private fun finish() {
        phase = "off"
        g.act.getSharedPreferences("kc", android.content.Context.MODE_PRIVATE).edit().putBoolean("trained", true).apply()
        coach("TRAINING COMPLETE", "You've seen the four ideas behind the game:<br>· Long shots can be <b>outrun</b> (BREAK).<br>· Close shots can only be <b>spoiled</b> (NOTCH).<br>· Silent data-link shots give <b>no warning</b>.<br>· Your own radar-guided shots need <b>support</b> until PITBULL.<br><br>Now take command of a whole air force. Night 1 is a good start.", listOf(
            Triple("COMMAND INDIA", true) { hideCoach(); g.startNight(0, "IN", false) },
            Triple("COMMAND PAKISTAN", true) { hideCoach(); g.startNight(0, "PK", false) },
            Triple("MAIN MENU", false) { quit() },
        ))
    }

    /** Called every frame while training is active. */
    fun tick() {
        val s = g.sim ?: return
        if (phase == "off" || phase == "intro" || phase == "done") return
        val L = lessons[idx]
        val (st, b, _) = units(s)
        if (st.defending) st.defMode?.let { used.add(it) }
        val fa = L.fireAt
        if (fa != null && !fired && s.t - t0 >= fa) {
            fired = true
            s.sweep()
            b.lastShot = s.t; s.setRadar(b, true)
            s.launch(b, st, "PL15")
            b.wpn["PL15"] = (b.wpn["PL15"] ?: 1) - 1
            m = s.missiles.last()
        }
        if (fa == null && m == null) {
            m = s.missiles.firstOrNull { it.shooter === st && it.kind != "cruise" }
            if (m != null) g.hintMsg("FOX-3! Keep the J-10 inside your radar cone until the Meteor goes PITBULL")
        }
        val mm = m
        if (phase == "run" && mm != null && L.warn != null && !warned && st.threats.contains(mm)) {
            warned = true; phase = "warn"
            g.setSpeed(0)
            g.select(null)
            coach(L.title, L.warn, listOf(Triple("BREAK", true) { defend("drag") }, Triple("NOTCH", true) { defend("notch") }, Triple("LET THE JET DECIDE", false) { defend(null) }))
        }
        if (phase == "warn" && g.st.speed > 0) { phase = "run"; hideCoach() }
        if (mm != null && mm.dead) {
            phase = "done"; warned = false
            g.setSpeed(0)
            val text = L.result(mm, st, used, b)
            if (st.state != "air") { st.state = "air"; st.count = 1 }
            val last = idx == lessons.size - 1
            coach(L.title, text, listOf(
                Triple(if (last) "FINISH" else "NEXT LESSON", true) { if (last) finish() else showIntro(idx + 1) },
                Triple("RETRY", false) { showIntro(idx) },
                Triple("QUIT", false) { quit() },
            ))
        }
    }
}
