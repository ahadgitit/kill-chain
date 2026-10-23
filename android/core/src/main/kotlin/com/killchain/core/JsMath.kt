package com.killchain.core

/**
 * Maths that gives bit-identical results to the JavaScript version of the game (V8), so the
 * Kotlin simulation can be checked event by event against it with the same random seed.
 * V8 uses fdlibm for the transcendental functions, as java.lang.StrictMath does; Math.hypot is
 * V8's own algorithm (scaled sum with Kahan compensation), reproduced here.
 */
object JsMath {
    fun sin(a: Double) = StrictMath.sin(a)
    fun cos(a: Double) = StrictMath.cos(a)
    fun tan(a: Double) = StrictMath.tan(a)
    fun asin(a: Double) = StrictMath.asin(a)
    fun atan(a: Double) = StrictMath.atan(a)
    fun atan2(y: Double, x: Double) = StrictMath.atan2(y, x)
    fun log(a: Double) = StrictMath.log(a)
    fun exp(a: Double) = StrictMath.exp(a)
    fun pow(a: Double, b: Double) = StrictMath.pow(a, b)
    fun sqrt(a: Double) = StrictMath.sqrt(a)

    fun hypot(a: Double, b: Double): Double {
        val x = Math.abs(a); val y = Math.abs(b)
        if (x == Double.POSITIVE_INFINITY || y == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
        if (x.isNaN() || y.isNaN()) return Double.NaN
        val max = if (x > y) x else y
        if (max == 0.0) return 0.0
        // V8's Kahan-compensated loop over two values, unrolled. After the first value the
        // compensation term is exactly zero, so the sum is simply n1² + n2² in that order.
        val n1 = x / max
        val n2 = y / max
        return StrictMath.sqrt(n1 * n1 + n2 * n2) * max
    }

    /** JS Math.round: rounds half up (toward +infinity). */
    fun round(a: Double): Double = Math.floor(a + 0.5)
}
