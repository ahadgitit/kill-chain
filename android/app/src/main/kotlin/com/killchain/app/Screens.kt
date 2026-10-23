package com.killchain.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.killchain.app.Ui.Companion.html
import com.killchain.app.Ui.Companion.k
import com.killchain.core.Data
import com.killchain.core.ENEMY
import com.killchain.core.Sim
import com.killchain.core.Summary
import kotlin.math.min

/** Full-screen screens: main menu, briefing, debrief, help, pause, about, first-run guide. */
class Screens(private val g: Game) {
    private val ui get() = g.ui
    private val act get() = g.act
    val layer = FrameLayout(g.act)
    private var backAction: (() -> kotlin.Unit)? = null
    private var soundLink: TextView? = null
    private val prefs = g.act.getSharedPreferences("kc", android.content.Context.MODE_PRIVATE)

    fun hideAll() { layer.removeAllViews(); backAction = null }
    fun back(): Boolean { val b = backAction ?: return false; b(); return true }
    fun syncSound() { soundLink?.text = if (g.sound.muted) "🔇 SOUND OFF" else "🔊 SOUND ON" }

    private fun h(s: String, color: Int = C.CYAN, size: Float = 16f) = ui.text(s, size, color).apply { letterSpacing = 0.12f; paint.isFakeBoldText = true }
    private fun p(s: String, size: Float = 12.5f, color: Int = C.TEXT) = ui.text(html(s), size, color)
    private fun link(s: String, onClick: () -> kotlin.Unit) = ui.text(s, 12.5f, C.CYAN).apply {
        setPadding(ui.dp(4), ui.dp(10), ui.dp(12), ui.dp(10)); letterSpacing = 0.08f
        setOnClickListener { g.sound.play("click"); onClick() }
    }
    private fun sideBtn(title: String, sub: String, accent: Int, onClick: () -> kotlin.Unit) =
        ui.button(title, sub, accent = accent, size = 13f) { g.sound.play("click"); onClick() }

    /** A centred card over a dark scrim; returns the card's content column. */
    private fun modal(maxW: Int = 720, scrim: Int = 0xd901050a.toInt(), onBack: (() -> kotlin.Unit)?): LinearLayout {
        hideAll()
        backAction = onBack
        val bg = FrameLayout(act).apply { setBackgroundColor(scrim); isClickable = true }
        val scroll = ScrollView(act).apply { isFillViewport = false; isVerticalScrollBarEnabled = true }
        val body = ui.col().apply { background = ui.box(0xf2061018.toInt(), C.LINE_HI, 10f); setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(18)) }
        scroll.addView(body)
        val w = min(ui.dp(maxW), (act.resources.displayMetrics.widthPixels * 0.92).toInt())
        bg.addView(scroll, FrameLayout.LayoutParams(w, -2, Gravity.CENTER).apply { topMargin = ui.dp(14); bottomMargin = ui.dp(14) })
        layer.addView(bg, FrameLayout.LayoutParams(-1, -1))
        bg.alpha = 0f; bg.animate().alpha(1f).setDuration(180).start()
        return body
    }

    // ---------- main menu ----------
    fun menuHome() {
        hideAll()
        backAction = null
        val bg = FrameLayout(act)
        // Fade the left side dark so the menu reads over the live battle on the right.
        bg.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xf201050a.toInt(), 0xb301050a.toInt(), 0x0001050a))
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        val col = ui.col().apply { setPadding(ui.dp(28), ui.dp(18), ui.dp(18), ui.dp(18)) }
        scroll.addView(col)
        val w = min(ui.dp(470), (act.resources.displayMetrics.widthPixels * 0.6).toInt())
        bg.addView(scroll, FrameLayout.LayoutParams(w, -1, Gravity.START))
        layer.addView(bg, FrameLayout.LayoutParams(-1, -1))

        val boot = ui.text("", 10.5f, C.DIM)
        col.addView(boot)
        bootText(boot)
        col.addView(ui.text(html("KILL<font color='${C.hex(C.RED)}'>CHAIN</font>"), 40f, C.WHITE).apply { letterSpacing = 0.18f; setShadowLayer(18f, 0f, 0f, C.CYAN) }, ui.lp(top = 8))
        col.addView(p("Command the air war over Kashmir and Punjab. A real-time strategy game of radar, data links and long-range missiles, inspired by the India–Pakistan air battles of May 2025.", 12.5f), ui.lp(top = 4))
        val live = ui.text(html("${Ui.col(C.RED, "●")} LIVE: an AI vs AI battle is running behind this menu"), 11f, C.DIM)
        ObjectAnimator.ofFloat(live, "alpha", 1f, 0.45f).apply { duration = 900; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start() }
        col.addView(live, ui.lp(top = 8))

        val saved = g.loadCampaign()
        val trained = prefs.getBoolean("trained", false)
        val modes = ui.col()
        val training = sideBtn("TRAINING: MISSILE EVASION", "Four short lessons: long shots, short shots, silent shots, and taking your own. 5 minutes. Start here.", C.HOT) { g.training?.begin() }
        if (!trained) modes.addView(training, ui.lp(top = 6))
        if (saved != null && saved.night < g.campNights) {
            val n = Data.NIGHTS[saved.night]
            modes.addView(sideBtn("CONTINUE CAMPAIGN", "${Game.SIDE_NAME[saved.side]} · next: night ${saved.night + 1}, ${n.title}. Total so far IND ${saved.totals["IN"]} · PAK ${saved.totals["PK"]}.", C.side(saved.side)) {
                g.campaign = saved; g.startNight(saved.night, saved.side, true)
            }, ui.lp(top = 6))
        }
        modes.addView(sideBtn("CAMPAIGN · ${g.campNights} NIGHTS", "6–10 May 2025, night by night. Losses, weapon stocks and damaged bases carry over. Highest total wins.", C.CYAN) { menuSide(0, true) }, ui.lp(top = 6))
        modes.addView(sideBtn("SINGLE NIGHT & SCENARIOS", "Play any one night, or Operation Deep Strike: drones, a diversion and a low-level strike.", C.CYAN) { menuNights() }, ui.lp(top = 6))
        if (trained) modes.addView(training, ui.lp(top = 6))
        col.addView(modes, ui.lp(top = 8))
        val links = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        links.addView(link("HOW TO PLAY") { help(null) })
        links.addView(link("GUIDE") { onboarding(0) })
        soundLink = link("") { g.sound.setMuted(!g.sound.muted); g.syncSound() }
        links.addView(soundLink); syncSound()
        links.addView(link("ABOUT") { about() })
        col.addView(links, ui.lp(top = 6))
        col.addView(p("An unofficial game. Aircraft and weapon figures are simplified open-source estimates. Every result comes from the simulation, not from either side's claims. Not a historical record.", 10f, C.DIM), ui.lp(top = 4))
    }

    private fun bootText(t: TextView) {
        val lines = listOf("SENSOR NETWORK" to "ONLINE", "AIR BASES" to "25 ON ALERT", "TACTICAL DATA LINK" to "SYNCED", "THEATRE" to "PUNJAB / KASHMIR")
        val sb = StringBuilder()
        for ((i, l) in lines.withIndex()) t.postDelayed({
            sb.append("&gt; ${l.first.padEnd(20, '.')} ${Ui.col(C.PK, l.second)}<br>")
            t.text = html(sb.toString())
        }, 260L * (i + 1))
    }

    private fun menuNights() {
        val body = modal(660, onBack = { menuHome() })
        body.addView(h("CHOOSE A NIGHT"))
        for ((i, n) in Data.NIGHTS.withIndex()) {
            body.addView(sideBtn("${n.label} · ${n.title}", "${n.date}. ${n.summary}", if (n.scenario) C.HOT else C.CYAN) { menuSide(i, false) }, ui.lp(top = 8))
        }
        body.addView(link("BACK") { menuHome() }, ui.lp(top = 6))
    }

    private fun menuSide(idx: Int, camp: Boolean) {
        val n = Data.NIGHTS[idx]
        val body = modal(660, onBack = { if (camp) menuHome() else menuNights() })
        fun goal(s: String) = if (n.targets[s]!!.isNotEmpty()) "Attack ${n.targets[s]!!.size} priority targets" else "Defend"
        body.addView(h("${if (camp) "CAMPAIGN" else "${n.label} · ${n.title}"}: CHOOSE YOUR SIDE"))
        val row = LinearLayout(act)
        row.addView(sideBtn("COMMAND INDIA", "${if (camp) "Strike first on night 1, then fight a war of air bases." else goal("IN") + "."} Rafale, Su-30MKI, MiG-29, Mirage 2000, Netra, Phalcon, S-400, Harop.", C.IN) { pickSide(idx, "IN", camp) },
            LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(sideBtn("COMMAND PAKISTAN", "${if (camp) "Defend on night 1, then strike back." else goal("PK") + "."} J-10CE, JF-17, F-16, Erieye, ZDK-03, HQ-9/P, drone swarms.", C.PK) { pickSide(idx, "PK", camp) },
            LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.dp(10) })
        body.addView(row, ui.lp(top = 10))
        body.addView(link("BACK") { if (camp) menuHome() else menuNights() }, ui.lp(top = 6))
    }
    private fun pickSide(idx: Int, side: String, camp: Boolean) {
        if (camp) { g.campaign = Campaign(side); g.saveCampaign() } else g.campaign = null
        g.startNight(idx, side, camp)
    }

    // ---------- briefing ----------
    private fun targetLabel(o: com.killchain.core.Target) = "<b>${o.id}</b> ${k("(" + (mapOf("site" to "site", "base" to "air base", "sam" to "SAM site", "radar" to "radar")[o.kind] ?: o.kind) + ")")}"

    fun briefing(idx: Int, side: String) {
        val s = g.sim ?: return
        val n = Data.NIGHTS[idx]; val them = ENEMY[side]!!
        val mine = n.targets[side]!!; val theirs = n.targets[them]!!
        val forces = s.bases.filter { it.side == side && it.pools.values.any { p -> p.ready > 0 } }.joinToString(" · ") { b ->
            b.pools.entries.filter { it.value.ready > 0 }.joinToString(", ") { (ty, p) -> "${b.name}: ${p.ready} ${Data.AIRCRAFT[ty]!!.name}" }
        }
        val body = modal(760, onBack = { g.showMenu() })
        body.addView(h("${n.label} · ${n.title}"))
        body.addView(p(k(n.date)), ui.lp(top = 2))
        body.addView(p("${n.summary} You command <b>${Ui.col(C.side(side), Game.SIDE_NAME[side]!!.uppercase())}</b>. Every aircraft starts on the ground."), ui.lp(top = 8))
        val grid = LinearLayout(act)
        fun colOf(title: String, list: List<com.killchain.core.Target>, empty: String, foot: String) = ui.col().apply {
            addView(h(title, C.CYAN, 12f))
            addView(p(if (list.isEmpty()) k(empty) else list.joinToString("<br>") { "• " + targetLabel(it) } + "<br>" + k(foot)), ui.lp(top = 4))
        }
        grid.addView(colOf("YOUR PRIORITY TARGETS", mine, "None tonight: your job is to defend.", "10 points each."), LinearLayout.LayoutParams(0, -2, 1f))
        grid.addView(colOf("ENEMY TARGETS TO DEFEND", theirs, "The enemy is defending tonight.", "8 points for each one still untouched at the end."), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(14) })
        body.addView(grid, ui.lp(top = 10))
        body.addView(p("${k("<b>How targets work:</b>")} every target needs <b>3 hits</b> to be destroyed, and you score for each hit. Aircraft go down after <b>2 missile hits</b>. Also scores: enemy aircraft shot down, other enemy bases or air defences, aircraft destroyed on the ground, strike weapons intercepted, drones. Escalation at 100% means <b>both sides lose</b>.", 11.5f), ui.lp(top = 10))
        body.addView(p("${k("<b>Your forces:</b>")} ${forces.ifEmpty { "none" }}. Up to ${Data.AIR_CAP[side]} aircraft can be airborne at once.", 11.5f), ui.lp(top = 6))
        body.addView(p("💡 " + tip(n.n, side), 12f, C.HOT).apply { background = ui.box(0x1affd166, 0x66ffd166, 6f); setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) }, ui.lp(top = 10))
        body.addView(sideBtn("BEGIN", "Your AEW&C aircraft take off on their own, and fighters scramble to defend their bases. You choose who to CHASE, ESCORT and send on GROUND ATTACK.", C.side(side)) {
            hideAll(); g.begin(idx, side)
        }, ui.lp(top = 12))
    }

    private fun tip(n: Int, side: String): String = when (n) {
        1 -> if (side == "IN") "Tap AMBALA, choose GROUND ATTACK and tap MURIDKE, with 1 or 2 escorts. PATHANKOT's Mirages carry short-range SPICE for KOTLI and SIALKOT (MJ). Escorts keep strikers pressing in close, where they hit more often."
             else "Watch for ⚠ STRIKER INBOUND warnings and send a CHASE from MINHAS or RAFIQUI to catch strike jets before they reach firing range. Then send a drone GROUND ATTACK from SIALKOT on UDHAMPUR."
        2 -> if (side == "IN") "Send Harop drones from AMRITSAR on GROUND ATTACK against Lahore's radar and SAMs. Your fighters scramble on their own against drone swarms near your bases."
             else "Send drone GROUND ATTACKs from SIALKOT in several waves: they are slow and cheap. Turn SAM radars OFF if Harops are hunting them."
        3 -> if (side == "IN") "CM-400s have about 100 km of reach, so Pakistani strike jets must come close: CHASE them as soon as the ⚠ STRIKER warning appears. Counter-strike SIALKOT and MURID with Rafales."
             else "Send GROUND ATTACKs from RAFIQUI and PESHAWAR with 2 escorts each. CM-400 reaches about 100 km, so expect Indian interceptors."
        4 -> if (side == "IN") "Send Su-30s from HALWARA and JODHPUR on GROUND ATTACK with escorts. BrahMos-A reaches about 170 km. A ceasefire comes at 45 min."
             else "CHASE the Su-30s on the ⚠ STRIKER warning, before they fire: BrahMos is fast and hard to intercept. Counter-strike ADAMPUR and PATHANKOT."
        else -> if (side == "IN") "1) AMRITSAR: send 100 drones on GROUND ATTACK at MUSHAF, with waypoints over Gujranwala and Hafizabad so the swarm crosses every SAM layer. Watch the SAM bars empty: each needs 10 min to reload. 2) When the SAMs run dry, send a DIVERSION from PATHANKOT or ADAMPUR at a target far from Sargodha, such as SIALKOT or KOTLI, to pull the defending fighters away. 3) Then send Rafales from AMBALA on GROUND ATTACK at MUSHAF along the drones' route, set to LOW LEVEL. They climb and fire near the target, and the whole package goes loud. 4) Run for the border: the further your diversion pulled Pakistan's fighters, the better your chances."
             else "Mushaf at Sargodha is India's target. Indian drones will try to drain your SAMs (only SAMs can kill drones). Keep fighters near Sargodha even when a raid appears elsewhere: it may be a diversion. Low-level strikers are invisible to radars, so patrol with fighters on the likely route."
    }

    // ---------- debrief ----------
    fun debrief(s: Sim, sum: Summary) {
        val me = g.st.side; val them = ENEMY[me]!!
        val n = s.night!!
        val my = sum.sides[me]!!; val th = sum.sides[them]!!
        val verdict = if (sum.crisis) "CRISIS: BOTH SIDES LOSE" else if (my.points > th.points) "VICTORY ON POINTS" else if (my.points == th.points) "STALEMATE" else "DEFEAT ON POINTS"
        val cur = g.current
        val camp = g.campaign
        val campDone = camp != null && cur != null && cur.campaign && camp.night >= g.campNights
        val body = modal(720, onBack = { hideAll() })
        body.addView(h("DEBRIEF · ${n.label}: ${s.endReason ?: ""}", C.CYAN, 13f))
        body.addView(ui.text(verdict, 26f, if (sum.crisis || my.points < th.points) C.RED else if (my.points == th.points) C.HOT else C.side(me)).apply { letterSpacing = 0.1f; paint.isFakeBoldText = true }, ui.lp(top = 6))
        val i = sum.sides["IN"]!!; val k2 = sum.sides["PK"]!!
        val rows = listOf(
            "Points" to ("<b>${i.points}</b>" to "<b>${k2.points}</b>"),
            "Priority targets destroyed / damaged" to ("${i.objDestroyed} / ${i.objDamaged}" to "${k2.objDestroyed} / ${k2.objDamaged}"),
            "Other bases / air defences hit" to ("${i.otherHits}" to "${k2.otherHits}"),
            "Kills claimed → confirmed" to ("${i.score.claimed} → ${i.score.kills}" to "${k2.score.claimed} → ${k2.score.kills}"),
            "Aircraft lost in the air" to ("${i.score.lost}" to "${k2.score.lost}"),
            "Aircraft lost on the ground" to ("${i.score.groundLost}" to "${k2.score.groundLost}"),
            "Strike weapons intercepted" to ("${i.score.intercepts}" to "${k2.score.intercepts}"),
            "Drones shot down" to ("${i.score.droneKills}" to "${k2.score.droneKills}"),
            "Missiles fired" to ("${i.score.shots}" to "${k2.score.shots}"),
        )
        val table = ui.col()
        fun tr(a: String, b: String, c: String, head: Boolean = false) = LinearLayout(act).apply {
            addView(p(a, 12f, if (head) C.DIM else C.TEXT), LinearLayout.LayoutParams(0, -2, 2.2f))
            addView(p(b, 12f, if (head) C.IN else C.WHITE).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(p(c, 12f, if (head) C.PK else C.WHITE).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
            setPadding(0, ui.dp(3), 0, ui.dp(3))
        }
        table.addView(tr("", "INDIA", "PAKISTAN", true))
        for ((label, v) in rows) table.addView(tr(label, v.first, v.second))
        body.addView(table, ui.lp(top = 8))
        body.addView(p(k("Claims were what each side's pilots reported during the night. Some were wrong: one was a decoy, some were misses. The confirmed numbers are the truth."), 10.5f), ui.lp(top = 6))
        for (sd in listOf("IN", "PK")) {
            val objs = s.objectives[sd]!!
            if (objs.isEmpty()) continue
            body.addView(h(if (sd == "IN") "INDIA'S TARGETS" else "PAKISTAN'S TARGETS", C.CYAN, 12f), ui.lp(top = 10))
            body.addView(p(objs.joinToString("&nbsp;&nbsp; ") { o -> val hh = min(3, o.ref?.hits ?: 0); "${if (hh >= 3) "✓" else if (hh > 0) "◐" else "✗"} ${o.id} $hh/3" }, 12f), ui.lp(top = 3))
        }
        if (camp != null && cur != null && cur.campaign) {
            body.addView(h("CAMPAIGN TOTAL", C.CYAN, 12f), ui.lp(top = 10))
            val tot = "IND ${camp.totals["IN"]} · PAK ${camp.totals["PK"]}" + if (campDone) " · " + (if (camp.totals[me]!! > camp.totals[them]!!) "YOU WIN THE CAMPAIGN" else if (camp.totals[me] == camp.totals[them]) "CAMPAIGN DRAWN" else "YOU LOSE THE CAMPAIGN") else ""
            body.addView(ui.text(tot, 15f, C.WHITE, true), ui.lp(top = 3))
        }
        val row = LinearLayout(act)
        row.addView(sideBtn("WATCH REPLAY", "The whole night from both sides, with nothing hidden.", C.CYAN) { g.enterReplay() }, LinearLayout.LayoutParams(0, -1, 1f))
        if (cur != null && cur.campaign && !campDone) {
            row.addView(sideBtn("NEXT: NIGHT ${n.n + 1}", "${Data.NIGHTS[n.n].title}. Your losses and stocks carry over.", C.side(me)) { g.startNight(camp!!.night, me, true) },
                LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.dp(10) })
        } else {
            row.addView(sideBtn("PLAY AGAIN", "Same night, same side.", C.side(me)) { g.startNight(cur!!.nightIdx, me, false) }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.dp(10) })
        }
        body.addView(row, ui.lp(top = 14))
        val links = LinearLayout(act)
        links.addView(link("SWITCH SIDES") { g.campaign = null; g.startNight(cur!!.nightIdx, them, false) })
        links.addView(link("VIEW THE MAP") { hideAll() })
        links.addView(link("MAIN MENU") { g.showMenu() })
        body.addView(links, ui.lp(top = 6))
    }

    // ---------- pause, help, about ----------
    fun pause(resume: () -> kotlin.Unit) {
        val close = { hideAll(); resume() }
        val body = modal(420, onBack = close)
        body.addView(h("PAUSED"))
        val s = g.sim
        if (s != null) body.addView(p(k("${s.cfg.name} · ${s.clock()}")), ui.lp(top = 2))
        body.addView(ui.button("RESUME", accent = C.HOT, filled = true) { g.sound.play("click"); close() }, ui.lp(top = 12))
        body.addView(ui.button("HOW TO PLAY") { g.sound.play("click"); help(resume) }, ui.lp(top = 8))
        val snd = ui.button(if (g.sound.muted) "SOUND: OFF" else "SOUND: ON") { }
        snd.setOnClickListener { g.sound.setMuted(!g.sound.muted); g.syncSound(); snd.text = if (g.sound.muted) "SOUND: OFF" else "SOUND: ON" }
        body.addView(snd, ui.lp(top = 8))
        if (g.debriefShown) body.addView(ui.button("SHOW DEBRIEF") { g.sound.play("click"); g.showDebrief() }, ui.lp(top = 8))
        body.addView(ui.button("QUIT TO MAIN MENU", accent = C.RED) { g.sound.play("click"); g.showMenu() }, ui.lp(top = 8))
    }

    fun help(resume: (() -> kotlin.Unit)?) {
        val done: () -> kotlin.Unit = { if (resume != null) { if (g.sim != null) pause(resume) else hideAll() } else menuHome() }
        val body = modal(760, onBack = done)
        body.addView(h("HOW TO PLAY"))
        body.addView(p(HELP), ui.lp(top = 8))
        body.addView(ui.button("GOT IT", accent = C.HOT, filled = true) { g.sound.play("click"); done() }, ui.lp(top = 12))
    }

    fun about() {
        val body = modal(640, onBack = { menuHome() })
        body.addView(h("ABOUT KILL CHAIN"))
        val ver = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (e: Exception) { "" }
        body.addView(p(k("Version $ver")), ui.lp(top = 2))
        body.addView(p(ABOUT), ui.lp(top = 8))
        body.addView(link("BACK") { menuHome() }, ui.lp(top = 6))
    }

    // ---------- first-run guide ----------
    fun onboarding(page: Int) {
        hideAll()
        val pages = GUIDE
        val pg = pages[page]
        backAction = { if (page > 0) onboarding(page - 1) else finishOnboarding(false) }
        val bg = FrameLayout(act).apply { setBackgroundColor(0xf701050a.toInt()); isClickable = true }
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(16)) }
        val ill = Illustration(act, g, page)
        row.addView(ill, LinearLayout.LayoutParams(0, -1, 0.9f))
        val text = ui.col()
        val scroll = ScrollView(act).apply { addView(text); isVerticalScrollBarEnabled = false }
        row.addView(scroll, LinearLayout.LayoutParams(0, -2, 1.1f).apply { leftMargin = ui.dp(22) })
        text.addView(ui.text("${page + 1} / ${pages.size}", 11f, C.DIM).apply { letterSpacing = 0.2f })
        text.addView(h(pg.first, C.WHITE, 22f), ui.lp(top = 6))
        text.addView(p(pg.second, 13.5f), ui.lp(top = 10))
        val btns = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        if (page < pages.size - 1) {
            btns.addView(ui.button("NEXT", accent = C.HOT, filled = true) { g.sound.play("click"); onboarding(page + 1) }.apply { minWidth = ui.dp(130) })
            btns.addView(link("SKIP") { finishOnboarding(false) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(14) })
        } else {
            btns.addView(ui.button("START TRAINING", accent = C.HOT, filled = true) { g.sound.play("click"); finishOnboarding(true) }.apply { minWidth = ui.dp(170) })
            btns.addView(link("MAIN MENU") { finishOnboarding(false) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(14) })
        }
        if (page > 0) btns.addView(link("BACK") { onboarding(page - 1) })
        text.addView(btns, ui.lp(top = 18))
        bg.addView(row, FrameLayout.LayoutParams(-1, -1))
        layer.addView(bg, FrameLayout.LayoutParams(-1, -1))
        text.alpha = 0f; text.translationX = ui.dp(16).toFloat()
        text.animate().alpha(1f).translationX(0f).setDuration(260).start()
    }
    private fun finishOnboarding(train: Boolean) {
        prefs.edit().putBoolean("onboarded", true).apply()
        if (train) g.training?.begin() else menuHome()
    }
    val onboarded get() = prefs.getBoolean("onboarded", false)

    companion object {
        private fun b(s: String) = "<b><font color='#e8f6ff'>$s</font></b>"
        private fun hd(s: String) = "<br><b><font color='#5fe0ff'>$s</font></b><br>"
        val GUIDE = listOf(
            "YOU ARE THE AIR COMMANDER" to
                "Kill Chain is a real-time strategy game about a modern air war, set over Kashmir and Punjab and inspired by the India–Pakistan air battles of May 2025.<br><br>" +
                "You don't fly a jet. You run the whole air force of ${b("India")} or ${b("Pakistan")} from a live radar map: you decide which aircraft go where, and the AI pilots fly them.<br><br>" +
                "Each night lasts about an hour of game time. You can speed it up or pause at any moment.",
            "GIVE MISSIONS" to
                "Tap one of your ${b("air bases")} (the small squares), choose how many aircraft to send, and pick a mission:<br><br>" +
                "${b("CHASE")}: hunt an enemy aircraft.<br>${b("ESCORT")}: protect your strike jets.<br>${b("GROUND ATTACK")}: hit an enemy site, SAM, radar or air base.<br>${b("DIVERSION")}: fake a strike to pull enemy fighters away.<br><br>" +
                "Then tap the target on the map. You can also tap a jet that is already flying and give it a new mission.",
            "SEE WITHOUT BEING SEEN" to
                "Radar finds enemies, but it also gives you away. ${b("AEW&amp;C")} aircraft (circles with a sweep) see furthest and share every track over the ${b("data link")}.<br><br>" +
                "A ${b("filled")} jet has its radar on. A ${b("hollow")} jet is silent: enemies can see it but can't lock it. ${b("LOW LEVEL")} jets hug the terrain and are invisible to radars, until they fire.<br><br>" +
                "That is the kill chain: one aircraft sees, another shoots, and the target may get no warning at all.",
            "MISSILES RUN OUT OF ENERGY" to
                "Missiles slow down as they fly. A red line from a missile to your jet means it can still reach you. A ${b("grey dashed")} line means it is losing energy and will fall short.<br><br>" +
                "Your jets dodge on their own, but when a decision matters the game pauses and asks you: ${b("BREAK")} away to outrun it, or ${b("NOTCH")} to hide from its radar.",
            "HOW TO WIN" to
                "Hit your ${b("priority targets")} (red reticles): each needs ${b("3 hits")}. Defend your own (amber rings). Shoot down enemy aircraft and keep yours alive.<br><br>" +
                "Watch the ${b("ESC")} meter at the top: every strike and kill raises escalation. At ${b("100%")} both sides lose.<br><br>" +
                "Start with the 5-minute ${b("training")}: four lessons on missiles and the kill chain. Then take command.",
        )

        val HELP = "${b("1. Detect.")} Radar finds enemies. AEW&amp;C aircraft (circles with a bar) and ground radars see furthest. Fighter radar only looks ±60° ahead.<br>" +
            "${b("2. Share.")} Linked units share every track over the data link, so a fighter with its radar OFF (hollow icon) still sees what the AEW&amp;C sees. It only switches its radar on at the moment it fires, and if it uses the AEW&amp;C's track its target gets no launch warning.<br>" +
            "${b("3. Shoot.")} Missiles slow down as they fly. Long shots at a target that turns away usually fail. The inner ring around a selected jet is a rough no-escape range. Missiles like PL-15E can be guided by the data link after launch. Meteor, AIM-120, R-77 and MICA need the shooter's own radar until their seeker turns on.<br>" +
            "${b("4. Support.")} After firing a radar-guided missile, your jet \"cranks\", turning 50° off to keep the target in its radar. If it turns away too early, the missile goes blind.<br>" +
            "${b("5. Survive.")} When warned, jets turn away (drag), jam and drop chaff. Radar emissions give you away: enemies detect radars further than radars detect jets.<br>" +
            hd("YOUR JOB: MISSIONS") +
            "• Tap one of your ${b("air bases")} (□ with a number), choose how many aircraft to send, and pick a mission. Or tap an aircraft that is already flying and give it a new one.<br>" +
            "• ${b("CHASE")}: tap an enemy aircraft. Each fighter carries 4 air-to-air missiles and fires 2 per enemy. An aircraft goes down after 2 hits (1 hit damages it).<br>" +
            "• ${b("ESCORT")}: tap one of your ground-attack flights. The escort flies ahead of it and fights off interceptors. A striker waiting for an escort holds in safe airspace until it catches up.<br>" +
            "• ${b("GROUND ATTACK")}: tap an enemy site, radar, SAM or air base, then choose 0, 1 or 2 escorts. Each striker carries 3 air-to-surface weapons. A target is destroyed after 3 hits. Closer shots hit more often. Drone bases send one-way drones.<br>" +
            "• ${b("DIVERSION")}: fake a strike with radars on, so the enemy sends fighters. Your jets hold short, drag the defenders back toward the border, then turn in again, for up to 25 minutes.<br>" +
            "• ${b("Routes")}: after choosing a target for a ground attack, drone attack or diversion, tap + WAYPOINT and then the map (up to 6 points).<br>" +
            "• ${b("Going loud")}: when a strike jet releases its weapons, its wingmen and escorts switch their radars on and climb out of low level.<br>" +
            "• ${b("Shortcut")}: with a flight selected, tap an enemy aircraft to chase it, or a target to attack it.<br>" +
            "• ${b("⚠ STRIKER INBOUND")}: an enemy strike jet gets a pulsing red ring and a line to its likely target. That's your cue to send a CHASE.<br>" +
            "• ${b("The enemy commander")} fights with a plan: fighter sweeps, escorts, feints, raids on your AEW&amp;C, massed waves. It always gives a tell first: purple ◆ INTEL lines with a chirp.<br>" +
            "• The AI flies everything else: it dodges missiles, manages fuel, refuels and re-arms. Ready fighters scramble on their own when enemies come near their base.<br>" +
            hd("THE RULES") +
            "• ${b("Radar on or off")}: RADAR OFF means enemy radars can see the jet but can't lock it (heat-seekers still can, inside ~20 km). To fire anything a jet must switch its radar on.<br>" +
            "• ${b("LOW LEVEL")}: radar off and terrain hugging. Invisible to AEW&amp;C, ground radars and SAMs; only enemy fighters can find it, and any fighter sees it inside 15 km. Slower, uses more fuel.<br>" +
            "• ${b("Exposed")}: a jet that switches its radar on inside enemy airspace stays on and can't go silent or low again until it lands.<br>" +
            "• ${b("Drone swarms")}: only SAMs can shoot drones down. Each SAM has a few missiles (the bar under its icon); empty, it needs 10 minutes to reload, and has one spare magazine per night.<br>" +
            "• ${b("Limits")}: key weapons are limited, fuel is limited, and only 24 Indian or 20 Pakistani aircraft can fly at once.<br>" +
            "• ${b("Air bases")}: hits close a runway at 60% damage and can destroy jets parked there.<br>" +
            "• ${b("Fog of war")}: kills are only claimed until the debrief reveals the truth.<br>" +
            "• ${b("Escalation")}: every strike and kill raises it. At 100% both sides lose.<br>" +
            hd("CONTROLS") +
            "• Drag to pan, pinch to zoom. Tap to select. Speed buttons at the top (II pauses). The Android back button closes panels and pauses the game.<br>" +
            "• ▤ lists your missions. ≡ is the event log. ◎ shows sensor coverage.<br>" +
            hd("SYMBOLS") +
            "• Each jet is drawn by type, and jets sent together fly in formation · filled: radar ON · hollow: silent · amber: damaged · dashed: passive track · ◇ target site · □ air base · ▣ SAM site · ✕ drones · red reticle: your target · amber ring: defend it.<br>" +
            "• A red line from a missile to your jet: it can still reach you. Grey dashed: it is losing energy."

        val ABOUT = "Kill Chain is a game: a simplified simulation of air combat between India and Pakistan, inspired by the air battles of 6–10 May 2025. It does not take sides, and it is not a historical record. Battles play out differently each time, from the simulation alone, not from either side's claims.<br><br>" +
            "Aircraft, missile and radar figures are rough open-source estimates, simplified for play. Real performance is classified and often disputed. Bases and targets are placed approximately. No real-world operational information is used.<br><br>" +
            "<b>Credits</b><br>Map data: Natural Earth (public domain).<br>Font: Share Tech Mono by Carrois Apostrophe, SIL Open Font License 1.1.<br>All sounds are synthesised in the app.<br><br>" +
            "The game has no ads, no accounts and no network access, and collects no data."
    }
}
