package com.x3.klax.input

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Temple-pad input, accepting EVERYTHING (field guide §6): touch events
 * (cyttsp5/cyttsp6 device), hover streams, and DPAD key events — firmware
 * builds differ. Screen touches mirror the pad so it's bench-testable.
 *
 * Two parallel outputs from the same finger motion:
 *  - onDrag: raw continuous (x+y) pixel deltas — drives the paddle.
 *  - onSwipe: quantized ±1 steps every 90 px — drives menu navigation.
 * onStrokeEnd fires on finger-up with the total signed stroke length
 * (used by swipe calibration). Taps stay instant (guide §11).
 */
class TemplePad(
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onSwipe: (steps: Int) -> Unit,
    private val onDrag: (pxDelta: Float) -> Unit = {},
    private val onStrokeEnd: (strokePx: Float) -> Unit = {}
) {
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = 0f
    private var lastTapT = 0L
    private var swipeAccum = 0f
    private var strokeAccum = 0f

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                downT = ev.eventTime
                moved = 0f; swipeAccum = 0f; strokeAccum = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val d = (ev.x - lastX) + (ev.y - lastY)
                lastX = ev.x; lastY = ev.y
                moved = maxOf(moved, abs(ev.x - downX) + abs(ev.y - downY))
                if (d != 0f) onDrag(d)
                strokeAccum += d
                swipeAccum += d
                while (swipeAccum >= SWIPE_PX) { onSwipe(1); swipeAccum -= SWIPE_PX }
                while (swipeAccum <= -SWIPE_PX) { onSwipe(-1); swipeAccum += SWIPE_PX }
            }
            MotionEvent.ACTION_UP -> {
                val dt = ev.eventTime - downT
                if (dt < TAP_MS && moved < TAP_PX) {
                    Log.d(TAG, "IN touch tap dev=${ev.device?.name}")
                    onTap()
                    if (ev.eventTime - lastTapT < DOUBLE_MS) onDoubleTap()
                    lastTapT = ev.eventTime
                } else {
                    onStrokeEnd(strokeAccum)
                }
            }
        }
        return true
    }

    fun onKey(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val now = System.currentTimeMillis()
                onTap()
                if (now - lastTapT < DOUBLE_MS) onDoubleTap()
                lastTapT = now
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                onSwipe(-1); onDrag(-KEY_DRAG_PX); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                onSwipe(1); onDrag(KEY_DRAG_PX); return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "X3KlaxIn"
        const val TAP_MS = 230L
        const val TAP_PX = 34f
        const val DOUBLE_MS = 300L
        const val SWIPE_PX = 90f
        const val KEY_DRAG_PX = 60f   // bench: arrow key = small paddle nudge
    }
}
