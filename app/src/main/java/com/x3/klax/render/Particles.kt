package com.x3.klax.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fixed-capacity particle pool — the shower of light a cleared klax throws.
 *
 * WHY STREAKS, NOT DOTS. The X3 waveguide is additive: black is transparent
 * and only lit pixels exist, so a particle is a moving scrap of light with
 * nothing behind it. A single-pixel dot at 60fps on a see-through display
 * reads as a flicker — the eye has no background to anchor it against and
 * the sample is too small to survive the optics. So every particle draws as
 * a short segment laid along its OWN velocity: head at the current position,
 * tail trailing back along the direction of travel, length proportional to
 * speed (clamped). Fast sparks stretch into hot dashes, slow ones shrink to
 * sparks, and the whole burst reads as motion instead of noise. It is the
 * same trick a long camera exposure plays on a firework, and it costs one
 * batch.line per particle.
 *
 * WHY A POOL. update()/draw() run on the GL thread every frame. A single
 * allocation in that path invites the collector, and on this hardware a GC
 * pause is not an abstraction — it is a hitch the wearer feels as the world
 * stuttering on their face (see SfxMixer for the audio-stack version of the
 * same lesson). So: parallel FloatArrays sized once at construction, a ring
 * cursor for recycling, and not one object created after the constructor
 * returns. `count` bigger than the pool is legal and harmless; the oldest
 * survivors are simply overwritten. The pool NEVER grows.
 *
 * Lengths below are in the coordinate space the game hands NeonBatch. The
 * defaults are tuned for the ~±10 x ±6 world the game logic works in; if a
 * caller drives the batch in plane-local meters instead, call
 * [scaleLengths] once at init rather than editing this file.
 */
class Particles(max: Int = 512) {

    // ---- tunables (length-dimensioned ones move together, see scaleLengths) ----

    /** Downward pull, world units/s^2. Enough arc that a burst falls back
     *  into the well instead of drifting off the top of the fov. */
    var gravity = 9.0f

    /** Velocity damping, 1/s. Sparks lose their punch and settle rather
     *  than sailing away — a burst should be over before the next tile lands. */
    var drag = 1.6f

    /** Core stroke width of a spark at full brightness. NeonBatch adds its
     *  own halo pass on top, so keep this thin. */
    var width = 0.05f

    /** Streak length = speed * this, i.e. "how much of a second of travel
     *  the exposure smears". Scale-free by construction. */
    var streakSeconds = 0.045f

    /** Streak length clamps: a settled particle still shows a spark, and a
     *  very fast one does not draw a laser across the playfield. */
    var minStreak = 0.12f
    var maxStreak = 0.90f

    /** Lifetime for [streak] particles (that entry point has no life param). */
    var streakLife = 0.45f

    /** Multiply every length-dimensioned tunable at once — the one knob to
     *  turn when the game feeds a different coordinate scale. */
    fun scaleLengths(s: Float) {
        gravity *= s; width *= s
        minStreak *= s; maxStreak *= s
    }

    // ---- the pool: parallel arrays, allocated once, never resized ----

    private val cap = if (max < 1) 1 else max

    private val pu = FloatArray(cap)      // position
    private val pv = FloatArray(cap)
    private val vu = FloatArray(cap)      // velocity (also the streak axis)
    private val vv = FloatArray(cap)
    private val cr = FloatArray(cap)      // colour
    private val cg = FloatArray(cap)
    private val cb = FloatArray(cap)
    private val life = FloatArray(cap)    // seconds remaining; <= 0 == dead slot
    private val life0 = FloatArray(cap)   // seconds at spawn, denominator of the fade

    /** Ring cursor. Allocation always walks forward, so the slot the cursor
     *  is sitting on is the least-recently-spawned one — that is what makes
     *  "recycle the oldest" an O(1) lookup instead of a search. */
    private var cursor = 0

    private var nLive = 0
    val live: Int get() = nLive

    // ---- cheap deterministic noise ----
    // xorshift32 rather than kotlin.random so the jitter is reproducible
    // between runs (useful when eyeballing an effect twice) and provably
    // object-free.
    private var rngState = 0x9E3779B9.toInt()

    private fun rnd(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        if (x == 0) x = 0x6D2B79F5           // xorshift's one dead state
        rngState = x
        return (x ushr 8) * (1f / 16777216f) // 24 bits -> [0,1)
    }

    private fun step(i: Int) = if (i + 1 >= cap) 0 else i + 1

    /**
     * Claim a slot. Prefers a dead one; when the pool is saturated it evicts
     * the oldest survivor at the cursor. The forward scan is bounded by cap
     * and in practice exits on the first probe, because the cursor always
     * points at the region of the ring that was filled longest ago — the
     * slots most likely to have expired.
     */
    private fun alloc(): Int {
        if (nLive < cap) {
            var i = cursor
            var probes = 0
            while (probes < cap) {
                if (life[i] <= 0f) { cursor = step(i); nLive++; return i }
                i = step(i); probes++
            }
        }
        // Full (or the live count drifted): overwrite the oldest in ring
        // order. nLive is unchanged — one out, one in.
        val i = cursor
        cursor = step(i)
        return i
    }

    private fun emit(
        u: Float, v: Float, velU: Float, velV: Float,
        r: Float, g: Float, b: Float, secs: Float
    ) {
        val i = alloc()
        pu[i] = u; pv[i] = v
        vu[i] = velU; vv[i] = velV
        cr[i] = r; cg[i] = g; cb[i] = b
        val l = if (secs > 1e-4f) secs else 1e-4f   // never divide by zero in draw
        life[i] = l; life0[i] = l
    }

    // ---- spawning ----

    /**
     * Radial spray from a point — the klax-cleared pop. Angles are stepped by
     * the golden angle plus jitter, which spreads a small `count` evenly
     * around the circle instead of clumping the way pure random does; speed
     * is spread wide (35%..100%) so the burst has a dense core and a few
     * long fliers rather than one uniform expanding ring.
     */
    fun burst(
        u: Float, v: Float, count: Int, speed: Float,
        r: Float, g: Float, b: Float, life: Float = 0.8f
    ) {
        var i = 0
        while (i < count) {
            val a = i * GOLDEN_ANGLE + rnd() * 0.5f
            val s = speed * (0.35f + 0.65f * rnd())
            emit(u, v, cos(a) * s, sin(a) * s, r, g, b, life * (0.65f + 0.5f * rnd()))
            i++
        }
    }

    /**
     * Directional jet along (du,dv) — a tile slamming into the well, a
     * column venting, the conveyor spitting a flipped tile back up. The
     * vector carries BOTH direction and speed. Emission points are jittered
     * across the beam and velocities fanned into a narrow cone so it reads
     * as a jet with width, not a single line of dots.
     */
    fun streak(
        u: Float, v: Float, du: Float, dv: Float, count: Int,
        r: Float, g: Float, b: Float
    ) {
        if (count <= 0) return
        var dirU = du; var dirV = dv
        var sp = sqrt(du * du + dv * dv)
        if (sp < 1e-5f) {           // degenerate vector: default to a slow upward puff
            dirU = 0f; dirV = 1f; sp = 1f
        } else {
            dirU /= sp; dirV /= sp
        }
        val perpU = -dirV; val perpV = dirU
        var i = 0
        while (i < count) {
            // Fan by pushing the unit direction sideways, then renormalising —
            // one sqrt, no atan2, same cone as an angular rotation.
            val k = (rnd() - 0.5f) * 2f * CONE
            var au = dirU + perpU * k
            var av = dirV + perpV * k
            val n = sqrt(au * au + av * av)
            if (n > 1e-6f) { au /= n; av /= n }
            val s = sp * (0.55f + 0.75f * rnd())
            val off = (rnd() - 0.5f) * minStreak * 1.5f      // across the beam
            emit(
                u + perpU * off, v + perpV * off,
                au * s, av * s, r, g, b,
                streakLife * (0.7f + 0.6f * rnd())
            )
            i++
        }
    }

    // ---- simulation ----

    /**
     * Semi-implicit Euler with drag then gravity. dt is clamped: the X3
     * occasionally stalls the GL thread for hundreds of milliseconds, and
     * without a clamp the frame after a stall teleports every live particle
     * off the playfield in one step.
     */
    fun update(dt: Float) {
        if (dt <= 0f) return
        val h = if (dt > MAX_STEP) MAX_STEP else dt
        // Implicit damping factor: unconditionally stable, unlike (1 - drag*dt)
        // which flips the velocity sign the moment drag*dt exceeds 1.
        val damp = 1f / (1f + drag * h)
        val gdt = gravity * h
        var i = 0
        while (i < cap) {
            val l = life[i]
            if (l > 0f) {
                val rem = l - h
                if (rem <= 0f) {
                    life[i] = 0f
                    nLive--
                } else {
                    life[i] = rem
                    val su = vu[i] * damp
                    val sv = vv[i] * damp - gdt
                    vu[i] = su; vv[i] = sv
                    pu[i] += su * h
                    pv[i] += sv * h
                }
            }
            i++
        }
    }

    /**
     * One batch.line per live particle, tail-to-head along its velocity.
     *
     * Alpha falls as the SQUARE of remaining life: on an additive display
     * overlapping sparks sum, so a linear fade still looks fully lit right
     * up to the moment it vanishes. Squaring makes them ember out.
     * Fresh particles (top 20% of life) are pulled toward white for a hot
     * core — free on an additive display, and it sells the impact.
     * The stroke thins as it fades so a dying spark is a hairline, not a
     * dim slab.
     *
     * Budget note: NeonBatch draws every line as a halo pass plus a core
     * pass — two quads, 12 vertices — so a saturated 512 pool is ~6k of its
     * 60k vertex budget. Comfortable, but that is the reason the pool is
     * capped: the ceiling is there so a cascade of clears cannot quietly
     * become the frame's biggest draw.
     */
    fun draw(batch: NeonBatch) {
        var i = 0
        while (i < cap) {
            val l = life[i]
            if (l > 0f) {
                val t = (l / life0[i]).coerceIn(0f, 1f)   // 1 at spawn -> 0 at death
                val a = t * t

                // Streak axis = own velocity, normalised.
                var du = vu[i]; var dv = vv[i]
                val sp = sqrt(du * du + dv * dv)
                if (sp > 1e-4f) { du /= sp; dv /= sp } else { du = 0f; dv = 1f }

                var len = sp * streakSeconds
                if (len < minStreak) len = minStreak
                if (len > maxStreak) len = maxStreak

                // NeonBatch takes one colour per segment, so the streak cannot
                // gradient along its length; the speed-driven length is what
                // carries the sense of a trail instead.
                val hot = if (t > 0.8f) (t - 0.8f) * 5f else 0f
                val r = cr[i] + (1f - cr[i]) * hot
                val g = cg[i] + (1f - cg[i]) * hot
                val b = cb[i] + (1f - cb[i]) * hot

                val hu = pu[i]; val hv = pv[i]            // head leads
                batch.line(
                    hu - du * len, hv - dv * len, hu, hv,
                    width * (0.45f + 0.55f * t),
                    r, g, b, a
                )
            }
            i++
        }
    }

    /** Kill everything — level change, death, pause. No reallocation. */
    fun clear() {
        life.fill(0f)
        nLive = 0
        cursor = 0
    }

    private companion object {
        /** 2*PI / phi^2 — successive angles never repeat a pattern. */
        const val GOLDEN_ANGLE = 2.399963f
        /** Half-width of the [streak] cone, as a sideways fraction of the
         *  unit direction (~15 degrees). */
        const val CONE = 0.28f
        /** Longest integration step accepted, seconds (20fps). */
        const val MAX_STEP = 0.05f
    }
}
