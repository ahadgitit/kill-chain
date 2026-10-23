package com.killchain.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.killchain.core.Geo

/** Kill Chain: the single activity. Everything on screen is built in code. */
class MainActivity : Activity() {
    lateinit var game: Game
    lateinit var view: GameView
    private lateinit var sound: Sound

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        assets.open("mapdata.bin").use { Geo.load(it) }
        val ui = Ui(this)
        sound = Sound(this)
        game = Game(this, ui, sound)
        game.screens = Screens(game)
        game.training = Training(game)
        game.build()
        view = GameView(this, game)
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(game.root, FrameLayout.LayoutParams(-1, -1))
        root.addView(game.screens.layer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            val cut = if (Build.VERSION.SDK_INT >= 28) ins.displayCutout else null
            if (cut != null) {
                game.setInsets(cut.safeInsetLeft, cut.safeInsetTop, cut.safeInsetRight, cut.safeInsetBottom)
                game.screens.layer.setPadding(cut.safeInsetLeft, 0, cut.safeInsetRight, 0)
            }
            ins
        }
        if (game.screens.onboarded) game.screens.menuHome() else game.screens.onboarding(0)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun onResume() { super.onResume(); immersive(); view.start(); sound.wake(); sound.resume() }
    override fun onPause() { super.onPause(); view.stop(); sound.pause(); game.pauseForBackground() }
    override fun onDestroy() { super.onDestroy(); sound.release(); game.R.release() }

    @Deprecated("Back is handled for every API level here")
    override fun onBackPressed() { if (!game.back()) @Suppress("DEPRECATION") super.onBackPressed() }
}
