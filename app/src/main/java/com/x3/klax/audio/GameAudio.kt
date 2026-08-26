package com.x3.klax.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.x3.klax.Settings
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Audio backend — RayNeo X3 Pro edition, audio RE-ENABLED with a
 * stall-aware design.
 *
 * DEVICE HISTORY (verified with FRAME HITCH instrumentation): keeping a
 * loaded SoundPool or an always-on looping MediaPlayer open made the X3
 * audio stack stall the GL thread 500-630 ms every ~5-6 s. One-shot
 * playback tested clean. The design below restores sound within those
 * constraints:
 *
 *  - SFX: NO SoundPool. SfxMixer holds raw PCM in memory and opens ONE
 *    AudioTrack only while sounds are audible, releasing it the moment
 *    the mixer goes silent. SFX VOL 0 = the track never even opens.
 *  - MUSIC: looping MediaPlayer, but MUSIC VOL 0 fully RELEASES the
 *    players (not a mute) — the escape hatch is in the settings menu,
 *    no rebuild needed if stalls correlate with music on your firmware.
 *  - VOICE: one-shot MediaPlayers — create, play, release on completion.
 *
 * Watch logcat for "FRAME HITCH" (tag X3Render) after any audio change.
 */
class GameAudio(private val ctx: Context, private val settings: Settings) {

    /** All MediaPlayer work happens off the GL thread. */
    private val exec = Executors.newSingleThreadExecutor()

    // ---- SFX: software mixer, no SoundPool anywhere --------------------
    private val mixer = SfxMixer()

    // ---- looping music (fully released while musicVol == 0) ------------
    private var musicTracks: List<String> = emptyList()
    private var titleTrack: String? = null
    private var powerupTrack: String? = null
    private var levelPlayer: MediaPlayer? = null
    private var powerupPlayer: MediaPlayer? = null
    private var currentLevelTrack = -1
    private var lastMusicLevel = 1        // to restart when vol leaves 0
    private var powerupWanted = false     // powerup phase active right now
    private var duckFactor = 1f

    // ---- commentary: one-shot only --------------------------------------
    private class Line(val id: String, val event: String, val quote: String)
    private val linesByEvent = HashMap<String, MutableList<Line>>()
    private var voicePlayer: MediaPlayer? = null
    @Volatile private var lastCommentMs = 0L
    private val commentCooldownMs = 3500L

    fun init() {
        mixer.load(ctx.assets, "sfx",
            listOf("move", "catch", "drop", "flip", "klax", "klax_big",
                   "level_up", "lose_life", "game_over", "ui"))
        val availableMusic = (ctx.assets.list("music") ?: emptyArray())
            .filter { it.endsWith(".mp3", true) }
            .sorted()
        titleTrack = availableMusic.firstOrNull { it.equals(TITLE_MUSIC, ignoreCase = true) }
            ?.let { "music/$it" }
        val orderedLevels = LEVEL_MUSIC_ORDER.filter { desired ->
            availableMusic.any { it.equals(desired, ignoreCase = true) }
        }
        val known = (LEVEL_MUSIC_ORDER + TITLE_MUSIC).map { it.lowercase() }.toSet()
        val extras = availableMusic.filter { it.lowercase() !in known }
        musicTracks = (orderedLevels + extras).map { "music/$it" }
        powerupTrack = (ctx.assets.list("powerup") ?: emptyArray())
            .filter { it.endsWith(".mp3", true) }.sorted().firstOrNull()?.let { "powerup/$it" }
        try {
            val json = ctx.assets.open("commentary.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(json).getJSONArray("lines")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val line = Line(o.getString("id"), o.getString("event"), o.optString("quote", ""))
                linesByEvent.getOrPut(line.event) { mutableListOf() }.add(line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "commentary.json: $t")
        }
        Log.i(TAG, "audio init: title=$titleTrack musicTracks=${musicTracks.size} first=${musicTracks.firstOrNull()} powerup=$powerupTrack voiceEvents=${linesByEvent.size}")
    }

    private fun cacheAsset(path: String): File {
        val out = File(ctx.cacheDir, path.replace('/', '_'))
        ctx.assets.open(path).use { input ->
            val assetSize = input.available().toLong()
            if (!out.exists() || out.length() != assetSize) {
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    /** One-shot SFX through the mixer. Cheap on the GL thread. */
    fun sfx(name: String, rate: Float = 1f) {
        mixer.play(name, settings.sfxVol / 10f, rate)
    }

    // ---- Level music -----------------------------------------------------

    /** Attract-screen music: the explicit MCP title song. */
    fun playTitleMusic() { exec.execute { playMusicNow(titleTrack, -2) } }

    fun playLevelMusic(level: Int) { exec.execute { playLevelMusicNow(level) } }

    private fun playLevelMusicNow(level: Int) {
        lastMusicLevel = level
        if (musicTracks.isEmpty() || settings.musicVol == 0) return
        val idx = (level - 1).mod(musicTracks.size)
        playMusicNow(musicTracks[idx], idx)
    }

    private fun playMusicNow(track: String?, trackIndex: Int) {
        if (track == null || settings.musicVol == 0) return
        if (trackIndex == currentLevelTrack && levelPlayer != null) {
            resumeLevelMusic(); return
        }
        stopLevelMusicNow()
        try {
            val f = cacheAsset(track)
            levelPlayer = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                isLooping = true
                prepare()
                applyMusicVol(this)
                start()
            }
            currentLevelTrack = trackIndex
        } catch (t: Throwable) {
            Log.w(TAG, "level music: $t")
        }
    }

    fun stopLevelMusic() { exec.execute { stopLevelMusicNow() } }

    private fun stopLevelMusicNow() {
        levelPlayer?.release(); levelPlayer = null; currentLevelTrack = -1
    }

    private fun pauseLevelMusic() { try { levelPlayer?.pause() } catch (_: Throwable) {} }
    private fun resumeLevelMusic() {
        try { if (levelPlayer?.isPlaying == false) levelPlayer?.start() } catch (_: Throwable) {}
    }

    fun startPowerupMusic() { exec.execute { powerupWanted = true; startPowerupMusicNow() } }

    private fun startPowerupMusicNow() {
        val track = powerupTrack ?: return
        if (powerupPlayer != null || settings.musicVol == 0) return
        pauseLevelMusic()
        try {
            val f = cacheAsset(track)
            powerupPlayer = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                isLooping = true
                prepare()
                applyMusicVol(this)
                start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "powerup music: $t")
        }
    }

    fun stopPowerupMusic() {
        exec.execute {
            powerupWanted = false
            powerupPlayer?.release(); powerupPlayer = null
            resumeLevelMusic()
        }
    }

    /**
     * MUSIC VOL changed. Volume 0 is a hard OFF: every music player is
     * RELEASED so no persistent audio object remains (X3 stall escape
     * hatch). Leaving 0 restarts whatever should be playing.
     */
    fun onMusicVolChanged() {
        exec.execute {
            if (settings.musicVol == 0) {
                powerupPlayer?.release(); powerupPlayer = null
                stopLevelMusicNow()
            } else {
                levelPlayer?.let { applyMusicVol(it) }
                powerupPlayer?.let { applyMusicVol(it) }
                if (levelPlayer == null && powerupPlayer == null) {
                    if (powerupWanted) startPowerupMusicNow()
                    if (powerupPlayer == null) playLevelMusicNow(lastMusicLevel)
                }
            }
        }
    }

    private fun applyMusicVol(mp: MediaPlayer) {
        val v = settings.musicVol / 10f * duckFactor
        try { mp.setVolume(v, v) } catch (_: Throwable) {}
    }

    private fun duckMusic(on: Boolean) {
        duckFactor = if (on) 0.18f else 1f
        levelPlayer?.let { applyMusicVol(it) }
        powerupPlayer?.let { applyMusicVol(it) }
    }

    // ---- Commentary: one-shot voice ---------------------------------------

    /**
     * Fire a commentary event; picks a random configured line for it.
     * Returns the chosen quote so the caller can caption it verbatim,
     * or null if nothing fired. Forced story beats always speak; optional
     * chatter respects the COMMENTARY toggle. Caption display is the
     * caller's business (CAPTIONS toggle).
     */
    fun say(event: String, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force && now - lastCommentMs < commentCooldownMs) return null
        val line = linesByEvent[event]?.randomOrNull() ?: return null
        lastCommentMs = now
        if (force || settings.commentary) exec.execute { playClip(line) }
        return line.quote
    }

    /** One-shot: create -> play -> release on completion/error. */
    private fun playClip(line: Line) {
        try {
            val f = cacheAsset("voice/${line.id}.mp3")
            voicePlayer?.release()   // interrupt a still-playing line
            voicePlayer = null
            duckMusic(true)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.setVolume(1f, 1f)
            mp.setOnCompletionListener { done ->
                exec.execute {
                    done.release()
                    if (voicePlayer == done) {
                        voicePlayer = null
                        duckMusic(false)
                    }
                }
            }
            mp.setOnErrorListener { done, _, _ ->
                exec.execute {
                    try { done.release() } catch (_: Throwable) {}
                    if (voicePlayer == done) {
                        voicePlayer = null
                        duckMusic(false)
                    }
                }
                true
            }
            mp.start()
            voicePlayer = mp
            Log.i(TAG, "voice ${line.id} one-shot (${f.length()} bytes)")
        } catch (t: Throwable) {
            duckMusic(false)
            Log.i(TAG, "voice clip MISSING for ${line.id} — run tools/generate_commentary.py and rebuild: $t")
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    fun pauseAll() {
        exec.execute {
            try { voicePlayer?.release() } catch (_: Throwable) {}
            voicePlayer = null
            duckMusic(false)
            pauseLevelMusic()
            try { powerupPlayer?.pause() } catch (_: Throwable) {}
        }
    }

    fun resumeAll() {
        exec.execute {
            if (powerupPlayer != null) {
                try { powerupPlayer?.start() } catch (_: Throwable) {}
            } else resumeLevelMusic()
        }
    }

    fun release() {
        exec.execute { releaseNow() }
        exec.shutdown()
        mixer.release()
    }

    private fun releaseNow() {
        stopLevelMusicNow()
        powerupPlayer?.release(); powerupPlayer = null
        voicePlayer?.release(); voicePlayer = null
    }

    companion object {
        private const val TAG = "X3KlaxAudio"
        private const val TITLE_MUSIC = "mcp.mp3"
        private val LEVEL_MUSIC_ORDER = listOf(
            "03 - IO Tower (Cover).mp3",
            "Astro Vinyl.mp3",
            "New Song Arpeggio (Cover).mp3",
            "Close Encounters Arpeggios 2 (Remastered x2) (Cover).mp3",
            "Disk Check Arpeggios.mp3",
            "Digital Overture.mp3",
            "Neon Pulse.mp3",
            "Neon Pulse-2.mp3",
            "Neon Pulse-3.mp3",
            "Neon Pulse-4.mp3",
            "Late Office Arpeggios.mp3",
            "fletch dark chip arpeggio.mp3",
            "Digging Deeper.mp3",
            "The Clockwork Dance.mp3",
            "Raga Thunder.mp3",
            "Sitar Thunder.mp3"
        )
    }
}
