# x3klax — KLAX (1990), neon remix, for the RayNeo X3 Pro

Free software, licensed under the GNU GPL v3 (see LICENSE). This project is
derived from `x3breakout`, which is GPL v3, so it stays GPL v3.

Tiles tumble down a conveyor. Catch them on the paddle, drop them into the well,
make lines of three. Diagonals score most.

Built on the `x3breakout` chassis: the stereo renderer, neon line batch, vector
font, temple touchpad, head tracker and music/SFX layer are all inherited. What
is new here is the game.

## Controls — the touchpad is the whole vocabulary

| gesture | in play | on a title / wave-clear / game-over screen |
|---|---|---|
| swipe | move the paddle one lane | — |
| tap | drop the bottom tile into the well | start / next wave / retry |
| double tap | flip the top tile back up the conveyor | — |

There is no in-play menu gesture, on purpose. A wearer whose hands are busy
elsewhere cannot be asked to remember a fourth thing.

**One flick moves exactly one lane**, however far your finger travels.
`TemplePad` quantises a stroke into a step every 90 px and emits them in a
`while` loop, so a 500 px flick arrives as five separate `onSwipe` calls — which
sent the paddle five lanes across a five-lane board. `LANE_GAP_MS` (220 ms)
collapses one stroke to one move while leaving deliberate repeat flicks working.

That bug also looked like a *sound* bug: with the paddle overshooting every tile,
nothing was ever caught, and with an empty paddle there was nothing to drop — so
the `catch` and `drop` cues appeared to be missing when they were simply never
reached.

## Why it looks like this

Everything is glowing line art on absolute black. The waveguide is **additive** —
black is not a colour, it is the absence of one, and the wearer sees their room
through it. A filled rectangle would hang a grey sheet over the real world and
spend battery drawing it. So the well is an open lattice, a tile is an outline
with an inner bar, and a cleared klax is celebrated with **particles**: on a
display that cannot flash the screen white, motion is the only exclamation mark
available.

The board leans back 22° (`ISO_TILT`). That lean is what makes a tile at the far
end of the conveyor genuinely further away in stereo, rather than merely higher
up the screen.

`FAR_SCALE` is the other half of the depth, and it must stay **below 1**. It was
1.55 at first, which splayed the rails outward as they receded and drew a tile at
the far end half again as big as one arriving at the paddle — inverse
perspective, which the eye reads as the far end looming toward you. Converging to
42% is what makes the belt feel deep, and unlike making it taller it costs no
vertical screen space, which is the only reason a doubled playfield fits
alongside it.

**If nothing appears on screen, check that `batch.setBasis(...)` is still called
in `Game.update`.** Coordinates in `Game.kt` are game metres; the batch draws in
world units. Without the basis the entire board renders at 1/92 scale — a couple
of lit pixels near the centre, indistinguishable from an app that failed to
start.

## Waves and music

`game/Levels.kt` holds ten waves. Wave 1 is deliberately almost unloseable —
three colours, seven seconds of fall time, a tile every four seconds, three
klaxes to clear. Goal types alternate between "make N klaxes" and "survive N
tiles" so it does not feel like one task repeated ten times.

**`laneJump` is the real difficulty dial, not speed.** Lanes were originally
picked uniformly at random, so consecutive tiles could land in lane 0 and lane 4
— four lane changes, and one flick moves one lane. At the old 2.2 s cadence that
was not merely hard, it was arithmetically impossible, and slowing the belt could
never fix it because the problem is DISTANCE, not time. `laneJump` caps how far a
tile may appear from the previous one: 1 at wave 1 (always within a single
flick), widening to 5 by wave 9, and widening again on every endless lap, where
it is the difficulty that still has room to grow once the belt is near
`SPEED_CAP`.

Verified on device: wave 1 spawns every 4.0 s with lane sequences like
`1 2 3 2 1 1 2 3` — never a jump greater than one.

Music lives in `assets/music/`, and **alphabetical order is level order**:

| wave | track |
|---|---|
| 1 | `01_neon_pulse` |
| 2 | `02_astro_vinyl` |
| 3 | `03_disk_check` |
| 4 | `04_neon_pulse_2` |
| 5 | `05_dark_chip` |
| 6 | `06_late_office` |
| 7 | `07_io_tower` |
| 8 | `08_neon_pulse_3` |
| 9 | `09_neon_pulse_4` |
| 10 | `10_mcp` |

`title_digital_overture.mp3` plays on the attract screen. To swap a track, drop a
replacement in with the same numeric prefix and rebuild.

## Sound effects

`assets/sfx/*.wav` are **generated, not sampled** — saw, square and triangle
oscillators in A-minor pentatonic. A klax is a rising A–C–E arpeggio; a lost life
is a filtered noise sweep over a falling saw.

**They live an octave above the music, and every one starts with a click.** The
first set was written in the 293–440 Hz range "so they would sit inside the
tracks" — which is exactly where a synthwave bass and its pads already are, so
`catch` was inaudible at 32% peak even though it was firing correctly. They now
sit at 880–1760 Hz, peak near full scale, and open with a ~6 ms noise transient:
the ear locates a sound by its attack, and a cue that fades in politely under a
track that is already playing will not be noticed at all.

Nothing ducks the music — `duckMusic` exists but only ever fired for the voice
lines, which this game does not use. If a cue still gets lost, that is the knob.

## Build

```
./gradlew :app:assembleDebug
adb -s <glasses-serial> install -r app/build/outputs/apk/debug/x3klax.apk
```

Two devices are usually attached (glasses and phone), so `-s` is not optional.
The glasses report `model:ARGF20`, `manufacturer:RayNeo`.

## Layout gotcha, learned the hard way

The vector font advances roughly **seven times its size parameter per
character**. Nine characters at size 0.0026 is 165 mm wide, and there is only
about 130 mm of screen to the right of the score — which is how the wave name
came to be drawn straight through the life pips. Stacked lines need ~11 mm of
clearance at size 0.0022 and ~14 mm at 0.0028.
