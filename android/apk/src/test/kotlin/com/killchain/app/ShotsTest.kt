package com.killchain.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.killchain.core.Data
import com.killchain.core.Target
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

/** Drives the real app through its screens and saves screenshots (build/shots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = "w780dp-h360dp-land-xhdpi")
class ShotsTest {
    private lateinit var act: MainActivity
    private val game get() = act.game

    private fun launch(onboarded: Boolean) {
        GameView.autoRun = false
        val ctl = Robolectric.buildActivity(MainActivity::class.java)
        ctl.get().getSharedPreferences("kc", 0).edit().putBoolean("onboarded", onboarded).commit()
        ctl.setup()
        act = ctl.get()
        frames(10)
    }
    /** Runs the game loop: [n] frames of 1/30 s. */
    private fun frames(n: Int, dt: Double = 1 / 30.0) {
        for (i in 0 until n) {
            game.frame(dt)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((dt * 1000).toLong()))
        }
    }
    /** Lets the background map render finish. */
    private fun settle() {
        frames(8)
        Thread.sleep(1500)
        frames(4)
    }
    private fun shot(name: String) {
        val v: View = act.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val dir = File(System.getProperty("kc.shots")!!).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun onboardingAndMenu() {
        launch(false)
        frames(20); shot("01-guide-1")
        game.screens.onboarding(2); frames(20); shot("02-guide-3")
        game.screens.onboarding(4); frames(20); shot("03-guide-5")
        game.screens.menuHome(); frames(30); settle(); shot("04-menu")
        game.screens.help(null); frames(5); shot("05-help")
        game.screens.about(); frames(5); shot("06-about")
    }

    @Test fun nightOne() {
        launch(true)
        game.startNight(0, "IN", false)
        frames(10); settle(); shot("10-briefing")
        game.screens.hideAll(); game.begin(0, "IN")
        frames(90); settle(); shot("11-start")
        val s = game.sim!!
        // Plan a strike from AMBALA on MURIDKE with one escort, as a player would.
        val amb = s.base("AMBALA")!!
        game.select(amb); frames(10); shot("12-base-panel")
        game.st.plan = Plan("strike", base = "AMBALA", type = amb.pools.keys.first(), n = 2)
        game.renderPanel(true)
        val tgt = s.sites.first { it.name == "MURIDKE" }
        val p = game.R.toScreen(tgt.x, tgt.y)
        game.onTap(p.x, p.y); frames(5); shot("13-strike-plan")
        // Launch, then run the night at speed with a look every few minutes.
        game.select(null)
        Panel(game).launchPlan(s)
        game.setSpeed(40)
        var k = 0
        while (!s.over && s.t < 30 * 60) { frames(30); k++ }
        game.setSpeed(0)
        val air = s.units.firstOrNull { it.kind == "air" && it.state == "air" && it.side == "IN" && it.d.role == "fighter" }
        if (air != null) { game.select(air); game.R.flyTo(View2(air.x, air.y, 1.2), 10.0) }
        frames(10); settle(); shot("14-mid-night")
        game.toggleDrawer("log"); frames(3); shot("15-log")
        game.toggleDrawer(null)
        game.pauseMenu(); frames(3); shot("16-pause")
        game.screens.hideAll()
        game.setSpeed(40)
        while (!s.over) frames(60)
        frames(30); shot("17-debrief")
        game.enterReplay(); frames(200); settle(); shot("18-replay")
        assertTrue(s.over)
    }

    @Test fun training() {
        launch(true)
        game.training!!.begin()
        frames(60); settle(); shot("20-training-intro")
        // Press START on the coach card.
        (game.coach.getChildAt(2) as android.widget.LinearLayout).getChildAt(0).performClick()
        var n = 0
        while (game.coach.visibility != View.VISIBLE && n < 4000) { frames(1); n++ }
        frames(2); shot("21-training-warning")
        (game.coach.getChildAt(2) as android.widget.LinearLayout).getChildAt(0).performClick()
        n = 0
        while (game.coach.visibility != View.VISIBLE && n < 8000) { frames(1); n++ }
        frames(2); shot("22-training-result")
    }

    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    @Test fun tablet() {
        launch(true)
        game.startNight(4, "IN", false)
        game.screens.hideAll(); game.begin(4, "IN")
        game.setSpeed(15)
        frames(600); game.setSpeed(0); settle(); shot("30-tablet-deep-strike")
    }
}
