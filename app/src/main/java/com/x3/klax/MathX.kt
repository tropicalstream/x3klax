package com.x3.klax

import kotlin.math.*

/**
 * Quaternion/vector helpers. Convention everywhere (field guide §4):
 * OpenXR — right-handed, +Y up, -Z forward, quaternions stored xyzw.
 */
object MathX {

    fun quatIdentity() = floatArrayOf(0f, 0f, 0f, 1f)

    fun quatMul(a: FloatArray, b: FloatArray): FloatArray {
        val (ax, ay, az, aw) = a
        val (bx, by, bz, bw) = b
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz
        )
    }

    fun quatConj(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    fun quatNormalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-9f) return quatIdentity()
        return floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }

    /** Rotate vector v by quaternion q. */
    fun quatRotate(q: FloatArray, v: FloatArray): FloatArray {
        val qv = floatArrayOf(v[0], v[1], v[2], 0f)
        val r = quatMul(quatMul(q, qv), quatConj(q))
        return floatArrayOf(r[0], r[1], r[2])
    }

    fun quatFromAxisAngle(ax: Float, ay: Float, az: Float, angleRad: Float): FloatArray {
        val h = angleRad * 0.5f
        val s = sin(h)
        return floatArrayOf(ax * s, ay * s, az * s, cos(h))
    }

    fun quatYaw(yawRad: Float) = quatFromAxisAngle(0f, 1f, 0f, yawRad)
    fun quatPitch(pitchRad: Float) = quatFromAxisAngle(1f, 0f, 0f, pitchRad)

    /** exp(0.5 * w * dt) — small-rotation quaternion from angular velocity. */
    fun quatFromGyro(wx: Float, wy: Float, wz: Float, dt: Float): FloatArray {
        val ang = sqrt(wx * wx + wy * wy + wz * wz) * dt
        if (ang < 1e-8f) return quatIdentity()
        val n = 1f / sqrt(wx * wx + wy * wy + wz * wz)
        return quatFromAxisAngle(wx * n, wy * n, wz * n, ang)
    }

    fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
        var dot = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw
        if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw }
        if (dot > 0.9995f) {
            return quatNormalize(floatArrayOf(
                a[0] + (bx - a[0]) * t, a[1] + (by - a[1]) * t,
                a[2] + (bz - a[2]) * t, a[3] + (bw - a[3]) * t))
        }
        val th = acos(dot.coerceIn(-1f, 1f))
        val sa = sin((1 - t) * th) / sin(th)
        val sb = sin(t * th) / sin(th)
        return floatArrayOf(
            a[0] * sa + bx * sb, a[1] * sa + by * sb,
            a[2] * sa + bz * sb, a[3] * sa + bw * sb)
    }

    /** Angular difference between two quaternions, radians. */
    fun quatAngle(a: FloatArray, b: FloatArray): Float {
        val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceIn(0f, 1f)
        return 2f * acos(d)
    }

    /** Extract yaw (rotation about +Y) from quaternion. */
    fun yawOf(q: FloatArray): Float {
        val f = quatRotate(q, floatArrayOf(0f, 0f, -1f)) // forward
        return atan2(f[0], -f[2])
    }

    /** 3x3 rotation matrix (row-major) from quaternion xyzw. */
    fun quatToMatrix3(q: FloatArray): FloatArray {
        val (x, y, z, w) = q
        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)
        )
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]
}

/** One-Euro filter (field guide §5: min_cutoff 1.2, beta 0.12) for positions. */
class OneEuro(private val minCutoff: Float = 1.2f, private val beta: Float = 0.12f) {
    private var x = 0f; private var dx = 0f; private var init = false
    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
    fun filter(v: Float, dt: Float): Float {
        if (!init) { x = v; init = true; return v }
        val d = (v - x) / dt
        dx += (d - dx) * alpha(1.0f, dt)
        val cutoff = minCutoff + beta * kotlin.math.abs(dx)
        x += (v - x) * alpha(cutoff, dt)
        return x
    }
    fun reset() { init = false; dx = 0f }
}
