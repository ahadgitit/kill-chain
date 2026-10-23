package com.killchain.core

// Platform, weapon, base and campaign data.
// Figures are simplified estimates from open sources. Real performance is classified, varies
// with altitude/aspect, and is often disputed. Basing is simplified to one aircraft type per
// base for playability.

/**
 * radar: detection range (km) vs a 5 m2 target. rcs: m2 (frontal, rough). ew: self-protection
 * jamming quality 0..1. speed/dash: km/s. turn: deg/s. linked: on its side's tactical data-link
 * network. fuel: seconds at cruise (dash burns 2.5x). turnaround: seconds to refuel and re-arm.
 */
class AircraftDef(
    val key: String, val name: String, val role: String, val radar: Double, val rcs: Double, val ew: Double,
    val speed: Double, val dash: Double, val turn: Double, val linked: Boolean, val value: Int, val fuel: Double,
    val turnaround: Double, val note: String, val mission: String? = null, val low: Boolean = false,
)

/**
 * kind: aam (beyond visual range), srm (short range), sam, cruise (incl. glide weapons).
 * range: kinematic distance (km) flown before the missile is too slow to manoeuvre. vMax km/s,
 * burn s, seeker: active seeker range km, tp: can be guided by third-party data-link tracks.
 * Cruise weapons: dmg = damage to a base per hit (runway closes at 60). Stand-off ranges are
 * deliberately about half the real figures so strike jets must come within reach of defenders.
 */
class WeaponDef(
    val key: String, val name: String, val kind: String, val range: Double, val vMax: Double = 0.0, val burn: Double = 0.0,
    val seeker: Double = 0.0, val pk: Double = 0.0, val tp: Boolean = false,
    val speed: Double = 0.0, val rcs: Double = 0.0, val low: Boolean = false, val dmg: Double = 0.0,
) {
    var drag = 0.0; private set
    var tMax = 0.0; private set
    init {
        if (kind != "cruise") {
            val burnDist = vMax * burn
            val coast = maxOf(5.0, range - burnDist)
            drag = (vMax - V_MIN) / coast                      // v' = -drag * v after burn-out
            tMax = burn + JsMath.log(vMax / V_MIN) / drag
        }
    }
}

class SamDef(val name: String, val radar: Double, val weapon: String, val ammo: Int)
class BaseDef(val id: String, val side: String, val x: Double, val y: Double, val type: String?, val count: Int, val preset: String?)
class SiteDef(val id: String, val side: String, val x: Double, val y: Double)
class SamSiteDef(val side: String, val callsign: String, val type: String, val pos: Pt)
class RadarSiteDef(val side: String, val callsign: String, val range: Double, val pos: Pt)
data class Target(val kind: String, val id: String)

class AewPlan(val base: String, val lat: Double, val lon: Double)
class CapPlan(val base: String, val n: Int, val lat: Double, val lon: Double, val preset: String? = null)
class StrikePlan(val t: Double, val base: String, val n: Int, val preset: String, val list: List<Target>)
class EscortPlan(val t: Double, val base: String, val n: Int, val preset: String, val lat: Double, val lon: Double)
class DronePlan(
    val t: Double, val base: String, val n: Int, val mode: String, val list: List<Target> = emptyList(),
    val lat: Double = 0.0, val lon: Double = 0.0, val type: String? = null,
)
class AiPlan(
    val aew: List<AewPlan> = emptyList(), val caps: List<CapPlan> = emptyList(), val strikes: List<StrikePlan> = emptyList(),
    val escorts: List<EscortPlan> = emptyList(), val drones: List<DronePlan> = emptyList(),
)
class Night(
    val n: Int, val title: String, val date: String, val startH: Int, val startM: Int, val duration: Double,
    val summary: String, val targets: Map<String, List<Target>>, val ai: Map<String, AiPlan>,
    val sites: Boolean = false, val ceasefireAt: Double? = null, val tag: String? = null, val scenario: Boolean = false,
    val pools: Map<String, Map<String, Int>> = emptyMap(), val counts: Map<String, Int> = emptyMap(),
    val sams: List<SamSiteDef> = emptyList(),
) {
    /** "NIGHT 3" or, for a scenario, its tag. */
    val label get() = tag ?: "NIGHT $n"
}

const val V_MIN = 0.35

object Data {
    private fun at(la: Double, lo: Double) = Geo.project(la, lo)
    private fun T(kind: String, id: String) = Target(kind, id)

    val AIRCRAFT: Map<String, AircraftDef> = linkedMapOf(
        "RAFALE" to AircraftDef("RAFALE", "Rafale", "fighter", 150.0, 1.5, 0.35, 0.26, 0.42, 10.0, true, 15, 7500.0, 480.0,
            "RBE2-AA AESA radar, SPECTRA EW suite. Meteor ramjet BVR missile, SCALP-EG cruise missile, HAMMER precision bombs."),
        "SU30" to AircraftDef("SU30", "Su-30MKI", "fighter", 160.0, 12.0, 0.2, 0.26, 0.42, 8.0, true, 12, 9000.0, 540.0,
            "Long legs and a big N011M Bars radar, but a large radar signature. R-77 missiles or one BrahMos-A cruise missile."),
        "MIG29" to AircraftDef("MIG29", "MiG-29UPG", "fighter", 110.0, 6.0, 0.15, 0.26, 0.43, 10.0, true, 9, 5400.0, 420.0,
            "Zhuk-ME radar, R-77 missiles. Agile, short-ranged interceptor."),
        "M2000" to AircraftDef("M2000", "Mirage 2000I", "fighter", 110.0, 2.5, 0.25, 0.25, 0.40, 10.0, true, 10, 6300.0, 420.0,
            "RDY-2 radar, MICA missiles, SPICE-2000 precision glide bombs (short range: must fly close)."),
        "J10C" to AircraftDef("J10C", "J-10CE", "fighter", 170.0, 3.0, 0.25, 0.26, 0.42, 10.0, true, 12, 6300.0, 420.0,
            "KLJ-7A AESA radar, PL-15E long-range missile with two-way data link (third-party targeting)."),
        "JF17B3" to AircraftDef("JF17B3", "JF-17 Blk III", "fighter", 150.0, 3.5, 0.2, 0.25, 0.38, 9.0, true, 8, 5800.0, 400.0,
            "KLJ-7A AESA radar, PL-15E capable, Link-17. Can carry the CM-400AKG supersonic strike missile."),
        "JF17B2" to AircraftDef("JF17B2", "JF-17 Blk II", "fighter", 105.0, 3.5, 0.15, 0.25, 0.38, 9.0, true, 8, 5800.0, 400.0,
            "KLJ-7 radar, SD-10A missiles, Link-17. Can carry the CM-400AKG."),
        "F16" to AircraftDef("F16", "F-16 Blk 52", "fighter", 110.0, 3.0, 0.2, 0.26, 0.42, 11.0, false, 12, 6300.0, 420.0,
            "APG-68(V)9 radar, AIM-120C-5, H-4 stand-off glide weapon. Not on the national Link-17 net in this sim."),
        "NETRA" to AircraftDef("NETRA", "Netra AEW&C", "aew", 250.0, 60.0, 0.1, 0.17, 0.2, 3.0, true, 30, 22000.0, 900.0,
            "Embraer 145 with DRDO AESA radar. Builds the air picture and shares it over the link."),
        "PHALCON" to AircraftDef("PHALCON", "A-50EI Phalcon", "aew", 380.0, 80.0, 0.1, 0.2, 0.22, 3.0, true, 30, 26000.0, 900.0,
            "Il-76 with EL/W-2090 radar. Very long range. Keep it far from the border."),
        "ERIEYE" to AircraftDef("ERIEYE", "Saab 2000 Erieye", "aew", 330.0, 50.0, 0.1, 0.17, 0.2, 3.0, true, 30, 22000.0, 900.0,
            "Erieye AESA radar. Feeds the Link-17 picture to fighters flying radar-silent."),
        "ZDK03" to AircraftDef("ZDK03", "ZDK-03", "aew", 420.0, 80.0, 0.1, 0.17, 0.2, 3.0, true, 30, 26000.0, 900.0,
            "Karakoram Eagle (Y-8 based) AEW&C with a rotodome radar. Covers the southern sector from Karachi."),
        // Drones: slow, small, low. They don't count against the airborne limit and never land.
        "HAROP" to AircraftDef("HAROP", "Harop", "drone", 0.0, 0.15, 0.0, 0.11, 0.13, 6.0, false, 1, 14000.0, 0.0,
            "Loitering munition. Hunts enemy radars by their emissions, then dives on them. A SAM that switches its radar off is safe from it.",
            mission = "sead", low = true),
        "SWARM" to AircraftDef("SWARM", "Loitering drone", "drone", 0.0, 0.2, 0.0, 0.08, 0.09, 6.0, false, 1, 9000.0, 0.0,
            "Cheap one-way attack drones, flown in swarms. Weak alone; in numbers they soak up expensive SAM missiles.",
            mission = "attack", low = true),
    )

    val WEAPONS: Map<String, WeaponDef> = linkedMapOf(
        "PL15" to WeaponDef("PL15", "PL-15E", "aam", 150.0, 1.5, 12.0, 25.0, 0.6, tp = true),
        "METEOR" to WeaponDef("METEOR", "Meteor", "aam", 170.0, 1.2, 70.0, 20.0, 0.7),
        "AIM120" to WeaponDef("AIM120", "AIM-120C-5", "aam", 100.0, 1.3, 8.0, 16.0, 0.55),
        "R77" to WeaponDef("R77", "R-77", "aam", 80.0, 1.2, 7.0, 15.0, 0.45),
        "MICA" to WeaponDef("MICA", "MICA-EM", "aam", 70.0, 1.1, 5.0, 12.0, 0.55),
        "SD10" to WeaponDef("SD10", "SD-10A", "aam", 75.0, 1.2, 7.0, 15.0, 0.45),
        "PL10" to WeaponDef("PL10", "PL-10", "srm", 14.0, 0.9, 4.0, 14.0, 0.7),
        "PL5" to WeaponDef("PL5", "PL-5EII", "srm", 12.0, 0.85, 4.0, 12.0, 0.55),
        "AIM9X" to WeaponDef("AIM9X", "AIM-9X", "srm", 14.0, 0.9, 4.0, 14.0, 0.7),
        "R73" to WeaponDef("R73", "R-73", "srm", 12.0, 0.85, 4.0, 12.0, 0.6),
        "MICAIR" to WeaponDef("MICAIR", "MICA-IR", "srm", 14.0, 0.9, 4.0, 14.0, 0.65),
        "S400" to WeaponDef("S400", "48N6 (S-400)", "sam", 250.0, 2.0, 12.0, 20.0, 0.55, tp = true),
        "HQ9" to WeaponDef("HQ9", "HQ-9/P", "sam", 130.0, 1.4, 10.0, 15.0, 0.5, tp = true),
        "MRSAM" to WeaponDef("MRSAM", "MRSAM", "sam", 90.0, 1.1, 8.0, 15.0, 0.55, tp = true),
        "AKASH" to WeaponDef("AKASH", "Akash", "sam", 30.0, 0.85, 5.0, 10.0, 0.5, tp = true),
        "LY80" to WeaponDef("LY80", "LY-80", "sam", 50.0, 1.0, 6.0, 10.0, 0.5, tp = true),
        "SCALP" to WeaponDef("SCALP", "SCALP-EG", "cruise", 140.0, speed = 0.27, rcs = 0.1, low = true, dmg = 35.0),
        "BRAHMOS" to WeaponDef("BRAHMOS", "BrahMos-A", "cruise", 200.0, speed = 0.85, rcs = 0.3, low = false, dmg = 45.0),
        "HAMMER" to WeaponDef("HAMMER", "HAMMER", "cruise", 70.0, speed = 0.3, rcs = 0.1, low = false, dmg = 20.0),
        "SPICE" to WeaponDef("SPICE", "SPICE-2000", "cruise", 60.0, speed = 0.22, rcs = 0.2, low = false, dmg = 30.0),
        "CM400" to WeaponDef("CM400", "CM-400AKG", "cruise", 120.0, speed = 1.0, rcs = 0.3, low = false, dmg = 40.0),
        "H4" to WeaponDef("H4", "H-4 SOW", "cruise", 60.0, speed = 0.22, rcs = 0.15, low = false, dmg = 30.0),
        "LS6" to WeaponDef("LS6", "LS-6 glide bomb", "cruise", 60.0, speed = 0.2, rcs = 0.15, low = false, dmg = 20.0),
    )

    val SAMS: Map<String, SamDef> = linkedMapOf(
        "S400" to SamDef("S-400", 250.0, "S400", 8),
        "HQ9" to SamDef("HQ-9/P", 150.0, "HQ9", 8),
        "MRSAM" to SamDef("MRSAM", 100.0, "MRSAM", 8),
        "AKASH" to SamDef("Akash", 60.0, "AKASH", 6),
        "LY80" to SamDef("LY-80", 80.0, "LY80", 6),
    )

    private fun ld(vararg p: Pair<String, Int>) = linkedMapOf(*p)

    /**
     * Loadouts, per jet, set by the mission. CHASE and ESCORT fly AIR: 4 air-to-air missiles,
     * fired 2 at a time. GROUND ATTACK flies STRIKE: 3 air-to-surface weapons (3 hits destroy a
     * target) plus 2 short-range missiles for self-defence.
     */
    val PRESETS: Map<String, Map<String, Map<String, Int>>> = linkedMapOf(
        "RAFALE" to linkedMapOf("AIR" to ld("METEOR" to 2, "MICA" to 2), "STRIKE" to ld("SCALP" to 3, "MICAIR" to 2)),
        "SU30" to linkedMapOf("AIR" to ld("R77" to 4), "STRIKE" to ld("BRAHMOS" to 3, "R73" to 2)),
        "MIG29" to linkedMapOf("AIR" to ld("R77" to 2, "R73" to 2)),
        "M2000" to linkedMapOf("AIR" to ld("MICA" to 2, "MICAIR" to 2), "STRIKE" to ld("SPICE" to 3, "MICAIR" to 2)),
        "J10C" to linkedMapOf("AIR" to ld("PL15" to 2, "PL10" to 2), "STRIKE" to ld("LS6" to 3, "PL10" to 2)),
        "JF17B3" to linkedMapOf("AIR" to ld("PL15" to 2, "PL5" to 2), "STRIKE" to ld("CM400" to 3, "PL5" to 2)),
        "JF17B2" to linkedMapOf("AIR" to ld("SD10" to 2, "PL5" to 2), "STRIKE" to ld("CM400" to 3, "PL5" to 2)),
        "F16" to linkedMapOf("AIR" to ld("AIM120" to 2, "AIM9X" to 2), "STRIKE" to ld("H4" to 3, "AIM9X" to 2)),
        "NETRA" to linkedMapOf("AEW" to ld()), "PHALCON" to linkedMapOf("AEW" to ld()),
        "ERIEYE" to linkedMapOf("AEW" to ld()), "ZDK03" to linkedMapOf("AEW" to ld()),
        "HAROP" to linkedMapOf("SEAD" to ld()), "SWARM" to linkedMapOf("ATTACK" to ld()),
    )

    /** National stocks of the decisive weapons only (everything else is plentiful). */
    val STOCKS: Map<String, Map<String, Int>> = linkedMapOf(
        "IN" to ld("METEOR" to 40, "SCALP" to 36, "BRAHMOS" to 24),
        "PK" to ld("PL15" to 48, "CM400" to 24),
    )

    /** Maximum manned aircraft airborne at once (sortie generation / airspace control). */
    val AIR_CAP = mapOf("IN" to 24, "PK" to 20)

    private fun base(id: String, side: String, la: Double, lo: Double, type: String? = null, count: Int = 0, preset: String? = null) =
        at(la, lo).let { BaseDef(id, side, it.x, it.y, type, count, preset) }

    /** Airbases: the aircraft based there at the start. Bases without a type are divert fields (and targets). */
    val BASES: List<BaseDef> by lazy {
        listOf(
            // India
            base("AMBALA", "IN", 30.37, 76.82, "RAFALE", 12, "STRIKE"),
            base("HALWARA", "IN", 30.75, 75.63, "SU30", 12, "AIR"),
            base("SIRSA", "IN", 29.56, 75.01, "SU30", 8, "AIR"),
            base("JODHPUR", "IN", 26.25, 73.05, "SU30", 8, "AIR"),
            base("ADAMPUR", "IN", 31.43, 75.76, "MIG29", 10, "AIR"),
            base("SRINAGAR", "IN", 33.99, 74.77, "MIG29", 6, "AIR"),
            base("PATHANKOT", "IN", 32.23, 75.63, "M2000", 10, "STRIKE"),
            base("BATHINDA", "IN", 30.27, 74.76, "NETRA", 2, "AEW"),
            base("AGRA", "IN", 27.16, 77.96, "PHALCON", 2, "AEW"),
            base("AMRITSAR", "IN", 31.71, 74.80, "HAROP", 18, "SEAD"),
            base("UDHAMPUR", "IN", 32.93, 75.13), base("SURATGARH", "IN", 29.39, 73.90), base("UTTARLAI", "IN", 25.81, 71.48),
            base("BHUJ", "IN", 23.29, 69.67), base("GWALIOR", "IN", 26.29, 78.23),
            // Pakistan
            base("MINHAS", "PK", 33.87, 72.40, "J10C", 12, "AIR"),
            base("RAFIQUI", "PK", 30.76, 72.28, "JF17B3", 12, "AIR"),
            base("PESHAWAR", "PK", 34.01, 71.51, "JF17B2", 12, "AIR"),
            base("MUSHAF", "PK", 32.05, 72.67, "F16", 10, "AIR"),
            base("JACOBABAD", "PK", 28.28, 68.45, "F16", 8, "AIR"),
            base("NUR KHAN", "PK", 33.62, 73.10, "ERIEYE", 3, "AEW"),
            base("MASROOR", "PK", 24.89, 66.94, "ZDK03", 2, "AEW"),
            base("SIALKOT", "PK", 32.52, 74.36, "SWARM", 24, "ATTACK"),
            base("MURID", "PK", 32.91, 72.79), base("SUKKUR", "PK", 27.72, 68.79), base("RAHIM YAR KHAN", "PK", 28.38, 70.28),
            base("BHOLARI", "PK", 25.24, 67.98), base("SKARDU", "PK", 35.34, 75.54),
        )
    }

    /** Strike targets (alleged militant infrastructure sites named in public reporting). */
    val SITES: List<SiteDef> by lazy {
        listOf("BAHAWALPUR" to (29.38 to 71.68), "MURIDKE" to (31.80 to 74.25), "SIALKOT (MJ)" to (32.48 to 74.62),
            "KOTLI" to (33.50 to 73.90), "MUZAFFARABAD" to (34.37 to 73.47))
            .map { (id, ll) -> at(ll.first, ll.second).let { SiteDef(id, "PK", it.x, it.y) } }
    }

    private fun sam(side: String, cs: String, type: String, la: Double, lo: Double) = SamSiteDef(side, cs, type, at(la, lo))

    val SAM_SITES: List<SamSiteDef> by lazy {
        listOf(
            sam("IN", "SUDARSHAN", "S400", 31.30, 75.60), sam("IN", "BARAK-1", "MRSAM", 31.60, 75.05),
            sam("IN", "AKASH-1", "AKASH", 32.20, 75.60), sam("IN", "AKASH-2", "AKASH", 33.95, 74.80), sam("IN", "AKASH-3", "AKASH", 29.60, 75.05),
            sam("PK", "HQ9-LHR", "HQ9", 31.45, 74.15), sam("PK", "HQ9-ISB", "HQ9", 33.55, 73.15),
            sam("PK", "LY80-SKT", "LY80", 32.45, 74.40), sam("PK", "LY80-BWP", "LY80", 29.42, 71.75),
            sam("PK", "LY80-MZD", "LY80", 34.30, 73.45), sam("PK", "LY80-RFQ", "LY80", 30.80, 72.35),
        )
    }

    val RADAR_SITES: List<RadarSiteDef> by lazy {
        listOf(
            RadarSiteDef("IN", "GR-AMRITSAR", 230.0, at(31.55, 75.15)), RadarSiteDef("IN", "GR-SURATGARH", 230.0, at(29.40, 73.95)),
            RadarSiteDef("IN", "GR-SRINAGAR", 200.0, at(33.95, 74.95)), RadarSiteDef("IN", "GR-JAISALMER", 230.0, at(26.90, 71.00)),
            RadarSiteDef("PK", "GR-LAHORE", 230.0, at(31.35, 73.95)), RadarSiteDef("PK", "GR-SAKESAR", 300.0, at(32.53, 71.94)),
            RadarSiteDef("PK", "GR-BAHAWALPUR", 230.0, at(29.45, 71.90)), RadarSiteDef("PK", "GR-TOPA", 230.0, at(33.90, 73.40)),
        )
    }

    /** Legacy per-flight loadouts (used by the training mission's pre-placed flights). */
    val LOADOUTS: Map<String, Map<String, Int>> = linkedMapOf(
        "RAFALE_CAP" to ld("METEOR" to 4, "MICA" to 4, "MICAIR" to 4),
        "J10C" to ld("PL15" to 6, "PL10" to 4),
        "AEW" to ld(),
    )

    private fun aew(b: String, la: Double, lo: Double) = AewPlan(b, la, lo)
    private fun cap(b: String, n: Int, la: Double, lo: Double, preset: String? = null) = CapPlan(b, n, la, lo, preset)

    /**
     * Campaign nights. targets: each side's priority targets for the night; 10 points each to the
     * attacker, 8 per untouched target to the defender. ai: the plan the computer follows for that side.
     */
    val NIGHTS: List<Night> by lazy {
        listOf(
            Night(1, "OPENING STRIKES", "6–7 MAY 2025", 19, 15, 50 * 60.0,
                "India strikes five sites across the border. Pakistan defends and hits back at forward Indian positions.",
                targets = mapOf(
                    "IN" to listOf(T("site", "BAHAWALPUR"), T("site", "MURIDKE"), T("site", "SIALKOT (MJ)"), T("site", "KOTLI"), T("site", "MUZAFFARABAD")),
                    "PK" to listOf(T("base", "UDHAMPUR"), T("radar", "GR-AMRITSAR")),
                ),
                ai = mapOf(
                    "IN" to AiPlan(
                        aew = listOf(aew("BATHINDA", 30.60, 75.85), aew("AGRA", 29.30, 76.60)),
                        caps = listOf(cap("HALWARA", 2, 31.35, 75.15), cap("SIRSA", 2, 29.90, 74.55), cap("SRINAGAR", 2, 33.75, 74.85), cap("ADAMPUR", 2, 32.10, 75.35)),
                        strikes = listOf(
                            StrikePlan(240.0, "AMBALA", 2, "STRIKE", listOf(T("site", "MURIDKE"), T("site", "MUZAFFARABAD"))),
                            StrikePlan(300.0, "AMBALA", 2, "STRIKE", listOf(T("site", "BAHAWALPUR"), T("site", "BAHAWALPUR"))),
                            StrikePlan(420.0, "PATHANKOT", 2, "STRIKE", listOf(T("site", "KOTLI"), T("site", "SIALKOT (MJ)"))),
                            StrikePlan(900.0, "AMBALA", 2, "STRIKE", listOf(T("site", "MUZAFFARABAD"), T("site", "MURIDKE"))),
                            StrikePlan(960.0, "PATHANKOT", 2, "STRIKE", listOf(T("site", "SIALKOT (MJ)"), T("site", "KOTLI"))),
                        ),
                        escorts = listOf(EscortPlan(200.0, "AMBALA", 2, "AIR", 32.0, 75.2)),
                    ),
                    "PK" to AiPlan(
                        aew = listOf(aew("NUR KHAN", 32.75, 72.95), aew("MASROOR", 26.2, 69.0)),
                        caps = listOf(cap("MINHAS", 2, 32.70, 73.95), cap("MINHAS", 2, 31.75, 73.85), cap("RAFIQUI", 2, 30.40, 73.10), cap("RAFIQUI", 2, 29.30, 72.10), cap("MUSHAF", 2, 33.40, 73.55)),
                        drones = listOf(DronePlan(900.0, "SIALKOT", 8, "attack", listOf(T("base", "UDHAMPUR"), T("radar", "GR-AMRITSAR")))),
                    ),
                ),
                sites = true,
            ),
            Night(2, "DRONE NIGHT", "7–8 MAY 2025", 18, 30, 50 * 60.0,
                "Pakistan sends drone swarms at Indian bases and radars. India hunts Pakistani air defences with Harop.",
                targets = mapOf(
                    "IN" to listOf(T("sam", "HQ9-LHR"), T("radar", "GR-LAHORE"), T("sam", "LY80-SKT"), T("radar", "GR-TOPA")),
                    "PK" to listOf(T("base", "ADAMPUR"), T("base", "PATHANKOT"), T("base", "AMRITSAR"), T("radar", "GR-AMRITSAR"), T("sam", "AKASH-1")),
                ),
                ai = mapOf(
                    "IN" to AiPlan(
                        aew = listOf(aew("BATHINDA", 30.75, 75.90)),
                        caps = listOf(cap("ADAMPUR", 2, 31.80, 75.20), cap("HALWARA", 2, 31.10, 75.00), cap("SRINAGAR", 2, 33.60, 74.90)),
                        drones = listOf(
                            DronePlan(180.0, "AMRITSAR", 6, "sead", lat = 31.50, lon = 74.25),
                            DronePlan(600.0, "AMRITSAR", 6, "sead", lat = 32.40, lon = 74.30),
                            DronePlan(1200.0, "AMRITSAR", 6, "sead", lat = 31.40, lon = 74.00),
                        ),
                    ),
                    "PK" to AiPlan(
                        aew = listOf(aew("NUR KHAN", 32.75, 72.95)),
                        caps = listOf(cap("MINHAS", 2, 32.60, 73.90), cap("RAFIQUI", 2, 31.20, 73.40), cap("MUSHAF", 2, 33.30, 73.60)),
                        drones = listOf(
                            DronePlan(120.0, "SIALKOT", 8, "attack", listOf(T("base", "PATHANKOT"), T("sam", "AKASH-1"))),
                            DronePlan(300.0, "SIALKOT", 8, "attack", listOf(T("base", "AMRITSAR"), T("radar", "GR-AMRITSAR"))),
                            DronePlan(540.0, "SIALKOT", 8, "attack", listOf(T("base", "ADAMPUR"))),
                        ),
                    ),
                ),
            ),
            Night(3, "RETALIATION", "9–10 MAY 2025", 22, 0, 50 * 60.0,
                "Pakistan goes on the offensive against Indian air bases and the S-400. India defends and counter-strikes.",
                targets = mapOf(
                    "IN" to listOf(T("base", "SIALKOT"), T("radar", "GR-LAHORE"), T("base", "MURID")),
                    "PK" to listOf(T("base", "ADAMPUR"), T("base", "UDHAMPUR"), T("base", "PATHANKOT"), T("base", "BATHINDA"), T("base", "SIRSA"), T("sam", "SUDARSHAN")),
                ),
                ai = mapOf(
                    "IN" to AiPlan(
                        aew = listOf(aew("BATHINDA", 30.60, 75.85), aew("AGRA", 29.30, 76.60)),
                        caps = listOf(cap("ADAMPUR", 2, 31.90, 75.30), cap("HALWARA", 2, 31.20, 75.00), cap("SIRSA", 2, 29.90, 74.50), cap("SRINAGAR", 2, 33.50, 74.90), cap("AMBALA", 2, 32.30, 75.40, "AIR")),
                        strikes = listOf(StrikePlan(900.0, "AMBALA", 2, "STRIKE", listOf(T("base", "SIALKOT"), T("base", "MURID")))),
                        drones = listOf(DronePlan(300.0, "AMRITSAR", 6, "sead", lat = 31.40, lon = 74.10)),
                    ),
                    "PK" to AiPlan(
                        aew = listOf(aew("NUR KHAN", 32.75, 72.95), aew("MASROOR", 26.2, 69.0)),
                        caps = listOf(cap("MINHAS", 2, 32.30, 73.80), cap("MINHAS", 2, 31.60, 73.70)),
                        strikes = listOf(
                            StrikePlan(240.0, "RAFIQUI", 2, "STRIKE", listOf(T("sam", "SUDARSHAN"), T("base", "ADAMPUR"))),
                            StrikePlan(300.0, "PESHAWAR", 2, "STRIKE", listOf(T("base", "UDHAMPUR"), T("base", "PATHANKOT"))),
                            StrikePlan(420.0, "MUSHAF", 2, "STRIKE", listOf(T("base", "PATHANKOT"), T("base", "PATHANKOT"))),
                            StrikePlan(480.0, "RAFIQUI", 2, "STRIKE", listOf(T("base", "BATHINDA"), T("base", "SIRSA"))),
                        ),
                        drones = listOf(DronePlan(150.0, "SIALKOT", 8, "attack", listOf(T("base", "PATHANKOT"), T("base", "UDHAMPUR")))),
                    ),
                ),
            ),
            Night(4, "AIRBASE STRIKES", "10 MAY 2025", 0, 0, 50 * 60.0,
                "India strikes Pakistani air bases with BrahMos and SCALP; Pakistan hits back at Indian bases. A ceasefire is coming.",
                targets = mapOf(
                    "IN" to listOf(T("base", "NUR KHAN"), T("base", "RAFIQUI"), T("base", "MURID"), T("base", "MUSHAF"), T("base", "JACOBABAD"), T("base", "SUKKUR"), T("base", "BHOLARI")),
                    "PK" to listOf(T("base", "ADAMPUR"), T("base", "PATHANKOT"), T("sam", "SUDARSHAN")),
                ),
                ai = mapOf(
                    "IN" to AiPlan(
                        aew = listOf(aew("BATHINDA", 30.60, 75.85), aew("AGRA", 28.80, 75.90)),
                        caps = listOf(cap("ADAMPUR", 2, 31.90, 75.30), cap("SRINAGAR", 2, 33.60, 74.90), cap("AMBALA", 2, 32.20, 75.30, "AIR")),
                        strikes = listOf(
                            StrikePlan(180.0, "HALWARA", 4, "STRIKE", listOf(T("base", "NUR KHAN"), T("base", "MURID"), T("base", "MUSHAF"), T("base", "RAFIQUI"))),
                            StrikePlan(240.0, "JODHPUR", 4, "STRIKE", listOf(T("base", "JACOBABAD"), T("base", "SUKKUR"), T("base", "BHOLARI"), T("base", "JACOBABAD"))),
                            StrikePlan(300.0, "AMBALA", 2, "STRIKE", listOf(T("base", "RAFIQUI"), T("base", "MUSHAF"))),
                        ),
                        drones = listOf(DronePlan(120.0, "AMRITSAR", 6, "sead", lat = 31.50, lon = 74.20)),
                    ),
                    "PK" to AiPlan(
                        aew = listOf(aew("NUR KHAN", 32.75, 72.95), aew("MASROOR", 26.2, 69.0)),
                        caps = listOf(cap("MINHAS", 2, 32.60, 73.90), cap("MINHAS", 2, 31.70, 73.80), cap("RAFIQUI", 2, 30.40, 73.10), cap("JACOBABAD", 2, 28.20, 70.00), cap("MUSHAF", 2, 33.30, 73.60)),
                        strikes = listOf(StrikePlan(600.0, "PESHAWAR", 2, "STRIKE", listOf(T("base", "ADAMPUR"), T("sam", "SUDARSHAN")))),
                        drones = listOf(DronePlan(300.0, "SIALKOT", 8, "attack", listOf(T("base", "PATHANKOT")))),
                    ),
                ),
                ceasefireAt = 45 * 60.0,
            ),
            // A hypothetical scenario (not part of the campaign): India's pre-emptive deep strike.
            Night(5, "OPERATION DEEP STRIKE", "HYPOTHETICAL", 1, 0, 100 * 60.0,
                "India plans a pre-emptive strike on Mushaf air base at Sargodha, deep inside Pakistan behind layered air defences. Drain the SAMs with drone swarms, pull Pakistan's fighters away with a diversion, slip in low, hit the base and get home.",
                targets = mapOf("IN" to listOf(T("base", "MUSHAF")), "PK" to listOf(T("base", "AMRITSAR"), T("base", "ADAMPUR"))),
                ai = mapOf(
                    "IN" to AiPlan(
                        aew = listOf(aew("BATHINDA", 30.70, 75.40)),
                        caps = listOf(cap("ADAMPUR", 2, 31.80, 75.20), cap("HALWARA", 2, 31.00, 74.90)),
                        drones = listOf(DronePlan(120.0, "AMRITSAR", 100, "attack", listOf(T("base", "MUSHAF")), type = "SWARM")),
                        strikes = listOf(StrikePlan(2100.0, "AMBALA", 2, "STRIKE", listOf(T("base", "MUSHAF"), T("base", "MUSHAF")))),
                    ),
                    "PK" to AiPlan(
                        aew = listOf(aew("NUR KHAN", 32.75, 72.95)),
                        caps = listOf(cap("MUSHAF", 2, 32.25, 73.00), cap("RAFIQUI", 2, 31.40, 73.70), cap("MINHAS", 2, 33.00, 73.60)),
                    ),
                ),
                tag = "SCENARIO", scenario = true,
                // Scenario-only forces: a big Indian drone swarm stock, thinner Pakistani fighter
                // bases, and layered SAMs along the road from the border to Sargodha.
                pools = mapOf("AMRITSAR" to mapOf("SWARM" to 150)),
                counts = mapOf("MUSHAF" to 6, "RAFIQUI" to 8, "MINHAS" to 8),
                sams = listOf(sam("PK", "LY80-GJW", "LY80", 32.16, 74.19), sam("PK", "LY80-HFZ", "LY80", 32.07, 73.69), sam("PK", "HQ9-SGD", "HQ9", 32.10, 72.55)),
            ),
        )
    }

    /** Nights that make up the campaign (scenarios are separate). */
    val CAMPAIGN_NIGHTS get() = NIGHTS.count { !it.scenario }
}
