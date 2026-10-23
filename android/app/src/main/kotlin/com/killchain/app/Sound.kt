package com.killchain.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.killchain.core.Synth
import java.io.File

/**
 * Game audio. Every sound is synthesised in code (com.killchain.core.Synth, a port of the Web
 * Audio patches) into WAV files on first launch, then played through SoundPool. The ambience
 * is a low pad loop plus a sonar ping every 4.5 s with an occasional faint return.
 */
class Sound(private val ctx: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val last = HashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())
    private var padStream = 0
    private var ambient = false
    private val rnd = java.util.Random()
    private val prefs = ctx.getSharedPreferences("kc", Context.MODE_PRIVATE)
    var muted = prefs.getBoolean("muted", false); private set

    private val gap = mapOf("launch" to 220, "lock" to 400, "rwr" to 1300, "active" to 1300, "hit" to 260, "impact" to 380,
        "takeoff" to 1500, "alarm" to 3500, "loss" to 900, "click" to 40, "intel" to 2500)

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) { loaded.add(id); if (ambient && id == ids["pad"]) startPad() } }
        Thread {
            val dir = File(ctx.filesDir, "sfx-v1").apply { mkdirs() }
            val names = listOf("click", "launch", "lock", "rwr", "active", "hit", "impact", "alarm", "loss", "intel", "takeoff", "ping", "ping2", "ping3", "pad")
            val todo = names.filter { !File(dir, "$it.wav").exists() }
            if (todo.isNotEmpty()) {
                val all = Synth.all()
                for (n in todo) {
                    val tmp = File(dir, "$n.tmp")
                    tmp.writeBytes(Synth.wav(all.getValue(n)))
                    tmp.renameTo(File(dir, "$n.wav"))
                }
            }
            main.post { for (n in names) ids[n] = pool.load(File(dir, "$n.wav").path, 1) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun play(name: String, vol: Double = 1.0) {
        if (muted) return
        val id = ids[name] ?: return
        if (id !in loaded) return
        val now = SystemClock.uptimeMillis()
        if (now - (last[name] ?: 0) < (gap[name] ?: 100)) return
        last[name] = now
        val v = vol.toFloat().coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, 1f)
    }

    fun setMuted(m: Boolean) {
        muted = m
        prefs.edit().putBoolean("muted", m).apply()
        if (m) stopPad() else if (ambient) startPad()
    }

    private val pinger = object : Runnable {
        override fun run() {
            if (!ambient) return
            if (!muted) {
                ids["ping"]?.takeIf { it in loaded }?.let { pool.play(it, 1f, 1f, 0, 0, 1f) }
                if (rnd.nextDouble() < 0.45) main.postDelayed({
                    if (ambient && !muted) {
                        val n = if (rnd.nextBoolean()) "ping2" else "ping3"
                        ids[n]?.takeIf { it in loaded }?.let { pool.play(it, 1f, 1f, 0, 0, 1f) }
                    }
                }, (1200 + rnd.nextInt(1600)).toLong())
            }
            main.postDelayed(this, 4500)
        }
    }

    private fun startPad() {
        if (muted || padStream != 0) return
        val id = ids["pad"] ?: return
        if (id !in loaded) return
        padStream = pool.play(id, 1f, 1f, 0, -1, 1f)
    }
    private fun stopPad() { if (padStream != 0) pool.stop(padStream); padStream = 0 }

    /** Ambience runs while the app is in the foreground. */
    fun resume() {
        if (ambient) return
        ambient = true
        startPad()
        main.postDelayed(pinger, 1500)
    }
    fun pause() {
        ambient = false
        main.removeCallbacks(pinger)
        stopPad()
        pool.autoPause()
    }
    fun wake() = pool.autoResume()
    fun release() { pause(); pool.release() }
}
