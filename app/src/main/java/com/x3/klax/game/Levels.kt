package com.x3.klax.game

/**
 * KLAX wave table.
 *
 * A wave is pure data: how fast the conveyor runs, how often it coughs up
 * a tile, how many colours are in play, what the player has to do to
 * clear, and which track plays underneath. No behaviour lives here — the
 * game reads these numbers and does the work.
 *
 * UNITS (the whole file depends on agreeing on these):
 *   speed       LANE UNITS per second, exactly as `Klax.startLevel` takes
 *               it — the caller passes this field straight through with no
 *               conversion, and adding one would silently break every
 *               number below. A falling tile's y runs 1.0 at the spawn lip
 *               down to 0.0 at the paddle (see Klax.update), so the whole
 *               run is 1.0 unit and seconds-of-warning = 1 / speed. That
 *               reaction time is the real difficulty dial; the numbers
 *               below were chosen by picking the reaction time first and
 *               solving for speed, not the other way round.
 *
 *               How far a lane is drawn in world units is the renderer's
 *               business alone. If the conveyor is later drawn longer or
 *               shorter, NOTHING here changes — which is the point of
 *               keeping the model normalized.
 *   spawnEvery  seconds between tile spawns. Tiles in flight at once is
 *               roughly (1 / speed) / spawnEvery — the OTHER difficulty
 *               dial, and the one that decides whether the player is
 *               reading the board or panicking.
 *   colours     size of the tile-colour pool, 3..5. Five is the design
 *               ceiling (KLAX itself never used more) so late waves have
 *               to get their pressure from speed and goals instead. The
 *               model's hard clamp is the package-level MAX_COLOURS in
 *               Klax.kt, which is 6; we deliberately stay under it.
 *   goalKlaxes  clear when this many klaxes have been made. 0 = not this
 *               kind of wave.
 *   goalTiles   clear when this many tiles have been handled. 0 = not
 *               this kind of wave.
 *               EXACTLY ONE of the two is non-zero on every wave, so the
 *               consumer can just branch on `goalKlaxes > 0`.
 *   music       0-based index into the ten level tracks on disk, which
 *               sort as 01_neon_pulse .. 10_mcp. Index == filename
 *               number minus one.
 *
 * WHY THE EARLY CURVE IS THIS GENTLE
 *
 * The brief is "easy but fun to start out with", and on this hardware
 * that is not politeness, it is a hard requirement. Three things are
 * stacked against a new player before difficulty even enters:
 *
 *  1. NOBODY KNOWS KLAX. It is a 1990 cabinet with a rule nobody
 *     remembers — you drop the BOTTOM tile of the paddle stack, and a
 *     klax is three in a line in the well including diagonals. Wave 1 has
 *     to teach that by letting it happen, which means the player needs
 *     slack to fumble, drop wrong, and watch what the well does about it.
 *     Three colours means random play alone produces near-misses that
 *     read as "oh, THAT is what I'm building".
 *
 *  2. THE INPUT IS THREE GESTURES. Tap, double tap, swipe — that is the
 *     entire vocabulary, on a temple pad, with no detent and no visible
 *     control to look at. Every action costs more thought than a joystick
 *     would, and mis-swipes are guaranteed for the first minute. Wave 1
 *     budgets six seconds of warning per tile precisely so a mis-swipe is
 *     recoverable instead of fatal.
 *
 *  3. IT IS A SEE-THROUGH DISPLAY ON YOUR FACE. The playfield competes
 *     with the actual room. Early waves stay sparse (~2.7 tiles in flight)
 *     because a crowded conveyor on an additive waveguide stops being a
 *     board and becomes glare.
 *
 * So wave 1 is deliberately close to unloseable: 3 colours, six full
 * seconds from lip to paddle, a tile only every 2.2 s, and a goal of 3
 * klaxes that falls out of ordinary play. It is a tutorial that never
 * says the word tutorial.
 *
 * From there the ramp is shaped, not linear. Waves 1-4 add only 8-10% of
 * speed each — small enough that it reads as "I'm getting better", not
 * "the game sped up". Waves 5-9 settle into the ~15% the brief asked for,
 * once the player has the drop in their fingers. Wave 10 adds only 11%,
 * because the boss wave should be won or lost on the well filling up
 * under a five-colour spread, not on raw conveyor speed outrunning the
 * paddle.
 *
 * Reaction time per wave (1 / speed), which is the number worth reading:
 *
 *   1  6.0s   2  5.6s   3  5.1s   4  4.6s   5  4.1s
 *   6  3.6s   7  3.1s   8  2.7s   9  2.4s  10  2.1s
 *
 * Even wave 10 leaves two full seconds per tile. That is the concession
 * to the three-gesture input: there is a floor below which the control
 * scheme, not the player, is what fails.
 *
 * Goal types alternate — klax-count, survive-N-tiles, klax-count — so the
 * campaign never feels like the same errand ten times. The survive waves
 * double as a breather: they reward not dying rather than performing, and
 * they are where a struggling player gets to bank a little confidence.
 *
 * NAMES are capped at 12 characters. The display is 640px per eye and the
 * vector font is wide; anything longer starts colliding with the HUD.
 */
class Wave(
    val n: Int,
    val name: String,
    val colours: Int,
    val speed: Float,
    val spawnEvery: Float,
    val goalKlaxes: Int,
    val goalTiles: Int,
    val music: Int,
    /** Max lanes a tile may spawn from the previous one. 1 = always one flick. */
    val laneJump: Int
)

object Levels {

    /**
     * Tile-colour ceiling. KLAX never went past five and neither do we.
     *
     * NOT named MAX_COLOURS: Klax.kt declares a package-level
     * `const val MAX_COLOURS = 6` (the model's hard clamp), and a member
     * with the same name would shadow it inside this object. That compiles
     * fine and is exactly why it is dangerous — two different ceilings
     * under one name, with which one you got depending on where you stood.
     */
    private const val WAVE_COLOURS_MAX = 5

    /**
     * Speed ceiling for looped waves. Past this a tile crosses the lane in
     * under 1.4 s, which is less time than it takes to swipe the paddle
     * across all five lanes. Beyond the cap the game stops being hard and
     * starts being broken.
     */
    private const val SPEED_CAP = 0.72f

    /** Spawn floor for looped waves, for the same reason from the other side. */
    private const val SPAWN_FLOOR = 0.55f

    /** Per-lap difficulty step once the table wraps. */
    private const val LAP_BOOST = 0.16f

    val all: List<Wave> = listOf(
        // n  name            col   speed   spawn  klax  tiles  music
        //  Six seconds of warning, three colours, a goal that happens on
        //  its own. This wave exists to teach the drop, not to test it.
        Wave(1, "NEON DAWN", 3, 0.1400f, 4.00f, 3, 0, 0, 1),

        //  Same three colours, barely faster. The change here is the GOAL:
        //  survive 25 tiles. It quietly says "you don't have to score to
        //  advance, you have to not drop things" — which is the other half
        //  of the game.
        Wave(2, "CHROME TIDE", 3, 0.1500f, 3.70f, 0, 25, 1, 1),

        //  Fourth colour arrives. Klaxes stop being accidental and start
        //  needing a plan, so the goal only creeps 3 -> 4.
        Wave(3, "VIOLET DRIFT", 4, 0.1650f, 3.40f, 4, 0, 2, 2),

        //  Breather at four colours: survive 35. Consolidates the new
        //  colour before the last one shows up.
        Wave(4, "NIGHT WIRE", 4, 0.1800f, 3.10f, 0, 35, 3, 2),

        //  Full five-colour spread, the one wave 4 was holding back.
        //  Everything the game will ever throw is now on the table; from
        //  here difficulty is tempo, not vocabulary.
        Wave(5, "LASER MILE", WAVE_COLOURS_MAX, 0.1980f, 2.90f, 5, 0, 4, 2),

        //  First wave where the ramp reaches the full ~15%. Survive 45.
        Wave(6, "BLACK ICE", WAVE_COLOURS_MAX, 0.2180f, 2.70f, 0, 45, 5, 3),

        //  Three seconds of warning. The player should now be reading the
        //  well ahead of the conveyor rather than reacting to it.
        Wave(7, "TOWER RUN", WAVE_COLOURS_MAX, 0.2400f, 2.50f, 6, 0, 6, 3),

        //  Density stops falling here: through waves 1-7 spawnEvery and
        //  speed tighten in step, so concurrency drifts DOWN (2.73 -> 2.40
        //  tiles in flight). From wave 8 spawnEvery tightens faster and
        //  the number climbs back, so the board reads busier again.
        Wave(8, "PULSE STORM", WAVE_COLOURS_MAX, 0.2650f, 2.30f, 0, 55, 7, 4),

        //  Last wave before the boss. Seven klaxes at five colours means
        //  the well has to be managed, not just fed.
        Wave(9, "GHOST SIGNAL", WAVE_COLOURS_MAX, 0.2920f, 2.10f, 7, 0, 8, 5),

        //  10_mcp — the boss track. Speed only +11%: the pressure is the
        //  eight-klax goal and the well filling underneath it. A finale
        //  should be beaten by playing well, not by having faster hands.
        Wave(10, "MCP RISING", WAVE_COLOURS_MAX, 0.3200f, 1.90f, 8, 0, 9, 5)
    )

    val count: Int = all.size

    /**
     * Wave for a 1-based level, wrapping past the end of the table.
     *
     * Lap 0 hands back the table entry untouched. Every lap after that
     * reuses the shape of the wave but tightens it: full colour spread
     * from the first wave on (the teaching waves have done their job and
     * would otherwise feel like a step backwards), speed up and spawn
     * gap down by LAP_BOOST, and goals grown so a lap-2 clear is worth
     * more than a lap-1 clear. Both are clamped — see SPEED_CAP.
     *
     * The name is NOT decorated on later laps. "VIOLET DRIFT II" busts the
     * 12-character budget, and the HUD already shows the real level number.
     */
    fun get(level: Int): Wave {
        val n = level.coerceAtLeast(1)
        val lap = (n - 1) / count
        val base = all[(n - 1) % count]
        if (lap == 0) return base

        val boost = 1f + LAP_BOOST * lap
        return Wave(
            n = n,
            name = base.name,
            colours = WAVE_COLOURS_MAX,
            speed = (base.speed * boost).coerceAtMost(SPEED_CAP),
            spawnEvery = (base.spawnEvery / boost).coerceAtLeast(SPAWN_FLOOR),
            goalKlaxes = if (base.goalKlaxes > 0) base.goalKlaxes + 2 * lap else 0,
            goalTiles = if (base.goalTiles > 0) base.goalTiles + 10 * lap else 0,
            music = base.music,
            // Each lap widens how far a tile may appear from the last one.
            // Past the table this is the difficulty that still has room to
            // grow: the belt is already near SPEED_CAP, but distance between
            // consecutive tiles keeps asking more of the player.
            laneJump = (base.laneJump + lap).coerceAtMost(COLS)
        )
    }
}
