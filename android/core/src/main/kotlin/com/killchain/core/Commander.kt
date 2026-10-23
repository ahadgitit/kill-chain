package com.killchain.core

/**
 * Side-level AI: the enemy commander (port of js/ai.js). It runs one side's war from its air bases
 * with a clear doctrine, in three layers, re-thought every 4 seconds:
 *  1. Picture: AEW&C orbits and combat air patrols stay manned (relief flights replace flights that go home).
 *  2. Fighter control (GCI): every intruder in its airspace, and every strike jet heading for it, gets
 *     a fighter response with numbers on its side (2 v 1, strikers first), from patrols in the area or
 *     scrambled from the nearest base.
 *  3. Operations: strikes fly as packages. A fighter sweep goes in first to clear the corridor, then the
 *     strike jets follow with escorts (or slip in low). It feints, pounces when the defence is thin,
 *     learns which targets are costing it aircraft, and changes phase when the night turns against it.
 * The player gets "tells" (◆ INTEL) before the big moves, so the AI can be read and beaten.
 */
class Commander(private val sim: Sim, val side: String, plan0: AiPlan?, private val oneShot: Boolean = false) {
    private val enemy = ENEMY[side]!!

    private class Station(val base: String, val n: Int, val at: Pt, val preset: String?) { var unit: Unit? = null; var launched = false }
    private class Package(var t: Double, val base: String, val n: Int, val preset: String, var list: List<Target>) {
        var stage = "planned"; var sweepN = 2; var escN = 2; var unit: Unit? = null
    }
    private class Sweep(val unit: Unit, val until: Double)
    private class Timed<T>(val plan: T) { var done = false }

    private val aews: List<Station>
    private val caps: List<Station>
    private val packages: MutableList<Package>
    private val escorts: List<Timed<EscortPlan>>
    private val drones: List<Timed<DronePlan>>
    private var timer = 0.0
    private var noStrikeUntil = 0.0
    private var stood = false
    private val scrambled = HashMap<String, Double>()
    var phase = 1; private set
    private val losses = HashMap<String, Int>()          // strike flights lost, per target id
    var lostStrikes = 0; private set
    private val gciCd = HashMap<String, Double>()          // scramble cooldown per base
    private var sweeps = mutableListOf<Sweep>()
    private var hvaaAt = -999.0                            // last raid on the enemy AEW&C
    var generated = 0; private set                         // packages planned on the fly
    private var feintStage: String?
    private var counterAt: Double?
    private val told = HashMap<String, Double>()

    init {
        val plan = plan0 ?: AiPlan()
        val live = !oneShot
        aews = plan.aew.map { Station(it.base, 1, Geo.project(it.lat, it.lon), null) }
        caps = plan.caps.map { Station(it.base, it.n, Geo.project(it.lat, it.lon), it.preset) }
        packages = if (live) plan.strikes.map { Package(it.t, it.base, it.n, it.preset, it.list.toList()) }.toMutableList() else mutableListOf()
        escorts = if (live) plan.escorts.map { Timed(it) } else emptyList()
        drones = if (live) plan.drones.map { Timed(it) } else emptyList()
        feintStage = if (live && packages.isNotEmpty()) "planned" else null
        // A side with no strikes on the schedule still hits back once the night is under way.
        counterAt = if (live && packages.isEmpty()) (if (sim.duration != 0.0) sim.duration else 3000.0) * (0.3 + sim.rand() * 0.1) else null
    }

    /** Keep station bookkeeping pointing at the right jet when a formation lead changes. */
    fun replaceUnit(old: Unit, nl: Unit) {
        for (x in caps) if (x.unit === old) x.unit = nl
        for (x in aews) if (x.unit === old) x.unit = nl
    }

    private fun live(u: Unit?) = u != null && u.state == "air" && u.order?.type != "rtb"

    private fun launch(baseId: String, n: Int, preset: String?, order: Order, typeHint: String? = null): Unit? {
        val b = sim.base(baseId) ?: return null
        val type = typeHint ?: b.home ?: b.pools.keys.firstOrNull() ?: return null
        if (b.pools[type] == null) return null
        val pr = if (preset != null && Data.PRESETS[type]!![preset] != null) preset else Data.PRESETS[type]!!.keys.first()
        val u = sim.launchFlight(baseId, type, n, order, pr, !oneShot)
        return u as? Unit
    }

    private class FighterBase(val base: String, val type: String, val ready: Int, val b: Base)
    /** Any base with ready fighters that can fly air-to-air, nearest to a point. */
    private fun fighterBaseNear(p: XY, min: Int = 2, maxD: Double = Double.POSITIVE_INFINITY): FighterBase? {
        var best: FighterBase? = null; var bd = maxD
        for (b in sim.bases) {
            if (b.side != side || !sim.baseOpen(b)) continue
            for ((ty, pool) in b.pools) {
                if (Data.AIRCRAFT[ty]!!.role != "fighter" || Data.PRESETS[ty]!!["AIR"] == null || pool.ready < min) continue
                val d = dist(b, p)
                if (d < bd) { bd = d; best = FighterBase(b.name, ty, pool.ready, b) }
            }
        }
        return best
    }
    private fun room(n: Int) = sim.airborne(side) + n <= Data.AIR_CAP[side]!!

    /** A warning to the human player about what this commander is up to. */
    private fun tell(text: String, kind: String? = null) {
        if (sim.player != enemy) return
        // One tell of a kind per minute, so a wave reads as one warning.
        if (kind != null && (told[kind] ?: -999.0) > sim.t - 60) return
        if (kind != null) told[kind] = sim.t
        sim.log(enemy, "◆ INTEL: $text", "intel")
    }

    /** The player's nearest named place to a point, for tells. */
    private fun placeNear(p: XY): String {
        var best: Ent? = null; var bd = Double.POSITIVE_INFINITY
        val named: List<Ent> = sim.bases.filter { it.side == enemy } + sim.sites.filter { it.side == enemy } +
            sim.units.filter { (it.kind == "sam" || it.kind == "radar") && it.side == enemy }
        for (x in named) { val d = dist(x, p); if (d < bd) { bd = d; best = x } }
        return best?.let { sim.targetId(it) } ?: "the frontier"
    }

    /** A point on the line from a base to a target, `depth` km inside enemy airspace. */
    private fun corridor(from: XY, to: XY, depth: Double): Pt {
        var f = 0.0
        while (f <= 1) {
            val x = from.x + (to.x - from.x) * f; val y = from.y + (to.y - from.y) * f
            if (Geo.sideOf(x, y) != side && Geo.frontierDist(x, y) >= depth) return Pt(x, y)
            f += 0.02
        }
        return Pt(to.x, to.y)
    }

    private fun enemyFightersNear(p: XY, r: Double): Int {
        var n = 0
        for (tr in sim.tracks[side]!!.values) {
            val e = tr.ref as? Unit ?: continue
            if (e.kind != "air" || e.ghost || e.d.role != "fighter" || !sim.isAlive(e) || sim.t - tr.t > 30) continue
            if (dist(sim.trackPos(tr), p) < r) n += e.count
        }
        return n
    }

    fun tick(dt: Double) {
        timer -= dt
        if (timer > 0) return
        timer = 4.0
        val t = sim.t

        // AEW&C orbits
        for (a in aews) {
            if (live(a.unit) || (oneShot && a.launched)) continue
            val u = launch(a.base, 1, "AEW", Order.orbit(a.at.x, a.at.y, 25.0))
            if (u != null) { a.unit = u; a.launched = true }
        }
        // Combat air patrols
        for (c in caps) {
            if (live(c.unit) || (oneShot && c.launched)) continue
            val order = Order.orbit(c.at.x, c.at.y, 15.0)
            var u = launch(c.base, c.n, c.preset ?: "AIR", order)
            if (u == null && !oneShot) {
                val alt = fighterBaseNear(c.at)
                if (alt != null) u = launch(alt.base, c.n, "AIR", order, alt.type)
            }
            if (u != null) { c.unit = u; c.launched = true }
        }
        if (oneShot) return

        // Escalation: stand down before the crisis spirals.
        if (!stood && sim.esc >= 88) {
            stood = true
            noStrikeUntil = t + 300
            sim.standDown(side)
        }
        gci()
        assess()
        runFeint()
        operations()
        hvaaRaid()
        for (s in sweeps) if (live(s.unit) && t > s.until) sim.orderRTB(s.unit)
        sweeps = sweeps.filterTo(ArrayList()) { live(it.unit) }

        for (s in escorts) {
            if (s.done || t < s.plan.t) continue
            val p = Geo.project(s.plan.lat, s.plan.lon)
            if (launch(s.plan.base, s.plan.n, s.plan.preset, Order.orbit(p.x, p.y, 15.0)) != null) s.done = true
        }
        for (s in drones) {
            val d = s.plan
            if (s.done || t < d.t) continue
            if (t > d.t + 900) { s.done = true; continue }
            val order = if (d.mode == "sead") Geo.project(d.lat, d.lon).let { p -> Order("sead").also { it.x = p.x; it.y = p.y; it.r = 20.0 } }
            else Order("drone").also { it.list = d.list.map { x -> Job(x.kind, x.id) }.toMutableList() }
            if (launch(d.base, d.n, null, order, d.type) != null) s.done = true
        }
    }

    // ---------- fighter control: never let an intruder go unanswered ----------
    private fun committed(e: Ent): Int {
        var n = 0
        for (u in sim.units) {
            if (u.side != side || u.kind != "air" || u.state != "air") continue
            if (u.engage === e || (u.order?.type == "attack" && u.order!!.target === e)) n += u.count
        }
        return n
    }

    private fun idleFighterNear(p: XY, r: Double): Unit? {
        var best: Unit? = null; var bd = r
        for (u in sim.units) {
            if (u.side != side || u.kind != "air" || u.state != "air" || u.ghost || u.d.role != "fighter") continue
            if (u.engage != null || u.bingo || u.cap == null || u.order == null || u.order!!.type != "orbit" || !sim.hasAAM(u)) continue
            if (u.mission?.kind == "escort") continue
            val d = dist(u, p)
            if (d < bd) { bd = d; best = u }
        }
        return best
    }

    private class Th(val e: Unit, val p: Pt, val striker: Boolean, val prio: Double)
    private fun gci() {
        val t = sim.t
        val threats = ArrayList<Th>()
        for (tr in sim.tracks[side]!!.values.toList()) {
            val e = tr.ref as? Unit ?: continue
            if (e.kind != "air" || !sim.isAlive(e) || !sim.manned(e) || e.d.role == "aew" || t - tr.t > 15) continue
            val p = sim.trackPos(tr)
            val inside = Geo.sideOf(p.x, p.y) == side
            val striker = tr.striker != null
            if (!inside && !(striker && Geo.frontierDist(p.x, p.y) < 70)) continue
            threats.add(Th(e, p, striker, if (striker) 3.0 else if (inside) 1 + Math.min(1.0, Geo.frontierDist(p.x, p.y) / 100) else 0.5))
        }
        for (th in threats.sortedByDescending { it.prio }) {
            // Numbers: a pair (or more) for every strike flight, two of ours for each intruding fighter.
            val want = if (th.striker) Math.min(4, Math.max(2, th.e.count + 1)) else Math.min(4, th.e.count * 2)
            var have = committed(th.e)
            while (have < want) {
                // A strike gets fighters pulled from further afield (which is what makes a diversion work).
                val u = idleFighterNear(th.p, if (th.striker) 250.0 else 180.0) ?: break
                u.engage = th.e
                u.anchor = Circle(u.cap!!.x, u.cap!!.y, u.cap!!.r)
                have += u.count
            }
            if (have >= want || !(th.striker || have == 0) || !room(2)) continue
            val fb = fighterBaseNear(th.p, 2, 260.0) ?: continue
            if ((gciCd[fb.base] ?: 0.0) > t) continue
            gciCd[fb.base] = t + 120
            sim.createMission(MissionSpec("chase", side, fb.base, fb.type, Math.min(if (want - have >= 3) 4 else 2, fb.ready), "AIR", prey = th.e))
        }
    }

    // ---------- phases: the commander changes its approach as the night goes ----------
    private fun assess() {
        val t = sim.t
        val behind = sim.points(side) < sim.points(enemy)
        if (phase == 1 && (lostStrikes >= 1 || sim.score[side]!!.lost >= 4 || (t > sim.duration * 0.4 && behind))) {
            phase = 2
            tell("enemy air activity is surging. Expect a massed wave with fighter cover.")
            // Bring the remaining packages forward into one heavy wave.
            val left = packages.filter { it.stage == "planned" }.sortedBy { it.t }
            left.forEachIndexed { i, p -> p.t = Math.min(p.t, t + 150 + i * 40); p.sweepN = 4 }
            if (left.isEmpty()) plan(t + 180, 2)
        }
        val ca = counterAt
        if (ca != null && t > ca) {
            counterAt = null
            if (plan(t + 150, 2)) tell("enemy strike jets are being armed. Expect a counter-raid with a fighter sweep ahead of it.")
        }
        if (phase == 2 && t > sim.duration * 0.72) {
            phase = 3
            if (plan(t + 150, 4)) tell("enemy strike aircraft are arming for a final push.")
        }
    }

    /** Plan a new strike package against the night's targets (returns true if it could). */
    private fun plan(at: Double, sweepN: Int): Boolean {
        if (stood || generated >= 2) return false
        val objs = sim.objectives[side]!!.filter { it.ref != null && sim.isAlive(it.ref) && it.ref.hits < 3 }
        if (objs.isEmpty()) return false
        var bestB: Base? = null; var bestO: Objective? = null; var bs = Double.POSITIVE_INFINITY
        for (b in sim.bases) {
            val home = b.home
            if (b.side != side || !sim.baseOpen(b) || home == null || Data.PRESETS[home]!!["STRIKE"] == null) continue
            val pool = b.pools[home]
            if (pool == null || pool.ready < 2) continue
            for (o in objs) {
                val s = dist(b, o.ref!!) + (losses[o.id] ?: 0) * 150
                if (s < bs) { bs = s; bestB = b; bestO = o }
            }
        }
        if (bestB == null || bestO == null) return false
        generated++
        val two = objs.find { it !== bestO && dist(it.ref!!, bestO.ref!!) < 120 } ?: bestO
        packages.add(Package(at, bestB.name, 2, "STRIKE", listOf(Target(bestO.kind, bestO.id), Target(two.kind, two.id))).also { it.sweepN = sweepN; it.escN = 2 })
        return true
    }

    // ---------- feint: show fighters at one target, then strike another ----------
    private fun runFeint() {
        val t = sim.t
        if (feintStage != "planned") return
        val real = packages.find { it.stage == "planned" }
        if (real == null) { feintStage = "done"; return }
        if (t < real.t - 330) return
        feintStage = "done"
        if (sim.rand() < 0.4 || !room(2)) return
        val tgt = sim.refFor(real.list[0].kind, real.list[0].id)
        val other = sim.objectives[side]!!.filter { it.ref != null && tgt != null }.sortedWith { a, b -> java.lang.Double.compare(dist(b.ref!!, tgt!!), dist(a.ref!!, tgt)) }.firstOrNull()
        if (other == null || dist(other.ref!!, tgt!!) < 120) return
        val fb = fighterBaseNear(other.ref) ?: return
        val at = corridor(fb.b, other.ref, 15.0)
        val u = launch(fb.base, 2, "AIR", Order.orbit(at.x, at.y, 20.0), fb.type) ?: return
        u.offensive = true
        sweeps.add(Sweep(u, t + 600))
        tell("enemy fighters are massing toward ${placeNear(at)}.")
    }

    // ---------- strike packages: sweep, then strike with escorts ----------
    private fun retarget(list: List<Target>, base: Base): List<Target> {
        fun ok(kind: String, id: String): Boolean {
            val r = sim.refFor(kind, id)
            return r != null && sim.isAlive(r) && r.hits < 3 && (losses[id] ?: 0) < 2
        }
        val alts = sim.objectives[side]!!.filter { ok(it.kind, it.id) }.sortedWith { a, b ->
            val dl = (losses[a.id] ?: 0) - (losses[b.id] ?: 0)
            if (dl != 0) dl else java.lang.Double.compare(dist(base, a.ref!!), dist(base, b.ref!!))
        }
        return list.map { x -> if (ok(x.kind, x.id) || alts.isEmpty()) x else Target(alts[0].kind, alts[0].id) }
    }

    private fun operations() {
        val t = sim.t
        for (pk in packages.toList()) {
            if (pk.stage == "done") continue
            if (pk.stage == "inbound") {
                val u = pk.unit!!
                if (u.state == "dead") {
                    pk.stage = "done"
                    lostStrikes++
                    for (x in pk.list) losses[x.id] = (losses[x.id] ?: 0) + 1
                } else if (u.state != "air" || u.order?.type != "strike") pk.stage = "done"
                continue
            }
            if (t < noStrikeUntil || stood) continue
            if (t > pk.t + 900) { pk.stage = "done"; continue }
            val b = sim.base(pk.base)
            if (b?.home == null) { pk.stage = "done"; continue }
            val tgt = sim.refFor(pk.list[0].kind, pk.list[0].id)
            if (pk.stage == "planned") {
                if (t < pk.t - 120) {
                    // Pounce: if the defence around the target is thin right now, go early.
                    if (t > 120 && pk.t - t < 420 && tgt != null && enemyFightersNear(tgt, 170.0) == 0) pk.t = t + 120
                    else continue
                }
                pk.list = retarget(pk.list, b)
                val aim = sim.refFor(pk.list[0].kind, pk.list[0].id) ?: tgt
                // Escorts and sweep size grow with what this target has already cost us.
                val lost = pk.list.maxOf { losses[it.id] ?: 0 }
                if (lost != 0) { pk.sweepN = 4; pk.escN = 2 }
                val fb = fighterBaseNear(b)
                if (fb != null && aim != null && room(pk.sweepN + pk.n + pk.escN)) {
                    val at = corridor(b, aim, 30.0)
                    val u = launch(fb.base, pk.sweepN, "AIR", Order.orbit(at.x, at.y, 25.0), fb.type)
                    if (u != null) {
                        u.offensive = true
                        sweeps.add(Sweep(u, t + 900))
                        tell("enemy fighter sweep (${1 + u.wing.size} ${u.d.name}) heading for ${placeNear(at)}. A strike usually follows.", "sweep")
                    }
                }
                pk.stage = "sweep"
                continue
            }
            if (pk.stage == "sweep" && t >= pk.t) {
                val type = b.home
                val per = Data.PRESETS[type]!![pk.preset] ?: emptyMap()
                val cruise = per.entries.filter { Data.WEAPONS[it.key]!!.kind == "cruise" }.sumOf { it.value } * pk.n
                val each = Math.max(1, cruise / pk.list.size)
                val u = launch(pk.base, pk.n, pk.preset, Order("strike").also { o -> o.list = pk.list.map { Job(it.kind, it.id, each) }.toMutableList() }) ?: continue
                pk.stage = "inbound"
                pk.unit = u
                // Either a low-level run under the radar while the sweep draws the defence's eyes (no
                // escort: it would give the strikers away), or a high run with an escort.
                if (sim.rand() < (if (phase >= 2) 0.7 else 0.45)) {
                    goLow(u)
                    tell("enemy strike jets may be coming in at low level, under your radar. Only your fighters will see them.", "low")
                } else {
                    val eb = fighterBaseNear(b)
                    if (eb != null) sim.createMission(MissionSpec("escort", side, eb.base, eb.type, Math.min(pk.escN, eb.ready), "AIR", escortUnit = u))
                }
            }
        }
    }

    // ---------- high-value raid: go for the enemy AEW&C when it comes close ----------
    private fun hvaaRaid() {
        val t = sim.t
        if (t - hvaaAt < 900 || t < 300 || !room(2)) return
        for (tr in sim.tracks[side]!!.values.toList()) {
            val e = tr.ref as? Unit ?: continue
            if (e.kind != "air" || e.d.role != "aew" || !sim.isAlive(e) || t - tr.t > 10) continue
            val p = sim.trackPos(tr)
            if (Geo.frontierDist(p.x, p.y) > 140 || committed(e) != 0) continue
            // Long-range missiles only: this is a dash into defended airspace.
            var bestBase: String? = null; var bestType = ""; var bd = 330.0
            for (b in sim.bases) {
                if (b.side != side || !sim.baseOpen(b)) continue
                for ((ty, pool) in b.pools) {
                    val air = Data.PRESETS[ty]!!["AIR"]
                    if (Data.AIRCRAFT[ty]!!.role != "fighter" || air == null || pool.ready < 2) continue
                    if (air.keys.none { Data.WEAPONS[it]!!.range >= 100 }) continue
                    val d = dist(b, p)
                    if (d < bd) { bd = d; bestBase = b.name; bestType = ty }
                }
            }
            if (bestBase == null) return
            hvaaAt = t
            val ms = sim.createMission(MissionSpec("chase", side, bestBase, bestType, 2, "AIR", prey = e))
            val mu = ms.unit
            if (mu != null) {
                mu.offensive = true
                goLow(mu)
                tell("enemy fighters are pushing toward your ${e.d.name}. Cover it or pull it back.")
            }
            return
        }
    }

    /** Terrain hugging, radar off, for a flight (lead and wingmen). */
    private fun goLow(u: Unit) {
        for (x in listOf(u) + u.wing) { x.radarMode = "low"; x.low = true; x.radarOn = false }
    }

    /** Called by the sim when enemy weapons are heading for one of our bases. */
    fun baseThreat(b: Base) {
        val t = sim.t
        if ((scrambled[b.name] ?: -999.0) > t - 300) return
        scrambled[b.name] = t
        sim.scrambleAll(b, true)
    }
}
