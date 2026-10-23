package com.killchain.core

/**
 * Runs nights AI-vs-AI (or with an idle player side) without a screen, like tools/headless.js.
 *   run <runs> <night> [seed]          averages, like the JS tool
 *   events <night> <seed> [player]     every event of one night, for comparing with the JS version
 */
fun main(args: Array<String>) {
    Geo.load()
    val mode = args.getOrNull(0) ?: "run"
    if (mode == "events") {
        val night = Data.NIGHTS[(args.getOrNull(1)?.toInt() ?: 1) - 1]
        val seed = args.getOrNull(2)?.toLong() ?: 1
        val player = args.getOrNull(3)?.takeIf { it != "none" }
        val sim = Sim(SimConfig(night.label + ": " + night.title, night.startH, night.startM, night.duration, night), player, seed)
        var last = 0
        while (!sim.over) {
            sim.update(5.0)
            for (e in sim.events) if (e.n > last) { println("${sim.clock(e.t)} ${e.side} ${e.text}"); last = e.n }
        }
        val s = sim.summary()
        println("points IN ${s.sides["IN"]!!.points} PK ${s.sides["PK"]!!.points} lost IN ${s.sides["IN"]!!.score.lost} PK ${s.sides["PK"]!!.score.lost} | ${sim.endReason}")
        return
    }
    val runs = args.getOrNull(1)?.toInt() ?: 1
    val night = Data.NIGHTS[(args.getOrNull(2)?.toInt() ?: 1) - 1]
    val seed0 = args.getOrNull(3)?.toLong() ?: 1
    var pIN = 0.0; var pPK = 0.0; var lIN = 0.0; var lPK = 0.0
    val t0 = System.currentTimeMillis()
    for (r in 0 until runs) {
        val sim = Sim(SimConfig(night.title, night.startH, night.startM, night.duration, night), null, seed0 + r)
        while (!sim.over) sim.update(5.0)
        val s = sim.summary()
        pIN += s.sides["IN"]!!.points; pPK += s.sides["PK"]!!.points; lIN += s.sides["IN"]!!.score.lost; lPK += s.sides["PK"]!!.score.lost
        println("run $r: lost IN ${s.sides["IN"]!!.score.lost} PK ${s.sides["PK"]!!.score.lost} | points IN ${s.sides["IN"]!!.points} PK ${s.sides["PK"]!!.points} ${sim.endReason}")
    }
    println("avg lost ${(lIN / runs).fixed(1)} ${(lPK / runs).fixed(1)} points ${(pIN / runs).fixed(1)} ${(pPK / runs).fixed(1)} (${System.currentTimeMillis() - t0} ms)")
}
