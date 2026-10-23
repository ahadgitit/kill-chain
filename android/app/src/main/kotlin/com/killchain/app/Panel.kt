package com.killchain.app

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.killchain.app.Game.Companion.fmtT
import com.killchain.app.Ui.Companion.html
import com.killchain.app.Ui.Companion.k
import com.killchain.app.Ui.Companion.warm
import com.killchain.app.Ui.Companion.warn
import com.killchain.core.Base
import com.killchain.core.Data
import com.killchain.core.ENEMY
import com.killchain.core.Ent
import com.killchain.core.Geo
import com.killchain.core.Missile
import com.killchain.core.MissionSpec
import com.killchain.core.SAM_RELOAD
import com.killchain.core.Sim
import com.killchain.core.Site
import com.killchain.core.Target
import com.killchain.core.Unit
import com.killchain.core.dist
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The selection panel's content, rebuilt every quarter second.
sealed class PItem
class PBtn(val label: String, val sub: String? = null, val style: String = "", val on: Boolean = false, val enabled: Boolean = true, val act: () -> kotlin.Unit) : PItem()
class PSeg(val btns: List<PBtn>) : PItem()
class PNote(val html: String) : PItem()
class PPool(val title: String, val sub: String, val stepper: Stepper?, val items: List<PItem>) : PItem()
class Stepper(val key: String, val n: Int, val step: Int, val max: Int, val unit: String)

class PanelModel(val title: String, val color: Int, val tag: String?, val info: String, val items: List<PItem>) {
    fun sig(): String {
        val sb = StringBuilder(title)
        fun walk(i: PItem) {
            when (i) {
                is PBtn -> sb.append('|').append(i.label).append(i.sub).append(i.on).append(i.enabled).append(i.style)
                is PSeg -> { sb.append("[") ; i.btns.forEach(::walk); sb.append("]") }
                is PNote -> sb.append('|').append(i.html)
                is PPool -> { sb.append('{').append(i.title).append(i.sub).append(i.stepper?.n).append(i.stepper?.max); i.items.forEach(::walk); sb.append('}') }
            }
        }
        items.forEach(::walk)
        return sb.toString()
    }
}

/** Port of the panel parts of js/ui.js: what each thing is, and the missions you can give. */
class Panel(private val g: Game) {
    private val ui get() = g.ui
    private val st get() = g.st

    companion object {
        val MISSION_TXT = mapOf(
            "chase" to ("CHASE" to "Tap an enemy aircraft. Air-to-air missiles."),
            "escort" to ("ESCORT" to "Tap one of your ground-attack flights. Air-to-air missiles."),
            "strike" to ("GROUND ATTACK" to "Tap a site, radar, SAM or air base."),
            "attack" to ("GROUND ATTACK" to "Tap a site, radar, SAM or air base. One-way drones."),
            "feint" to ("DIVERSION" to "Fake a strike, radars on, to pull enemy fighters away."),
        )
        private val TAP_HINT = mapOf(
            "chase" to "Tap an enemy aircraft to chase", "escort" to "Tap one of your ground-attack flights",
            "strike" to "Tap an enemy target", "attack" to "Tap an enemy target", "feint" to "Tap the enemy target to fake an attack on",
        )
    }

    private fun mbtn(kind: String, extra: String? = null, start: () -> Plan): PBtn =
        PBtn(MISSION_TXT[kind]!!.first, extra ?: MISSION_TXT[kind]!!.second, "mission") {
            st.plan = start()
            g.hintMsg(TAP_HINT[kind]!!)
            g.renderPanel(true)
        }

    private fun hitsRow(ref: Ent): String {
        val h = min(3, ref.hits)
        val pips = (0 until 3).joinToString("") { if (it < h) Ui.col(C.RED, "■") else k("□") }
        return "${k("Hits")} $pips ${if (h >= 3) warn("DESTROYED") else if (h > 0) warm("DAMAGED ($h/3)") else "intact (0/3)"}"
    }
    private fun objFor(s: Sim, ref: Ent): String? {
        val mine = s.objectives[st.side]!!.find { it.ref === ref }
        val theirs = s.objectives[ENEMY[st.side]]!!.find { it.ref === ref }
        return if (mine != null) "YOUR TARGET${if (mine.hit) " · HIT" else ""}" else if (theirs != null) "DEFEND${if (theirs.hit) " · HIT" else ""}" else null
    }
    private fun note(s: String) = "<br><small>${k(s)}</small>"

    // ---------- bases ----------
    private fun missionButtons(s: Sim, b: Base, ty: String): List<PItem> {
        val def = Data.AIRCRAFT[ty]!!
        val key = b.name + ty
        fun plan(kind: String) = Plan(kind, base = b.name, type = ty, n = st.deployN[key] ?: 2)
        if (def.role == "aew") return listOf(PNote("AEW&amp;C aircraft fly automatically: they take off, orbit and are relieved on their own."))
        if (def.role == "drone") return listOf(mbtn("attack") { plan("attack") })
        val out = mutableListOf<PItem>(mbtn("chase") { plan("chase") }, mbtn("escort") { plan("escort") }, mbtn("feint") { plan("feint") })
        val wk = s.missionWeapon(ty, "STRIKE")
        if (wk != null) { val w = Data.WEAPONS[wk]!!; out.add(mbtn("strike", "${w.name}, launches from ~${(w.range * 0.85).roundToInt()} km. No air-to-air missiles.") { plan("strike") }) }
        return out
    }

    private fun describeBase(s: Sim, b: Base): PanelModel {
        val own = b.side == st.side
        val stTxt = if (!s.baseOpen(b)) warn("RUNWAY CLOSED · repairs ~${fmtT((b.damage - 59) / 5 * 60)}")
        else if (b.damage > 0) warm("DAMAGED ${b.damage.roundToInt()}%") else "OPERATIONAL"
        var info = "$stTxt<br>${hitsRow(b)}"
        if (!own) {
            val types = b.pools.keys.joinToString(", ") { Data.AIRCRAFT[it]!!.name }
            info += note("${if (types.isNotEmpty()) "Intelligence: $types based here." else "Divert airfield."} Send a GROUND ATTACK here from one of your bases. Hits close the runway at 60% damage and can destroy aircraft on the ground.")
            return PanelModel("${b.name} AIR BASE", C.side(b.side), objFor(s, b), info, emptyList())
        }
        val stocks = s.stocks[st.side]!!.entries.joinToString(" · ") { (kk, n) -> "${Data.WEAPONS[kk]!!.name} <b>$n</b>" }
        info += "<br>${k("Airborne")} ${s.airborne(st.side)} / ${Data.AIR_CAP[st.side]} · ${k("Key stocks")} $stocks"
        info += note(if (b.pools.isEmpty()) "Divert airfield: no aircraft based here, but any of your jets can land, refuel and re-arm." else "Ready fighters here scramble on their own if enemies come close.")
        val items = mutableListOf<PItem>()
        for ((ty, p) in b.pools) {
            val def = Data.AIRCRAFT[ty]!!
            val up = s.units.filter { it.kind == "air" && it.state == "air" && it.base == b.name && it.type == ty && !it.ghost }.sumOf { it.count }
            val next = if (p.rearm.isNotEmpty()) p.rearm.min() - s.t else 0.0
            val key = b.name + ty
            val drone = def.role == "drone"
            val n = max(1, min(st.deployN[key] ?: (if (drone) min(p.ready, if (def.mission == "attack") 20 else 4) else 2), max(1, p.ready)))
            st.deployN[key] = n
            val sub = "${p.ready} ready · ${p.rearm.size} re-arming${if (p.rearm.isNotEmpty()) " (next ${fmtT(next)})" else ""} · $up airborne"
            items.add(PPool(def.name, sub, if (def.role != "aew") Stepper(key, n, if (drone) 10 else 1, p.ready, if (drone) "drones on:" else "aircraft on:") else null, missionButtons(s, b, ty)))
        }
        return PanelModel("${b.name} AIR BASE", C.side(b.side), objFor(s, b), info, items)
    }

    // ---------- planning ----------
    fun escortRoom(s: Sim, pl: Plan): Int {
        if (pl.unit != null) return 0
        val b = s.base(pl.base) ?: return 0
        val p = b.pools[pl.type] ?: return 0
        if (Data.PRESETS[pl.type]?.get("AIR") == null) return 0
        return max(0, min(2, p.ready - pl.n))
    }

    fun plan(s: Sim): PanelModel {
        val pl = st.plan!!
        val who = pl.unit?.let { "${it.callsign} (${it.d.name} ×${it.count})" } ?: "${pl.n} ${Data.AIRCRAFT[pl.type]!!.name} from <b>${pl.base}</b>"
        var info = who
        val items = mutableListOf<PItem>()
        val tgt = pl.target
        val routed = (pl.kind == "strike" || pl.kind == "attack" || pl.kind == "feint") && tgt != null
        if (routed) {
            val n = pl.via.size
            info += "<br>${k("Route")} ${if (n > 0) "$n waypoint${if (n > 1) "s" else ""}, then the target" else "direct to the target"}${if (pl.addingWp) " · " + warm("tap the map to place a waypoint") else ""}"
            val seg = mutableListOf(PBtn("+ WAYPOINT", on = pl.addingWp) {
                pl.addingWp = !pl.addingWp
                if (pl.addingWp) g.hintMsg("Tap the map to place waypoint ${pl.via.size + 1}")
                g.renderPanel(true)
            })
            if (n > 0) seg.add(PBtn("CLEAR ROUTE") { pl.via.clear(); pl.addingWp = false; g.renderPanel(true) })
            items.add(PSeg(seg))
        }
        if (pl.kind != "strike" && routed) {
            info += if (pl.kind == "feint") "<br>Fake target: <b>${tgt!!.id}</b>" + note("Your jets fly at it with radars ON so the enemy reads it as a strike and sends fighters. They turn for home when enemy fighters come close or they get within 70 km, dragging the defenders with them.")
            else "<br>Target: <b>${tgt!!.id}</b>" + note("The swarm flies the route to the target. Only SAMs can shoot drones down, so a big swarm can drain the SAMs along its path; empty SAMs need ${(SAM_RELOAD / 60).toInt()} minutes to reload, and have only one spare magazine.")
            items.add(PBtn("LAUNCH", style = "go") { launchPlan(s) })
        } else if (pl.kind == "strike" && tgt != null) {
            val room = escortRoom(s, pl)
            val esc = min(pl.escortN ?: 0, room)
            info += "<br>Target: <b>${tgt.id}</b> · 3 hits destroy it" +
                note("The striker presses in as close as it can and fires all 3 weapons. Closer shots hit more often and spend less time over enemy air defences. If enemy fighters close in, it fires early from further out.") +
                "<br>Escort from <b>${pl.base}</b>: " + if (room > 0) "" else k("no spare fighters here. Send one later from another base with ESCORT; the striker waits for it in safe airspace.")
            if (room > 0) items.add(PSeg((0..room).map { n -> PBtn(if (n > 0) "$n ESCORT${if (n > 1) "S" else ""}" else "NO ESCORT", on = esc == n) { pl.escortN = n; g.renderPanel(true) } }))
            items.add(PBtn("LAUNCH${if (esc > 0) " + $esc ESCORT" else ""}", style = "go") { launchPlan(s) })
        } else {
            info += "<br>" + warm(when (pl.kind) {
                "chase" -> "Tap an enemy aircraft (or cruise missile) to chase."
                "escort" -> "Tap one of your ground-attack flights to escort."
                "feint" -> "Tap an enemy target to fake an attack on."
                else -> "Tap an enemy target: site, radar, SAM or air base."
            })
        }
        items.add(PBtn("CANCEL", style = "danger") { st.plan = null; g.renderPanel(true) })
        return PanelModel(MISSION_TXT[pl.kind]!!.first, C.side(st.side), null, info, items)
    }

    fun launchPlan(s: Sim) {
        val pl = st.plan ?: return
        val preset = if (pl.kind == "strike") "STRIKE" else if (pl.kind == "attack") null else "AIR"
        val via = if (pl.via.isNotEmpty()) pl.via.toList() else null
        val escN = if (pl.kind == "strike") min(pl.escortN ?: 0, escortRoom(s, pl)) else 0
        val u = pl.unit
        val ms = if (u != null) s.assignMission(u, MissionSpec(pl.kind, preset = preset, target = pl.target, prey = pl.prey, escortUnit = pl.escortUnit, via = via))
        else s.createMission(MissionSpec(pl.kind, st.side, pl.base!!, pl.type!!, pl.n, preset, pl.target, pl.prey, pl.escortUnit, via = via))
        val mu = ms.unit
        if (escN > 0) {
            val airborne = mu != null && mu.state == "air"
            s.createMission(MissionSpec("escort", st.side, pl.base!!, pl.type!!, escN, "AIR", escortUnit = if (airborne) mu else null, escortOf = if (airborne) null else ms.id))
        }
        st.plan = null
        if (mu != null && mu.state == "air") g.select(mu) else g.select(null)
        val prey = pl.prey
        val deep = pl.kind == "chase" && prey != null && prey.kind == "air" && Geo.sideOf(prey.x, prey.y) != st.side
        val what = when (pl.kind) {
            "feint" -> "faking a strike on ${pl.target!!.id}, radars on"
            "attack" -> "swarm heading for ${pl.target!!.id}"
            "chase" -> if (deep) "chasing; the target is over enemy territory, so they will hold near the border and fire when in range" else "chasing the target"
            "escort" -> "escorting"
            else -> "attacking ${pl.target!!.id}"
        }
        g.sound.play("click")
        g.hintMsg(if (ms.status.startsWith("waiting")) "${ms.name}: ${ms.status}" else "${ms.name}: ${if (mu != null) mu.callsign + " " else ""}$what${if (escN > 0) ", escort launching" else ""}. The AI flies it.")
        g.renderMissions()
    }

    /** Is a picked thing a valid target for the mission being planned? Sets it on the plan if so. */
    fun planTarget(s: Sim, pl: Plan, hit: Game.Hit?): Boolean {
        if (hit == null) return false
        val r = hit.ref
        if (pl.kind == "chase") {
            val ok = hit.kind == "enemy" && ((r.kind == "air" && !s.isDrone(r)) || r.kind == "cruise")
            if (ok) pl.prey = r
            return ok
        }
        if (pl.kind == "escort") {
            val ok = hit.kind == "own" && r is Unit && r.kind == "air" && r !== pl.unit && (s.strikeWeapon(r) != null || r.mission?.kind == "strike")
            if (ok) pl.escortUnit = r as Unit
            return ok
        }
        val strikeable = r.side != st.side && (hit.kind == "site" || hit.kind == "ebase" || (hit.kind == "enemy" && (r.kind == "sam" || r.kind == "radar")))
        if (strikeable) pl.target = Target(r.kind, s.targetId(r))
        return strikeable
    }

    // ---------- things on the map ----------
    private fun status(s: Sim, u: Unit): String {
        if (u.defending) return warn("DEFENDING: missile inbound")
        val o = u.order
        val tgt = u.engage ?: if (o?.type == "attack") o.target else null
        if (tgt != null) { val tr = s.trackFor(u.side, tgt); return "ENGAGING ${if (tr != null) "T" + tr.num else "target"}" }
        if (o == null) return "ORBITING"
        if (o.type == "orbit") return if (u.d.role == "aew") "ON STATION" else if (s.isDrone(u)) "LOITERING" else "COMBAT AIR PATROL"
        if (o.type == "sead") return "HUNTING RADARS"
        if (u.holding) return warm("HOLDING: waiting for escort")
        if (o.type == "strike" || o.type == "drone") return if (o.list.isNotEmpty()) "${if (u.firing) "FIRING" else "INBOUND"} → ${o.list[0].id}" else "EGRESS"
        if (o.type == "rtb") return "RETURNING TO ${o.base ?: u.base}"
        if (o.type == "feint") return "DIVERSION${if (o.mode == "drag") " · dragging" else ""}"
        return o.type.uppercase()
    }
    private fun radarState(u: Unit): String {
        if (u.exposed) return warn("ON · EXPOSED until it lands")
        if (u.low) return "<b>LOW LEVEL</b> (radar off)"
        val mode = mapOf("auto" to "auto", "on" to "set ON", "off" to "set OFF", "low" to "low")[u.radarMode] ?: "auto"
        return "${if (u.radarOn) "ON" else "silent"} ($mode)"
    }
    private fun wpnRows(u: Unit): String {
        fun chips(ag: Boolean) = u.wpn.entries.filter { it.value > 0 && (Data.WEAPONS[it.key]!!.kind == "cruise") == ag }
            .joinToString(" · ") { "${Data.WEAPONS[it.key]!!.name} ×${it.value}" }.ifEmpty { k("none") }
        return "<br>${k("AIR-TO-AIR")} ${chips(false)}<br>${k("AIR-TO-SURFACE")} ${chips(true)}"
    }
    private fun bar(f: Double, col: Int): String { val n = (f * 10).roundToInt().coerceIn(0, 10); return Ui.col(col, "▮".repeat(n)) + k("▯".repeat(10 - n)) }

    fun describe(s: Sim, ref: Any): PanelModel? {
        if (ref is Base) return describeBase(s, ref)
        if (ref is Site) {
            val info = hitsRow(ref) + note(if (st.side == "IN") "Send a GROUND ATTACK from one of your air bases and tap this site. Strike jets launch once they are in weapon range." else "Protect this site. Cruise missiles fly low, so they need AEW&amp;C coverage to be seen in time for an intercept.")
            return PanelModel("TARGET: ${ref.name}", C.RED, objFor(s, ref), info, emptyList())
        }
        val e = ref as? Ent ?: return null
        if (e.side != st.side) return describeTrack(s, e)
        val u = e as? Unit ?: return null
        if (u.kind == "sam") {
            val w = Data.WEAPONS[u.samDef!!.weapon]!!
            var info = if (u.dead) warn("DESTROYED") else "${k("Missiles:")} ${u.ammo} × ${w.name} · ${k("reach")} ~${(w.range * 0.7).roundToInt()} km · ${k("radar")} ${if (u.radarOn) "ON" else warn("OFF")}"
            if (!u.dead && u.reloadAt != null) info += "<br>" + warm("RELOADING: ready in ${fmtT(u.reloadAt!! - s.t)}")
            else if (!u.dead && u.dry) info += "<br>" + warn("EMPTY: no spare magazines left tonight")
            info += note("Fires automatically at enemies in reach. While its radar is ON it can be found and hunted by anti-radiation drones. Turn it OFF to hide, but then it can't shoot.")
            val items = if (u.dead) emptyList() else listOf<PItem>(PBtn("RADAR ${if (u.radarOn) "ON" else "OFF"}", on = u.radarOn) {
                s.cmdSamRadar(u, !u.radarOn)
                g.hintMsg(if (u.radarOn) "${u.callsign} radar ON: it can shoot, but drones can home on it" else "${u.callsign} radar OFF: hidden from anti-radiation drones, but blind")
                g.renderPanel(true)
            })
            return PanelModel("${u.callsign} · ${u.samDef!!.name}", C.side(u.side), objFor(s, u), info, items)
        }
        if (u.kind == "radar") {
            val info = (if (u.dead) warn("DESTROYED") + "<br>" else "") + k("Feeds the ${Game.NET[u.side]}. Range ~${u.range.roundToInt()} km against fighters at altitude, much less against low-flying cruise missiles and drones.")
            val items = if (u.dead) emptyList() else listOf<PItem>(PBtn("RADAR ${if (u.radarOn) "ON" else "OFF"}", on = u.radarOn) { s.cmdSamRadar(u, !u.radarOn); g.renderPanel(true) })
            return PanelModel("${u.callsign} · GROUND RADAR", C.side(u.side), objFor(s, u), info, items)
        }
        val def = u.d
        if (u.ghost) return PanelModel(u.callsign, C.side(u.side), null, k("Electronic decoys pretending to be a pair of ${def.name}s. Enemy missiles fired at them are wasted. They vanish in ${fmtT(u.fuel)}."), emptyList())
        if (s.isDrone(u)) {
            return PanelModel("${u.callsign} · ${def.name} ×${u.count}", C.side(u.side), null,
                "${status(s, u)}<br>${k("Endurance")} ${fmtT(u.fuel)}" + note(def.note), listOf(mbtn("attack") { Plan("attack", u) }))
        }
        val threats = u.threats.filter { !it.dead }
        val near = threats.minByOrNull { dist(it, u) }
        val fuelF = min(1.0, u.fuel / def.fuel)
        var info = "${status(s, u)}${if (u.bingo) " · " + warn("BINGO") else ""}${if (u.dmg > 0) " · " + warn("DAMAGED") else ""}${u.mission?.let { " · ${k("mission")} ${it.name}" } ?: ""}"
        if (def.role != "aew") info += wpnRows(u)
        info += "<br>${k("Fuel")} ${bar(fuelF, if (fuelF < 0.3) C.RED else C.CYAN)} · ${k("Radar")} ${if (def.role == "aew") "ON (AEW&amp;C)" else radarState(u)}"
        if (near != null) info += "<br>" + warn("⚠ ${threats.size} missile${if (threats.size > 1) "s" else ""} inbound · nearest ${near.w.name}, ${dist(near, u).roundToInt()} km${if (u.defending) " · evading (${if (u.defMode == "notch") "notch" else "break"})" else ""}")
        info += note(def.note)
        val items = mutableListOf<PItem>()
        if (def.role == "fighter") {
            val mode = u.radarMode
            fun radarBtn(m: String, label: String, on: Boolean, enabled: Boolean = true) = PBtn(label, on = on, enabled = enabled) { setRadar(s, u, m) }
            items.add(PSeg(listOf(
                radarBtn("auto", "AUTO", mode == "auto" && !u.exposed),
                radarBtn("on", "RADAR ON", mode == "on" || u.exposed),
                radarBtn("off", "RADAR OFF", mode == "off" && !u.exposed, !u.exposed),
                radarBtn("low", "LOW LEVEL", mode == "low" && !u.exposed, !u.exposed),
            )))
            if (s.hasAAM(u)) { items.add(mbtn("chase") { Plan("chase", u) }); items.add(mbtn("escort") { Plan("escort", u) }); items.add(mbtn("feint") { Plan("feint", u) }) }
            val sw = s.strikeWeapon(u)
            if (sw != null) items.add(mbtn("strike", "${Data.WEAPONS[sw]!!.name} ×${u.wpn[sw]}") { Plan("strike", u) })
        }
        items.add(PBtn("RETURN TO BASE", style = "danger") {
            u.mission?.status = "done"
            s.cmdRTB(u)
            g.hintMsg("${u.callsign} returning to ${u.order?.base ?: u.base}: it lands, refuels and re-arms there")
            g.renderPanel(true)
        })
        return PanelModel("${u.callsign} · ${def.name}${if (u.count > 1) " ×" + u.count else ""}", C.side(u.side), null, info, items)
    }

    private fun setRadar(s: Sim, u: Unit, mode: String) {
        val err = s.cmdRadarMode(u, mode)
        if (err != null) { g.hintMsg(err); g.renderPanel(true); return }
        for (w in g.followers(u)) s.cmdRadarMode(w, mode)
        g.hintMsg(when (mode) {
            "auto" -> "${u.callsign}: radar on automatic. It switches on to look and to shoot."
            "on" -> "${u.callsign}: radar ON. It sees further, but enemy radars can lock it and fire."
            "off" -> "${u.callsign}: radar OFF. Enemy radars still see it but can't lock it. It only fires at its mission target, switching the radar on to do it."
            else -> "${u.callsign}: LOW LEVEL, radar off. Hidden from AEW&C, ground radars and SAMs; only enemy fighters nearby can see it. Slower and uses more fuel."
        } + if (u.exposed) "" else " Switching the radar on inside enemy airspace exposes the jet until it lands.")
        g.renderPanel(true)
    }

    private fun describeTrack(s: Sim, e: Ent): PanelModel? {
        val tr = s.trackFor(st.side, e) ?: return null
        if (e.kind == "sam" || e.kind == "radar") {
            val u = e as Unit
            val reach = if (e.kind == "sam") "Estimated reach ~${(Data.WEAPONS[u.samDef!!.weapon]!!.range * 0.7).roundToInt()} km: stay outside the red ring. " else ""
            return PanelModel("${if (e.kind == "sam") "SAM SITE" else "RADAR"}: ${u.callsign}", C.side(e.side), objFor(s, e),
                hitsRow(e) + note("Known from intelligence and its emissions. ${reach}Strike it with a GROUND ATTACK, or send drones."), emptyList())
        }
        if (e is Missile) {
            return PanelModel("T${tr.num} · ${e.w.name.uppercase()}", C.side(e.side), null,
                k("Inbound to ${e.tgt?.let { s.targetId(it) } ?: "?"}. Select a fighter and tap it to attempt an intercept."), emptyList())
        }
        val u = e as Unit
        val seen = u.seen[st.side]
        val sources = seen?.by?.mapNotNull { s.unit(it)?.callsign }?.take(4)?.joinToString(", ") ?: ""
        val hdg = ((Math.toDegrees(u.hdg) + 360) % 360).roundToInt()
        val age = s.t - tr.t
        val n = if (tr.count > 0) tr.count else u.count
        val esm = tr.q == "esm"
        var info = "${k("Quality")} ${if (esm) "PASSIVE (radar emissions only)" else if (age > 3) "LOST ${age.roundToInt()}s ago" else "RADAR track"}" +
            if (!esm && age <= 3) " · ${k("hdg")} ${String.format("%03d", hdg)}°" else ""
        if (sources.isNotEmpty()) info += "<br>${k("Seen by")} $sources"
        info += "<br>${k("Data-link firing track")} ${if (seen?.net == true) "YES" else warn("NO")}"
        if (tr.striker != null) info += "<br>" + warn("⚠ STRIKER: likely target ${tr.striker}")
        info += note("${u.d.note} Select one of your fighters, then tap this track to attack it.")
        return PanelModel("T${tr.num} · ${if (esm) u.d.name + "?" else u.d.name + if (n > 1) " ×$n" else ""}", C.side(e.side), null, info, emptyList())
    }

    // ---------- views ----------
    fun view(i: PItem): View = when (i) {
        is PBtn -> btnView(i)
        is PSeg -> LinearLayout(ui.ctx).apply {
            for ((j, b) in i.btns.withIndex()) addView(btnView(b, 11f).apply { minHeight = ui.dp(34); setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6)) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (j > 0) leftMargin = ui.dp(4) })
        }
        is PNote -> ui.text(html(i.html), 11f, C.DIM)
        is PPool -> ui.col().apply {
            background = ui.box(0x14000000, C.LINE, 6f)
            setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(8))
            addView(ui.text(html("<b>${i.title}</b>&nbsp;&nbsp;${k(i.sub)}"), 12f, C.WHITE))
            i.stepper?.let { sp ->
                val row = LinearLayout(ui.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(ui.text("Send", 12f, C.DIM))
                fun step(d: Int) {
                    g.sound.play("click")
                    var n = max(1, (g.st.deployN[sp.key] ?: 2) + d)
                    if (d > 0) n = min(n, max(1, sp.max))
                    g.st.deployN[sp.key] = n
                    g.renderPanel(true)
                }
                val minus = ui.button("−", size = 15f) { step(-sp.step) }.apply { minWidth = ui.dp(40) }
                val plus = ui.button("+", size = 15f) { step(sp.step) }.apply { minWidth = ui.dp(40) }
                row.addView(minus, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(8) })
                row.addView(ui.text("${sp.n}", 16f, C.WHITE, bold = true).apply { gravity = Gravity.CENTER; minWidth = ui.dp(36) })
                row.addView(plus)
                row.addView(ui.text(sp.unit, 12f, C.DIM), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(8) })
                addView(row, ui.lp(top = 6))
            }
            for (b in i.items) addView(view(b), ui.lp(top = 5))
        }
    }
    private fun btnView(b: PBtn, size: Float = 12f): android.widget.TextView {
        val accent = when (b.style) { "danger" -> C.RED; "go" -> C.HOT; else -> C.CYAN }
        val v = ui.button(b.label, b.sub, accent = accent, on = b.on, filled = b.style == "go", size = size) { g.sound.play("click"); b.act() }
        if (!b.enabled) { v.isEnabled = false; v.alpha = 0.35f }
        return v
    }
}
