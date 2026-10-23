package com.killchain.core

import java.io.DataInputStream
import com.killchain.core.JsMath.asin
import com.killchain.core.JsMath.atan
import com.killchain.core.JsMath.atan2
import com.killchain.core.JsMath.cos
import com.killchain.core.JsMath.hypot
import com.killchain.core.JsMath.sin
import kotlin.math.max
import kotlin.math.min

/** A point in projected kilometres (x east, y south). */
data class Pt(override val x: Double, override val y: Double) : XY

/**
 * Map geometry. Natural Earth outlines (mapdata.bin, exported from js/mapdata.js) projected with a
 * stereographic projection centred on 30N 73E: conformal (shapes stay true) and within ~2.5% on
 * distance out to 2000 km, so the game can treat projected km as real km.
 */
object Geo {
    private const val R = 6371.0
    private const val D2R = Math.PI / 180
    private val LAT0 = 30 * D2R
    private val LON0 = 73 * D2R
    private val sin0 = sin(LAT0)
    private val cos0 = cos(LAT0)

    fun project(lat: Double, lon: Double): Pt {
        val p = lat * D2R
        val l = lon * D2R - LON0
        val cp = cos(p); val sp = sin(p); val cl = cos(l)
        val k = 2 * R / (1 + sin0 * sp + cos0 * cp * cl)
        return Pt(k * cp * sin(l), -k * (cos0 * sp - sin0 * cp * cl))
    }

    fun unproject(x: Double, y0: Double): Pair<Double, Double> {
        val y = -y0
        val rho = hypot(x, y)
        if (rho < 1e-9) return 30.0 to 73.0
        val c = 2 * atan(rho / (2 * R))
        val sc = sin(c); val cc = cos(c)
        val lat = asin(cc * sin0 + y * sc * cos0 / rho)
        val lon = LON0 + atan2(x * sc, rho * cos0 * cc - y * sin0 * sc)
        return lat / D2R to lon / D2R
    }

    /** Flat [lon, lat, ...] to flat [x, y, ...] in single precision (as the JS Float32Array). */
    fun projFlat(a: DoubleArray): FloatArray {
        val out = FloatArray(a.size)
        var i = 0
        while (i < a.size) {
            val p = project(a[i + 1], a[i])
            out[i] = p.x.toFloat(); out[i + 1] = p.y.toFloat()
            i += 2
        }
        return out
    }

    class Country(val name: String, val rings: List<FloatArray>)
    class City(val name: String, val side: String, val rank: Int, val x: Double, val y: Double)
    class Label(val text: String, val kind: String, val rank: Int, val x: Double, val y: Double)

    lateinit var bbox: DoubleArray; private set
    lateinit var countries: List<Country>; private set
    lateinit var borders: List<FloatArray>; private set
    lateinit var coasts: List<FloatArray>; private set
    lateinit var rivers: List<FloatArray>; private set
    lateinit var lakes: List<FloatArray>; private set
    /** International border / working boundary, then the Line of Control (for drawing). */
    lateinit var ib: FloatArray; private set
    lateinit var loc: FloatArray; private set
    /** Coarser copy of the frontier for game logic (side-of-border tests run often). */
    private lateinit var fx: DoubleArray
    private lateinit var fy: DoubleArray
    private var loaded = false

    /** Load the map. Reads mapdata.bin from the classpath unless a stream is given. */
    @Synchronized
    fun load(input: java.io.InputStream? = null) {
        if (loaded) return
        val src = input ?: Geo::class.java.getResourceAsStream("/mapdata.bin") ?: error("mapdata.bin missing")
        DataInputStream(src.buffered()).use { d ->
            val magic = ByteArray(4); d.readFully(magic)
            require(String(magic) == "KCM1") { "bad map file" }
            fun line(): DoubleArray = DoubleArray(d.readInt()) { d.readDouble() }
            fun lines(): List<DoubleArray> = List(d.readInt()) { line() }
            bbox = DoubleArray(4) { d.readDouble() }
            countries = List(d.readInt()) {
                val n = ByteArray(d.readInt()); d.readFully(n)
                Country(String(n, Charsets.UTF_8), lines().map(::projFlat))
            }
            borders = lines().map(::projFlat)
            coasts = lines().map(::projFlat)
            rivers = lines().map(::projFlat)
            lakes = lines().map(::projFlat)
            val frontierFlat = projFlat(line())
            val locStart = d.readInt()
            ib = frontierFlat.copyOfRange(0, (locStart + 1) * 2)
            loc = frontierFlat.copyOfRange(locStart * 2, frontierFlat.size)
            val coarse = projFlat(line())
            fx = DoubleArray(coarse.size / 2) { coarse[it * 2].toDouble() }
            fy = DoubleArray(coarse.size / 2) { coarse[it * 2 + 1].toDouble() }
        }
        loaded = true
    }

    val cities: List<City> by lazy {
        listOf(
            // Pakistan
            c("Lahore", 31.55, 74.34, "PK", 1), c("Islamabad", 33.69, 73.05, "PK", 1), c("Karachi", 24.86, 67.01, "PK", 1),
            c("Multan", 30.20, 71.47, "PK", 2), c("Faisalabad", 31.42, 73.08, "PK", 2), c("Peshawar", 34.01, 71.58, "PK", 2),
            c("Quetta", 30.18, 66.98, "PK", 2), c("Hyderabad", 25.40, 68.37, "PK", 3), c("Gujranwala", 32.16, 74.19, "PK", 3),
            c("Sialkot", 32.49, 74.53, "PK", 3), c("Bahawalpur", 29.40, 71.68, "PK", 3), c("Sukkur", 27.70, 68.86, "PK", 3),
            c("Gwadar", 25.13, 62.32, "PK", 3), c("Gilgit", 35.92, 74.31, "PK", 3),
            // India
            c("New Delhi", 28.61, 77.21, "IN", 1), c("Mumbai", 19.08, 72.88, "IN", 1), c("Kolkata", 22.57, 88.36, "IN", 1),
            c("Chennai", 13.08, 80.27, "IN", 1), c("Bengaluru", 12.97, 77.59, "IN", 1), c("Hyderabad", 17.39, 78.49, "IN", 2),
            c("Ahmedabad", 23.02, 72.57, "IN", 2), c("Pune", 18.52, 73.86, "IN", 2), c("Jaipur", 26.91, 75.79, "IN", 2),
            c("Lucknow", 26.85, 80.95, "IN", 2), c("Nagpur", 21.15, 79.09, "IN", 2), c("Bhopal", 23.26, 77.41, "IN", 3),
            c("Amritsar", 31.63, 74.87, "IN", 3), c("Jammu", 32.73, 74.86, "IN", 3), c("Srinagar", 34.08, 74.80, "IN", 2),
            c("Chandigarh", 30.73, 76.78, "IN", 3), c("Jodhpur", 26.24, 73.02, "IN", 3), c("Bikaner", 28.02, 73.31, "IN", 3),
            c("Ludhiana", 30.90, 75.85, "IN", 3), c("Leh", 34.15, 77.58, "IN", 3), c("Guwahati", 26.14, 91.74, "IN", 2),
            c("Patna", 25.59, 85.14, "IN", 3), c("Kochi", 9.93, 76.27, "IN", 3), c("Visakhapatnam", 17.69, 83.22, "IN", 3),
            c("Bhubaneswar", 20.30, 85.82, "IN", 3), c("Goa", 15.50, 73.83, "IN", 3), c("Thiruvananthapuram", 8.52, 76.94, "IN", 3),
            // Neighbours
            c("Kabul", 34.53, 69.17, "X", 1), c("Kandahar", 31.61, 65.71, "X", 3), c("Kathmandu", 27.72, 85.32, "X", 1),
            c("Dhaka", 23.81, 90.41, "X", 1), c("Thimphu", 27.47, 89.64, "X", 2), c("Colombo", 6.93, 79.86, "X", 1),
            c("Lhasa", 29.65, 91.11, "X", 2), c("Kashgar", 39.47, 75.99, "X", 2), c("Zahedan", 29.50, 60.86, "X", 3),
            c("Dushanbe", 38.56, 68.79, "X", 2), c("Muscat", 23.59, 58.41, "X", 2), c("Yangon", 16.84, 96.17, "X", 2),
            c("Mandalay", 21.96, 96.09, "X", 3), c("Male", 4.18, 73.51, "X", 3),
        )
    }

    val labels: List<Label> by lazy {
        listOf(
            l("PAKISTAN", 28.6, 68.6, "PK", 1), l("INDIA", 22.5, 78.5, "IN", 1),
            l("AFGHANISTAN", 33.6, 65.6, "X", 2), l("IRAN", 31.0, 57.5, "X", 2), l("CHINA", 34.5, 86.0, "X", 2),
            l("NEPAL", 28.25, 83.9, "X", 3), l("BHUTAN", 27.7, 90.4, "X", 3), l("BANGLADESH", 24.0, 90.2, "X", 3),
            l("MYANMAR", 21.5, 95.6, "X", 3), l("SRI LANKA", 7.6, 80.7, "X", 3), l("TAJIKISTAN", 38.7, 71.0, "X", 3),
            l("OMAN", 21.0, 56.8, "X", 3),
            l("ARABIAN SEA", 16.0, 65.0, "SEA", 2), l("BAY OF BENGAL", 15.0, 88.0, "SEA", 2), l("INDIAN OCEAN", 4.0, 80.0, "SEA", 2),
            l("TIBETAN PLATEAU", 32.4, 84.0, "TER", 3), l("THAR DESERT", 27.0, 71.6, "TER", 3), l("HIMALAYA", 30.2, 80.6, "TER", 3),
            l("HINDU KUSH", 35.6, 70.6, "TER", 3), l("KARAKORAM", 35.9, 76.4, "TER", 3), l("DECCAN PLATEAU", 17.0, 77.0, "TER", 3),
            l("LINE OF CONTROL", 34.2, 73.55, "LOC", 4),
        )
    }

    private fun c(n: String, la: Double, lo: Double, side: String, rank: Int) = project(la, lo).let { City(n, side, rank, it.x, it.y) }
    private fun l(t: String, la: Double, lo: Double, kind: String, rank: Int) = project(la, lo).let { Label(t, kind, rank, it.x, it.y) }

    /**
     * Which side of the India-Pakistan frontier a point is on: "PK" (west) or "IN" (east),
     * decided by the nearest frontier segment.
     */
    fun sideOf(x: Double, y: Double): String {
        var best = Double.POSITIVE_INFINITY
        var cross = 0.0
        for (i in 0 until fx.size - 1) {
            val ax = fx[i]; val ay = fy[i]
            val dx = fx[i + 1] - ax; val dy = fy[i + 1] - ay
            var t = ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)
            t = max(0.0, min(1.0, t))
            val ex = x - ax - t * dx; val ey = y - ay - t * dy
            val d = ex * ex + ey * ey
            if (d < best) { best = d; cross = dx * (y - ay) - dy * (x - ax) }
        }
        return if (cross < 0) "PK" else "IN"
    }

    /** Distance (km) from a point to the frontier. */
    fun frontierDist(x: Double, y: Double): Double {
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until fx.size - 1) {
            val ax = fx[i]; val ay = fy[i]
            val dx = fx[i + 1] - ax; val dy = fy[i + 1] - ay
            var t = ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)
            t = max(0.0, min(1.0, t))
            best = min(best, hypot(x - ax - t * dx, y - ay - t * dy))
        }
        return best
    }
}
