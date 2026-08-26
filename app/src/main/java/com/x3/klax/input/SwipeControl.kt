package com.x3.klax.input

import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * Paddle control from the RIGHT-ARM swipe on the X3 temple pad — the
 * camera/MediaPipe hand tracker is gone (too problematic on-device).
 *
 * Continuous pad drags integrate into an absolute paddle position 0..1.
 * One full comfortable arm stroke maps to full paddle travel; that stroke
 * length in pad pixels is what calibration measures.
 *
 * Thread contract: onDrag/onStrokeEnd are called on the UI thread (single
 * writer); the GL thread only reads the @Volatile outputs.
 */
class SwipeControl(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("x3klax_swipe", Context.MODE_PRIVATE)

    /** Pad pixels of one full arm stroke == full paddle travel. Calibrated. */
    private var fullSweepPx = prefs.getFloat("fullSweepPx", DEFAULT_SWEEP_PX)

    /** Paddle position across the playfield, 0..1 (0.5 = center). */
    @Volatile var pos01 = 0.5f; private set

    // ---- calibration: 3 full strokes, average their length ----
    @Volatile var calibrating = false; private set
    @Volatile var calCount = 0; private set
    @Volatile var calLockSerial = 0; private set
    private val strokes = ArrayList<Float>()

    fun sweepPx() = fullSweepPx

    /** Raw pad drag delta on (x+y), UI thread. sensMult = settings multiplier. */
    fun onDrag(pxDelta: Float, sensMult: Float) {
        pos01 = (pos01 + pxDelta * sensMult / fullSweepPx).coerceIn(0f, 1f)
    }

    /** Finger-up after a real drag; total signed stroke px. */
    fun onStrokeEnd(strokePx: Float) {
        if (!calibrating) return
        val len = abs(strokePx)
        if (len < MIN_STROKE_PX) return   // ignore twitches and taps
        strokes.add(len)
        calCount = strokes.size
        calLockSerial++
        Log.i(TAG, "swipe calibration stroke $calCount: ${len.toInt()}px")
        if (strokes.size >= STROKES_NEEDED) {
            fullSweepPx = (strokes.average().toFloat() * 1.05f)
                .coerceIn(200f, 4000f)
            prefs.edit().putFloat("fullSweepPx", fullSweepPx).apply()
            calibrating = false
            Log.i(TAG, "swipe calibration saved fullSweepPx=$fullSweepPx")
        }
    }

    fun beginCalibration() {
        strokes.clear()
        calCount = 0
        calibrating = true
        Log.i(TAG, "swipe calibration started")
    }

    fun cancelCalibration() {
        if (calibrating) {
            calibrating = false
            Log.i(TAG, "swipe calibration cancelled")
        }
    }

    fun recenter() { pos01 = 0.5f }

    fun calibrationLabel(): String = if (calibrating) {
        "STROKE $calCount/$STROKES_NEEDED"
    } else {
        "SWEEP ${fullSweepPx.toInt()}PX"
    }

    companion object {
        private const val TAG = "X3KlaxSwipe"
        const val DEFAULT_SWEEP_PX = 700f
        const val STROKES_NEEDED = 3
        const val MIN_STROKE_PX = 120f
    }
}
