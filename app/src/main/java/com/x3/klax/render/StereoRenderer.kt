package com.x3.klax.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.x3.klax.game.Game
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/**
 * Side-by-side stereo, head-locked big-screen presentation — same model as
 * NeonTetris3D (the reference for how the window should look and act):
 * fixed camera 27 units back, 55° vertical FOV, slow sinusoidal sway, small
 * per-eye offset. The playfield fills the view; no world anchoring.
 */
class StereoRenderer(private val game: Game) : GLSurfaceView.Renderer {

    private var width = 0
    private var height = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private var lastFrameNs = 0L
    private var timeSec = 0f

    @Volatile var fps = 0f; private set
    private var fpsAccum = 0f
    private var fpsCount = 0

    companion object {
        private const val TAG = "X3Render"
        const val CAM_DIST = 27f     // NeonTetris3D camera distance
        const val FOV_Y = 55f        // NeonTetris3D vertical FOV
        const val EYE_OFF = 1.0f     // per-eye x offset at CAM_DIST (≈ their off*20)
        const val SWAY_X = 2.6f
        const val SWAY_Y = 0.9f
        const val BASE_EY = 1.2f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f) // black = transparent on waveguides
        game.batch.init()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val rawDt = if (lastFrameNs == 0L) 0.016f else (now - lastFrameNs) * 1e-9f
        if (rawDt > 0.12f) Log.w(TAG, "FRAME HITCH ${(rawDt * 1000f).toInt()}ms")
        val dt = rawDt.coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        timeSec += dt
        fpsAccum += dt; fpsCount++
        if (fpsAccum >= 0.5f) { fps = fpsCount / fpsAccum; fpsAccum = 0f; fpsCount = 0 }
        game.fps = fps

        // Simulate + build the neon geometry once per frame...
        game.update(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // ...then draw it twice, once per eye half.
        val half = width / 2
        val aspect = half.toFloat() / height
        Matrix.perspectiveM(proj, 0, FOV_Y, aspect, 0.5f, 200f)
        drawEye(-1, 0, half)
        drawEye(1, half, half)
    }

    private fun drawEye(eye: Int, x: Int, w: Int) {
        GLES20.glViewport(x, 0, w, height)
        val ex = sin(timeSec * 0.13f) * SWAY_X + eye * EYE_OFF
        val ey = BASE_EY + sin(timeSec * 0.09f) * SWAY_Y
        Matrix.setLookAtM(view, 0, ex, ey, CAM_DIST, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        game.batch.draw(mvp)
    }

}
