package com.killchain.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/**
 * Offline sound synthesis: the Web Audio patches of js/audio.js rendered to PCM, so the Android app
 * can play them through SoundPool without shipping any audio files.
 */
object Synth {
    const val RATE = 22050

    class Buf(val seconds: Double) {
        val data = FloatArray((seconds * RATE).toInt() + 1)
        fun add(other: FloatArray, at: Double = 0.0, gain: Double = 1.0) {
            val off = (at * RATE).toInt()
            for (i in other.indices) { val j = off + i; if (j in data.indices) data[j] += (other[i] * gain).toFloat() }
        }
    }

    /** Web Audio's exponentialRampToValueAtTime between two points. */
    private fun expRamp(v0: Double, v1: Double, t0: Double, t1: Double, t: Double): Double {
        if (t <= t0) return v0
        if (t >= t1) return v1
        return v0 * Math.pow(v1 / v0, (t - t0) / (t1 - t0))
    }

    /** A piecewise envelope of exponential ramps: points (time, value). */
    class Env(vararg pts: Pair<Double, Double>) {
        private val p = pts.toList()
        fun at(t: Double): Double {
            if (t <= p.first().first) return p.first().second
            for (i in 0 until p.size - 1) {
                val (t0, v0) = p[i]; val (t1, v1) = p[i + 1]
                if (t <= t1) return if (t1 == t0) v1 else expRamp(v0, v1, t0, t1, t)
            }
            return p.last().second
        }
    }
    /** setValueAtTime(0.0001, 0); ramp to peak at a; ramp down to 0.0001 at a + dec. */
    fun env(a: Double, peak: Double, dec: Double) = Env(0.0 to 0.0001, a to peak, a + dec to 0.0001)

    private val rng = java.util.Random(7)
    fun noise(seconds: Double): FloatArray = FloatArray((seconds * RATE).toInt()) { (rng.nextFloat() * 2 - 1) }

    enum class Wave { SINE, SQUARE, TRIANGLE, SAW }
    /** An oscillator with a frequency envelope (and optional vibrato in Hz). */
    fun osc(wave: Wave, seconds: Double, freq: (Double) -> Double, vibHz: Double = 0.0, vibDepth: Double = 0.0): FloatArray {
        val n = (seconds * RATE).toInt()
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val f = freq(t) + if (vibHz > 0) vibDepth * sin(2 * PI * vibHz * t) else 0.0
            ph += f / RATE
            ph -= Math.floor(ph)
            out[i] = when (wave) {
                Wave.SINE -> sin(2 * PI * ph)
                Wave.SQUARE -> if (ph < 0.5) 1.0 else -1.0
                Wave.TRIANGLE -> 4 * Math.abs(ph - 0.5) - 1
                Wave.SAW -> 2 * ph - 1
            }.toFloat()
        }
        return out
    }

    enum class Filter { LOWPASS, BANDPASS }
    /** RBJ biquad with a time-varying cutoff (like a Web Audio BiquadFilterNode). */
    fun filter(x: FloatArray, type: Filter, freq: (Double) -> Double, q: Double): FloatArray {
        val y = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val t = i.toDouble() / RATE
            val f = freq(t).coerceIn(10.0, RATE * 0.45)
            val w0 = 2 * PI * f / RATE
            val cw = Math.cos(w0); val sw = Math.sin(w0)
            val alpha = sw / (2 * q)
            val b0: Double; val b1: Double; val b2: Double
            when (type) {
                Filter.LOWPASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
                Filter.BANDPASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha }
            }
            val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
            val xi = x[i].toDouble()
            val yi = (b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
            y[i] = yi.toFloat()
        }
        return y
    }

    fun gain(x: FloatArray, e: Env): FloatArray = FloatArray(x.size) { (x[it] * e.at(it.toDouble() / RATE)).toFloat() }

    /** tone(): an oscillator with a 5 ms attack, held, then a 10 ms release (as in audio.js). */
    fun tone(wave: Wave, freq: Double, dur: Double, peak: Double): FloatArray =
        gain(osc(wave, dur + 0.02, { freq }), Env(0.0 to 0.0001, 0.005 to peak, dur - 0.01 to peak, dur to 0.0001))

    // ---------- the game's sounds ----------
    fun launch(v: Double = 1.0) = Buf(1.9).apply {
        add(gain(filter(noise(1.8), Filter.BANDPASS, { t -> expRamp(2600.0, 500.0, 0.0, 1.5, t) }, 1.2), env(0.03, 0.35 * v, 1.5)))
        add(gain(filter(noise(1.2), Filter.LOWPASS, { 180.0 }, 0.707), env(0.02, 0.5 * v, 1.0)))
    }.data
    fun lock(v: Double = 1.0) = Buf(0.3).apply {
        add(tone(Wave.SQUARE, 1400.0, 0.07, 0.05 * v)); add(tone(Wave.SQUARE, 1400.0, 0.07, 0.05 * v), 0.13)
    }.data
    fun rwr(v: Double = 1.0) = Buf(1.0).apply {
        for (i in 0 until 12) add(tone(Wave.SQUARE, if (i % 2 == 1) 1250.0 else 980.0, 0.06, 0.045 * v), i * 0.075)
    }.data
    fun active(v: Double = 1.0) = Buf(1.25).apply {
        add(gain(osc(Wave.SQUARE, 1.2, { 1150.0 }, 14.0, 350.0), Env(0.0 to 0.0001, 0.02 to 0.05 * v, 1.0 to 0.05 * v, 1.15 to 0.0001)))
    }.data
    fun hit(v: Double = 1.0) = Buf(1.7).apply {
        add(gain(filter(noise(1.6), Filter.LOWPASS, { t -> expRamp(2200.0, 180.0, 0.0, 1.2, t) }, 0.707), env(0.004, 0.45 * v, 1.4)))
        add(gain(osc(Wave.SINE, 0.7, { t -> expRamp(90.0, 35.0, 0.0, 0.5, t) }), env(0.005, 0.5 * v, 0.6)))
    }.data
    fun impact(v: Double = 1.0) = Buf(3.1).apply {
        add(gain(filter(noise(3.0), Filter.LOWPASS, { t -> expRamp(1100.0, 90.0, 0.0, 2.4, t) }, 0.707), env(0.01, 0.6 * v, 2.6)))
        add(gain(osc(Wave.SINE, 1.5, { t -> expRamp(60.0, 25.0, 0.0, 1.2, t) }), env(0.01, 0.8 * v, 1.3)))
    }.data
    fun takeoff(v: Double = 1.0) = Buf(4.6).apply {
        val dur = 4.5
        val lp: (Double) -> Double = { t -> if (t < 2.2) expRamp(250.0, 2400.0, 0.0, 2.2, t) else expRamp(2400.0, 700.0, 2.2, dur, t) }
        add(gain(filter(noise(dur), Filter.LOWPASS, lp, 0.8), Env(0.0 to 0.0001, 1.6 to 0.3 * v, dur to 0.0001)))
        add(gain(filter(osc(Wave.SAW, dur, { t -> expRamp(260.0, 1100.0, 0.0, 2.5, t) }), Filter.BANDPASS, { 1400.0 }, 2.0),
            Env(0.0 to 0.0001, 1.5 to 0.025 * v, dur to 0.0001)))
    }.data
    fun alarm(v: Double = 1.0) = Buf(1.0).apply {
        for (i in 0 until 4) add(tone(Wave.TRIANGLE, if (i % 2 == 1) 660.0 else 880.0, 0.22, 0.07 * v), i * 0.24)
    }.data
    fun loss(v: Double = 1.0) = Buf(1.8).apply {
        add(hit(v))
        add(gain(osc(Wave.TRIANGLE, 1.1, { t -> expRamp(440.0, 180.0, 0.0, 0.9, t) }), env(0.02, 0.05 * v, 0.9)), 0.3)
    }.data
    fun intel(v: Double = 1.0) = Buf(0.5).apply {
        doubleArrayOf(620.0, 780.0, 1040.0).forEachIndexed { i, f -> add(tone(Wave.SINE, f, 0.09, 0.05 * v), i * 0.11) }
    }.data
    fun click(v: Double = 1.0) = tone(Wave.SINE, 2100.0, 0.025, 0.03 * v)

    /** Ambience: a sonar ping with a long, darkening echo (0.42 s delay, 0.45 feedback). */
    fun ping(freq: Double = 1180.0, peak: Double = 0.05, dec: Double = 2.2): FloatArray {
        val dry = gain(osc(Wave.SINE, dec + 0.05, { t -> expRamp(freq, freq * 0.985, 0.0, dec, t) }), env(0.012, peak, dec))
        val out = Buf(dec + 4.5)
        out.add(dry)
        // Feedback delay through a one-pole low-pass "damp" at 2.2 kHz, mixed in at 0.5.
        val delay = (0.42 * RATE).toInt()
        val wet = FloatArray(out.data.size)
        val a = exp(-2 * PI * 2200 / RATE)
        var lp = 0.0
        val line = FloatArray(out.data.size)
        for (i in wet.indices) {
            val inp = (if (i < dry.size) dry[i] else 0f) + (if (i >= delay) line[i - delay] * 0.45f else 0f)
            lp = (1 - a) * inp + a * lp
            line[i] = lp.toFloat()
            wet[i] = if (i >= delay) line[i - delay] else 0f
        }
        out.add(wet, gain = 0.5)
        return out.data
    }

    /** Ambience: the low pad (55 / 55.4 / 82.5 Hz) with a slow swell, as a seamless 20 s loop. */
    fun pad(): FloatArray {
        val sec = 20.0
        val n = (sec * RATE).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val swell = 0.02 + 0.012 * sin(2 * PI * 0.05 * t)
            (swell * (sin(2 * PI * 55 * t) + sin(2 * PI * 55 * t * (55.4 / 55)) * 1.0 + 0.35 * sin(2 * PI * 82.5 * t))).toFloat()
        }
    }

    /** Master gain 0.7 and a soft limiter, then 16-bit mono WAV bytes. */
    fun wav(x: FloatArray, master: Double = 0.7): ByteArray {
        val pcm = ByteArray(x.size * 2)
        for (i in x.indices) {
            val v = Math.tanh(x[i] * master * 1.6) / 1.6   // gentle compressor, as the Web Audio patch
            val s = (v * 32767).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (s and 0xff).toByte(); pcm[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { for (k in 0..3) out.write((v shr (8 * k)) and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /** All the sounds the game plays, by name. */
    fun all(): Map<String, FloatArray> = linkedMapOf(
        "launch" to launch(), "lock" to lock(), "rwr" to rwr(), "active" to active(), "hit" to hit(), "impact" to impact(),
        "takeoff" to takeoff(0.8), "alarm" to alarm(), "loss" to loss(), "intel" to intel(), "click" to click(),
        "ping" to ping(), "ping2" to ping(1180 * 0.93, 0.012, 0.7), "ping3" to ping(1180 * 1.03, 0.012, 0.7), "pad" to pad(),
    )

    @Suppress("unused") private fun unused() = ln(1.0) + tan(0.0)
}
