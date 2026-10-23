package com.killchain.core

import com.killchain.core.JsMath.atan2
import com.killchain.core.JsMath.cos
import com.killchain.core.JsMath.hypot
import com.killchain.core.JsMath.sin
import java.util.Locale

// Simulation core: bases and sorties, sensors, data-link picture, missiles, strikes, drones,
// electronic warfare, escalation, claims and replay recording. A line-by-line port of js/sim.js:
// with the same seed it produces the same night, event for event.

const val DEG = Math.PI / 180
val ENEMY = mapOf("IN" to "PK", "PK" to "IN")
val SIDES = listOf("IN", "PK")
private val GIMBAL = 60 * DEG
private const val AI_FIRE = 0.75          // fraction of max launch range used by automatic shots
const val CLOSED_AT = 60.0                // base damage at which the runway closes
private const val REPAIR_PER_MIN = 5.0    // base damage repaired per minute
/** Formation slots [right, back] in units of FORM_KM: lead, wingman, element lead, its wingman, ... */
val FORMATION = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(-1.0, 1.0), doubleArrayOf(1.2, 0.6), doubleArrayOf(2.2, 1.6),
    doubleArrayOf(-2.0, 2.0), doubleArrayOf(3.2, 2.6), doubleArrayOf(-3.0, 3.0), doubleArrayOf(4.2, 3.6))
const val FORM_KM = 1.5
// Emission control and low flying.
const val VIS_KM = 15.0                   // any fighter sees aircraft this close, radar or not
private const val LOW_SPEED = 0.85        // terrain hugging: slower ...
private const val LOW_FUEL = 1.6          // ... and thirstier
private const val LOW_LOOKDOWN = 0.6      // fighter radars see a low flyer at this fraction of their range
const val SAM_RELOAD = 600.0              // seconds for an empty SAM battery to reload
private const val SAM_RELOADS = 1         // spare magazines per site per night

object EW {
    class Kind(val dur: Double, val cd: Double, val r: Double = 0.0, val name: String)
    val jam = Kind(75.0, 360.0, name = "LINK JAM")
    val barrage = Kind(60.0, 240.0, 120.0, "BARRAGE JAM")
    val decoy = Kind(300.0, 300.0, name = "DECOYS")
}

private val CALLSIGN = mapOf(
    "RAFALE" to "VAJRA", "SU30" to "SHAKTI", "MIG29" to "BAAZ", "M2000" to "TRISHUL", "NETRA" to "NETRA", "PHALCON" to "PHALCON", "HAROP" to "HAROP",
    "J10C" to "COBRA", "JF17B3" to "THUNDER", "JF17B2" to "STORM", "F16" to "FALCON", "ERIEYE" to "SKYEYE", "ZDK03" to "EAGLE", "SWARM" to "SWARM",
)

fun clamp(v: Double, a: Double, b: Double) = Math.max(a, Math.min(b, v))
fun angNorm(a: Double) = atan2(sin(a), cos(a))
fun dist(a: XY, b: XY) = hypot(a.x - b.x, a.y - b.y)
fun bearing(a: XY, b: XY) = atan2(b.x - a.x, -(b.y - a.y)) // 0 = north, clockwise
fun velX(e: Ent) = sin(e.hdg) * e.spd
fun velY(e: Ent) = -cos(e.hdg) * e.spd
/** JS Number.prototype.toFixed. */
fun Double.fixed(n: Int): String = String.format(Locale.US, "%.${n}f", this)
private fun jsRound(v: Double) = JsMath.round(v).toInt()

/** The deterministic random generator of the JS version (mulberry32). */
class Mulberry32(seed: Long) {
    private var s = seed.toInt()
    fun next(): Double {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}

class Steer(val hdg: Double, val spd: Double)
class Threat(val mach: Double, val d: Double, val lethal: Boolean, val tgo: Int = 0)

class Sim(val cfg: SimConfig, val player: String?, seed: Long? = null) {
    val night: Night? = cfg.night
    private val rng = Mulberry32(seed ?: System.currentTimeMillis())
    fun rand() = rng.next()
    var t = 0.0
    val duration = cfg.duration
    var nextId = 1
    var units = mutableListOf<Unit>()
    var missiles = mutableListOf<Missile>()
    val events = ArrayDeque<GameEvent>()
    var evSeq = 0
    var fx = mutableListOf<Fx>()
    var over = false
    var crisis = false
    var endReason: String? = null
    private var sweepTimer = 0.0
    private val trackNo = hashMapOf("IN" to 100, "PK" to 100)
    val tracks = mapOf("IN" to LinkedHashMap<Int, Track>(), "PK" to LinkedHashMap<Int, Track>())
    private val callNo = HashMap<String, Int>()
    val score = mapOf("IN" to Score(), "PK" to Score())
    val stocks: Map<String, MutableMap<String, Int>>
    val bases: List<Base>
    val sites: List<Site>
    val objectives: Map<String, List<Objective>>
    var esc: Double
    private val escMarks = HashSet<Int>()
    val ew = mapOf("IN" to EwState(), "PK" to EwState())
    val aewClear = hashMapOf("IN" to 0.0, "PK" to 0.0)
    var prompts = mutableListOf<Prompt>()
    private val promptCd = HashMap<String, Double>()
    val missions = mutableListOf<Mission>()
    private var missionTimer = 0.0
    private val missionNo = HashMap<String, Int>()
    val replay = mutableListOf<Frame>()
    private var replayTimer = 0.0
    val commanders = mutableListOf<Commander>()
    private var defending = false
    private val defendCd = HashMap<String, Double>()
    private val patrolCd = HashMap<String, Double>()

    init {
        val carry = cfg.carry
        val src = carry?.stocks ?: Data.STOCKS
        stocks = SIDES.associateWith { LinkedHashMap(src[it] ?: emptyMap()) }
        bases = Data.BASES.map { b ->
            val base = Base(b.id, b.side, b.x, b.y, b.type)
            val n: Int? = if (cfg.emptyBases) 0 else if (carry?.pools?.get(b.id) != null) null else (night?.counts?.get(b.id) ?: b.count)
            if (b.type != null && n != null) base.pools[b.type] = Pool(n)
            carry?.pools?.get(b.id)?.let { for ((ty, c) in it) base.pools[ty] = Pool(c) }
            // Scenario extras: more aircraft or drone types at a base.
            if (!cfg.emptyBases) night?.pools?.get(b.id)?.let { for ((ty, c) in it) base.pools[ty] = Pool(c) }
            for (ty in base.pools.keys) base.preset[ty] = if (b.type == ty && b.preset != null) b.preset else Data.PRESETS[ty]!!.keys.first()
            carry?.damage?.get(b.id)?.let { if (it != 0) base.damage = it.toDouble() }
            base
        }
        sites = if (night != null && night.sites) Data.SITES.map { Site(it.id, it.side, it.x, it.y) } else emptyList()
        val deadStatics = HashSet(carry?.deadStatics ?: emptyList())
        if (!cfg.noStatics) {
            for (d in Data.SAM_SITES) addStatic("sam", d.side, d.callsign, d.type, d.pos, 0.0, deadStatics.contains(d.callsign))
            for (d in night?.sams ?: emptyList()) addStatic("sam", d.side, d.callsign, d.type, d.pos, 0.0, false)
            for (d in Data.RADAR_SITES) addStatic("radar", d.side, d.callsign, null, d.pos, d.range, deadStatics.contains(d.callsign))
        }
        for (d in cfg.flights) addFlight(d)
        objectives = SIDES.associateWith { s ->
            night?.targets?.get(s)?.map { Objective(it.kind, it.id, refFor(it.kind, it.id)) } ?: emptyList()
        }
        esc = if (carry != null) JsMath.round(carry.esc * 0.6) else 10.0
        // Intelligence: fixed SAM and radar sites are known to the other side before the war.
        for (u in units.toList()) if (u.kind == "sam" || u.kind == "radar") updateTrack(ENEMY[u.side]!!, u, "esm")
        if (night != null && cfg.ai) {
            for (s in SIDES) if (s != player) commanders.add(Commander(this, s, night.ai[s]))
        }
        sweep()
        log("ALL", "${cfg.name}: simulation started", "info")
    }

    // ---------- setup ----------
    fun addFlight(d: FlightDef): Unit {
        val def = Data.AIRCRAFT[d.type]!!
        val u = newFlight(d.side, d.type, d.count, d.base, d.callsign)
        u.x = d.pos.x; u.y = d.pos.y
        u.wpn = LinkedHashMap(Data.LOADOUTS[d.loadout]!!)
        u.loadoutKey = d.loadout
        u.order = cloneOrder(d.order)
        u.fuel = def.fuel
        return u
    }

    fun newFlight(side: String, type: String, count: Int, baseId: String, callsign: String? = null): Unit {
        val def = Data.AIRCRAFT[type]!!
        val base = base(baseId)!!
        val pre = CALLSIGN[type] ?: type
        callNo[pre] = (callNo[pre] ?: 0) + 1
        val u = Unit(nextId++, "air", side, callsign ?: "$pre-${callNo[pre]}", type)
        u.def = def
        u.count = count
        u.base = baseId
        u.x = base.x; u.y = base.y
        u.hdg = if (side == "IN") -90 * DEG else 90 * DEG
        u.spd = def.speed
        u.radarOn = def.role == "aew" || (def.role == "fighter" && !def.linked)
        u.fuel = def.fuel
        u.launchedAt = t
        u.ai = side != player
        units.add(u)
        return u
    }

    private fun addStatic(kind: String, side: String, callsign: String, type: String?, pos: Pt, range: Double, dead: Boolean) {
        val u = Unit(nextId++, kind, side, callsign, type)
        u.x = pos.x; u.y = pos.y
        u.dead = dead
        u.radarOn = true
        if (kind == "sam") { u.samDef = Data.SAMS[type]; u.ammo = u.samDef!!.ammo; u.range = u.samDef!!.radar; u.reloads = SAM_RELOADS }
        else u.range = range
        units.add(u)
    }

    fun cloneOrder(o: Order?): Order? {
        if (o == null) return null
        val c = Order(o.type)
        c.x = o.x; c.y = o.y; c.r = o.r; c.base = o.base; c.target = o.target
        c.list = o.list.map { Job(it.kind, it.id, it.n) }.toMutableList()
        c.via = o.via?.map { Pt(it.x, it.y) }?.toMutableList()
        c.done = o.done; c.mode = o.mode; c.hold = o.hold; c.toldAt = o.toldAt
        return c
    }

    /** Planned route: fly through the waypoints in order (passed within 8 km), then on to the target. */
    fun viaAim(u: Unit, o: Order): Pt? {
        val via = o.via ?: return null
        while (via.isNotEmpty() && dist(u, via[0]) < 8) via.removeAt(0)
        return via.firstOrNull()
    }

    fun base(id: String?): Base? = bases.find { it.name == id }
    fun site(id: String?): Site? = sites.find { it.name == id }
    fun unit(id: Int): Unit? = units.find { it.id == id }
    fun staticBy(callsign: String): Unit? = units.find { (it.kind == "sam" || it.kind == "radar") && it.callsign == callsign }
    fun refFor(kind: String, id: String): Ent? = when (kind) {
        "site" -> site(id)
        "base" -> base(id)
        else -> staticBy(id)
    }
    fun targetId(ref: Ent): String = when (ref) {
        is Base -> ref.name
        is Site -> ref.name
        is Unit -> ref.callsign
        else -> ref.id.toString()
    }

    fun log(side: String, text: String, cls: String = "info", striker: Unit? = null, alert: Boolean = false, ref: Ent? = null) {
        events.addLast(GameEvent(++evSeq, t, side, text, cls, striker, alert, ref))
        if (events.size > 500) events.removeFirst()
    }

    fun clock(at: Double? = null): String {
        val tot = cfg.startH * 3600 + cfg.startM * 60 + Math.floor(at ?: t).toInt()
        val h = (tot / 3600) % 24; val m = (tot / 60) % 60; val sec = tot % 60
        return String.format(Locale.US, "%02d%02d:%02dZ", h, m, sec)
    }

    // ---------- queries ----------
    fun isAlive(e: Ent?): Boolean {
        if (e == null) return false
        return when (e.kind) {
            "air" -> (e as Unit).state == "air"
            "cruise" -> !e.dead
            "sam", "radar" -> !e.dead
            else -> true
        }
    }
    fun isLinked(u: Unit) = u.kind != "air" || u.d.linked
    fun emitting(u: Unit): Boolean {
        if (u.kind == "air") return u.state == "air" && !u.ghost && (u.radarOn || u.d.role == "aew")
        return (u.kind == "sam" || u.kind == "radar") && !u.dead && u.radarOn
    }
    fun sensorRange(u: Unit): Double {
        if (u.kind == "air") return if (emitting(u)) u.d.radar else 0.0
        return if (emitting(u)) u.range else 0.0
    }
    fun targetsOf(side: String): List<Ent> {
        val out = ArrayList<Ent>()
        for (u in units) if (u.side == side && u.kind == "air" && u.state == "air") out.add(u)
        for (m in missiles) if (m.side == side && m.kind == "cruise" && !m.dead) out.add(m)
        return out
    }
    fun isDrone(e: Ent) = e is Unit && e.kind == "air" && e.d.role == "drone"
    fun manned(e: Ent) = e is Unit && e.kind == "air" && !e.ghost && e.d.role != "drone"
    /**
     * A fighter with its radar off can be seen but not locked: radar-guided missiles (BVR
     * missiles and SAMs) can't be fired at it, only heat-seekers inside their range.
     */
    fun isSilent(t: Ent) = t is Unit && t.kind == "air" && t.d.role == "fighter" && !t.ghost && !t.radarOn

    /**
     * Switch a fighter's radar. Turning it on inside enemy airspace exposes the jet: its radar
     * stays on and it can't go low again until it lands. It also ends terrain hugging.
     */
    fun setRadar(u: Unit, on: Boolean) {
        if (u.d.role != "fighter" || on == u.radarOn) return
        if (!on && u.exposed) return
        u.radarOn = on
        if (!on) return
        if (u.low) {
            u.low = false
            if (u.radarMode == "low") u.radarMode = "auto"
            if (u.side == player) log(u.side, "${u.callsign} radar ON: climbing out of terrain hugging", "warn")
        }
        if (!u.exposed && Geo.sideOf(u.x, u.y) != u.side) {
            u.exposed = true
            if (u.side == player) log(u.side, "${u.callsign} radar ON inside enemy airspace: position EXPOSED until it lands", "warn")
        }
    }

    /** Radar state for a fighter each step, from its mode: auto (the AI decides), on, off, or low (terrain hugging). */
    fun radarLogic(u: Unit) {
        if (u.d.role != "fighter") return
        if (u.exposed) { u.radarOn = true; u.low = false; return }
        val mode = u.radarMode
        val guiding = missiles.any { it.shooter === u && !it.lock && !it.w.tp && it.kind != "cruise" && it.kind != "srm" }
        val firing = t - u.lastShot < 6 || t - u.lastLaunch < 6
        val want: Boolean = when (mode) {
            "on" -> true
            "off", "low" -> guiding || firing
            else -> {
                val aew = hasAEW(u.side) && ew[ENEMY[u.side]]!!.jam.until <= t
                val tgt = u.engage ?: (if (u.order?.type == "attack") u.order!!.target else null)
                val near = tgt != null && isAlive(tgt) && dist(u, tgt) < u.d.radar
                if (u.d.linked) !aew || guiding || firing || (near && (!fireQuality(u, tgt!!) || !tpOnly(u)))
                else tgt != null || firing || nearestEnemy(u, 150.0) != null
            }
        }
        u.low = mode == "low" && !want
        setRadar(u, want)
    }

    fun airborne(side: String): Int {
        var n = 0
        for (u in units) if (u.side == side && u.state == "air" && manned(u)) n += u.count
        return n
    }
    fun baseOpen(b: Base) = b.damage < CLOSED_AT

    private fun rcsOf(t: Ent) = if (t is Unit) t.d.rcs else (t as Missile).w.rcs
    fun detects(s: Unit, t: Ent): Boolean {
        var r0 = sensorRange(s)
        if (r0 == 0.0) return false
        // Terrain hugging: hidden from AEW&C and ground radars; fighters see it only looking down, closer in.
        if (t is Unit && t.kind == "air" && t.low) {
            if (s.kind != "air" || s.d.role != "fighter") return false
            r0 *= LOW_LOOKDOWN
        }
        val rcs = rcsOf(t)
        val low = if (t.kind == "cruise") (t as Missile).w.low else (t as Unit).d.low
        var f = JsMath.pow(rcs / 5, 0.25)
        if (low) f *= if (s.kind == "air") (if (s.d.role == "aew") 0.7 else 0.55) else 0.35
        if (t is Unit && t.kind == "air") f *= 1 - t.d.ew * 0.3
        val bj = ew[t.side]!!.barrage
        if (bj.until > this.t && hypot(t.x - bj.x, t.y - bj.y) < EW.barrage.r) f *= 0.55
        val d = dist(s, t)
        if (d > r0 * f) return false
        if (s.kind == "air" && s.d.role == "fighter") {
            if (Math.abs(angNorm(bearing(s, t) - s.hdg)) > GIMBAL) return false
        }
        if (s.kind == "air" && notching(t, s)) return false
        return true
    }

    /**
     * A target flying at ~90 degrees to a pulse-Doppler radar has almost no closing speed, so the
     * radar filters it out with the ground clutter ("the notch").
     */
    fun notching(t: Ent, obs: XY): Boolean {
        if (t !is Unit || t.kind != "air" || !t.defending || t.defMode != "notch") return false
        val aspect = Math.abs(angNorm(t.hdg - bearing(obs, t)))
        return Math.abs(aspect - Math.PI / 2) < 20 * DEG
    }

    /**
     * Rough look at an incoming missile: its Mach number, distance, and whether it still has the
     * energy to reach the target if the target keeps doing what it does.
     */
    fun missileThreat(m: Missile, dragging: Boolean): Threat {
        val t = m.target!!
        val d = dist(m, t)
        val los = bearing(m, t)
        val tvx = velX(t); val tvy = velY(t)
        val tdash = (t as? Unit)?.def?.dash ?: 0.0
        val away = if (dragging) tdash else tvx * sin(los) - tvy * cos(los)
        val mach = m.spd / 0.295
        val need = Math.max(V_MIN, (if (dragging) tdash else t.spd) * 1.1)
        // Fly the missile forward in 1 s steps against a target holding its current motion.
        var v = m.spd; var gap = d; var tm = m.t
        for (i in 0 until 240) {
            if (tm < m.w.burn) v = Math.min(m.w.vMax, v + 0.3) else v -= m.w.drag * v
            tm += 1
            gap -= v - away
            if (gap <= 0) return Threat(mach, d, v > need, i + 1)
            if (v < need) break
        }
        return Threat(mach, d, false)
    }

    fun autoDefMode(u: Unit, m: Missile?): String {
        if (m == null || m.kind == "srm") return "drag"
        // Drag if the missile can be outrun; if it is too fast to outrun, notch it.
        return if (missileThreat(m, true).lethal) "notch" else "drag"
    }

    /** Can unit u launch on / guide against t using its own radar or the data link? */
    fun fireQuality(u: Unit, t: Ent): Boolean {
        val seen = t.seen[u.side]
        if (seen == null || !seen.any) return false
        if (seen.by.contains(u.id)) return true
        return isLinked(u) && seen.net
    }
    fun ownTrack(u: Unit, t: Ent): Boolean = t.seen[u.side]?.by?.contains(u.id) == true
    fun inFlightAt(side: String, t: Ent): Int {
        var n = 0
        for (m in missiles) if (m.side == side && m.target === t && m.kind != "cruise") n++
        return n
    }

    /**
     * Rough max launch range (km) of weapon w from shooter against target t, accounting for target
     * closing speed and shooter launch speed.
     */
    fun maxRange(w: WeaponDef, shooter: Unit, t: Ent): Double {
        val d = Math.max(0.1, dist(shooter, t))
        val closing = (velX(t) * (shooter.x - t.x) + velY(t) * (shooter.y - t.y)) / d
        // Only half credit for a closing target: it can still turn away.
        val eff = if (closing > 0) closing * 0.5 else closing
        val bonus = if (shooter.kind == "air") shooter.spd * 20 else 0.0
        return Math.max(w.range * 0.3, (w.range + eff * w.tMax * 0.8 + bonus) * 0.85)
    }

    fun chooseWeapon(u: Unit, t: Ent, factor: Double): String? {
        val d = dist(u, t)
        val pool: Map<String, Int> = if (u.kind == "sam") (if (u.ammo > 0) mapOf(u.samDef!!.weapon to u.ammo) else emptyMap()) else u.wpn
        var best: String? = null; var bestRange = Double.POSITIVE_INFINITY
        val silent = isSilent(t)
        for ((k, c) in pool) {
            if (c == 0) continue
            val w = Data.WEAPONS[k]!!
            if (w.kind == "cruise") continue
            if (silent && w.kind != "srm") continue   // no radar lock on a silent jet
            if (w.kind == "srm" && d > w.range * 0.9) continue
            val r = maxRange(w, u, t) * factor
            if (r >= d && w.range < bestRange) { best = k; bestRange = w.range }
        }
        return best
    }
    fun bestRange(u: Unit, kind: String = "aam"): Double {
        var r = 0.0
        for ((k, c) in u.wpn) if (c != 0 && Data.WEAPONS[k]!!.kind == kind) r = Math.max(r, Data.WEAPONS[k]!!.range)
        return r
    }
    fun hasAAM(u: Unit): Boolean {
        for ((k, c) in u.wpn) if (c != 0 && (Data.WEAPONS[k]!!.kind == "aam" || Data.WEAPONS[k]!!.kind == "srm")) return true
        return false
    }
    /** True when every remaining BVR missile can be guided by third-party link tracks. */
    fun tpOnly(u: Unit): Boolean {
        for ((k, c) in u.wpn) if (c != 0 && Data.WEAPONS[k]!!.kind == "aam" && !Data.WEAPONS[k]!!.tp) return false
        return true
    }
    /** A missile needs the shooter's own radar track unless it can use link tracks. */
    fun canGuide(u: Unit, t: Ent, k: String): Boolean {
        val w = Data.WEAPONS[k]!!
        if (w.kind == "srm" || u.kind == "sam") return true
        return ownTrack(u, t) || (w.tp && isLinked(u) && t.seen[u.side]?.net == true)
    }
    fun strikeWeapon(u: Unit): String? {
        var best: String? = null
        for ((k, c) in u.wpn) if (c != 0 && Data.WEAPONS[k]!!.kind == "cruise" && (best == null || Data.WEAPONS[k]!!.range > Data.WEAPONS[best]!!.range)) best = k
        return best
    }
    fun hasAEW(side: String) = units.any { it.side == side && it.kind == "air" && it.state == "air" && it.d.role == "aew" }
    fun trackFor(side: String, e: Ent): Track? = tracks[side]!![e.id]
    fun nearestOpenBase(side: String, p: XY, type: String? = null): Base? {
        var best: Base? = null; var bd = Double.POSITIVE_INFINITY
        for (b in bases) {
            if (b.side != side || !baseOpen(b)) continue
            val d = dist(b, p) * (if (type != null && b.home == type) 0.7 else 1.0)
            if (d < bd) { bd = d; best = b }
        }
        return best
    }

    // ---------- sensors & picture ----------
    fun sweep() {
        for (side in SIDES) {
            val enemy = ENEMY[side]!!
            val jammed = ew[enemy]!!.jam.until > t   // our data link is being jammed
            val sensors = units.filter { it.side == side && emitting(it) }
            val table = tracks[side]!!
            for (tg in targetsOf(enemy)) {
                val by = ArrayList<Int>()
                var net = false
                for (s in sensors) {
                    if (detects(s, tg)) { by.add(s.id); if (isLinked(s) && !jammed) net = true }
                }
                // Eyeballs: any fighter sees an aircraft close by, radar on or off.
                if (tg.kind == "air") {
                    for (f in units) {
                        if (f.side != side || f.kind != "air" || f.state != "air" || f.ghost || f.d.role != "fighter" || by.contains(f.id)) continue
                        if (dist(f, tg) < VIS_KM) { by.add(f.id); if (isLinked(f) && !jammed) net = true }
                    }
                }
                tg.seen[side] = Seen(by.isNotEmpty(), net, by)
                if (by.isNotEmpty()) updateTrack(side, tg, "radar")
            }
            // Passive detection (RWR / ESM) of enemy emitters.
            val listeners = units.filter { it.side == side && (if (it.kind == "air") it.state == "air" else !it.dead) }
            for (e in units.toList()) {
                if (e.side != enemy || !emitting(e)) continue
                val tr = table[e.id]
                if (tr != null && tr.q == "radar" && t - tr.t < 2) continue
                if (tr != null && tr.isStatic) continue
                val r = Math.min(450.0, sensorRange(e) * 1.25)
                if (listeners.any { dist(it, e) <= r }) updateTrack(side, e, "esm")
            }
            val it = table.entries.iterator()
            while (it.hasNext()) {
                val tr = it.next().value
                if (tr.isStatic) { if (tr.ref.dead && tr.bda) it.remove(); continue }
                if (!isAlive(tr.ref) || t - tr.t > 45) it.remove()
            }
        }
        checkBaseThreats()
        strikerWarnings()
    }

    /** Early warning: flag enemy strike jets when first detected and again when close. */
    private fun strikerWarnings() {
        for (side in SIDES) {
            for (tr in tracks[side]!!.values.toList()) {
                val e = tr.ref as? Unit ?: continue
                val o = e.order
                // A diversion is built to look exactly like a strike.
                if (e.kind != "air" || e.ghost || !isAlive(e) || o == null || (o.type != "strike" && o.type != "feint") || o.done || o.list.isEmpty()) continue
                val job = o.list[0]
                val tgt = refFor(job.kind, job.id) ?: continue
                val d = dist(e, tgt)
                tr.striker = targetId(tgt)
                if (tr.warnStage == 0) {
                    tr.warnStage = 1
                    log(side, "⚠ STRIKER INBOUND: T${tr.num} ${e.d.name}${if (e.count > 1) " ×" + e.count else ""} heading for ${tr.striker}, ${d.fixed(0)} km out. Send a CHASE.", "loss", striker = e)
                } else if (tr.warnStage == 1 && d < 100) {
                    tr.warnStage = 2
                    log(side, "⚠ STRIKER CLOSING: T${tr.num} is ${d.fixed(0)} km from ${tr.striker}", "loss", striker = e)
                }
            }
        }
    }

    fun updateTrack(side: String, e: Ent, q: String) {
        val table = tracks[side]!!
        var tr = table[e.id]
        if (tr == null) {
            trackNo[side] = trackNo[side]!! + 1
            tr = Track(e.id, e, trackNo[side]!!, t, e.kind, e.kind == "sam" || e.kind == "radar")
            table[e.id] = tr
            if (e is Unit && e.kind == "air" && side == player && !isDrone(e)) {
                log(side, "New track T${tr.num}: ${e.d.name}${if (e.count > 1) " x" + e.count else ""} (${if (q == "esm") "passive" else "radar"})", "info")
            }
        }
        if (q == "esm" && tr.q == "radar" && t - tr.t < 2) return
        val err = if (q == "esm" && !tr.isStatic) 6.0 else 0.0
        tr.q = q
        tr.t = t
        tr.x = e.x + (rand() - 0.5) * err
        tr.y = e.y + (rand() - 0.5) * err
        tr.vx = if (q == "esm") 0.0 else velX(e)
        tr.vy = if (q == "esm") 0.0 else velY(e)
        tr.count = (e as? Unit)?.count ?: 1
    }
    fun trackPos(tr: Track): Pt {
        val dt = Math.min(60.0, t - tr.t)
        return Pt(tr.x + tr.vx * dt, tr.y + tr.vy * dt)
    }

    // ---------- main step ----------
    fun update(dt0: Double) {
        if (over) return
        val step = 0.25
        var dt = dt0
        while (dt > 1e-6) {
            val h = Math.min(step, dt)
            step(h)
            dt -= h
            if (over) break
        }
    }

    private fun step(dt: Double) {
        t += dt
        sweepTimer -= dt
        if (sweepTimer <= 0) { sweep(); sweepTimer = 1.0 }
        for (c in commanders) c.tick(dt)
        missionTimer -= dt
        if (missionTimer <= 0) { tickMissions(); missionTimer = 2.0 }
        for (b in bases) updateBase(b, dt)
        var i = 0
        while (i < units.size) {
            val u = units[i++]
            if (u.kind == "air") { if (u.state == "air") updateFlight(u, dt) }
            else if (u.kind == "sam" && !u.dead) updateSam(u)
        }
        i = 0
        while (i < missiles.size) {
            val m = missiles[i++]
            if (m.kind == "cruise") updateCruise(m, dt) else updateMissile(m, dt)
        }
        missiles = missiles.filterTo(ArrayList()) { !it.dead }
        if (units.size > 400) units = units.filterTo(ArrayList()) { it.kind != "air" || it.state == "air" }
        fx = fx.filterTo(ArrayList()) { t - it.t < 12 }
        pointDefence(dt)
        esc = Math.max(0.0, esc - dt * 0.5 / 60)
        prompts = prompts.filterTo(ArrayList()) { !it.done }
        replayTimer -= dt
        if (replayTimer <= 0) { record(); replayTimer = 3.0 }
        val cf = night?.ceasefireAt
        if (cf != null && cf != 0.0 && t >= cf) finish("CEASEFIRE: hostilities end")
        if (t >= duration) finish("Night over")
    }

    // ---------- bases & sorties ----------
    private fun updateBase(b: Base, dt: Double) {
        if (b.damage > 0) {
            val wasClosed = !baseOpen(b)
            b.damage = Math.max(0.0, b.damage - dt * REPAIR_PER_MIN / 60)
            if (wasClosed && baseOpen(b)) log(b.side, "${b.name}: runway repaired, base operational", "good")
        }
        for ((ty, p) in b.pools) {
            if (p.rearm.isEmpty()) continue
            val done = p.rearm.count { it <= t }
            if (done > 0) {
                p.rearm = p.rearm.filterTo(ArrayList()) { it > t }
                p.ready += done
                if (b.side == player) log(b.side, "${b.name}: $done ${Data.AIRCRAFT[ty]!!.name} refuelled and re-armed", "info")
            }
        }
    }

    /** Weapons one jet of this type would take off with from base b. */
    fun loadoutFor(b: Base, type: String, preset: String? = null): Map<String, Int> =
        Data.PRESETS[type]!![preset ?: b.preset[type]] ?: emptyMap()

    /** Launch n aircraft of a type from a base. Returns the lead aircraft, or an error message. */
    fun launchFlight(baseId: String, type: String, n0: Int, order0: Order? = null, preset0: String? = null, ai: Boolean? = null): Any {
        val b = base(baseId)
        val def = Data.AIRCRAFT[type]!!
        val p = b?.pools?.get(type) ?: return "No aircraft of that type here"
        if (!baseOpen(b)) return "${b.name} runway is closed"
        var n = Math.min(n0, p.ready)
        if (n <= 0) return "No aircraft ready"
        if (def.role != "drone") {
            val room = Data.AIR_CAP[b.side]!! - airborne(b.side)
            if (room <= 0) return "Airborne limit reached (${Data.AIR_CAP[b.side]})"
            n = Math.min(n, room)
        }
        val preset = preset0 ?: b.preset[type]
        val per = Data.PRESETS[type]!![preset] ?: emptyMap()
        val stock = stocks[b.side]!!
        val wpn = LinkedHashMap<String, Int>()
        for ((k, c) in per) {
            val have = stock[k]
            val take = Math.min(c * n, have ?: (c * n))
            if (have != null) stock[k] = have - take
            if (take > 0) wpn[k] = take
        }
        p.ready -= n
        val order = order0 ?: defaultOrbit(b)
        // Fighters fly as individual aircraft: a lead and wingmen who keep formation on it while
        // they share its task, but can fight, evade and be re-tasked on their own.
        val each = if (def.role == "fighter") n else 1
        val jobs = if (order.type == "strike") splitStrike(order, each, wpn) else null
        var lead: Unit? = null
        for (i in 0 until each) {
            val u = newFlight(b.side, type, if (def.role == "fighter") 1 else n, b.name, lead?.let { "${it.callsign}.${i + 1}" })
            u.wpn = LinkedHashMap()
            for ((k, c) in wpn) {
                val share = c / each + (if (i < c % each) 1 else 0)
                if (share > 0) u.wpn[k] = share
            }
            u.preset = preset
            u.ai = ai ?: (b.side != player)
            u.order = if (jobs != null) Order("strike").also { o -> o.list = jobs[i]; o.via = order.via?.map { Pt(it.x, it.y) }?.toMutableList() } else cloneOrder(order)
            val aim: XY = if (order.hasPoint) Pt(order.x, order.y) else orderPoint(u) ?: b
            val h = bearing(b, aim)
            if (h != 0.0 && !h.isNaN()) u.hdg = h
            if (u.ai && u.order!!.type == "orbit") u.cap = Circle(u.order!!.x, u.order!!.y, if (u.order!!.r != 0.0) u.order!!.r else 15.0)
            if (u.order!!.type == "strike" || u.order!!.type == "drone") u.strikeSince = t
            if (lead != null) {
                u.lead = lead; u.slot = i
                // Wingmen take off a few seconds behind the lead.
                u.x -= sin(u.hdg) * i * 1.5; u.y += cos(u.hdg) * i * 1.5
                lead.wing.add(u)
            } else { lead = u; u.wing = mutableListOf() }
        }
        if (b.side == player) log(b.side, "${lead!!.callsign}: $n ${def.name} airborne from ${b.name}", "info")
        return lead!!
    }

    /**
     * Share a strike order's targets out between the jets of a flight: each jet takes the jobs in
     * turn, with all of its own strike weapons spread over them.
     */
    private fun splitStrike(order: Order, each: Int, wpn: Map<String, Int>): List<MutableList<Job>> {
        val list = order.list
        val cruise = wpn.entries.filter { Data.WEAPONS[it.key]!!.kind == "cruise" }.sumOf { it.value }
        val per = Math.max(1, cruise / each)
        return List(each) { i ->
            val mine = if (list.size >= each) list.filterIndexed { j, _ -> j % each == i } else listOf(list[i % list.size])
            val n = Math.max(1, per / mine.size)
            mine.map { Job(it.kind, it.id, n) }.toMutableList()
        }
    }

    // ---------- formations ----------
    /**
     * A wingman shares its lead's task (same order object), unless it is on its own strike run, or
     * has gone home on its own (fuel, weapons).
     */
    private fun formSync(u: Unit) {
        var l = u.lead
        if (l != null && (l.state != "air" || l.lead != null)) { regroup(l); l = u.lead }
        if (l == null) return
        if (u.mission !== l.mission || (u.order?.type == "rtb" && u.order !== l.order)) { u.lead = null; return }
        if (u.order == null || u.order!!.type != "strike") {
            u.order = l.order
            if (l.cap != null) u.cap = l.cap
            if (l.anchor != null) u.anchor = l.anchor
        }
    }

    /** Is this wingman flying in formation right now (same target, nothing else to do)? */
    private fun inFormation(u: Unit): Boolean {
        val l = u.lead
        if (l == null || l.state != "air" || u.engage != null || u.firing || u.defending || dist(u, l) > 120) return false
        val o = u.order ?: return false
        val lo = l.order ?: return false
        if (o.type == "strike") return lo.type == "strike" && !l.firing && o.list.isNotEmpty() && lo.list.isNotEmpty() && o.list[0].id == lo.list[0].id
        if (o !== lo) return false
        if (o.type == "attack") return isAlive(o.target) && dist(u, o.target!!) > bestRange(u) * 1.2
        return true
    }

    /** Fly to the slot: off the lead's wing, a little behind, matching its speed. */
    private fun formSteer(u: Unit): Steer {
        val l = u.lead!!
        val k = FORMATION[u.slot % FORMATION.size]
        val fx = sin(l.hdg); val fy = -cos(l.hdg)
        val sx = l.x - fy * k[0] * FORM_KM - fx * k[1] * FORM_KM
        val sy = l.y + fx * k[0] * FORM_KM - fy * k[1] * FORM_KM
        val along = (sx - u.x) * fx + (sy - u.y) * fy
        val spd = clamp(l.spd + along * 0.03, l.d.speed * 0.75, u.d.dash)
        return Steer(bearing(u, Pt(sx + fx * 6, sy + fy * 6)), spd)
    }

    /** The lead has gone (shot down, landed, or given another task): the next wingman leads. */
    fun regroup(l: Unit) {
        val fol = units.filter { it.lead === l && it.state == "air" }.sortedBy { it.slot }
        if (fol.isEmpty()) return
        val nl = fol[0]
        nl.lead = null; nl.slot = 0; nl.wing = fol.drop(1).toMutableList()
        fol.drop(1).forEachIndexed { i, u -> u.lead = nl; u.slot = i + 1 }
        val ms = l.mission
        if (ms != null && ms.unit === l && ms.status != "done" && nl.mission === ms) ms.unit = nl
        for (m in missions) if (m.escortUnit === l) m.escortUnit = nl
        if (l.offensive) nl.offensive = true
        for (c in commanders) c.replaceUnit(l, nl)
    }

    /** Take a jet out of its formation (it has been given its own task). */
    fun detach(u: Unit) {
        if (units.any { it.lead === u }) regroup(u)
        u.lead = null; u.inForm = false
    }

    private fun defaultOrbit(b: Base): Order {
        // Hold 25 km off the base, toward the frontier.
        val h = if (b.side == "IN") -90 * DEG else 90 * DEG
        return Order.orbit(b.x + sin(h) * 25, b.y - cos(h) * 25, 12.0)
    }

    fun orderPoint(u: Unit): XY? {
        val o = u.order ?: return null
        if (o.hasPoint) return Pt(o.x, o.y)
        if (o.type == "attack") return o.target
        if ((o.type == "strike" || o.type == "drone") && o.list.isNotEmpty()) return refFor(o.list[0].kind, o.list[0].id)
        return null
    }

    private fun land(u: Unit, b: Base) {
        u.state = "landed"
        u.exposed = false; u.low = false; u.radarMode = "auto"; u.egress = false
        regroup(u)
        u.threats = mutableListOf(); u.defending = false
        val p = b.pools.getOrPut(u.type!!) { Pool(0) }
        if (b.preset[u.type] == null) b.preset[u.type] = Data.PRESETS[u.type]!!.keys.first()
        val turn = u.d.turnaround * (1 + b.damage / 100)
        for (i in 0 until u.count) p.rearm.add(t + turn)
        val stock = stocks[u.side]!!
        for ((k, c) in u.wpn) stock[k]?.let { stock[k] = it + c }
        if (u.side == player) log(u.side, "${u.callsign} landed at ${b.name}: refuel and re-arm ${jsRound(turn / 60)} min", "info")
    }

    /** Damage to a base from a weapon or drone hit: runway damage plus aircraft on the ground. */
    private fun hitBase(b: Base, dmg: Double, attacker: String, killChance: Double, esc0: Double?): Int {
        val wasOpen = baseOpen(b)
        b.damage = Math.min(100.0, b.damage + dmg)
        var killed = 0
        for ((ty, p) in b.pools) {
            val onGround = p.ready + p.rearm.size
            for (i in 0 until Math.min(onGround, 4)) {
                if (rand() >= killChance) continue
                if (p.ready > 0) p.ready-- else if (p.rearm.isNotEmpty()) p.rearm.removeAt(p.rearm.size - 1) else break
                killed++
                score[attacker]!!.groundKills++
                score[attacker]!!.groundKillValue += Data.AIRCRAFT[ty]!!.value
                score[b.side]!!.groundLost++
            }
        }
        if (killed > 0) log(b.side, "${b.name}: $killed aircraft destroyed on the ground", "loss")
        if (wasOpen && !baseOpen(b)) {
            log("ALL", "${b.name}: RUNWAY CLOSED (damage ${jsRound(b.damage)}%)", "impact")
            for (u in units.toList()) {
                if (u.state == "air" && u.side == b.side && u.order?.type == "rtb" && u.order!!.base == b.name) orderRTB(u)
            }
        }
        addEsc((esc0 ?: 4.0) + killed, "base attack")
        return killed
    }

    /**
     * Every ground target takes 3 hits to destroy. One or two hits leave it damaged: a base loses
     * runway capacity, a SAM site or radar loses range.
     */
    fun applyHit(ref: Ent, attacker: String, drone: Boolean) {
        if (ref.dead) return
        ref.hits += 1
        val id = targetId(ref)
        if (ref is Base) hitBase(ref, 34.0, attacker, if (drone) 0.1 else 0.2, if (drone) 1.0 else 4.0)
        else if (ref.kind == "sam" || ref.kind == "radar") {
            val s = ref as Unit
            if (s.hits >= 3) {
                s.dead = true
                fx.add(Fx("kill", s.x, s.y, t))
                log(s.side, "$id DESTROYED", "loss")
                addEsc(2.0, "air defences destroyed")
            } else {
                s.range = JsMath.round(s.range * 0.75)
                if (s.kind == "sam") s.ammo = Math.ceil(s.ammo / 2.0).toInt()
                log(s.side, "$id damaged (${s.hits}/3 hits): range reduced", "warn")
            }
        } else if (ref.hits == 3) addEsc(3.0, "site destroyed")
        targetHit(attacker, ref)
    }

    fun targetFrac(ref: Ent?): Double = Math.min(3, ref?.hits ?: 0) / 3.0

    /** Shared bookkeeping when a weapon lands on a target. */
    private fun targetHit(attacker: String, ref: Ent) {
        val id = targetId(ref)
        val hits = Math.min(3, ref.hits)
        val obj = objectives[attacker]!!.find { it.ref === ref }
        if (obj != null) {
            obj.hit = true
            if (hits >= 3 && !obj.destroyed) { obj.destroyed = true; log("ALL", "PRIORITY TARGET $id DESTROYED", "impact") }
            else if (hits < 3) log("ALL", "PRIORITY TARGET $id HIT ($hits/3)", "impact")
            score[attacker]!!.objHits.add(id)
        } else score[attacker]!!.otherHits.add(ref)
        score[attacker]!!.hits++
    }

    // ---------- flights ----------
    private fun updateFlight(u: Unit, dt: Double) {
        val def = u.d
        if (u.ghost) { updateGhost(u, dt); return }
        if (def.role == "drone") { updateDrone(u, dt); return }
        if (u.lead != null) formSync(u)
        // Threat assessment.
        u.threats = u.threats.filterTo(ArrayList()) { !it.dead && it.target === u }
        val holding = u.holdUntil > t
        val pressing = if (holding) emptyList() else u.threats.filter { it.lock || (it.warned && dist(it, u) < 40) }
        val md = u.manualDef
        if (md != null && t > md.until) u.manualDef = null
        u.defending = pressing.isNotEmpty() || u.manualDef != null
        val near = if (u.threats.isNotEmpty()) u.threats.reduce { a, b -> if (dist(a, u) < dist(b, u)) a else b } else null
        u.defMode = u.manualDef?.mode ?: if (u.defending) autoDefMode(u, near) else null

        // Fuel.
        u.fuel -= dt * (if (u.spd > def.speed * 1.1) 2.5 else 1.0) * (if (u.low) LOW_FUEL else 1.0)
        // (A wingman following its lead home still checks its own fuel: it may be further out.)
        val o0 = u.order
        if (!u.bingo && o0 != null && (o0.type != "rtb" || (u.lead != null && o0 === u.lead!!.order))) {
            val b = nearestOpenBase(u.side, u, u.type) ?: base(u.base)!!
            if (u.fuel < dist(u, b) / def.speed + 600) {
                u.bingo = true
                val engaged = u.engage != null || u.order?.type == "attack"
                if (!u.ai && engaged && promptFor("bingo", u)) {
                    // the player decides (see answer())
                } else {
                    log(u.side, "${u.callsign} BINGO fuel, returning to ${b.name}", "warn")
                    orderRTB(u, b.name)   // the base the fuel check was made against
                }
            }
        }
        if (u.fuel <= 0) { lose(u, u.count, "ran out of fuel"); return }
        if (!u.crossed && Geo.sideOf(u.x, u.y) != u.side) {
            u.crossed = true
            addEsc(2.0, "airspace violation")
            log(u.side, "${u.callsign} crossed into enemy airspace", "warn")
        }

        if (u.ai) aiThink(u)
        radarLogic(u)

        var want = u.hdg; var spd = def.speed
        if (u.defending) {
            // Turn away from the nearest missile (or the nearest enemy, for a pre-emptive break).
            val from: Ent? = near ?: nearestEnemy(u, 400.0, true)
            val away = if (from != null) bearing(from, u) else u.hdg
            if (u.defMode == "notch") {
                val a = angNorm(away + 90 * DEG); val b = angNorm(away - 90 * DEG)
                want = if (Math.abs(angNorm(a - u.hdg)) < Math.abs(angNorm(b - u.hdg))) a else b
            } else want = away
            spd = def.dash
            if (near != null && (u.chaffAt == 0.0 || t - u.chaffAt > 8) && dist(near, u) < 12) {
                u.chaffAt = t
                fx.add(Fx("chaff", u.x, u.y, t))
            }
        } else {
            var r = steer(u)
            u.inForm = u.state == "air" && inFormation(u)
            if (u.inForm) r = formSteer(u)
            want = r.hdg; spd = r.spd
        }
        if (u.defending) u.inForm = false
        if (u.state != "air") return // landed during steer
        if (u.low) spd *= LOW_SPEED
        move(u, want, spd, dt, if (u.defending) 1.3 else 1.0)
        if (u.salvo != null) fireSalvo(u)
        if (!u.defending && u.roe == "free" && def.role == "fighter") autoEngage(u)
    }

    private fun move(u: Ent, want: Double, spd: Double, dt: Double, turnMul: Double = 1.0) {
        val maxTurn = (u as Unit).d.turn * DEG * dt * turnMul
        u.hdg = angNorm(u.hdg + clamp(angNorm(want - u.hdg), -maxTurn, maxTurn))
        u.spd += clamp(spd - u.spd, -0.01 * dt, 0.01 * dt)
        u.x += sin(u.hdg) * u.spd * dt
        u.y -= cos(u.hdg) * u.spd * dt
    }

    private fun steer(u: Unit): Steer {
        val def = u.d
        val o = u.order
        val tgt = u.engage ?: (if (o?.type == "attack") o.target else null)
        if (tgt != null) return steerAttack(u, tgt)
        if (o == null) return orbitAt(u, u.x, u.y, 15.0)
        if (o.type == "orbit") return orbitAt(u, o.x, o.y, o.r)
        if (o.type == "rtb") {
            var b = base(o.base)
            if (b == null || !baseOpen(b)) {
                b = nearestOpenBase(u.side, u, u.type)
                if (b == null) return orbitAt(u, u.x, u.y, 15.0)
                if (o.base != null && o.base != b.name) log(u.side, "${u.callsign} diverting to ${b.name}", "warn")
                o.base = b.name
            }
            if (dist(u, b) < 6) { land(u, b); return Steer(u.hdg, 0.0) }
            // After a strike, race for home at full speed while still over enemy territory.
            val run = u.egress && Geo.sideOf(u.x, u.y) != u.side
            return Steer(bearing(u, b), if (run) def.dash else def.speed)
        }
        if (o.type == "strike") return steerStrike(u, o)
        if (o.type == "feint") return steerFeint(u, o)
        return Steer(u.hdg, def.speed)
    }

    fun orbitAt(u: Unit, px: Double, py: Double, r0: Double): Steer {
        val r = if (r0 != 0.0) r0 else 15.0
        val p = Pt(px, py)
        val d = dist(u, p)
        val h = if (d > r * 1.5) bearing(u, p) else bearing(p, u) + 90 * DEG + clamp((d - r) / r, -1.0, 1.0) * 40 * DEG
        return Steer(leash(u, h), u.d.speed)
    }

    /** AI fighters stay inside their own airspace (no deliberate border crossing). */
    private fun leash(u: Unit, h: Double): Double {
        if (!u.ai || u.d.role == "drone") return h
        // Strike flights and their escorts are allowed into enemy airspace. So are the commander's
        // fighter sweeps and raids (u.offensive). A chase you ordered follows its target wherever it goes.
        val ms = u.mission
        val chase = ms != null && ms.kind == "chase" && !ms.auto && u.side == player
        val ot = u.order?.type
        if (chase || u.offensive || (u.lead?.offensive == true) || ot == "strike" || ot == "feint" || ms?.kind == "escort") return h
        fun ok(hh: Double): Boolean {
            val x = u.x + sin(hh) * 25; val y = u.y - cos(hh) * 25
            return Geo.sideOf(x, y) == u.side && Geo.frontierDist(x, y) > 10
        }
        if (ok(h)) return h
        for (off in intArrayOf(30, 60, 90, 120, 150)) {
            for (s in intArrayOf(1, -1)) {
                val hh = h + s * off * DEG
                if (ok(hh)) return hh
            }
        }
        return h + Math.PI
    }

    private fun steerAttack(u: Unit, tg: Ent): Steer {
        val def = u.d
        fun done() {
            u.engage = null
            if (u.order?.type == "attack") {
                val a = u.anchor
                u.order = Order.orbit(a?.x ?: u.x, a?.y ?: u.y, 15.0)
            }
        }
        val tr = trackFor(u.side, tg)
        if (!isAlive(tg) || tr == null) {
            done()
            val a = u.anchor
            return if (a != null) orbitAt(u, a.x, a.y, a.r) else orbitAt(u, u.x, u.y, 15.0)
        }
        if (!hasAAM(u)) {
            log(u.side, "${u.callsign} Winchester (no missiles left)", "info")
            done()
            if (u.ai || u.order?.type != "strike") orderRTB(u)
            return Steer(u.hdg, def.speed)
        }
        val tp = trackPos(tr)
        val brg = bearing(u, tp)
        val needSupport = missiles.any { it.shooter === u && it.target === tg && it.kind != "cruise" && !it.lock && !(it.w.tp && tg.seen[u.side]?.net == true) }
        if (needSupport) {
            // Crank: keep the target near the edge of the radar gimbal while slowing closure.
            val left = angNorm(brg - 50 * DEG); val right = angNorm(brg + 50 * DEG)
            val h = if (Math.abs(angNorm(left - u.hdg)) < Math.abs(angNorm(right - u.hdg))) left else right
            return Steer(leash(u, h), def.speed)
        }
        val want = if (tg.kind == "air") (tg as Unit).count else 1
        val anchor = u.anchor
        if (inFlightAt(u.side, tg) >= want && anchor != null) {
            // A chase keeps after its target while missiles (its own, a SAM's or another jet's) are on
            // the way: it shadows the target, ready to fire again if it breaks free.
            if (u.mission?.kind == "chase") {
                val d = dist(u, tp)
                return Steer(leash(u, brg), if (d > 30) def.dash else def.speed)
            }
            // A patrol's missiles are self-sufficient (data link / seeker): turn cold, back to station.
            return Steer(leash(u, bearing(u, anchor)), def.speed)
        }
        tryFire(u, tg, if (u.ai) AI_FIRE else 0.95)
        return Steer(leash(u, brg), def.dash)
    }

    private fun steerStrike(u: Unit, o: Order): Steer {
        val def = u.d
        while (o.list.isNotEmpty()) {
            val r = refFor(o.list[0].kind, o.list[0].id)
            if (r != null && isAlive(r) && r.hits < 3) break
            o.list.removeAt(0)
        }
        if (o.list.isEmpty()) { strikeDone(u); return Steer(u.hdg, def.speed) }
        val job = o.list[0]
        val ref = refFor(job.kind, job.id)!!
        val wk = strikeWeapon(u)
        if (wk == null) { o.list.clear(); strikeDone(u); return Steer(u.hdg, def.speed) }
        val w = Data.WEAPONS[wk]!!
        val d = dist(u, ref)
        val maxR = w.range * 0.85
        // Hold in safe airspace until an assigned escort has joined up (up to 7 minutes).
        if (!u.firing) {
            val esc = missions.find { m -> m.kind == "escort" && m.status != "done" && (m.escortUnit === u || (u.lead != null && m.escortUnit === u.lead) || (u.mission != null && m.escortOf == u.mission!!.id)) }
            val e = esc?.unit
            if (esc != null && !(e != null && e.state == "air" && dist(e, u) < 25)) {
                if (u.waitSince == 0.0) u.waitSince = t
                if (t - u.waitSince < 420) { u.holding = true; return holdSafe(u) }
            }
            u.holding = false
            u.holdPt = null
        }
        // Fly the planned route first.
        val wp = if (!u.firing) viaAim(u, o) else null
        if (wp != null) return Steer(bearing(u, wp), def.speed)
        // Press in as close as possible; fire early only if threatened. An escort flying with the
        // striker keeps it pressing in; a missile at it never does.
        val escorted = units.any { e ->
            val em = e.mission
            e.side == u.side && e.state == "air" && em != null && em.kind == "escort" && em.status != "done" &&
                (em.escortUnit === u || (u.lead != null && em.escortUnit === u.lead) || (u.mission != null && em.escortOf == u.mission!!.id)) && hasAAM(e) && dist(e, u) < 40
        }
        // Down low the striker is hidden: only a fighter close enough to spot it counts as a threat.
        val threatened = u.threats.isNotEmpty() || (!escorted && enemyFightersNear(u, if (u.low) VIS_KM + 10 else 55.0))
        if (u.firing || d <= Math.min(maxR, 25.0) || (threatened && d <= maxR)) {
            if (!u.firing) {
                u.firing = true
                log(u.side, "${u.callsign} ${if (threatened && d > 25) "under threat, firing early" else "in close, firing"}: ${d.fixed(0)} km from ${job.id}", "shot")
            }
            if (t - u.lastLaunch > 1.5) {
                launchCruise(u, wk, ref)
                u.lastLaunch = t
                job.n--
                if (job.n <= 0 || (u.wpn[wk] ?: 0) == 0) { o.list.removeAt(0); u.firing = false }
                if (o.list.isEmpty()) strikeDone(u)
            }
            return Steer(bearing(u, ref), def.speed)
        }
        // AI strike packages give up if they can't reach a launch point in time.
        if (u.ai && t - u.strikeSince > 2700) { log(u.side, "${u.callsign} strike aborted", "info"); orderRTB(u) }
        return Steer(leash(u, bearing(u, ref)), def.speed)
    }

    /** A diversion wants to be seen: radars on for the flight. */
    private fun feintRadar(u: Unit) {
        for (x in listOf(u) + units.filter { it.lead === u }) { x.radarMode = "on"; setRadar(x, true) }
    }

    /**
     * At weapons release the whole package goes loud: the striker's wingmen and its escorts climb
     * out of terrain hugging and switch their radars on, ready to fight their way home.
     */
    private fun packageLoud(u: Unit) {
        for (e in units.toList()) {
            if (e === u || e.side != u.side || e.kind != "air" || e.state != "air" || e.d.role != "fighter" || dist(e, u) > 60) continue
            val ms = e.mission
            val mate = e.lead === u || u.lead === e || (u.lead != null && e.lead === u.lead)
            val escort = ms != null && ms.kind == "escort" && ms.status != "done" && (ms.escortUnit === u || ms.escortUnit === u.lead || (u.mission != null && ms.escortOf == u.mission!!.id))
            if (!mate && !escort) continue
            if (e.radarMode == "low" || e.radarMode == "off") e.radarMode = "auto"
            setRadar(e, true)
        }
    }

    /**
     * Diversion: a bait that keeps the defenders busy. It flies at the fake target with radars on,
     * as if it were a strike, and holds 70 km short of it. When enemy fighters come within 40 km (or
     * a missile is on its way) it drags them back toward the border, then turns in again. It goes
     * home after 25 minutes or at bingo fuel.
     */
    private fun steerFeint(u: Unit, o: Order): Steer {
        val ref = refFor(o.list[0].kind, o.list[0].id)
        if (ref == null) { orderRTB(u); return Steer(u.hdg, u.d.speed) }
        if (u.feintSince == 0.0) u.feintSince = t
        if (t - u.feintSince > 1500) {
            o.done = true
            if (u.side == player) log(u.side, "${u.callsign} diversion over, heading home", "info")
            orderRTB(u)
            return Steer(u.hdg, u.d.speed)
        }
        val home = Geo.sideOf(u.x, u.y) == u.side
        val threat = u.threats.isNotEmpty() || enemyFightersNear(u, 40.0)
        if (o.mode != "drag" && threat && !home) {
            o.mode = "drag"
            if (u.side == player && t - (o.toldAt ?: -99.0) > 60) {
                o.toldAt = t
                val drawn = units.count { e -> e.side != u.side && e.kind == "air" && e.state == "air" && (e.engage === u || (e.order?.type == "attack" && e.order!!.target === u)) }
                log(u.side, "${u.callsign} diversion dragging ${if (drawn > 0) drawn.toString() else "the"} enemy fighter${if (drawn == 1) "" else "s"} back toward the border", "good")
            }
        }
        if (o.mode == "drag") {
            if (home && Geo.frontierDist(u.x, u.y) > 25) o.mode = "in"
            else {
                val b = base(u.base) ?: nearestOpenBase(u.side, u, u.type)
                return Steer(if (b != null) bearing(u, b) else u.hdg + Math.PI, u.d.dash)
            }
        }
        val wp = viaAim(u, o)
        if (wp != null) return Steer(bearing(u, wp), u.d.speed)
        if (dist(u, ref) < 70) {
            val hold = o.hold ?: Circle(u.x, u.y, 12.0).also { o.hold = it }
            return orbitAt(u, hold.x, hold.y, hold.r)
        }
        return Steer(bearing(u, ref), u.d.speed)
    }

    /** Waiting for an escort: orbit inside our own airspace, well back from the border. */
    private fun holdSafe(u: Unit): Steer {
        if (Geo.sideOf(u.x, u.y) != u.side || Geo.frontierDist(u.x, u.y) < 20) return Steer(bearing(u, base(u.base)!!), u.d.speed)
        val hp = u.holdPt ?: Circle(u.x, u.y, 10.0).also { u.holdPt = it }
        return orbitAt(u, hp.x, hp.y, hp.r)
    }

    fun enemyFightersNear(u: XY, r: Double, side: String): Boolean {
        for (tr in tracks[side]!!.values) {
            val e = tr.ref as? Unit ?: continue
            if (e.kind != "air" || e.ghost || e.d.role != "fighter" || !isAlive(e)) continue
            if (dist(u, trackPos(tr)) < r) return true
        }
        return false
    }
    private fun enemyFightersNear(u: Unit, r: Double) = enemyFightersNear(u, r, u.side)

    private fun strikeDone(u: Unit) {
        u.egress = true
        log(u.side, "${u.callsign} weapons released, egressing", "info")
        if (u.ai || !hasAAM(u)) orderRTB(u)
        else u.order = Order.orbit(u.x + sin(u.hdg + Math.PI) * 40, u.y - cos(u.hdg + Math.PI) * 40, 15.0)
    }

    fun orderRTB(u: Unit, baseId: String? = null) {
        // Home base if it's open and within reach on the fuel left, otherwise the nearest open base.
        val home = base(u.base)
        val reach = home != null && u.kind == "air" && u.fuel > dist(u, home) / u.d.speed * 1.2 + 300
        val b = if (baseId != null) base(baseId) else (if (home != null && baseOpen(home) && reach) home else nearestOpenBase(u.side, u, u.type))
        u.order = Order.rtb(b?.name ?: u.base)
        u.engage = null
    }

    // ---------- drones ----------
    private fun updateDrone(u: Unit, dt: Double) {
        val def = u.d
        val o = u.order ?: Order.orbit(u.x, u.y, 15.0)
        u.fuel -= dt
        if (u.fuel <= 0) { u.state = "expended"; log(u.side, "${u.callsign}: drones out of fuel", "info"); return }
        var aim: XY? = null; var hit: Ent? = null
        if (o.type == "drone" && o.list.isNotEmpty()) {
            while (o.list.isNotEmpty() && !isAlive(refFor(o.list[0].kind, o.list[0].id))) o.list.removeAt(0)
            val wp = viaAim(u, o)
            if (wp != null) aim = wp
            else if (o.list.isNotEmpty()) { val r = refFor(o.list[0].kind, o.list[0].id); aim = r; hit = r }
        } else if (o.type == "sead" || (o.type == "orbit" && def.mission == "sead")) {
            // Anti-radiation seeker: home on the nearest enemy emitter within 45 km.
            var best: Unit? = null; var bd = 45.0
            for (e in units) {
                if (e.side == u.side || (e.kind != "sam" && e.kind != "radar") || !emitting(e)) continue
                val d = dist(u, e)
                if (d < bd) { bd = d; best = e }
            }
            if (best != null) { aim = best; hit = best }
        }
        val want = if (aim != null) bearing(u, aim) else (if (o.hasPoint) orbitAt(u, o.x, o.y, o.r) else orbitAt(u, u.x, u.y, 20.0)).hdg
        move(u, want, if (aim != null) def.dash else def.speed, dt)
        if (hit != null && dist(u, hit) < 1.5) droneImpact(u, hit, o)
    }

    private fun droneImpact(u: Unit, ref: Ent, o: Order) {
        val attackMode = o.type == "drone"
        val n = if (attackMode) Math.max(1, Math.ceil(u.count.toDouble() / Math.max(1, o.list.size)).toInt()) else 1
        var hits = 0
        // Small warheads: one swarm can damage a target (at most 2 of its 3 hits), but finishing
        // it off takes a proper strike.
        val cap = if (u.d.mission == "attack") 2 else Int.MAX_VALUE
        var i = 0
        while (i < n && u.count > 0) {
            i++
            u.count--
            if (rand() > 0.6 || hits >= cap) continue
            hits++
            applyHit(ref, u.side, true)
        }
        fx.add(Fx("impact", ref.x, ref.y, t))
        if (hits > 0) {
            log("ALL", "DRONE STRIKE: $hits hit${if (hits > 1) "s" else ""} on ${targetId(ref)}", "impact")
            addEsc(1.0, "drone strike")
        }
        if (attackMode) o.list.removeAt(0)
        if (u.count <= 0) u.state = "expended"
        else if (attackMode && o.list.isEmpty()) u.order = Order.orbit(u.x, u.y, 15.0)
    }

    /** Guns and short-range defences around bases and SAM sites shoot at drones nearby. */
    private fun pointDefence(dt: Double) {
        for (u in units) {
            if (u.state != "air" || !isDrone(u)) continue
            val near = bases.any { it.side != u.side && dist(it, u) < 6 } ||
                units.any { it.kind == "sam" && !it.dead && it.side != u.side && dist(it, u) < 6 }
            if (near && rand() < 0.05 * dt) {
                u.count--
                score[ENEMY[u.side]]!!.droneKills++
                fx.add(Fx("miss", u.x, u.y, t))
                if (u.count <= 0) u.state = "dead"
            }
        }
    }

    // ---------- electronic warfare ----------
    private fun ewTimer(side: String, kind: String) = when (kind) { "jam" -> ew[side]!!.jam; "barrage" -> ew[side]!!.barrage; else -> ew[side]!!.decoy }
    private fun ewKind(kind: String) = when (kind) { "jam" -> EW.jam; "barrage" -> EW.barrage; else -> EW.decoy }
    fun ewReady(side: String, kind: String) = ewTimer(side, kind).ready <= t
    fun cmdEW(side: String, kind: String, x: Double, y: Double): Boolean {
        val e = ewTimer(side, kind)
        if (e.ready > t) return false
        e.until = t + ewKind(kind).dur
        e.ready = t + ewKind(kind).cd
        if (kind == "barrage") { e.x = x; e.y = y }
        if (kind == "decoy") spawnDecoys(side, x, y)
        log(side, when (kind) {
            "jam" -> "LINK JAM: enemy data link degraded for 75 s"
            "barrage" -> "BARRAGE JAM: enemy radars degraded in the area for 60 s"
            else -> "DECOYS launched: fake fighter tracks heading for the target area"
        }, "shot")
        if (kind == "jam") log(ENEMY[side]!!, "Data link jammed! Fighters must use their own radar to shoot", "warn")
        return true
    }
    private fun spawnDecoys(side: String, x: Double, y: Double) {
        val type = if (side == "IN") "RAFALE" else "J10C"
        val p = Pt(x, y)
        val fr = units.filter { it.side == side && it.state == "air" && manned(it) }
        val from: XY = if (fr.isNotEmpty()) fr.reduce { a, b -> if (dist(a, p) < dist(b, p)) a else b }
        else nearestOpenBase(side, p) ?: bases.first { it.side == side }
        for (i in 0 until 2) {
            val u = newFlight(side, type, 2, bases.first { it.side == side }.name, "DECOY-${i + 1}")
            u.ghost = true; u.ai = true; u.radarOn = false
            u.x = from.x + (if (i > 0) 6 else -6); u.y = from.y + (if (i > 0) 4 else -4)
            u.order = Order.orbit(x + (if (i > 0) 15 else -15), y, 15.0)
            u.hdg = bearing(u, Pt(u.order!!.x, u.order!!.y))
            u.fuel = EW.decoy.dur
        }
    }
    private fun updateGhost(u: Unit, dt: Double) {
        u.fuel -= dt
        if (u.fuel <= 0) { u.state = "expended"; return }
        val o = u.order!!
        val r = orbitAt(u, o.x, o.y, o.r)
        move(u, r.hdg, u.d.speed, dt)
    }

    // ---------- shooting ----------
    private fun autoEngage(u: Unit) {
        if (t - u.lastShot < 3) return
        // Radar off / terrain hugging: hold fire except on the jet's own mission target.
        if ((u.radarMode == "off" || u.radarMode == "low") && !u.exposed) return
        val enemies = targetsOf(ENEMY[u.side]!!)
        var best: Ent? = null; var bestScore = -1.0
        for (tg in enemies) {
            if (isDrone(tg)) continue               // drone swarms are a job for SAMs, not fighters
            if (!fireQuality(u, tg)) continue
            val cheap = tg.kind == "cruise"
            if (cheap && u.order?.type == "strike") continue
            val want = if (tg.kind == "air") (tg as Unit).count else 1
            if (inFlightAt(u.side, tg) >= Math.min(want, if (isDrone(tg)) 1 else want)) continue
            val k = chooseWeapon(u, tg, AI_FIRE)
            if (k == null || !canGuide(u, tg, k)) continue
            val off = Math.abs(angNorm(bearing(u, tg) - u.hdg))
            if (off > (if (Data.WEAPONS[k]!!.kind == "srm") 90 else 60) * DEG) continue
            val prio = if (isDrone(tg)) 0.3 else if (tg.kind == "cruise") 0.5 else if ((tg as Unit).d.role == "aew") 3.0 else if (strikeWeapon(tg) != null) 2.0 else 1.0
            val sc = prio * 1000 - dist(u, tg)
            if (sc > bestScore) { bestScore = sc; best = tg }
        }
        if (best != null) tryFire(u, best, AI_FIRE)
    }

    fun tryFire(u: Unit, tg: Ent, factor: Double): Boolean {
        if (t - u.lastShot < 3) return false
        if (u.kind == "air" && isDrone(tg)) return false
        if (!fireQuality(u, tg)) return false
        // Two missiles per enemy aircraft (it takes two hits to bring one down).
        val want = if (manned(tg)) 2 else 1
        if (inFlightAt(u.side, tg) >= want) return false
        val k = chooseWeapon(u, tg, factor) ?: return false
        // Firing means radar on (and the end of terrain hugging).
        if (u.kind == "air" && u.d.role == "fighter" && !u.radarOn) setRadar(u, true)
        if (!canGuide(u, tg, k)) return false
        val w = Data.WEAPONS[k]!!
        if (u.kind == "air") {
            val off = Math.abs(angNorm(bearing(u, tg) - u.hdg))
            if (off > (if (w.kind == "srm") 90 else 60) * DEG) return false
            u.wpn[k] = u.wpn[k]!! - 1
        } else u.ammo--
        u.lastShot = t
        launch(u, tg, k)
        if (want == 2) u.salvo = Salvo(tg, k, t + 1)
        return true
    }

    /** Second missile of a salvo, one second after the first. */
    private fun fireSalvo(u: Unit) {
        val s = u.salvo ?: return
        if (t < s.at) return
        u.salvo = null
        if (!isAlive(s.t)) return
        if (u.kind == "air") { val c = u.wpn[s.k] ?: 0; if (c == 0) return; u.wpn[s.k] = c - 1 } else { if (u.ammo <= 0) return; u.ammo-- }
        launch(u, s.t, s.k)
    }

    fun launch(u: Unit, tg: Ent, k: String) {
        val w = Data.WEAPONS[k]!!
        val m = Missile(nextId++, w.kind, u.side, k, w)
        m.x = u.x; m.y = u.y
        m.hdg = if (u.kind == "air") u.hdg else bearing(u, tg)
        m.spd = Math.max(0.1, if (u.spd != 0.0) u.spd else 0.1)
        m.target = tg; m.shooter = u
        m.est = Est(tg.x, tg.y, velX(tg), velY(tg))
        missiles.add(m)
        score[u.side]!!.shots++
        val tr = trackFor(u.side, tg)
        val tname = if (tg.kind == "air") "T${tr?.num ?: "?"} ${(tg as Unit).d.name}" else "cruise missile"
        val call = if (w.kind == "srm") "FOX-2" else if (w.kind == "sam") "BIRD AWAY" else "FOX-3"
        val how = if (w.kind == "aam" && !ownTrack(u, tg)) " (data-link cue, radar silent)" else ""
        if (!isDrone(tg)) log(u.side, "${u.callsign} $call ${w.name} at $tname, ${dist(u, tg).fixed(0)} km$how", "shot")
        // A radar-guided launch from an emitting shooter tripping the target's RWR.
        if (tg.kind == "air" && !isDrone(tg) && (u.kind == "sam" || (u.radarOn && ownTrack(u, tg)))) {
            m.warned = true
            warn(tg as Unit, m, "LAUNCH WARNING")
        }
    }

    private fun warn(tg: Unit, m: Missile, what: String) {
        if (tg.kind != "air" || tg.ghost || isDrone(tg)) return
        if (!tg.threats.contains(m)) tg.threats.add(m)
        log(tg.side, "${tg.callsign}: $what, ${m.w.name} ${dist(m, tg).fixed(0)} km", "warn", alert = true, ref = tg)
    }

    // ---------- missiles ----------
    private fun updateMissile(m: Missile, dt: Double) {
        val w = m.w
        val tg = m.target!!
        val alive = isAlive(tg)
        m.t += dt
        if (m.t < w.burn) m.spd = Math.min(w.vMax, m.spd + 0.3 * dt)
        else m.spd -= w.drag * m.spd * dt

        // Mid-course guidance: updates from the shooter, or the data link for tp missiles.
        var support = m.lock
        val lockable = m.kind == "srm" || m.kind == "cruise" || !isSilent(tg)
        if (!support && alive && lockable) {
            val s = m.shooter
            val shooterOk = isAlive(s) && ownTrack(s, tg) && (s.kind != "air" || Math.abs(angNorm(bearing(s, tg) - s.hdg)) <= GIMBAL)
            support = shooterOk || (w.tp && tg.seen[m.side]?.net == true)
        }
        if (support && alive) {
            m.est = Est(tg.x, tg.y, velX(tg), velY(tg))
        } else {
            m.est.x += m.est.vx * dt; m.est.y += m.est.vy * dt
        }
        m.supported = support

        if (!m.active && dist(m, m.est) <= w.seeker) {
            m.active = true
            if (w.kind == "aam" && isAlive(m.shooter) && !isDrone(tg)) log(m.side, "${m.shooter.callsign} ${w.name} PITBULL (seeker active)", "info")
        }
        // A notching target can break a radar seeker's lock.
        if (m.lock && alive && m.kind != "srm" && notching(tg, m) && rand() < 0.25 * dt) {
            m.lock = false
            log(tg.side, "${(tg as Unit).callsign} notched the ${w.name}: seeker lock broken", "good")
        }
        if (m.active && !m.lock && alive) trySeeker(m)

        // Lead pursuit toward the (true or estimated) target.
        val ax: Double; val ay: Double; val avx: Double; val avy: Double
        if (m.lock && alive) { ax = tg.x; ay = tg.y; avx = velX(tg); avy = velY(tg) }
        else { ax = m.est.x; ay = m.est.y; avx = m.est.vx; avy = m.est.vy }
        val d = hypot(ax - m.x, ay - m.y)
        val tgo = d / Math.max(0.2, m.spd)
        val ip = Pt(ax + avx * tgo * 0.8, ay + avy * tgo * 0.8)
        val want = bearing(m, ip)
        val rate = (if (w.kind == "srm") 50 else 30) * DEG * dt
        m.hdg = angNorm(m.hdg + clamp(angNorm(want - m.hdg), -rate, rate))

        val px = m.x; val py = m.y
        m.x += sin(m.hdg) * m.spd * dt
        m.y -= cos(m.hdg) * m.spd * dt
        m.trailT -= dt
        if (m.trailT <= 0) { m.trail.addLast(Pt(px, py)); if (m.trail.size > 24) m.trail.removeFirst(); m.trailT = 1.0 }

        if (m.lock && alive && dist(m, tg) <= m.spd * dt * 1.5 + 0.5) { resolve(m); return }
        if ((m.t > w.burn && m.spd < V_MIN) || m.t > w.tMax * 1.5) {
            m.dead = true
            m.end = "energy"
            if (alive && m.lock && tg.kind == "air" && !isDrone(tg)) log(tg.side, "${(tg as Unit).callsign} defeated a ${w.name} (out of energy)", "good")
            else if (alive && !isDrone(tg)) log(m.side, "${w.name} from ${m.shooter.callsign} lost its target", "info")
        }
    }

    private fun trySeeker(m: Missile) {
        val tg = m.target!!
        val d = dist(m, tg)
        val off = Math.abs(angNorm(bearing(m, tg) - m.hdg))
        if (m.kind != "srm" && notching(tg, m)) return
        if (m.kind != "srm" && isSilent(tg)) return
        if (d <= m.w.seeker * 1.3 && off < 40 * DEG) {
            m.lock = true
            if (tg.kind == "air" && !m.warned) warn(tg as Unit, m, "MISSILE WARNING (no prior launch warning!)")
            else if (tg.kind == "air" && !m.activeWarned) warn(tg as Unit, m, "MISSILE ACTIVE")
            m.warned = true
            m.activeWarned = true
        }
    }

    private fun resolve(m: Missile) {
        val tg = m.target!!
        val w = m.w
        m.dead = true
        val energy = clamp((m.spd - V_MIN) / (w.vMax * 0.5), 0.25, 1.0)
        var pk = w.pk * energy
        if (tg is Unit && tg.kind == "air" && !tg.ghost && !isDrone(tg)) {
            pk *= 1 - tg.d.ew * 0.4
            if (tg.defending) {
                pk *= if (tg.d.role == "aew") 0.9
                else if (w.kind != "srm" && notching(tg, m)) 0.45 // seeker loses the target in clutter
                else 0.95 - 0.3 * (1 - energy)                          // drag + chaff: works on a tired missile
            }
        } else if (tg.kind == "cruise") pk *= 0.6 * clamp(0.35 / (tg as Missile).w.speed, 0.3, 1.0)   // fast missiles are hard to hit
        else if (isDrone(tg)) pk *= 0.6
        m.pk = pk
        m.end = "miss"
        val hit = rand() < pk
        if (hit) {
            m.end = "hit"
            fx.add(Fx("kill", tg.x, tg.y, t, tg))
        } else fx.add(Fx("miss", tg.x, tg.y, t, tg))

        if (tg is Missile) {
            if (hit) {
                tg.dead = true
                score[m.side]!!.intercepts++
                log(m.side, "${tg.w.name} intercepted by ${w.name}", "kill")
                log(tg.side, "A ${tg.w.name} was shot down", "warn")
            }
            return
        }
        val tu = tg as Unit
        if (isDrone(tu)) {
            if (hit) { tu.count--; score[m.side]!!.droneKills++; if (tu.count <= 0) tu.state = "dead" }
            return
        }
        // One hit damages; a second hit on a damaged flight brings a jet down.
        if (hit && !tu.ghost) {
            tu.dmg += 1
            if (tu.dmg < 2) {
                val tr0 = trackFor(m.side, tu)
                log(m.side, "${w.name} from ${m.shooter.callsign}: HIT on T${tr0?.num ?: "?"} ${tu.d.name}, damaged", "kill")
                log(tu.side, "${tu.callsign} DAMAGED by a ${w.name}: one more hit will bring a jet down", "loss")
                return
            }
            tu.dmg = 0
        }
        // Fog of war: the shooter only gets a claim, not the truth.
        val r = rand()
        val sc = score[m.side]!!
        val tr = trackFor(m.side, tu)
        val tn = "T${tr?.num ?: "?"} ${tu.d.name}"
        if (if (hit) r < 0.8 else r < 0.1) {
            sc.claimed++
            if (!hit || tu.ghost) sc.falseClaims++
            log(m.side, "SPLASH: $tn (probable kill, ${w.name} from ${m.shooter.callsign})", "kill")
        } else if (if (hit) true else r < 0.3) {
            log(m.side, "${w.name} from ${m.shooter.callsign}: result UNCONFIRMED on $tn", "info")
        } else log(m.side, "${w.name} from ${m.shooter.callsign} missed $tn", "info")
        if (!hit) {
            if (!tu.ghost) log(tu.side, "${tu.callsign} evaded a ${w.name}", "good")
            return
        }
        if (tu.ghost) { tu.count--; if (tu.count <= 0) tu.state = "dead"; return }
        sc.kills++
        sc.killValue += tu.d.value
        addEsc(if (tu.d.role == "aew") 8.0 else 2.0, "aircraft shot down")
        lose(tu, 1, "shot down by ${w.name}")
    }

    fun lose(u: Unit, n: Int, why: String) {
        u.count -= n
        score[u.side]!!.lost += n
        log(u.side, "${u.callsign}: ${u.d.name} LOST, $why", "loss")
        if (u.count <= 0) { u.state = "dead"; u.threats = mutableListOf(); regroup(u) }
    }

    private fun launchCruise(u: Unit, k: String, ref: Ent) {
        val w = Data.WEAPONS[k]!!
        if (u.kind == "air") { setRadar(u, true); packageLoud(u) }   // weapons release needs the radar on
        u.wpn[k] = u.wpn[k]!! - 1
        val m = Missile(nextId++, "cruise", u.side, k, w)
        m.x = u.x; m.y = u.y
        m.hdg = bearing(u, ref); m.spd = w.speed
        m.tgt = ref; m.shooter = u
        m.launchDist = dist(u, ref)
        missiles.add(m)
        log(u.side, "${u.callsign} launched ${w.name} at ${targetId(ref)} (${dist(u, ref).fixed(0)} km)", "shot")
    }

    private fun updateCruise(m: Missile, dt: Double) {
        m.t += dt
        val ref = m.tgt!!
        val want = bearing(m, ref)
        m.hdg = angNorm(m.hdg + clamp(angNorm(want - m.hdg), -5 * DEG * dt, 5 * DEG * dt))
        val px = m.x; val py = m.y
        m.x += sin(m.hdg) * m.spd * dt
        m.y -= cos(m.hdg) * m.spd * dt
        m.trailT -= dt
        if (m.trailT <= 0) { m.trail.addLast(Pt(px, py)); if (m.trail.size > 24) m.trail.removeFirst(); m.trailT = if (m.spd > 0.5) 1.0 else 4.0 }
        // Short-range guns and missiles along the route: the longer a weapon flies over enemy ground,
        // the more likely it is shot down. Fast weapons are harder to hit.
        val groundRisk = (if (m.spd > 0.6) 0.0012 else 0.0025) * m.spd * dt
        if (Geo.sideOf(m.x, m.y) != m.side && rand() < groundRisk) {
            m.dead = true
            score[ENEMY[m.side]]!!.intercepts++
            fx.add(Fx("miss", m.x, m.y, t))
            log(ENEMY[m.side]!!, "Ground air defences shot down a ${m.w.name}", "good")
            log(m.side, "A ${m.w.name} was shot down by ground fire (launched ${m.launchDist.fixed(0)} km out)", "warn")
            return
        }
        if (dist(m, ref) < m.spd * dt + 0.3) {
            m.dead = true
            // Closer launches are more accurate.
            if (rand() < clamp(0.95 - m.launchDist * 0.0015, 0.55, 0.95)) {
                fx.add(Fx("impact", ref.x, ref.y, t))
                log("ALL", "IMPACT: ${m.w.name} hit ${targetId(ref)}", "impact")
                applyHit(ref, m.side, false)
                if (ref.kind == "site") addEsc(2.0, "strike")
            } else log(m.side, "${m.w.name} missed ${targetId(ref)} (launched ${m.launchDist.fixed(0)} km out)", "info")
        }
        if (m.t > m.w.range / m.w.speed * 1.3) m.dead = true
    }

    // ---------- SAMs ----------
    private fun updateSam(u: Unit) {
        if (u.salvo != null) fireSalvo(u)
        // An empty battery reloads (the loading bar on the map shows progress).
        if (u.ammo <= 0 && u.reloadAt == null && !u.dry) {
            if (u.reloads > 0) {
                u.reloads--
                u.reloadAt = t + SAM_RELOAD
                log(u.side, "${u.callsign} out of missiles: reloading, ready in ${(SAM_RELOAD / 60).toInt()} min (last spare magazine)", "warn")
            } else {
                u.dry = true
                log(u.side, "${u.callsign} out of missiles, no reloads left tonight", "loss")
            }
            log(ENEMY[u.side]!!, "${u.callsign} has stopped firing: probably out of missiles", "good")
        }
        val ra = u.reloadAt
        if (ra != null && t >= ra) {
            u.reloadAt = null; u.ammo = u.samDef!!.ammo
            log(u.side, "${u.callsign} reloaded: ${u.ammo} missiles ready", "good")
        }
        if (!u.radarOn || u.ammo <= 0 || t - u.lastShot < 4) return
        var best: Ent? = null; var bestScore = Double.NEGATIVE_INFINITY
        for (tg in targetsOf(ENEMY[u.side]!!)) {
            if (!fireQuality(u, tg)) continue
            if (chooseWeapon(u, tg, AI_FIRE) == null) continue
            val sc = (if (isDrone(tg)) 200 else if (tg.kind == "air") 1000 else 500) - dist(u, tg)
            if (sc > bestScore) { bestScore = sc; best = tg }
        }
        if (best != null) tryFire(u, best, AI_FIRE)
    }

    // ---------- AI (per flight) ----------
    private fun aiThink(u: Unit) {
        val def = u.d
        if (def.role == "aew") return
        if (!hasAAM(u) && strikeWeapon(u) == null && u.order != null && u.order!!.type != "rtb") { u.engage = null; orderRTB(u); return }
        val o = u.order
        val cap = u.cap
        if (o == null || o.type != "orbit" || cap == null) return
        if (u.engage != null && !isAlive(u.engage)) u.engage = null
        if (u.engage != null && dist(u, cap) > 140) u.engage = null
        // Radar off / low level: hold fire, don't go looking for a fight.
        val quiet = (u.radarMode == "off" || u.radarMode == "low") && !u.exposed
        if (u.engage == null && hasAAM(u) && !quiet) {
            val commit = Math.max(60.0, bestRange(u) * 1.3)
            // Patrols guarding an asset also go after cruise missiles.
            val guard = u.mission?.patrolOf != null
            val e = nearestEnemy(u, commit, !guard, true)
            if (e != null) { u.engage = e; u.anchor = Circle(cap.x, cap.y, cap.r) }
        }
    }

    fun nearestEnemy(u: Unit, range: Double, airOnly: Boolean = false, mannedOnly: Boolean = false): Ent? {
        var best: Ent? = null; var bd = range
        for (tr in tracks[u.side]!!.values) {
            val e = tr.ref
            if (!isAlive(e) || (airOnly && e.kind != "air")) continue
            if (e.kind != "air" && e.kind != "cruise") continue
            if (mannedOnly && isDrone(e)) continue
            val d = dist(u, trackPos(tr))
            if (d < bd) { bd = d; best = e }
        }
        return best
    }

    // ---------- escalation ----------
    fun addEsc(n: Double, @Suppress("UNUSED_PARAMETER") why: String) {
        val before = esc
        esc = Math.min(100.0, esc + n)
        for (mark in intArrayOf(50, 75, 90)) {
            if (before < mark && esc >= mark && !escMarks.contains(mark)) {
                escMarks.add(mark)
                log("ALL", "ESCALATION $mark%: ${if (mark == 50) "international pressure rising" else if (mark == 75) "leaders under pressure to stand down" else "crisis is close to spiralling out of control"}", "warn")
            }
        }
        if (player != null && before < 80 && esc >= 80) promptFor("escalation", null)
        if (esc >= 100) finish("ESCALATION 100%: the crisis spiralled out of control", true)
    }

    // ---------- decision cards (player only) ----------
    fun promptFor(kind: String, ref: Ent?): Boolean {
        if (player == null || over) return false
        val key = kind + (if (ref != null) ":" + ref.id else "")
        if ((promptCd[key] ?: 0.0) > t || (promptCd[kind] ?: 0.0) > t) return false
        if (prompts.any { !it.done }) return false
        if (kind == "aew" && t < 180) return false
        val cds = mapOf("guide" to 30.0, "bingo" to 60.0, "base" to 300.0, "aew" to 600.0, "escalation" to 99999.0)
        promptCd[key] = t + cds[kind]!!
        promptCd[kind] = t + 20
        fun p(text: String, options: List<PromptOption>, def: String): Boolean {
            prompts.add(Prompt(nextId++, kind, ref, text, options, def, t))
            return true
        }
        fun o(k: String, l: String, x: String) = PromptOption(k, l, x)
        val u = ref as? Unit
        return when (kind) {
            "guide" -> p("${u!!.callsign} is being targeted while its radar-guided missile is still in flight.",
                listOf(o("hold", "HOLD & GUIDE", "Keep cranking until the missile goes active. Risky."), o("drag", "BREAK", "Run. Your missile goes blind."), o("notch", "NOTCH", "Beam the threat. Your missile may lose support.")), "drag")
            "bingo" -> p("${u!!.callsign} is at BINGO fuel in the middle of a fight.",
                listOf(o("rtb", "RETURN TO BASE", "Disengage now and land."), o("press", "PRESS ON", "Keep fighting. If the tanks run dry, the jets are lost.")), "rtb")
            "base" -> p("Enemy weapons are inbound to ${(ref as Base).name}. Aircraft on the ground are exposed.",
                listOf(o("scramble", "SCRAMBLE READY JETS", "Get them airborne before impact (uses airborne slots)."), o("shelter", "STAY IN SHELTERS", "Keep slots free and hope the shelters hold.")), "shelter")
            "aew" -> p("An enemy AEW&C (${u!!.d.name}) is within reach. Shooting it down is a big blow, but it raises escalation sharply.",
                listOf(o("fire", "WEAPONS FREE ON AEW&C", "Clear your fighters and SAMs to engage it for 5 minutes."), o("hold", "HOLD FIRE", "Leave it alone for now.")), "hold")
            "escalation" -> p("Escalation has passed 80%. International pressure is intense and the next step could spiral out of control.",
                listOf(o("stand", "STAND DOWN STRIKES", "Recall strike flights for 5 min. Escalation -15."), o("press", "PRESS ON", "Keep going and risk losing everything at 100%.")), "stand")
            else -> false
        }
    }

    fun answer(p: Prompt, key: String) {
        if (p.done) return
        p.done = true
        p.choice = key
        val u = p.ref
        when (p.kind) {
            "guide" -> if (u is Unit && isAlive(u)) {
                if (key == "hold") { u.holdUntil = t + 25; u.manualDef = null; log(u.side, "${u.callsign} holding to guide its missile", "warn") }
                else cmdDefend(u, key)
            }
            "bingo" -> if (u is Unit && isAlive(u)) {
                if (key == "rtb") orderRTB(u)
                else log(u.side, "${u.callsign} pressing on below bingo fuel", "warn")
            }
            "base" -> if (key == "scramble") scrambleAll(u as Base, true)
            "aew" -> if (key == "fire") { aewClear[player!!] = t + 300; log(player, "Weapons free on enemy AEW&C for 5 minutes", "warn") }
            "escalation" -> if (key == "stand") standDown(player!!)
        }
    }

    fun standDown(side: String) {
        esc = Math.max(0.0, esc - 15)
        for (u in units.toList()) {
            val ot = u.order?.type
            if (u.side == side && u.state == "air" && (ot == "strike" || ot == "drone")) orderRTB(u)
        }
        log("ALL", "${if (side == "IN") "India" else "Pakistan"} stands down strike operations. Escalation eases.", "good")
    }

    /** Enemy weapons/drones heading for one of our bases: AI bases scramble. */
    private fun checkBaseThreats() {
        for (side in SIDES) {
            for (tr in tracks[side]!!.values.toList()) {
                val e = tr.ref
                var ref: Ent? = null
                if (e is Missile && e.kind == "cruise" && !e.dead) ref = e.tgt
                else if (e is Unit && isDrone(e) && e.state == "air" && e.order?.type == "drone" && e.order!!.list.isNotEmpty()) ref = refFor(e.order!!.list[0].kind, e.order!!.list[0].id)
                if (ref == null || ref !is Base || ref.side != side || dist(e, ref) > 150) continue
                val ready = ref.pools.values.sumOf { it.ready }
                if (ready == 0) continue
                // The player's bases defend themselves through autoDefend(); AI bases scramble.
                if (side != player) for (c in commanders) if (c.side == side) c.baseThreat(ref)
            }
        }
    }

    /** Launch every ready jet at a base, in flights of up to four. */
    fun scrambleAll(b: Base, away: Boolean): Int {
        var launched = 0
        for (ty in b.pools.keys.toList()) {
            if (Data.AIRCRAFT[ty]!!.role == "drone") continue
            while (b.pools[ty]!!.ready > 0) {
                val order = if (away) Order.orbit(b.x + (rand() - 0.5) * 60, b.y + (rand() - 0.5) * 60, 12.0) else null
                val u = launchFlight(b.name, ty, Math.min(4, b.pools[ty]!!.ready), order)
                if (u is String) return launched
                launched += (u as Unit).count
            }
        }
        return launched
    }

    // ---------- player commands ----------
    fun cmdMove(u: Unit, x: Double, y: Double) {
        detach(u)
        if (u.mission?.at != null) u.mission!!.at = Pt(x, y)
        if (isDrone(u)) { u.order = Order(if (u.d.mission == "sead") "sead" else "orbit").also { it.x = x; it.y = y; it.r = 20.0 }; return }
        u.order = Order.orbit(x, y, if (u.d.role == "aew") 25.0 else 15.0)
        u.engage = null; u.anchor = null
        if (u.ai) u.cap = Circle(x, y, 15.0)
    }
    fun cmdAttack(u: Unit, tg: Ent) {
        detach(u)
        val cap = u.cap
        if (u.ai && cap != null && u.order?.type == "orbit") {
            // A patrol keeps its station: engage, then return to it.
            u.engage = tg
            u.anchor = Circle(cap.x, cap.y, cap.r)
            return
        }
        u.anchor = Circle(u.x, u.y, 15.0)
        u.order = Order("attack").also { it.target = tg }
        u.engage = null
    }
    fun cmdStrike(u: Unit, ref: Ent): Boolean {
        detach(u)
        val id = targetId(ref)
        if (isDrone(u)) { u.order = Order("drone").also { it.list = mutableListOf(Job(ref.kind, id)) }; return true }
        val wk = strikeWeapon(u) ?: return false
        u.order = Order("strike").also { it.list = mutableListOf(Job(ref.kind, id, Math.min(2, u.wpn[wk]!!))) }
        u.strikeSince = t
        u.engage = null
        return true
    }
    fun cmdRTB(u: Unit) { detach(u); orderRTB(u) }
    /** Manual defence: "drag" (BREAK), "notch", or null to hand back to automatic. */
    fun cmdDefend(u: Unit, mode: String?) {
        u.manualDef = if (mode != null) ManualDef(mode, t + 45) else null
        u.holdUntil = 0.0
    }
    fun cmdSamRadar(u: Unit, on: Boolean) { u.radarOn = on }
    /**
     * Player radar mode for a fighter: "auto", "on", "off" or "low" (terrain hugging). Returns an
     * error message if the jet is exposed (it can only stay radar-on until it lands).
     */
    fun cmdRadarMode(u: Unit, mode: String): String? {
        if (u.d.role != "fighter") return "Only fighters can change radar mode"
        if (u.exposed && mode != "on" && mode != "auto") return "${u.callsign} is exposed: radar stays on until it lands"
        u.radarMode = mode
        if (mode == "on") setRadar(u, true)
        else if (mode == "off" || mode == "low") { setRadar(u, false); u.low = mode == "low" }
        return null
    }

    // ---------- missions: the player plans, the AI flies ----------
    /**
     * kind: cap | aew | strike | escort | sead | attack | chase | feint. A CAP or AEW&C mission keeps
     * its station manned: when a flight heads home, a relief flight launches from the base.
     */
    fun createMission(m: MissionSpec): Mission {
        val pre = if (m.auto) "DEFEND" else mapOf("cap" to "CAP", "aew" to "AEW", "strike" to "STRIKE", "escort" to "ESCORT", "sead" to "SEAD", "attack" to "ATTACK", "chase" to "CHASE", "feint" to "DIVERSION")[m.kind]!!
        missionNo[pre] = (missionNo[pre] ?: 0) + 1
        val ms = Mission(nextId++, "$pre-${missionNo[pre]}", m.kind, m.side)
        ms.launchAt = t
        ms.base = m.base; ms.type = m.type; ms.n = m.n; ms.preset = m.preset; ms.target = m.target; ms.prey = m.prey
        ms.escortUnit = m.escortUnit; ms.escortOf = m.escortOf; ms.auto = m.auto; ms.at = m.at; ms.patrolOf = m.patrolOf
        ms.via = m.via; ms.tot = m.tot; ms.unit = m.unit
        if (m.repeat != null) ms.repeat = m.repeat
        val tot = ms.tot
        if (ms.kind == "strike" && tot != null && tot != 0.0) ms.launchAt = strikeLaunchTime(ms)
        missions.add(ms)
        ms.unit?.let { bindMission(ms, it) }
        tickMissions()
        if (ms.side == player && !ms.auto) {
            val whenTxt = if (ms.launchAt > t + 5) ", takes off at ${clock(ms.launchAt).substring(0, 4)}Z" else ""
            log(ms.side, "${ms.name} planned: ${ms.n} ${Data.AIRCRAFT[ms.type]!!.name} from ${ms.base}$whenTxt", "info")
        }
        return ms
    }

    fun missionWeapon(type: String, preset: String?): String? {
        var best: String? = null
        for (k in (Data.PRESETS[type]!![preset] ?: emptyMap()).keys) if (Data.WEAPONS[k]!!.kind == "cruise" && (best == null || Data.WEAPONS[k]!!.range > Data.WEAPONS[best]!!.range)) best = k
        return best
    }

    /** When a strike must take off so its weapons arrive at the time-on-target. */
    private fun strikeLaunchTime(ms: Mission): Double {
        val b = base(ms.base); val ref = ms.target?.let { refFor(it.kind, it.id) }
        val wk = missionWeapon(ms.type, ms.preset)
        if (b == null || ref == null || wk == null) return t
        val w = Data.WEAPONS[wk]!!
        val d = dist(b, ref); val launchDist = Math.min(d, w.range * 0.85)
        return ms.tot!! - (d - launchDist) / Data.AIRCRAFT[ms.type]!!.speed - launchDist / w.speed - 30
    }
    fun missionArrival(ms: Mission): Double {
        val b = base(ms.base); val ref = ms.target?.let { refFor(it.kind, it.id) }
        val wk = missionWeapon(ms.type, ms.preset)
        if (b == null || ref == null || wk == null) return t
        val w = Data.WEAPONS[wk]!!
        val d = dist(b, ref); val launchDist = Math.min(d, w.range * 0.85)
        return Math.max(t, ms.launchAt) + (d - launchDist) / Data.AIRCRAFT[ms.type]!!.speed + launchDist / w.speed + 30
    }

    /** Give an airborne flight a new mission (it keeps its current weapons). */
    fun assignMission(u: Unit, m: MissionSpec): Mission {
        detach(u)
        val old = u.mission
        if (old != null && old.unit === u && old.status != "done") old.status = "done"
        return createMission(MissionSpec(m.kind, u.side, u.base, u.type!!, u.count, m.preset, m.target, m.prey, m.escortUnit,
            m.escortOf, m.auto, m.at, m.patrolOf, m.via, m.tot, u, m.repeat))
    }

    private fun bindMission(ms: Mission, u: Unit) {
        u.mission = ms
        u.ai = true
        u.engage = null
        u.order = missionOrder(ms)
        if (ms.kind == "chase") u.anchor = Circle(u.x, u.y, 15.0)
        if (u.order!!.type == "strike" || u.order!!.type == "drone") u.strikeSince = t
        ms.flights = Math.max(1, ms.flights)
        if (ms.kind == "feint") feintRadar(u)
        ms.status = "airborne"
        if (ms.kind == "escort") steerEscort(ms, u)
    }

    private fun missionOrder(ms: Mission): Order {
        val o = missionOrder0(ms)
        val via = ms.via
        if (via != null && via.isNotEmpty() && (o.type == "strike" || o.type == "drone" || o.type == "feint")) o.via = via.map { Pt(it.x, it.y) }.toMutableList()
        return o
    }
    private fun cruiseCount(w: Map<String, Int>) = w.entries.filter { Data.WEAPONS[it.key]!!.kind == "cruise" }.sumOf { it.value }
    private fun missionOrder0(ms: Mission): Order {
        val tg = ms.target
        if (ms.kind == "feint") return Order("feint").also { it.list = mutableListOf(Job(tg!!.kind, tg.id)) }
        if (ms.kind == "chase") return Order("attack").also { it.target = ms.prey }
        val u = ms.unit
        if (ms.kind == "strike" && u != null && u.state == "air") return Order("strike").also { it.list = mutableListOf(Job(tg!!.kind, tg.id, cruiseCount(u.wpn))) }
        if (ms.kind == "cap" || ms.kind == "aew") return Order.orbit(ms.at!!.x, ms.at!!.y, if (ms.kind == "aew") 25.0 else 15.0)
        if (ms.kind == "sead") return Order("sead").also { it.x = ms.at!!.x; it.y = ms.at!!.y; it.r = 20.0 }
        if (ms.kind == "attack") return Order("drone").also { it.list = mutableListOf(Job(tg!!.kind, tg.id)) }
        if (ms.kind == "strike") {
            val per = Data.PRESETS[ms.type]!![ms.preset] ?: emptyMap()
            return Order("strike").also { it.list = mutableListOf(Job(tg!!.kind, tg.id, cruiseCount(per) * ms.n)) }
        }
        val b = base(ms.base)!!
        return Order.orbit(b.x, b.y, 12.0)
    }

    private fun tickMissions() {
        var i = 0
        while (i < missions.size) {
            val ms = missions[i++]
            if (ms.status == "done") continue
            val u = ms.unit
            val alive = u != null && u.state == "air"
            if (alive) {
                val o = u!!.order
                val home = o?.type == "rtb"
                ms.status = if (home) "returning" else when (ms.kind) {
                    "feint" -> if (o?.mode == "drag") "dragging them back" else "diverting"
                    "strike" -> if (o?.type == "strike") "inbound" else "egress"
                    "escort" -> "escorting"
                    "chase" -> "chasing"
                    else -> "on station"
                }
                if (ms.kind == "escort" && !home) steerEscort(ms, u)
                if (ms.kind == "chase" && !home) checkChase(ms, u)
                if (ms.patrolOf != null && !home) checkPatrol(ms, u)
                if (!home || !ms.repeat) continue
            }
            val once = !ms.repeat
            if (once && ms.flights > 0) { if (!alive) ms.status = "done"; continue }
            if (t < ms.launchAt) { ms.status = "scheduled"; continue }
            if (ms.kind == "escort" && ms.escortUnit == null) {
                val s = missions.find { it.id == ms.escortOf }
                if (s == null || s.status == "done") { ms.status = "done"; continue }
                if (!(s.unit != null && s.unit!!.state == "air")) { ms.status = "waiting for strike"; continue }
            }
            if (ms.kind == "chase" && !isAlive(ms.prey)) { ms.status = "done"; continue }
            val r = launchFlight(ms.base, ms.type, ms.n, missionOrder(ms), ms.preset, true)
            if (r is String) { ms.status = "waiting: " + r.lowercase(); continue }
            r as Unit
            if (alive) u!!.mission = null                   // the old flight just flies home
            r.mission = ms
            for (w in r.wing) { w.mission = ms; if (ms.kind == "chase") w.anchor = Circle(w.x, w.y, 15.0) }
            if (ms.kind == "feint") feintRadar(r)
            ms.unit = r
            ms.flights++
            ms.status = "launching"
            if (r.order!!.type == "strike" || r.order!!.type == "drone") r.strikeSince = t
            if (ms.kind == "chase") { r.anchor = Circle(r.x, r.y, 15.0); r.hdg = bearing(r, ms.prey!!) }
        }
        autoDefend()
        if (cfg.autoPatrol) for (s in SIDES) autoPatrol(s)
    }

    /**
     * Rule: when an enemy strike jet comes close to one of our ground assets (site, base, radar or
     * SAM), the closest fighter (airborne or ready on the ground) patrols over it.
     */
    private fun autoPatrol(side: String) {
        val assets: List<Ent> = bases.filter { it.side == side } + sites.filter { it.side == side && it.hits < 3 } +
            units.filter { (it.kind == "sam" || it.kind == "radar") && it.side == side && !it.dead }
        val guarded = missions.filter { it.patrolOf != null && it.status != "done" }.map { it.patrolOf }.toMutableSet()
        for (tr in tracks[side]!!.values.toList()) {
            val e = tr.ref as? Unit ?: continue
            if (e.kind != "air" || !isAlive(e) || e.order?.type != "strike") continue
            val p = trackPos(tr)
            var asset: Ent? = null; var bd = 120.0
            for (a in assets) { val d = dist(a, p); if (d < bd) { bd = d; asset = a } }
            if (asset == null || guarded.contains(asset) || (patrolCd[targetId(asset)] ?: 0.0) > t) continue
            // Closest fighter: free airborne fighters, or ready jets on the ground (take-off counts as 40 km).
            var bestUnit: Unit? = null; var bestBase: String? = null; var bestType = ""; var bestN = 0; var bestD = Double.POSITIVE_INFINITY
            for (u in units) {
                if (u.side != side || u.state != "air" || u.kind != "air" || u.ghost || u.d.role != "fighter" || !hasAAM(u)) continue
                // Only idle patrols can be re-tasked; never pull a jet off an intercept or escort.
                val um = u.mission
                if (um != null && um.status != "done" && (um.kind != "cap" || um.patrolOf != null)) continue
                if (u.engage != null || (u.order != null && u.order!!.type != "orbit")) continue
                val d = dist(u, asset)
                if (d < bestD) { bestD = d; bestUnit = u; bestBase = null }
            }
            if (airborne(side) < Data.AIR_CAP[side]!!) {
                for (b in bases) {
                    if (b.side != side || !baseOpen(b)) continue
                    for ((ty, pool) in b.pools) {
                        if (Data.AIRCRAFT[ty]!!.role != "fighter" || Data.PRESETS[ty]!!["AIR"] == null || pool.ready < 1) continue
                        val d = dist(b, asset) + 40
                        if (d < bestD) { bestD = d; bestUnit = null; bestBase = b.name; bestType = ty; bestN = Math.min(2, pool.ready) }
                    }
                }
            }
            if (bestUnit == null && bestBase == null) continue
            patrolCd[targetId(asset)] = t + 180
            val spec = MissionSpec("cap", side, bestBase ?: "", bestType, bestN, "AIR", auto = true, patrolOf = asset, at = Pt(asset.x, asset.y), repeat = false)
            val ms = if (bestUnit != null) assignMission(bestUnit, spec) else createMission(spec)
            val mu = ms.unit
            if (mu != null && mu.order?.type == "orbit") mu.order!!.r = 20.0
            guarded.add(asset)
            if (side == player && mu != null) log(side, "${mu.callsign} patrolling over ${targetId(asset)}: enemy strike jet ${bd.fixed(0)} km away", "warn")
        }
    }

    /** A patrol over a threatened asset ends once no enemy strike jet has been near it for 2 minutes. */
    private fun checkPatrol(ms: Mission, u: Unit) {
        val a = ms.patrolOf!!
        var near = false
        for (tr in tracks[ms.side]!!.values) {
            val e = tr.ref as? Unit ?: continue
            if (e.kind == "air" && isAlive(e) && e.order?.type == "strike" && dist(trackPos(tr), a) < 150) { near = true; break }
        }
        if (near) { ms.quietSince = 0.0; return }
        if (ms.quietSince == 0.0) ms.quietSince = t
        if (t - ms.quietSince > 120) { ms.status = "done"; orderRTB(u) }
    }

    /** A chase ends when the target is gone, lost, or (for automatic intercepts) has fled home. */
    private fun checkChase(ms: Mission, u: Unit) {
        val e = ms.prey!!
        val own = ms.side == player
        fun end(why: String) {
            ms.status = "done"
            orderRTB(u)
            if (own) log(ms.side, "${ms.name}: $why, ${u.callsign} returning to base", if (why.contains("turned")) "good" else "info")
        }
        if (!isAlive(e)) {
            val st = (e as? Unit)?.state
            return end(if (e.kind == "air" && st == "landed") "target landed safely at its base" else if (e.kind == "air" && st != "air") "target destroyed" else "target no longer on radar")
        }
        if (trackFor(ms.side, e) == null) return end("lost contact with the target")
        // "Chased away": our jets got close while the target was near (or over) our side, then it
        // retreated deep into its own airspace.
        if (e.kind == "air") {
            val home = Geo.sideOf(e.x, e.y) == e.side
            val fd = Geo.frontierDist(e.x, e.y)
            if ((!home || fd < 30) && dist(u, e) < 100) ms.wasClose = true
            // Your own chases follow the target until it is shot down or lands; automatic scrambles
            // and the AI's interceptors stop once it has fled into its own airspace.
            val followHome = !ms.auto && ms.side == player
            if (!followHome && ms.wasClose && home && fd > 50 && dist(u, e) > 60) return end("target turned back into its own airspace")
        }
        if (!hasAAM(u)) end("out of missiles")
    }

    /** Ready fighters at the player's bases scramble on their own when enemies come close. */
    private fun autoDefend() {
        val side = player
        if (side == null || night == null || defending) return
        defending = true
        val chased = missions.filter { it.side == side && it.kind == "chase" && it.status != "done" }.map { it.prey }.toMutableSet()
        for (b in bases) {
            if (b.side != side || !baseOpen(b) || (defendCd[b.name] ?: 0.0) > t) continue
            val pool = b.pools.entries.find { (ty, p) -> Data.AIRCRAFT[ty]!!.role == "fighter" && Data.PRESETS[ty]!!["AIR"] != null && p.ready > 0 } ?: continue
            var threat: Ent? = null; var bd = 110.0
            for (tr in tracks[side]!!.values) {
                val e = tr.ref
                if (chased.contains(e) || !isAlive(e) || (e is Unit && e.ghost)) continue
                if (e.kind != "cruise" && e.kind != "air") continue
                if (e is Unit && e.kind == "air" && (e.d.role == "aew" || isDrone(e))) continue   // fighters can't stop drones
                val p = trackPos(tr)
                if (Geo.sideOf(p.x, p.y) != side && Geo.frontierDist(p.x, p.y) > 25) continue
                val d = dist(b, p)
                if (d < bd) { bd = d; threat = e }
            }
            if (threat == null) continue
            defendCd[b.name] = t + 90
            val ms = createMission(MissionSpec("chase", side, b.name, pool.key, Math.min(2, pool.value.ready), "AIR", prey = threat, auto = true))
            chased.add(threat)
            val mu = ms.unit
            if (mu != null) {
                val tr = trackFor(side, threat)
                log(side, "${b.name} scrambles ${mu.callsign} to intercept T${tr?.num ?: "?"} (${if (threat is Missile) threat.w.name else (threat as Unit).d.name})", "warn")
            }
        }
        defending = false
    }

    /** Escorts hold a point 20 km ahead of their strike flight, toward its target. */
    private fun steerEscort(ms: Mission, u: Unit) {
        val s = if (ms.escortUnit != null) null else missions.find { it.id == ms.escortOf }
        val st = ms.escortUnit ?: s?.unit
        if (st == null || st.state != "air" || st.order?.type == "rtb") {
            if (u.order?.type != "rtb") { orderRTB(u); u.egress = st?.egress == true }
            return
        }
        val so = st.order
        val job = so?.list?.firstOrNull()
        val ref: XY = so?.via?.firstOrNull() ?: job?.let { refFor(it.kind, it.id) } ?: s?.target?.let { refFor(it.kind, it.id) } ?: st
        val h = bearing(st, ref)
        val px = st.x + sin(h) * 20; val py = st.y - cos(h) * 20
        u.order = Order.orbit(px, py, 10.0)
        u.cap = Circle(px, py, 10.0)
    }

    fun cancelMission(ms: Mission) {
        ms.repeat = false
        ms.status = "done"
        val u = ms.unit
        if (u != null && u.state == "air") orderRTB(u)
        log(ms.side, "${ms.name} cancelled", "info")
    }

    // ---------- replay ----------
    private fun record() {
        val u = ArrayList<FrameUnit>()
        for (x in units) {
            if (x.kind == "air") { if (x.state == "air") u.add(FrameUnit(x.id, x.side, x.type!!, jsRound(x.x), jsRound(x.y), JsMath.round(x.hdg * 100) / 100, x.count, x.radarOn, x.ghost, x.callsign)) }
            else u.add(FrameUnit(x.id, x.side, x.kind, jsRound(x.x), jsRound(x.y), 0.0, if (x.dead) 0 else 1, x.radarOn, false, x.callsign))
        }
        val m = missiles.map { FrameMissile(JsMath.round(it.x * 10) / 10, JsMath.round(it.y * 10) / 10, it.side, it.kind == "cruise") }
        val f = fx.filter { it.t > t - 3 && (it.kind == "kill" || it.kind == "impact") }.map { FrameFx(it.kind, jsRound(it.x), jsRound(it.y)) }
        replay.add(Frame(t, u, m, f, bases.map { jsRound(it.damage) }, jsRound(esc)))
    }

    // ---------- end & scoring ----------
    fun finish(reason: String, crisis: Boolean = false) {
        if (over) return
        over = true
        this.crisis = crisis
        endReason = reason
        record()
        log("ALL", "Night ended: $reason", "info")
    }

    /** Targets score by the share of their 3 hits taken. */
    fun points(side: String): Int {
        val s = score[side]!!
        val enemy = ENEMY[side]!!
        var p = 0.0
        for (o in objectives[side]!!) p += 10 * targetFrac(o.ref)
        for (o in objectives[enemy]!!) p += 8 * (1 - targetFrac(o.ref))
        for (r in s.otherHits) p += 6 * targetFrac(r)
        return jsRound(p + s.killValue + s.groundKillValue / 2.0 + s.intercepts * 3 + s.droneKills)
    }

    fun summary(): Summary = Summary(crisis, SIDES.associateWith { side ->
        val objs = objectives[side]!!
        val destroyed = objs.count { targetFrac(it.ref) >= 1 }
        val damaged = objs.count { it.hit && targetFrac(it.ref) < 1 }
        SideSummary(score[side]!!, destroyed, damaged, destroyed + damaged, score[side]!!.otherHits.size, points(side))
    })

    /** State to carry into the next night of a campaign. */
    fun carryOut(): Carry {
        val pools = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (b in bases) {
            val m = LinkedHashMap<String, Int>()
            for ((ty, p) in b.pools) m[ty] = p.ready + p.rearm.size
            pools[b.name] = m
        }
        for (u in units) {
            if (u.kind != "air" || u.state != "air" || u.ghost || isDrone(u)) continue
            val b = base(u.base)!!
            pools[b.name]!![u.type!!] = (pools[b.name]!![u.type] ?: 0) + u.count
            val stock = stocks[u.side]!!
            for ((k, c) in u.wpn) stock[k]?.let { stock[k] = it + c }
        }
        val damage = LinkedHashMap<String, Int>()
        for (b in bases) if (b.damage > 0) damage[b.name] = jsRound(b.damage * 0.4)
        // Half of destroyed air defences are repaired or replaced between nights.
        val deadStatics = units.filter { (it.kind == "sam" || it.kind == "radar") && it.dead && rand() < 0.5 }.map { it.callsign }
        return Carry(pools, stocks.mapValues { LinkedHashMap(it.value) }, damage, deadStatics, esc)
    }
}
