package de.honeybalecta.vorlesepult

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** Fertige Sprachausgabe eines Satzes: Abtastwerte (-1…1) und Abtastrate. */
class Audio(val samples: FloatArray, val sampleRate: Int) {
    val seconds: Double get() = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0
}

/** Spielt Sätze nacheinander über AudioTrack ab; ein Satz zur Zeit, jederzeit abbrechbar. */
class Player(ctx: Context, private val onInterrupted: () -> Unit) {
    companion object { private const val TAG = "VP.Player" }

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focusReq: AudioFocusRequest? = null
    private var hasFocus = false

    private val lock = Object()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var serial = 0L

    /** Hörfokus holen, damit Musik anderer Apps pausiert. */
    @Synchronized fun focus() {
        if (hasFocus) return
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    hasFocus = false
                    stop()
                    onInterrupted()
                }
            }.build().also { focusReq = it }
        hasFocus = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Synchronized fun unfocus() {
        if (!hasFocus) return
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        hasFocus = false
    }

    /**
     * Spielt [audio] ab und ruft [onEnd] mit true auf, wenn der Satz vollständig gespielt wurde,
     * mit false bei Abbruch. Ein laufender Satz wird vorher gestoppt.
     */
    fun play(audio: Audio, onEnd: (Boolean) -> Unit) {
        stop()
        val my = synchronized(lock) { ++serial }
        if (audio.samples.isEmpty() || audio.sampleRate <= 0) { onEnd(true); return }
        val worker = Thread(run@{
            var ok = false
            var at: AudioTrack? = null
            try {
                val sr = audio.sampleRate
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val buf = max(minBuf, sr * 4 / 2) // ~ halbe Sekunde
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                at = t
                var superseded = false
                synchronized(lock) { if (my != serial) superseded = true else track = t }
                if (superseded) return@run
                t.play()
                val s = audio.samples
                var off = 0
                while (off < s.size) {
                    synchronized(lock) { if (my != serial) superseded = true }
                    if (superseded) return@run
                    val n = min(4096, s.size - off)
                    val w = t.write(s, off, n, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { Log.w(TAG, "write fehlgeschlagen: $w"); return@run }
                    off += w
                }
                // Warten, bis alles gespielt ist (Kopfposition erreicht Ende), höchstens Dauer + 2 s
                val total = s.size
                val deadline = System.currentTimeMillis() + (audio.seconds * 1000).toLong() + 2000
                while (System.currentTimeMillis() < deadline) {
                    synchronized(lock) { if (my != serial) superseded = true }
                    if (superseded) return@run
                    val pos = t.playbackHeadPosition.toLong() and 0xffffffffL
                    if (pos >= total) break
                    Thread.sleep(15)
                }
                ok = true
            } catch (e: Exception) {
                Log.w(TAG, "Wiedergabe abgebrochen", e)
            } finally {
                runCatching { at?.stop() }
                runCatching { at?.release() }
                synchronized(lock) { if (track === at) track = null }
                onEnd(ok)
            }
        }, "vp-play")
        synchronized(lock) { thread = worker }
        worker.start()
    }

    /** Laufende Wiedergabe sofort beenden. */
    fun stop() {
        val at = synchronized(lock) { ++serial; val x = track; track = null; x }
        if (at != null) {
            runCatching { at.pause() }
            runCatching { at.flush() }
        }
    }

    fun release() { stop(); unfocus() }
}
