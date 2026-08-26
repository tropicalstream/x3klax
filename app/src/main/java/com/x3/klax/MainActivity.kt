package com.x3.klax

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.x3.klax.audio.GameAudio
import com.x3.klax.game.Game
import com.x3.klax.input.SwipeControl
import com.x3.klax.input.TemplePad
import com.x3.klax.render.StereoRenderer
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var swipeCtl: SwipeControl
    private lateinit var audio: GameAudio
    private lateinit var game: Game
    private lateinit var settings: Settings
    private lateinit var pad: TemplePad
    private var thermalThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()

        settings = Settings(this)
        swipeCtl = SwipeControl(this)
        audio = GameAudio(this, settings)
        audio.init()
        audio.playTitleMusic()
        game = Game(settings, audio, swipeCtl)

        pad = TemplePad(
            onTap = { game.tap() },
            onDoubleTap = { game.doubleTap() },
            onSwipe = { steps -> game.swipe(steps) },
            onDrag = { px -> swipeCtl.onDrag(px, settings.swipeSensMultiplier) },
            onStrokeEnd = { px -> swipeCtl.onStrokeEnd(px) }
        )

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(StereoRenderer(game))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)

        // Immersive fullscreen (field guide §4)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        startThermalReader()
    }

    /**
     * Field guide §5.1: the RayNeo runtime sometimes NPEs in
     * FFalconXRClient.loadProfile (null profile PFD, often after EMFILE).
     * Swallow exactly that stack; let everything else crash normally.
     */
    private fun installCrashGuard() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val fromLoadProfile = e is NullPointerException &&
                e.stackTrace.any { it.className.contains("FFalconXRClient") &&
                                   it.methodName.contains("loadProfile") }
            if (fromLoadProfile) {
                Log.w(TAG, "swallowed FFalconXRClient.loadProfile NPE (known firmware bug)")
            } else {
                prev?.uncaughtException(t, e)
            }
        }
    }

    /** §10: keep thermal data current without frequent sysfs/log churn. */
    private fun startThermalReader() {
        thermalThread = Thread {
            var ticks = 0
            while (!Thread.currentThread().isInterrupted) {
                try {
                    // Discover relevant zones ONCE — scanning and reading
                    // every thermal_zone each pass can block ~1 s in the
                    // kernel on some SoCs (reads hit slow ADC buses).
                    if (thermalZones == null) {
                        val found = ArrayList<File>()
                        val all = File("/sys/class/thermal")
                            .listFiles { f -> f.name.startsWith("thermal_zone") }
                            ?.sortedBy { it.name } ?: emptyList()
                        for (z in all) {
                            if (found.size >= 4) break
                            val type = try {
                                File(z, "type").readText().trim().lowercase()
                            } catch (_: Throwable) { "" }
                            if ("cpu" in type || "gpu" in type || "soc" in type ||
                                "battery" in type || "camera" in type) {
                                found.add(File(z, "temp"))
                            }
                        }
                        if (found.isEmpty()) for (z in all.take(2)) found.add(File(z, "temp"))
                        thermalZones = found
                    }
                    var maxT = 0
                    for (f in thermalZones ?: emptyList()) {
                        try {
                            val v = f.readText().trim().toInt()
                            val c = if (v > 1000) v / 1000 else v
                            if (c in 20..110 && c > maxT) maxT = c
                        } catch (_: Throwable) {}
                    }
                    game.tempC = maxT
                    if (ticks++ % 3 == 0) {
                        Log.d(TAG, "status swipe=${swipeCtl.sweepPx().toInt()}px temp=${maxT}C")
                    }
                    Thread.sleep(10000)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private var thermalZones: List<File>? = null

    // ---- temple pad: accept touches, hovers AND key events (§6) --------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val name = ev.device?.name ?: ""
        if (name.contains("cyttsp")) { pad.onTouch(ev); return true }
        pad.onTouch(ev) // screen touches mirror the pad (bench testing)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val name = ev.device?.name ?: ""
        if (name.contains("cyttsp")) { pad.onTouch(ev); return true }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (pad.onKey(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- lifecycle ------------------------------------------------------

    override fun onResume() {
        super.onResume()
        glView.onResume()
        audio.resumeAll()
    }

    override fun onPause() {
        glView.onPause()
        audio.pauseAll()
        super.onPause()
    }

    override fun onDestroy() {
        thermalThread?.interrupt()
        audio.release()
        super.onDestroy()
    }

    companion object { private const val TAG = "X3Klax" }
}
