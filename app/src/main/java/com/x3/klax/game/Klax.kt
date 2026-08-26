package com.x3.klax.game

import kotlin.random.Random

/**
 * KLAX, the pure model: no Android, no GL, no clock of its own. The whole
 * game is (seed, level parameters, three input calls, dt) -> events.
 *
 * That separation is not tidiness for its own sake. Debugging on the glasses
 * is slow and it lies to you — a truncated logcat and an off-head projector
 * make correct code look broken — so every rule that can be settled on a
 * laptop with a unit test is settled in this file, and the renderer is left
 * with nothing to decide.
 *
 * Coordinate conventions match the rest of the project: row 0 of the well is
 * the BOTTOM (the renderer's +v is up), and a falling tile's y runs 1.0 at the
 * far end of the conveyor down to 0.0 at the paddle.
 */

const val COLS = 5
const val ROWS = 5
const val PADDLE_MAX = 5

/** Tile colours are indices into the renderer's neon palette, which has six
 *  entries; asking for more would draw tiles nobody can tell apart anyway. */
const val MAX_COLOURS = 6

private const val KIND_H = 0
private const val KIND_V = 1
private const val KIND_D = 2

/** A flipped tile is a discard, not a threat — send it back up the chute
 *  faster than it came down so the lane frees up instead of idling. */
private const val FLIP_SPEEDUP = 1.6f

/** Longest frame the model will honour. The X3 hitches; without this clamp a
 *  200 ms stall would teleport a tile straight through the paddle (a life the
 *  player never had a chance at) and dump a burst of spawns on the way out. */
private const val MAX_DT = 0.10f

/** A tile descending a lane. lane 0..4, y 1.0 (far) -> 0.0 (at paddle). */
class Falling(@JvmField var lane: Int, @JvmField var colour: Int,
              @JvmField var y: Float, @JvmField var flipped: Boolean = false)

/**
 * Everything the presentation layer is allowed to react to. The model never
 * plays a sound or spawns a particle itself; it says what happened and where,
 * and Game/NeonBatch decide how loud it is.
 */
sealed class Ev {
    object Caught : Ev()
    object Dropped : Ev()
    object Flipped : Ev()
    object Missed : Ev()
    object Overflow : Ev()
    class Cleared(val cells: List<Int>, val kind: Int, val len: Int, val points: Int) : Ev()
    object GoalMet : Ev()
}

class Klax(seed: Long) {

    /** [row][col], row 0 = BOTTOM, -1 empty. Columns are always contiguous
     *  from the bottom — drops land on the stack, clears collapse the gap. */
    val well = Array(ROWS) { IntArray(COLS) { -1 } }

    /** The paddle is a queue, not a stack of choices: index 0 is the tile
     *  nearest the well (the next to drop), the last index is the tile that
     *  landed most recently (the only one that can be flipped away). */
    val paddle = ArrayList<Int>()

    var paddleLane = 2          // centre lane: level 1 opens with no travel owed
    val falling = ArrayList<Falling>()

    var level = 1; var score = 0; var lives = 3
    var klaxesMade = 0; var tilesSeen = 0
    var goalKlaxes = 0; var goalTiles = 0
    var over = false

    /** Held as a field, never re-seeded: same seed, same tile sequence, same
     *  game — which is the only way a bug report from the glasses is useful. */
    private val rnd = Random(seed)

    // ---- level parameters, set by startLevel ----
    private var colours = 3
    private var speed = 0.25f           // lane units per second (1.0 -> 0.0)
    /**
     * How many lanes a new tile may appear from the previous one.
     *
     * THE REAL DIFFICULTY DIAL, and it was missing. Lanes were picked uniformly
     * at random, so two tiles in a row could land in lane 0 and lane 4 — four
     * lane changes, and on a temple pad one flick is one lane. At wave 1's old
     * 2.2 s cadence that was not hard, it was arithmetically impossible, and no
     * amount of slowing the belt fixes it because the problem is DISTANCE, not
     * time. At 1 the next tile is always within a single flick.
     */
    @JvmField var laneJump: Int = COLS

    private var lastSpawnLane = COLS / 2

    private var spawnEvery = 2.0f       // seconds between tiles
    private var spawnTimer = 0f
    private var goalFired = false

    /**
     * Event plumbing. `evs` is a REUSED buffer — the list update() returns is
     * valid only until the next update(), because a fresh ArrayList every
     * frame is exactly the kind of drip that showed up on device as periodic
     * sub-second freezes. `pending` exists because drop()/flip() are called
     * from the input path, outside update(); their events queue here and are
     * drained on the next tick regardless of call order.
     */
    private val evs = ArrayList<Ev>(8)
    private val pending = ArrayList<Ev>(8)

    /** Scratch mask for the cells a resolve pass is about to wipe. */
    private val marks = Array(ROWS) { BooleanArray(COLS) }

    // ------------------------------------------------------------------
    // level setup

    /**
     * Difficulty lives entirely in these arguments, so the ramp is a table in
     * the caller rather than magic numbers buried in the rules. A goal of 0 is
     * disabled; when both are 0 the level simply never ends (attract mode).
     * When both are set, both must be met — a level asks for one thing or for
     * two, never for whichever happens first.
     */
    fun startLevel(level: Int, colours: Int, speed: Float, spawnEvery: Float,
                   goalKlaxes: Int, goalTiles: Int) {
        this.level = level
        this.colours = colours.coerceIn(1, MAX_COLOURS)
        this.speed = speed.coerceAtLeast(0.01f)
        // A spawn interval under ~0.15 s is unreadable at any conveyor speed,
        // and lets the while-loop below fire several tiles in one frame.
        this.spawnEvery = spawnEvery.coerceAtLeast(0.15f)
        this.goalKlaxes = goalKlaxes
        this.goalTiles = goalTiles

        // Level 1 IS the start of a run — there is no other entry point, and
        // treating it as one keeps the caller from having to reset lives by
        // hand (and from resuming a run that already ended).
        if (level <= 1) { score = 0; lives = 3 }
        over = lives <= 0

        klaxesMade = 0; tilesSeen = 0
        goalFired = false
        for (r in 0 until ROWS) well[r].fill(-1)
        paddle.clear()
        falling.clear()
        paddleLane = 2
        // First tile is a full interval away: the level title card gets read
        // before anything is at stake.
        spawnTimer = 0f
        lastSpawnLane = COLS / 2
        // `pending` is private, so clearing it here is free. `evs` is NOT
        // cleared, and that is deliberate: startLevel is normally called from
        // inside the caller's loop over the very list update() just returned
        // (GoalMet arrives, the campaign advances), and clearing a list while
        // its iterator is live throws ConcurrentModificationException. update()
        // clears `evs` at the top of every tick anyway, so nothing stale can
        // reach the new level.
        pending.clear()
    }

    // ------------------------------------------------------------------
    // tick

    fun update(dt: Float): List<Ev> {
        evs.clear()
        for (i in pending.indices) evs.add(pending[i])   // indexed: addAll copies
        pending.clear()
        if (over) return evs

        // NaN is not a long frame, it is a poisoned one, and coerceIn passes it
        // straight through (every comparison against NaN is false). One NaN
        // would leave spawnTimer and every tile's y as NaN for the rest of the
        // session: no tile ever arrives, no tile ever spawns, and the game sits
        // there looking alive. Treat it as a dropped frame.
        val d = if (dt.isNaN()) 0f else dt.coerceIn(0f, MAX_DT)

        spawnTimer += d
        while (spawnTimer >= spawnEvery) {
            spawnTimer -= spawnEvery
            spawnTile()
        }

        // Backwards, because arrivals remove from the list mid-walk.
        var i = falling.size - 1
        while (i >= 0) {
            val t = falling[i]
            if (t.flipped) {
                t.y += speed * FLIP_SPEEDUP * d
                if (t.y >= 1f) falling.removeAt(i)      // off the top, gone for good
            } else {
                t.y -= speed * d
                if (t.y <= 0f) {
                    t.y = 0f
                    falling.removeAt(i)
                    arrive(t)
                    if (over) break                     // no piling on after the last life
                }
            }
            i--
        }

        checkGoal()
        return evs
    }

    private fun spawnTile() {
        // Never drop a tile straight onto the tail of another in the same
        // lane: two overlapping tiles read as one, and a lane holding two is
        // unfair to a paddle that can only be in one place. A handful of
        // retries, then spawn anyway — if all five lanes are that crowded the
        // player is already drowning, and stalling the spawner would quietly
        // stall a "survive N tiles" goal too.
        val lo = (lastSpawnLane - laneJump).coerceAtLeast(0)
        val hi = (lastSpawnLane + laneJump).coerceAtMost(COLS - 1)
        var lane = lo + rnd.nextInt(hi - lo + 1)
        var tries = 0
        while (tries < 4 && laneBusy(lane)) { lane = lo + rnd.nextInt(hi - lo + 1); tries++ }
        lastSpawnLane = lane
        falling.add(Falling(lane, rnd.nextInt(colours), 1f))
        tilesSeen++
    }

    private fun laneBusy(lane: Int): Boolean {
        for (i in falling.indices) {
            val t = falling[i]
            if (t.lane == lane && !t.flipped && t.y > 0.72f) return true
        }
        return false
    }

    /** A tile has reached the paddle end of its lane. */
    private fun arrive(t: Falling) {
        // A full paddle cannot catch: the tile lands on tiles that are already
        // there and spills. Same outcome as an empty lane, same price.
        if (t.lane != paddleLane || paddle.size >= PADDLE_MAX) {
            evs.add(Ev.Missed)
            loseLife()
            return
        }
        paddle.add(t.colour)        // onto the top; index 0 stays the next to drop
        evs.add(Ev.Caught)
    }

    // ------------------------------------------------------------------
    // input — three gestures, three methods, nothing else

    /** The temple pad reports whole ±1 steps, so a step is a lane: no
     *  acceleration, no momentum, nothing to overshoot on a 5-lane board. */
    fun moveLane(dir: Int) {
        paddleLane = (paddleLane + dir).coerceIn(0, COLS - 1)
    }

    /**
     * Bottom paddle tile into well column [paddleLane]. Returns true if a tile
     * left the paddle at all — including the overflow case, which is a drop
     * that happened and went badly, not a drop that was refused.
     */
    fun drop(): Boolean {
        if (over || paddle.isEmpty()) return false
        val colour = paddle.removeAt(0)
        val h = wellHeight(paddleLane)
        if (h >= ROWS) {
            // The tile spills off the top of a full column and is lost. It is
            // NOT put back: a full column under a full paddle would otherwise
            // be a dead end with no legal move left, and the player should
            // always be able to drain the paddle and keep playing.
            pending.add(Ev.Overflow)
            loseLife()
            return true
        }
        well[h][paddleLane] = colour
        pending.add(Ev.Dropped)
        resolve()
        return true
    }

    /** Top paddle tile back up the conveyor. The escape valve for a colour
     *  the well has no use for; it costs tempo, never a life. */
    fun flip(): Boolean {
        if (over || paddle.isEmpty()) return false
        val colour = paddle.removeAt(paddle.size - 1)
        falling.add(Falling(paddleLane, colour, 0f, flipped = true))
        pending.add(Ev.Flipped)
        return true
    }

    /** First empty row in a column — its height, since columns never hold
     *  gaps (drops land on top, collapse() closes what a clear opens). */
    fun wellHeight(col: Int): Int {
        for (r in 0 until ROWS) if (well[r][col] < 0) return r
        return ROWS
    }

    private fun loseLife() {
        lives--
        if (lives <= 0) { lives = 0; over = true }
        // The well and the paddle survive: in Klax the punishment for a miss
        // is the lost tile and the lost tempo, not a board wipe.
    }

    // ------------------------------------------------------------------
    // klax detection

    /**
     * Find every klax, clear them all at once, let the well fall, and look
     * again. Two rules are hiding in that order:
     *
     * One drop can complete two lines through the same tile (the classic
     * horizontal-plus-diagonal). Clearing a line at a time would destroy the
     * second line's evidence before it was scored, so a pass collects ALL
     * lines currently in the well, emits one Cleared each, and wipes their
     * union together.
     *
     * And a collapse can land a klax nobody aimed at — the cascade — so the
     * pass repeats until the well stops changing. The guard is paranoia: 25
     * cells cannot cascade forever, but a runaway loop here would hang the GL
     * thread with no way to see why.
     */
    private fun resolve() {
        var guard = ROWS * COLS
        while (guard-- > 0) {
            for (r in 0 until ROWS) marks[r].fill(false)
            var found = 0

            for (r in 0 until ROWS) found += ray(r, 0, 0, 1, KIND_H)
            for (c in 0 until COLS) found += ray(0, c, 1, 0, KIND_V)
            // Diagonals are swept from the two edges each direction can start
            // from — left column plus bottom row, right column plus bottom row
            // — with the shared corner claimed once so no line is found twice.
            for (r in 0 until ROWS) found += ray(r, 0, 1, 1, KIND_D)
            for (c in 1 until COLS) found += ray(0, c, 1, 1, KIND_D)
            for (r in 0 until ROWS) found += ray(r, COLS - 1, 1, -1, KIND_D)
            for (c in 0 until COLS - 1) found += ray(0, c, 1, -1, KIND_D)

            if (found == 0) return
            klaxesMade += found
            for (r in 0 until ROWS) for (c in 0 until COLS) if (marks[r][c]) well[r][c] = -1
            collapse()
        }
    }

    /**
     * Walk one line of cells and emit its MAXIMAL same-colour runs of 3+.
     * Maximal matters: a run of four is one klax worth four, not two
     * overlapping klaxes worth three each.
     */
    private fun ray(r0: Int, c0: Int, dr: Int, dc: Int, kind: Int): Int {
        var found = 0
        var r = r0; var c = c0
        var runColour = -1
        var runLen = 0
        var rr = r0; var rc = c0        // first cell of the run in progress
        while (r in 0 until ROWS && c in 0 until COLS) {
            val v = well[r][c]
            if (v >= 0 && v == runColour) {
                runLen++
            } else {
                if (runLen >= 3) { emit(rr, rc, dr, dc, runLen, kind); found++ }
                runColour = v
                runLen = if (v >= 0) 1 else 0
                rr = r; rc = c
            }
            r += dr; c += dc
        }
        if (runLen >= 3) { emit(rr, rc, dr, dc, runLen, kind); found++ }
        return found
    }

    private fun emit(r0: Int, c0: Int, dr: Int, dc: Int, len: Int, kind: Int) {
        // Allocation is fine here and only here: a clear is a rare, loud event,
        // not part of the per-frame path.
        val cells = ArrayList<Int>(len)
        var r = r0; var c = c0
        for (i in 0 until len) {
            marks[r][c] = true
            cells.add(r * COLS + c)     // renderer bursts particles at exactly these
            r += dr; c += dc
        }
        val pts = points(kind, len)
        score += pts
        pending.add(Ev.Cleared(cells, kind, len, pts))
    }

    /**
     * Klax pays for difficulty, not for volume. A vertical is three tiles into
     * one column and is nearly free; a horizontal needs three lanes caught and
     * dropped at the same height; a diagonal needs both at once and is what
     * the whole game is really about. Length multiplies hard for the same
     * reason — a five is a plan, a three is a habit.
     *
     * Deliberately NOT scaled by level: the ramp is already in speed and
     * colour count, and doubling points per level would make the early klaxes
     * feel worthless in the HUD next to late ones.
     */
    private fun points(kind: Int, len: Int): Int {
        val base = when (kind) {
            KIND_H -> 100
            KIND_V -> 50
            else -> 500
        }
        val stretch = when {
            len <= 3 -> 1
            len == 4 -> 5
            else -> 20
        }
        return base * stretch
    }

    /** Gravity: close every gap a clear opened, keeping column order. */
    private fun collapse() {
        for (c in 0 until COLS) {
            var w = 0
            for (r in 0 until ROWS) {
                val v = well[r][c]
                if (v < 0) continue
                well[r][c] = -1
                well[w][c] = v          // w == r is a no-op write, not a bug
                w++
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Checked on the tick rather than at the moment a klax lands, so GoalMet
     * always arrives in the same event stream as everything else and never
     * fires twice.
     */
    private fun checkGoal() {
        if (goalFired || over) return
        if (goalKlaxes <= 0 && goalTiles <= 0) return            // endless level
        if (goalKlaxes > 0 && klaxesMade < goalKlaxes) return
        if (goalTiles > 0 && tilesSeen < goalTiles) return
        goalFired = true
        evs.add(Ev.GoalMet)
    }
}
