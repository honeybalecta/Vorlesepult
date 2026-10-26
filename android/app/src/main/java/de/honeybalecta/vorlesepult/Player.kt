package de.honeybalecta.vorlesepult

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** Fertige Sprachausgabe eines Satzes: Abtastwerte (-1…1) und Abtastrate. */
class Audio(val samples: FloatArray, val sampleRate: Int) {
    val seconds: Double get() = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0
}

/** Spielt einen Satz über AudioTrack ab; ein Satz zur Zeit, jederzeit abbrechbar, mit merkbarer Position fürs Weiterlesen. */
class Player {
    companion object {
        private const val TAG = "VP.Player"
        val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val lock = Object()
    private var track: AudioTrack? = null
    private var serial = 0L
    private var startFrame = 0
    private var totalFrames = 0

    /**
     * Spielt [audio] ab [start] (Abtastwert) und ruft [onEnd] mit true auf, wenn der Satz vollständig gespielt wurde,
     * mit false bei Abbruch. Ein laufender Satz wird vorher gestoppt.
     */
    fun play(audio: Audio, start: Int, onEnd: (Boolean) -> Unit) {
        stop()
        val from = start.coerceIn(0, audio.samples.size)
        val my = synchronized(lock) { startFrame = from; totalFrames = audio.samples.size; ++serial }
        if (audio.samples.size - from <= 0 || audio.sampleRate <= 0) { onEnd(true); return }
        val worker = Thread(run@{
            var ok = false
            var at: AudioTrack? = null
            try {
                val sr = audio.sampleRate
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val buf = max(minBuf, sr * 4 / 2) // ~ halbe Sekunde
                val t = AudioTrack.Builder()
                    .setAudioAttributes(ATTRS)
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
                var off = from
                while (off < s.size) {
                    synchronized(lock) { if (my != serial) superseded = true }
                    if (superseded) return@run
                    val n = min(4096, s.size - off)
                    val w = t.write(s, off, n, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { Log.w(TAG, "write fehlgeschlagen: $w"); return@run }
                    if (w == 0) { Thread.sleep(5); continue }
                    off += w
                }
                // Warten, bis alles gespielt ist (Kopfposition erreicht Ende), höchstens Dauer + 2 s
                val need = (s.size - from).toLong()
                val deadline = System.currentTimeMillis() + (need * 1000 / sr) + 2000
                while (System.currentTimeMillis() < deadline) {
                    synchronized(lock) { if (my != serial) superseded = true }
                    if (superseded) return@run
                    val pos = t.playbackHeadPosition.toLong() and 0xffffffffL
                    if (pos >= need) break
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
        worker.start()
    }

    /**
     * Laufende Wiedergabe sofort beenden. Liefert die erreichte Position (Abtastwert im Satz),
     * damit später an dieser Stelle weitergelesen werden kann; -1, wenn nichts lief.
     */
    fun stop(): Int {
        val (at, from, total) = synchronized(lock) { ++serial; val x = track; track = null; Triple(x, startFrame, totalFrames) }
        if (at == null) return -1
        val played = runCatching { at.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L)
        runCatching { at.pause() }
        runCatching { at.flush() }
        return (from + played).coerceIn(0L, total.toLong()).toInt()
    }
}
