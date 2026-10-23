package com.killchain.core

/** Anything with a map position. */
interface XY {
    val x: Double
    val y: Double
}

/** A circle on the map (patrol stations, anchors, hold points). */
class Circle(override var x: Double, override var y: Double, var r: Double) : XY

/** What one side's sensors made of a target on the last sweep. */
class Seen(val any: Boolean, val net: Boolean, val by: List<Int>)

/**
 * Everything that can be a target or a track: aircraft and static sites (Unit), missiles,
 * air bases and strike sites. Mirrors the duck-typed objects of the JS version.
 */
abstract class Ent : XY {
    abstract val id: Int
    abstract val kind: String          // air | sam | radar | aam | srm | sam | cruise | base | site
    abstract val side: String
    override var x = 0.0
    override var y = 0.0
    var hdg = 0.0
    var spd = 0.0
    var dead = false
    var hits = 0
    val seen = HashMap<String, Seen>(2)
}

class Job(val kind: String, val id: String, var n: Int = 0)

/** A flight's current task. type: orbit | rtb | attack | strike | drone | sead | feint. */
class Order(var type: String) {
    var x = Double.NaN
    var y = Double.NaN
    var r = 0.0
    var base: String? = null
    var target: Ent? = null
    var list: MutableList<Job> = mutableListOf()
    var via: MutableList<Pt>? = null
    // Diversion state
    var done = false
    var mode: String? = null
    var hold: Circle? = null
    var toldAt: Double? = null

    val hasPoint get() = !x.isNaN()

    companion object {
        fun orbit(x: Double, y: Double, r: Double) = Order("orbit").also { it.x = x; it.y = y; it.r = r }
        fun rtb(base: String?) = Order("rtb").also { it.base = base }
    }
}

class ManualDef(val mode: String, val until: Double)
class Salvo(val t: Ent, val k: String, val at: Double)

/** An aircraft flight (one fighter, or a group of drones / an AEW&C) or a static SAM / radar site. */
class Unit(override val id: Int, override val kind: String, override val side: String, var callsign: String, val type: String?) : Ent() {
    // Aircraft
    var def: AircraftDef? = null
    var count = 1
    var wpn = LinkedHashMap<String, Int>()
    var base = ""
    var state = "air"
    var order: Order? = null
    var roe = "free"
    var radarOn = false
    var fuel = 0.0
    var lastShot = -99.0
    var lastLaunch = -99.0
    var launchedAt = 0.0
    var threats = mutableListOf<Missile>()
    var defending = false
    var defMode: String? = null
    var manualDef: ManualDef? = null
    var holdUntil = Double.NEGATIVE_INFINITY
    var ai = false
    var engage: Ent? = null
    var anchor: Circle? = null
    var cap: Circle? = null
    var bingo = false
    var crossed = false
    var mission: Mission? = null
    var preset: String? = null
    var loadoutKey: String? = null
    // Formation
    var lead: Unit? = null
    var slot = 0
    var wing = mutableListOf<Unit>()
    var inForm = false
    // Emission control / strike state
    var ghost = false
    var low = false
    var radarMode = "auto"
    var exposed = false
    var egress = false
    var offensive = false
    var firing = false
    var holding = false
    var holdPt: Circle? = null
    var waitSince = 0.0
    var strikeSince = 0.0
    var chaffAt = 0.0
    var salvo: Salvo? = null
    var dmg = 0
    var feintSince = 0.0
    // Static sites
    var samDef: SamDef? = null
    var ammo = 0
    var range = 0.0
    var reloads = 0
    var reloadAt: Double? = null
    var dry = false
    var linked = true

    val d: AircraftDef get() = def!!
    val isAir get() = kind == "air"
    /** Display name of the type (aircraft, SAM system or ground radar). */
    val typeName: String get() = def?.name ?: samDef?.name ?: "Ground radar"
}

class Est(override var x: Double, override var y: Double, var vx: Double, var vy: Double) : XY {
    override fun toString() = "($x,$y)"
}

class Missile(override val id: Int, override val kind: String, override val side: String, val wkey: String, val w: WeaponDef) : Ent() {
    lateinit var shooter: Unit
    var target: Ent? = null       // air-to-air / SAM missiles
    var tgt: Ent? = null          // cruise weapons: the ground target
    var est = Est(0.0, 0.0, 0.0, 0.0)
    var active = false
    var lock = false
    var warned = false
    var activeWarned = false
    val trail = ArrayDeque<Pt>()
    var trailT = 0.0
    var t = 0.0
    var pk: Double? = null
    var end: String? = null
    var supported = false
    var launchDist = 0.0
    val count = 1
}

class Pool(var ready: Int, var rearm: MutableList<Double> = mutableListOf())

class Base(val name: String, override val side: String, x0: Double, y0: Double, val home: String?) : Ent() {
    override val id: Int = -1
    override val kind = "base"
    val pools = LinkedHashMap<String, Pool>()
    val preset = LinkedHashMap<String, String>()
    var damage = 0.0
    init { x = x0; y = y0 }
}

class Site(val name: String, override val side: String, x0: Double, y0: Double) : Ent() {
    override val id: Int = -2
    override val kind = "site"
    init { x = x0; y = y0 }
}

class Mission(val id: Int, val name: String, val kind: String, val side: String) {
    var status = "planned"
    var unit: Unit? = null
    var flights = 0
    var repeat = kind == "cap" || kind == "aew"
    var launchAt = 0.0
    var base = ""
    var type = ""
    var n = 0
    var preset: String? = null
    var target: Target? = null
    var prey: Ent? = null
    var escortUnit: Unit? = null
    var escortOf: Int? = null
    var auto = false
    var at: Pt? = null
    var patrolOf: Ent? = null
    var via: List<Pt>? = null
    var tot: Double? = null
    var wasClose = false
    var quietSince = 0.0
}

/** Parameters for createMission (the JS version passes a loose object). */
class MissionSpec(
    val kind: String, val side: String = "", val base: String = "", val type: String = "", val n: Int = 0,
    val preset: String? = null, val target: Target? = null, val prey: Ent? = null, val escortUnit: Unit? = null,
    val escortOf: Int? = null, val auto: Boolean = false, val at: Pt? = null, val patrolOf: Ent? = null,
    val via: List<Pt>? = null, val tot: Double? = null, val unit: Unit? = null, val repeat: Boolean? = null,
)

class Track(val id: Int, val ref: Ent, val num: Int, val first: Double, val kind: String, val isStatic: Boolean) {
    var q = ""
    var t = 0.0
    var x = 0.0
    var y = 0.0
    var vx = 0.0
    var vy = 0.0
    var count = 1
    var striker: String? = null
    var warnStage = 0
    var bda = false
}

class Fx(val kind: String, val x: Double, val y: Double, val t: Double, val ref: Ent? = null) {
    /** Renderer's screen offset for the jet the missile went for (set when first drawn). */
    var off: Pair<Float, Float>? = null
    var offSet = false
}

class GameEvent(
    val n: Int, val t: Double, val side: String, val text: String, val cls: String,
    val striker: Unit? = null, val alert: Boolean = false, val ref: Ent? = null,
)

class Score {
    var kills = 0; var killValue = 0; var lost = 0; var shots = 0; var intercepts = 0; var droneKills = 0
    var groundKills = 0; var groundKillValue = 0; var hits = 0; var claimed = 0; var falseClaims = 0; var groundLost = 0
    val objHits = LinkedHashSet<String>()
    val otherHits = LinkedHashSet<Ent>()
}

class Objective(val kind: String, val id: String, val ref: Ent?) {
    var hit = false
    var destroyed = false
}

class PromptOption(val key: String, val label: String, val text: String)
class Prompt(val id: Int, val kind: String, val ref: Ent?, val text: String, val options: List<PromptOption>, val def: String, val t: Double) {
    var done = false
    var choice: String? = null
}

class EwTimer { var until = 0.0; var ready = 0.0; var x = 0.0; var y = 0.0 }
class EwState { val jam = EwTimer(); val barrage = EwTimer(); val decoy = EwTimer() }

/** One replay frame (recorded every 3 s). */
class FrameUnit(val id: Int, val side: String, val type: String, val x: Int, val y: Int, val hdg: Double, val count: Int, val radar: Boolean, val ghost: Boolean, val callsign: String)
class FrameMissile(val x: Double, val y: Double, val side: String, val cruise: Boolean)
class FrameFx(val kind: String, val x: Int, val y: Int)
class Frame(val t: Double, val units: List<FrameUnit>, val missiles: List<FrameMissile>, val fx: List<FrameFx>, val damage: List<Int>, val esc: Int)

/** State carried from one campaign night to the next. */
class Carry(
    val pools: Map<String, Map<String, Int>>, val stocks: Map<String, Map<String, Int>>, val damage: Map<String, Int>,
    val deadStatics: List<String>, val esc: Double,
)

/** A pre-placed flight (training). */
class FlightDef(val side: String, val callsign: String, val type: String, val count: Int, val loadout: String, val base: String, val pos: Pt, val order: Order)

class SimConfig(
    val name: String, val startH: Int, val startM: Int, val duration: Double, val night: Night? = null,
    val carry: Carry? = null, val flights: List<FlightDef> = emptyList(), val emptyBases: Boolean = false,
    val noStatics: Boolean = false, val ai: Boolean = true, val autoPatrol: Boolean = false,
)

class SideSummary(val score: Score, val objDestroyed: Int, val objDamaged: Int, val objHits: Int, val otherHits: Int, val points: Int)
class Summary(val crisis: Boolean, val sides: Map<String, SideSummary>)
