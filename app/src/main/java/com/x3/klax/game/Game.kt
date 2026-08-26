package com.x3.klax.game

import com.x3.klax.Settings
import com.x3.klax.audio.GameAudio
import com.x3.klax.input.SwipeControl
import com.x3.klax.render.NeonBatch
import com.x3.klax.render.Particles
import com.x3.klax.render.VectorFont
import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  KLAX — the 1990 arcade game, remixed for a pair of AR glasses.
 * ============================================================================
 *
 * Tiles tumble down a conveyor, a paddle catches them, and the player drops
 * them into a well to make lines of three. [Klax] owns those rules and knows
 * nothing about pixels; this class turns them into light and sound, and turns
 * three touchpad gestures into intent.
 *
 * ## Everything here is drawn on black glass
 *
 * The waveguide is ADDITIVE: black is not a colour, it is the absence of one,
 * and the wearer sees their room through it. So there is no board, no panel and
 * no background — a filled rectangle would hang a bright grey sheet over the
 * real world and spend battery doing it. The playfield is a lattice of glowing
 * strokes, and a tile is an outline that gets its weight from colour and bloom
 * rather than from fill.
 *
 * That same constraint is why a cleared klax is celebrated with PARTICLES. On a
 * display that cannot flash the screen white, motion is the only exclamation
 * mark available.
 *
 * ## Three gestures is the whole vocabulary
 *
 * The temple pad offers tap, double tap and swipe, and that is all. Klax needs
 * exactly four verbs, so the mapping is nearly forced — and, importantly, has
 * no modes:
 *
 *     swipe       move the paddle one lane
 *     tap         drop the bottom tile into the well
 *     double tap  flip the top tile back up the conveyor
 *     tap         also starts, advances and restarts, between waves
 *
 * There is deliberately no in-play menu gesture. A wearer whose hands are busy
 * elsewhere cannot be asked to remember a fourth thing.
 */
class Game(
    private val settings: Settings,
    private val audio: GameAudio,
    private val swipe: SwipeControl
) {

    val batch = NeonBatch()

    /** Set by the renderer each frame. */
    @JvmField var fps: Float = 0f

    /** Set by the host from the thermal sensor. */
    @JvmField var tempC: Int = 0

    private companion object {
        // Plane-local metres. The whole board lives inside roughly +-0.13
        // across and +-0.08 up, which is what sits comfortably in the middle of
        // the field of view without making the wearer scan for it.
        // MEASURED FROM THE DEVICE, not guessed: the visible field is about
        // +-0.144 across and +-0.117 up. Every number below is spent out of
        // that budget, and the budget is the reason the conveyor is not twice
        // as tall as it used to be — see CONVEYOR_TOP.
        const val CELL_W = 0.0320f          // 2x
        const val CELL_H = 0.0230f          // 2x, minus what the screen cannot give
        const val WELL_BOTTOM = -0.1040f
        const val WELL_LEFT = -0.0800f      // 5 * CELL_W, centred
        const val PADDLE_V = 0.0200f        // just above the lip at +0.011
        const val CONVEYOR_TOP = 0.1150f
        const val TITLE_V = 0.0300f

        /**
         * How wide and how large the FAR end of the conveyor is, relative to
         * the near end. Less than one, because that is what distance does.
         *
         * THIS USED TO BE 1.55 AND POINTED THE WRONG WAY: the rails splayed
         * outward as they receded and a tile at the top of the conveyor was
         * drawn HALF AGAIN AS BIG as one about to reach the paddle. That is
         * inverse perspective — the eye reads it as the far end looming toward
         * you — and it is why the belt never felt deep no matter how tall it
         * got. Converging to 42% is what actually doubles the depth, and it
         * costs no vertical screen space at all, which is the only reason both
         * this and a doubled playfield can fit.
         */
        const val FAR_SCALE = 0.42f
        const val RUNGS = 12

        /** Seconds a cleared cell keeps glowing before it is gone. */
        const val FLASH_S = 0.35f

        const val STATE_ATTRACT = 0
        const val STATE_PLAY = 1
        const val STATE_WAVE_DONE = 2
        const val STATE_OVER = 3

        /** Fixed, so a session is reproducible when something goes wrong. */
        const val SEED = 0x4B4C4158L

        /**
         * The board leans back about 22 degrees — a slightly isometric table
         * rather than a flat sign hanging in the air. On a stereo display the
         * lean is what gives the conveyor real depth: a tile at the far end is
         * genuinely further away, so it arrives at the paddle rather than
         * merely sliding down a picture of a conveyor.
         */
        const val ISO_TILT = 0.38f

        /** Minimum gap between lane changes, so one flick moves one lane. */
        const val LANE_GAP_MS = 150L
    }

    /**
     * SYNTHWAVE, AND ALSO LEGIBLE. Hue alone carries the whole game — the
     * player must tell two tiles apart at a glance, at the edge of vision,
     * through glass with a lit room behind it. These six are spaced around the
     * wheel and all near full brightness for that reason: the muted teals and
     * indigos that look best in a screenshot are the first to vanish against a
     * bright wall.
     */
    private val tileR = floatArrayOf(0.20f, 1.00f, 1.00f, 0.35f, 1.00f, 0.65f)
    private val tileG = floatArrayOf(1.00f, 0.15f, 0.85f, 0.55f, 0.50f, 0.30f)
    private val tileB = floatArrayOf(0.95f, 0.85f, 0.10f, 1.00f, 0.20f, 1.00f)

    private val klax = Klax(SEED)
    private val parts = Particles(512)

    private var state = STATE_ATTRACT
    private var stateT = 0f
    private var wave = Levels.get(1)
    private var musicPlaying = -1

    /** Queued gestures, drained on the GL thread. See [tap]. */
    private val events = ArrayList<Char>(8)

    /** Cell index -> seconds of afterglow left, for tiles that just cleared. */
    private val flash = FloatArray(ROWS * COLS)

    /** Last known colour per cell, so the afterglow keeps the right hue. */
    private val lastColour = IntArray(ROWS * COLS)

    private var shake = 0f

    /** When the paddle last changed lane; see [onSwipe]. */
    private var lastLaneMs = 0L

    /** Game metres -> world units, per WINDOW SIZE setting. */
    private val windowScales = floatArrayOf(70f, 92f, 115f)

    init {
        // Particles are written in generic units; this board is in metres. One
        // call rescales every length-dimensioned tunable the pool has, which is
        // far less error-prone than scaling at each of a dozen call sites.
        parts.scaleLengths(0.013f)
    }

    // ---- input ------------------------------------------------------------
    //
    // These arrive on the UI thread and are consumed on the GL thread, so they
    // are queued rather than acted on immediately: a paddle that moves halfway
    // through a frame's simulation is how a tile lands in the wrong column.

    fun tap() { synchronized(events) { events.add('T') } }
    fun doubleTap() { synchronized(events) { events.add('D') } }
    fun swipe(steps: Int) { synchronized(events) { events.add(if (steps > 0) '+' else '-') } }

    private fun drainInput() {
        synchronized(events) {
            for (e in events) when (e) {
                '+' -> onSwipe(1)
                '-' -> onSwipe(-1)
                'T' -> onTap()
                'D' -> onDouble()
            }
            events.clear()
        }
    }

    /**
     * ONE LANE PER SWIPE, however long the swipe is.
     *
     * TemplePad quantises a stroke into a step every 90 px and emits them in a
     * `while` loop, so a single flick of 270 px arrives here as THREE calls and
     * the paddle jumped three lanes. That is unplayable in a five-lane game:
     * you cannot line up on a falling tile if the paddle overshoots it, and
     * with nothing ever caught there is nothing to drop either — which is why
     * the catch and drop sounds seemed to be missing. They were firing
     * correctly; the paddle just never caught anything.
     *
     * The whole stroke lands inside one frame, so the guard only has to be
     * longer than a stroke's worth of steps and shorter than the gap between
     * two deliberate flicks.
     */
    private fun onSwipe(dir: Int) {
        if (state != STATE_PLAY) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLaneMs < LANE_GAP_MS) return
        val before = klax.paddleLane
        klax.moveLane(dir)
        if (klax.paddleLane != before) {
            lastLaneMs = now
            audio.sfx("move")
        }
    }

    private fun onTap() {
        when (state) {
            STATE_ATTRACT -> startWave(1)
            STATE_OVER -> startWave(1)
            STATE_WAVE_DONE -> startWave(klax.level + 1)
            else -> if (klax.drop()) audio.sfx("drop")
        }
    }

    private fun onDouble() {
        if (state != STATE_PLAY) return
        if (klax.flip()) audio.sfx("flip")
    }

    // ---- level flow -------------------------------------------------------

    private fun startWave(n: Int) {
        wave = Levels.get(n)
        klax.laneJump = wave.laneJump
        klax.startLevel(n, wave.colours, wave.speed, wave.spawnEvery, wave.goalKlaxes, wave.goalTiles)
        java.util.Arrays.fill(flash, 0f)
        parts.clear()
        state = STATE_PLAY
        stateT = 0f
        shake = 0f
        // Only restart the track when the wave actually changes song, or every
        // level would slam the same intro back to bar one.
        if (musicPlaying != wave.music) {
            audio.playLevelMusic(wave.music + 1)
            musicPlaying = wave.music
        }
        audio.sfx("ui")
    }

    // ---- frame ------------------------------------------------------------

    fun update(dt: Float) {
        stateT += dt
        drainInput()

        if (state == STATE_PLAY) step(dt)

        parts.update(dt)
        for (i in flash.indices) if (flash[i] > 0f) flash[i] = (flash[i] - dt).coerceAtLeast(0f)
        if (shake > 0f) shake = (shake - dt * 2.4f).coerceAtLeast(0f)

        // THE PLANE BASIS, WITHOUT WHICH NOTHING IS VISIBLE. Every coordinate
        // in this file is in game metres, and the batch draws in world units;
        // this is the transform between them. Omitting it does not fail loudly
        // — it draws the entire board at 1/92nd scale, which on a 640px eye is
        // a couple of lit pixels near the centre and looks exactly like a game
        // that failed to start.
        val s = windowScales[settings.windowSize.coerceIn(0, 2)]
        val ct = cos(ISO_TILT)
        val st = sin(ISO_TILT)
        batch.setBasis(
            0f, 0f, 0f,
            s, 0f, 0f,
            0f, s * ct, -s * st,
            0f, s * st, s * ct
        )
        batch.lift = 0f

        batch.begin()
        when (state) {
            STATE_ATTRACT -> drawAttract()
            STATE_PLAY -> { drawBoard(); drawHud() }
            STATE_WAVE_DONE -> { drawBoard(); drawHud(); drawWaveDone() }
            else -> { drawBoard(); drawOver() }
        }
        parts.draw(batch)
    }

    private fun step(dt: Float) {
        // Snapshot colours BEFORE the model runs: a cleared cell is already
        // empty by the time the event reaches us, and the afterglow needs to
        // know what colour used to be there.
        rememberColours()

        for (e in klax.update(dt)) when (e) {
            is Ev.Caught -> {
                audio.sfx("catch")
                parts.streak(laneU(klax.paddleLane), PADDLE_V, 0f, 0.02f, 6, 0.6f, 0.9f, 1f)
            }
            is Ev.Dropped -> Unit
            is Ev.Flipped -> Unit
            is Ev.Missed -> {
                audio.sfx("lose_life")
                shake = 1f
                parts.burst(laneU(klax.paddleLane), PADDLE_V, 40, 0.09f, 1f, 0.25f, 0.15f, 0.9f)
            }
            is Ev.Overflow -> {
                audio.sfx("lose_life")
                shake = 1f
                parts.burst(laneU(klax.paddleLane), WELL_BOTTOM + ROWS * CELL_H,
                    40, 0.09f, 1f, 0.40f, 0.10f, 0.9f)
            }
            is Ev.Cleared -> onCleared(e)
            is Ev.GoalMet -> {
                audio.sfx("level_up")
                state = STATE_WAVE_DONE
                stateT = 0f
            }
        }

        if (klax.over && state == STATE_PLAY) {
            audio.sfx("game_over")
            audio.stopLevelMusic()
            musicPlaying = -1
            state = STATE_OVER
            stateT = 0f
        }
    }

    private fun rememberColours() {
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val c = klax.well[row][col]
                if (c >= 0) lastColour[row * COLS + col] = c
            }
        }
    }

    /**
     * A klax is the entire point of the game, so it gets the loudest thing this
     * display can do. Each cleared cell throws a burst in its own colour, and a
     * diagonal — the hardest line to build and the highest scoring — throws
     * more of them and shakes the board harder.
     */
    private fun onCleared(e: Ev.Cleared) {
        audio.sfx(if (e.len >= 4 || e.kind == 2) "klax_big" else "klax")
        val n = when (e.kind) { 2 -> 34; 1 -> 24; else -> 20 }
        for (idx in e.cells) {
            if (idx < 0 || idx >= flash.size) continue
            val row = idx / COLS
            val col = idx % COLS
            flash[idx] = FLASH_S
            val c = lastColour[idx].coerceIn(0, tileR.size - 1)
            parts.burst(cellU(col), cellV(row), n, 0.075f, tileR[c], tileG[c], tileB[c], 0.9f)
        }
        shake = if (e.kind == 2) 0.7f else 0.4f
    }

    // ---- geometry ---------------------------------------------------------

    private fun laneU(lane: Int) = WELL_LEFT + (lane + 0.5f) * CELL_W
    private fun cellU(col: Int) = WELL_LEFT + (col + 0.5f) * CELL_W
    private fun cellV(row: Int) = WELL_BOTTOM + (row + 0.5f) * CELL_H

    private fun shakeU() = if (shake <= 0f) 0f else sin(stateT * 61f) * 0.0016f * shake
    private fun shakeV() = if (shake <= 0f) 0f else cos(stateT * 47f) * 0.0016f * shake

    // ---- drawing ----------------------------------------------------------

    private fun drawBoard() {
        val su = shakeU()
        val sv = shakeV()
        drawConveyor(su, sv)
        drawFalling(su, sv)
        drawWell(su, sv)
        drawPaddle(su, sv)
    }

    /**
     * The well is a lattice, not a box. A closed filled rectangle reads as a
     * solid object hanging in front of the room; an open lattice reads as a
     * hologram laid over it, which is what this actually is.
     */
    private fun drawWell(su: Float, sv: Float) {
        val l = WELL_LEFT + su
        val r = WELL_LEFT + COLS * CELL_W + su
        val b = WELL_BOTTOM + sv
        val t = WELL_BOTTOM + ROWS * CELL_H + sv

        for (c in 0..COLS) {
            val u = l + c * CELL_W
            batch.line(u, b, u, t, 0.0007f, 0.25f, 0.55f, 0.95f, 0.26f)
        }
        // A solid floor bar, not a hairline: this is the thing tiles land on.
        batch.fill((l + r) * 0.5f, b - 0.0018f, (r - l) * 0.5f, 0.0022f, 0f,
            0.30f, 0.70f, 1f, 0.55f)
        batch.line(l, b, r, b, 0.0014f, 0.45f, 0.85f, 1f, 0.9f)
        // The lip: the line above which a drop becomes an overflow and costs a
        // life. It earns its own brightness.
        batch.line(l, t, r, t, 0.0007f, 0.65f, 0.30f, 0.95f, 0.35f)

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val idx = row * COLS + col
                val c = klax.well[row][col]
                if (c >= 0) {
                    drawTile(cellU(col) + su, cellV(row) + sv, c, 1f)
                } else if (flash[idx] > 0f) {
                    // Afterglow, so a clear reads as something that HAPPENED
                    // rather than as tiles that are simply missing.
                    val a = flash[idx] / FLASH_S
                    drawTile(cellU(col) + su, cellV(row) + sv, lastColour[idx], a * 0.8f)
                }
            }
        }
    }

    /**
     * Five rails splaying outward toward the far end. It is the cheapest honest
     * depth cue there is — no perspective maths, but the eye still reads the
     * top of the screen as further away, which is what makes a tile appear to
     * come TOWARD the player rather than merely slide down.
     */
    private fun drawConveyor(su: Float, sv: Float) {
        // A LADDER, not five bare rails. The arcade belt is a grid — lane
        // dividers crossed by closely spaced rungs — and the rungs are what
        // sell the motion: they give the falling tiles something to measure
        // themselves against, so a tile reads as travelling along a surface
        // rather than drifting down empty space.
        val n = COLS
        for (lane in 0..n) {
            val uNear = (WELL_LEFT + lane * CELL_W)
            val a = if (lane == klax.paddleLane || lane == klax.paddleLane + 1) 0.55f else 0.26f
            batch.line(uNear * FAR_SCALE + su, CONVEYOR_TOP + sv, uNear + su, PADDLE_V + sv,
                0.0007f, 0.35f, 0.85f, 1f, a)
        }
        var i = 0
        while (i <= RUNGS) {
            val y = i / RUNGS.toFloat()
            val v = PADDLE_V + y * (CONVEYOR_TOP - PADDLE_V) + sv
            val w = 1f - y * (1f - FAR_SCALE)
            // Brighter close in, so the belt fades into the distance rather
            // than ending abruptly at the top of the screen.
            val a = 0.30f * (1f - y * 0.62f)
            batch.line(WELL_LEFT * w + su, v, (WELL_LEFT + COLS * CELL_W) * w + su, v,
                0.0006f, 0.30f, 0.70f, 1f, a)
            i++
        }
    }

    private fun drawFalling(su: Float, sv: Float) {
        for (t in klax.falling) {
            val y = t.y.coerceIn(0f, 1f)
            val w = 1f - y * (1f - FAR_SCALE)
            val u = laneU(t.lane) * w + su
            val v = PADDLE_V + y * (CONVEYOR_TOP - PADDLE_V) + sv
            drawTile(u, v, t.colour, 1f, w)
            // A flipped tile travels the wrong way; the player must be able to
            // pick it out of the traffic instantly.
            if (t.flipped) batch.circle(u, v, CELL_W * 0.62f * w, 0.0005f, 1f, 1f, 1f, 0.35f)
        }
    }

    /**
     * A bracket rather than a slab, with the held stack drawn as the tiles it
     * is actually carrying: the next drop is planned from this, so "how many,
     * and what colour" has to be readable without counting.
     */
    private fun drawPaddle(su: Float, sv: Float) {
        val u = laneU(klax.paddleLane) + su
        val v = PADDLE_V + sv
        val hw = CELL_W * 0.60f
        batch.line(u - hw, v + 0.0060f, u - hw, v - 0.0030f, 0.0013f, 0.6f, 1f, 1f, 0.9f)
        batch.line(u + hw, v + 0.0060f, u + hw, v - 0.0030f, 0.0013f, 0.6f, 1f, 1f, 0.9f)
        batch.line(u - hw, v - 0.0030f, u + hw, v - 0.0030f, 0.0015f, 0.6f, 1f, 1f, 0.9f)

        for (i in klax.paddle.indices) {
            drawTile(u, v + 0.0090f + i * (CELL_H * 0.52f), klax.paddle[i], 0.95f, 0.70f)
        }
    }

    /**
     * A SOLID BEVELLED BLOCK, the way the arcade drew it.
     *
     * This was an outline with an inner bar, on the reasoning that an additive
     * waveguide should never fill anything. That reasoning is sound for a HUD
     * laid over the real world and wrong for a game you are looking AT: the
     * wireframe read as a blueprint of Klax rather than Klax. Solid tiles are
     * what make the well look like a bin filling with objects instead of a
     * lattice with marks in it.
     *
     * The body is deliberately dim (45%) and the EDGES carry the brightness —
     * a lit face at full strength would bloom into its neighbours and turn a
     * column of tiles into one glowing smear. The light top edge and dark
     * bottom edge are the whole 3D effect, and cost two lines.
     */
    private fun drawTile(u: Float, v: Float, colour: Int, alpha: Float, scale: Float = 1f) {
        val c = colour.coerceIn(0, tileR.size - 1)
        val r = tileR[c]; val g = tileG[c]; val b = tileB[c]
        val hw = CELL_W * 0.44f * scale
        val hh = CELL_H * 0.42f * scale

        // Body.
        batch.fill(u, v, hw, hh, 0f, r * 0.45f, g * 0.45f, b * 0.45f, alpha * 0.85f)

        // Edges, at full colour, so the block has a defined boundary against
        // whatever is behind it.
        val w = 0.0011f * scale
        batch.line(u - hw, v - hh, u + hw, v - hh, w, r, g, b, alpha)
        batch.line(u + hw, v - hh, u + hw, v + hh, w, r, g, b, alpha)
        batch.line(u + hw, v + hh, u - hw, v + hh, w, r, g, b, alpha)
        batch.line(u - hw, v + hh, u - hw, v - hh, w, r, g, b, alpha)

        // Bevel: a near-white highlight along the top and left, and nothing
        // along the bottom and right. Light from above is the convention every
        // arcade block used and the eye reads it instantly as raised.
        val hi = 0.35f * alpha
        batch.line(u - hw * 0.82f, v + hh * 0.66f, u + hw * 0.82f, v + hh * 0.66f,
            0.0009f * scale, 1f, 1f, 1f, hi)
        batch.line(u - hw * 0.82f, v + hh * 0.66f, u - hw * 0.82f, v - hh * 0.55f,
            0.0008f * scale, 1f, 1f, 1f, hi * 0.7f)
    }

    /**
     * SMALL, AND OUT OF THE WAY IN THE TOP-LEFT.
     *
     * The playfield doubled and took the bottom of the screen with it, so the
     * readout moved to the one corner nothing else uses. It is also smaller
     * than it was: the score is a thing you check between waves, not something
     * to read while a tile is falling, and on a see-through display every lit
     * pixel you are not looking at is one more thing between you and the room.
     */
    private fun drawHud() {
        val left = -0.1400f
        val top = 0.1100f
        VectorFont.draw(batch, "SCORE ${klax.score}", left, top, 0.0016f,
            0.5f, 0.95f, 1f, 0.70f)
        val goal = if (wave.goalKlaxes > 0) "KLAX ${klax.klaxesMade}/${wave.goalKlaxes}"
                   else "TILES ${klax.tilesSeen}/${wave.goalTiles}"
        VectorFont.draw(batch, goal, left, top - 0.0110f, 0.0016f,
            0.6f, 0.85f, 0.6f, 0.55f)

        // Lives stay as pips and stay on the far side, because a second number
        // in the same corner would be read as part of the score.
        var i = 0
        while (i < klax.lives) {
            batch.circle(0.1280f - i * 0.0075f, top, 0.0020f, 0.0006f,
                1f, 0.75f, 0.2f, 0.85f)
            i++
        }

        // The wave name announces itself once over the board and then leaves.
        if (stateT < 2.5f) {
            val a = if (stateT > 2.0f) (2.5f - stateT) / 0.5f else 1f
            VectorFont.draw(batch, wave.name, 0f, 0.0600f, 0.0034f,
                1f, 0.45f, 0.95f, a * 0.9f, true)
        }
    }

    private fun drawAttract() {
        val pulse = 0.55f + 0.45f * sin(stateT * 2.2f)
        VectorFont.draw(batch, "KLAX", 0f, TITLE_V + 0.026f, 0.0090f, 0.3f, 1f, 1f, 1f, true)
        VectorFont.draw(batch, "NEON REMIX", 0f, TITLE_V - 0.002f, 0.0028f,
            1f, 0.35f, 0.9f, 0.9f, true)
        VectorFont.draw(batch, "TAP TO START", 0f, TITLE_V - 0.026f, 0.0030f,
            1f, 1f, 0.6f, pulse, true)
        VectorFont.draw(batch, "SWIPE  MOVE LANE", -0.126f, -0.052f, 0.0022f, 0.5f, 0.9f, 1f, 0.55f)
        VectorFont.draw(batch, "TAP    DROP TILE", -0.126f, -0.070f, 0.0022f, 0.5f, 0.9f, 1f, 0.55f)
        VectorFont.draw(batch, "DOUBLE FLIP BACK", -0.126f, -0.088f, 0.0022f, 0.5f, 0.9f, 1f, 0.55f)

        // A slow drift of sparks, so the attract screen looks alive rather than
        // like a frozen card the wearer assumes has crashed.
        if (parts.live < 60) {
            val u = -0.10f + ((stateT * 0.05f) % 0.20f)
            parts.streak(u, -0.020f, 0.010f, 0.045f, 2, 0.4f, 0.9f, 1f)
        }
    }

    private fun drawWaveDone() {
        VectorFont.draw(batch, "WAVE CLEAR", 0f, TITLE_V, 0.0044f, 0.4f, 1f, 0.7f, 1f, true)
        VectorFont.draw(batch, "SCORE ${klax.score}", 0f, TITLE_V - 0.016f, 0.0028f,
            1f, 1f, 1f, 0.9f, true)
        val pulse = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP FOR NEXT WAVE", 0f, TITLE_V - 0.034f, 0.0028f,
            1f, 1f, 0.6f, pulse, true)
        if (stateT < 0.7f && parts.live < 220) {
            parts.burst(0f, TITLE_V, 5, 0.06f, 0.4f, 1f, 0.8f, 1.1f)
        }
    }

    private fun drawOver() {
        VectorFont.draw(batch, "GAME OVER", 0f, TITLE_V, 0.0044f, 1f, 0.3f, 0.4f, 1f, true)
        VectorFont.draw(batch, "SCORE ${klax.score}", 0f, TITLE_V - 0.016f, 0.0028f,
            1f, 1f, 1f, 0.9f, true)
        val pulse = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP TO RETRY", 0f, TITLE_V - 0.034f, 0.0028f,
            1f, 1f, 0.6f, pulse, true)
    }
}
