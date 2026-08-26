package com.x3.klax.audio

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Software SFX mixer for the RayNeo X3 Pro — the SoundPool replacement.
 *
 * Why not SoundPool: a loaded SoundPool held open makes the X3 audio
 * stack stall the GL thread ~500-630 ms every ~5-6 s (verified with
 * FRAME HITCH instrumentation). One-shot playback tested clean. So:
 *
 *  - WAV clips are decoded to plain PCM ShortArrays at init — pure
 *    memory, zero audio objects at rest.
 *  - Active sounds are mixed in software on one daemon thread into a
 *    single mono AudioTrack that is CREATED when sound starts and
 *    STOPPED + RELEASED as soon as the mixer goes silent. While the
 *    game is quiet, no Android audio object exists at all.
 *  - Pitch via nearest-neighbour resampling (the `rate` parameter).
 *
 * If FRAME HITCH stalls ever correlate with SFX bursts, set SFX VOL to
 * 0 in the settings menu — play() is skipped entirely at zero volume
 * and the track never opens.
 */
class SfxMixer {

    private val clips = HashMap<String, ShortArray>()

    private class Slot {
        var data: ShortArray? = null
        var pos = 0f
        var step = 1f
        var vol = 0f
    }

    private val slots = Array(8) { Slot() }
    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Parse 16-bit PCM WAVs from assets into memory. Call once at init. */
    fun load(assets: AssetManager, dir: String, names: List<String>) {
        for (name in names) {
            try {
                val bytes = assets.open("$dir/$name.wav").use { it.readBytes() }
                val data = parseWav(bytes)
                if (data != null) clips[name] = data
                else Log.w(TAG, "sfx $name: unsupported wav layout")
            } catch (t: Throwable) {
                Log.w(TAG, "sfx $name missing: $t")
            }
        }
        Log.i(TAG, "sfx mixer loaded ${clips.size} clips (pure PCM, no audio objects)")
    }

    /** Find the 'data' chunk of a canonical RIFF/WAVE 16-bit PCM file. */
    private fun parseWav(b: ByteArray): ShortArray? {
        if (b.size < 44) return null
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val len = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                      ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val n = minOf(len, b.size - i - 8) / 2
                val out = ShortArray(n)
                var p = i + 8
                for (j in 0 until n) {
                    out[j] = ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)).toShort()
                    p += 2
                }
                return out
            }
            i += 8 + len + (len and 1)
        }
        return null
    }

    /** Fire-and-forget one-shot. Safe from any thread; cheap on the GL thread. */
    fun play(name: String, vol: Float, rate: Float = 1f) {
        if (vol <= 0.01f) return
        val data = clips[name] ?: return
        synchronized(lock) {
            val s = slots.firstOrNull { it.data == null } ?: slots[0] // steal oldest
            s.data = data
            s.pos = 0f
            s.step = rate.coerceIn(0.25f, 4f)
            s.vol = vol.coerceIn(0f, 1f)
            if (thread == null) startThread()
            lock.notifyAll()
        }
    }

    private fun startThread() {
        running = true
        thread = Thread {
            val buf = ShortArray(512)          // ~23 ms blocks at 22050 Hz
            var track: AudioTrack? = null
            while (running) {
                var any = false
                synchronized(lock) {
                    java.util.Arrays.fill(buf, 0)
                    for (s in slots) {
                        val d = s.data ?: continue
                        any = true
                        var i = 0
                        while (i < buf.size) {
                            val p = s.pos.toInt()
                            if (p >= d.size) { s.data = null; break }
                            val mixed = buf[i] + (d[p] * s.vol).toInt()
                            buf[i] = mixed.coerceIn(-32768, 32767).toShort()
                            s.pos += s.step
                            i++
                        }
                    }
                }
                if (any) {
                    if (track == null) track = openTrack()
                    try {
                        track?.write(buf, 0, buf.size)   // blocking write paces us
                    } catch (t: Throwable) {
                        Log.w(TAG, "track write: $t")
                        try { track?.release() } catch (_: Throwable) {}
                        track = null
                    }
                } else {
                    // Silence: close the audio path COMPLETELY, then sleep
                    // until the next play(). Nothing persistent stays open.
                    track?.let {
                        try { it.stop() } catch (_: Throwable) {}
                        try { it.release() } catch (_: Throwable) {}
                    }
                    track = null
                    synchronized(lock) {
                        if (running && slots.all { it.data == null }) {
                            try { lock.wait() } catch (_: InterruptedException) {}
                        }
                    }
                }
            }
            track?.let { try { it.release() } catch (_: Throwable) {} }
        }.apply { isDaemon = true; name = "SfxMixer"; start() }
    }

    private fun openTrack(): AudioTrack? = try {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    } catch (t: Throwable) {
        Log.w(TAG, "openTrack: $t")
        null
    }

    fun release() {
        running = false
        synchronized(lock) {
            for (s in slots) s.data = null
            lock.notifyAll()
        }
        thread = null
    }

    companion object {
        private const val TAG = "X3KlaxSfx"
        const val SAMPLE_RATE = 22050   // matches the generated assets/sfx WAVs
    }
}
