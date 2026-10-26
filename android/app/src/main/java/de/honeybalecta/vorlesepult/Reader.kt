package de.honeybalecta.vorlesepult

import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Der Vorleser: hält die Satzliste, liest Satz für Satz, lässt die nächsten Sätze vorausberechnen und kennt
 * Pause, Weiter, Sprünge und Stopp. Er gehört zur App, nicht zum Fenster – das Vorlesen läuft deshalb auch
 * im Hintergrund und bei gesperrtem Bildschirm weiter; [PlaybackService] hält den Prozess dafür am Leben
 * und stellt die Steuerung in der Benachrichtigung bereit.
 *
 * Alle Methoden laufen auf dem Haupt-Thread; Aufrufe von anderen Threads werden dorthin verlegt.
 */
class Reader(private val ctx: Context, private val engine: Engine, private val player: Player) {
    companion object {
        private const val TAG = "VP.Reader"
        private const val PREFETCH = 2           // so viele Sätze im Voraus berechnen
        private const val REWIND_MS = 400        // beim Weiterlesen ein Stück zurück
        private const val WAKE_MS = 10 * 60 * 1000L
    }

    enum class State(val key: String) { IDLE("idle"), LOADING("loading"), PLAYING("playing"), PAUSED("paused") }

    /** Ein Satz: Text für die Sprachausgabe und Pause danach in Millisekunden. */
    class Item(val text: String, val gapMs: Int)

    interface Listener {
        fun onReaderChanged(reader: Reader)
        fun onReaderError(message: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    var state = State.IDLE
        private set
    var queue: List<Item> = emptyList()
        private set
    var index = 0
        private set
    /** Nummer der Satzliste, wie sie die Oberfläche vergeben hat – damit Markierung und Liste zusammenpassen. */
    var version = 0
        private set
    var title = ""
        private set
    /** Wahr, wenn das Ende des Dokuments erreicht wurde (und deshalb Ruhe ist). */
    var ended = false
        private set
    var voiceId: String = engine.voices.firstOrNull()?.id ?: ""
        private set
    var sid = 0
        private set
    var speed = 1f
        private set

    /** Jede Änderung des Ablaufs zählt hoch; verspätete Rückrufe älterer Generationen werden ignoriert. */
    private var gen = 0
    private var playingAudio: Audio? = null
    private var pausedAudio: Audio? = null
    private var pausedIndex = -1
    private var pausedFrame = 0

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusReq: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnFocus = false
    private val wakeLock = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vorlesepult:Wiedergabe").apply { setReferenceCounted(false) }
    private var serviceStarted = false

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    val isActive: Boolean get() = state != State.IDLE
    val currentText: String get() = queue.getOrNull(index)?.text ?: ""
    fun voiceName(): String = engine.voice(voiceId)?.name ?: voiceId

    fun snapshot(): JSONObject = JSONObject().apply {
        put("state", state.key); put("index", index); put("total", queue.size); put("version", version)
        put("title", title); put("voice", voiceId); put("sid", sid); put("speed", speed.toDouble()); put("ended", ended)
    }

    /** Satzliste aus JSON: [{"t":"Text für die Sprachausgabe","g":160}, …]. */
    fun parseQueue(json: String): List<Item> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.optString("t", ""), o.optInt("g", 160))
        }
    }

    // ── Steuerung ──

    /** Neue Satzliste übernehmen und bei [start] zu lesen beginnen. */
    fun play(items: List<Item>, start: Int, docTitle: String, ver: Int) = onMain {
        load(items, start, docTitle, ver)
        if (queue.isEmpty()) finish() else speakCurrent(0)
    }

    /** Satzliste übernehmen, aber noch nicht lesen: Pause mit markiertem Satz. */
    fun prepare(items: List<Item>, start: Int, docTitle: String, ver: Int) = onMain {
        load(items, start, docTitle, ver)
        state = if (queue.isEmpty()) State.IDLE else State.PAUSED
        changed()
    }

    private fun load(items: List<Item>, start: Int, docTitle: String, ver: Int) {
        gen++
        player.stop()
        engine.cancelAll()
        queue = items; index = start.coerceIn(0, maxOf(0, items.size - 1)); title = docTitle; version = ver
        ended = false; playingAudio = null; pausedAudio = null; pausedIndex = -1
    }

    /**
     * Satzliste nach einer Bearbeitung ersetzen, ohne den laufenden Satz zu unterbrechen.
     * [current] ist die Stelle des gerade gelesenen Satzes in der neuen Liste.
     */
    fun setQueue(items: List<Item>, current: Int, ver: Int) = onMain {
        if (state == State.IDLE) return@onMain
        if (items.isEmpty()) { stop(); return@onMain }
        queue = items; version = ver
        index = current.coerceIn(0, items.size - 1)
        if (pausedAudio != null) pausedIndex = index
        if (state == State.PLAYING) prefetchAhead()
        changed()
    }

    fun setTitle(t: String) = onMain { if (t != title) { title = t; if (state != State.IDLE) changed() } }

    /** Stimme, Sprecher:in und Tempo für die folgenden Sätze; ein laufender Satz beginnt mit der neuen Stimme von vorn. */
    fun setVoice(id: String, speaker: Int, rate: Float) = onMain {
        val r = rate.coerceIn(0.5f, 2.5f)
        if (id == voiceId && speaker == sid && r == speed) return@onMain
        voiceId = id; sid = speaker; speed = r
        pausedAudio = null; pausedIndex = -1
        if (state == State.PLAYING || state == State.LOADING) speakCurrent(0) else if (state != State.IDLE) changed()
    }

    fun resume() = onMain {
        when (state) {
            State.PAUSED -> {
                val a = pausedAudio
                if (a != null && pausedIndex == index) speakCurrent(maxOf(0, pausedFrame - a.sampleRate * REWIND_MS / 1000))
                else speakCurrent(0)
            }
            State.IDLE -> if (queue.isNotEmpty()) speakCurrent(0)
            else -> {}
        }
    }

    fun pause() = onMain { pauseImpl(releaseFocus = true) }

    private fun pauseImpl(releaseFocus: Boolean) {
        if (state != State.PLAYING && state != State.LOADING) return
        gen++
        val frame = player.stop()
        engine.cancelAll()
        val a = playingAudio
        if (frame >= 0 && a != null) { pausedAudio = a; pausedIndex = index; pausedFrame = frame }
        else { pausedAudio = null; pausedIndex = -1 }
        state = State.PAUSED
        if (releaseFocus) abandonFocus()
        changed()
    }

    fun toggle() = onMain { if (state == State.PLAYING || state == State.LOADING) pause() else resume() }

    /** Alles beenden; die Satzliste wird verworfen. */
    fun stop() = onMain {
        gen++
        player.stop(); engine.cancelAll()
        playingAudio = null; pausedAudio = null; pausedIndex = -1
        queue = emptyList(); index = 0; ended = false
        state = State.IDLE
        abandonFocus()
        changed()
    }

    private fun finish() {
        gen++
        player.stop(); engine.cancelAll()
        playingAudio = null; pausedAudio = null; pausedIndex = -1
        state = State.IDLE; ended = true
        abandonFocus()
        changed()
    }

    fun next() = onMain { if (index + 1 < queue.size) seek(index + 1) }
    fun prev() = onMain { if (index > 0) seek(index - 1) }

    /** Zu Satz [i] springen; läuft die Wiedergabe, geht es dort sofort weiter. */
    fun seek(i: Int) = onMain {
        if (queue.isEmpty()) return@onMain
        index = i.coerceIn(0, queue.size - 1)
        pausedAudio = null; pausedIndex = -1
        when (state) {
            State.PLAYING, State.LOADING -> speakCurrent(0)
            else -> changed()
        }
    }

    // ── Ablauf ──

    private fun speakCurrent(startFrame: Int) {
        val g = ++gen
        player.stop()
        val item = queue.getOrNull(index)
        if (item == null) { finish(); return }
        requestFocus()
        val key = engine.cacheKey(voiceId, sid, speed, item.text)
        val resumeAudio = pausedAudio?.takeIf { pausedIndex == index && startFrame > 0 }
        pausedAudio = null; pausedIndex = -1
        val ready = resumeAudio ?: engine.cached(key)
        if (ready != null) {
            // Laufende Vorausberechnungen dürfen weiterlaufen, wenn sie den nächsten Satz betreffen
            val nextKey = queue.getOrNull(index + 1)?.let { engine.cacheKey(voiceId, sid, speed, it.text) } ?: key
            engine.cancelForSpeak(nextKey)
            startAudio(g, ready, startFrame)
            return
        }
        state = State.LOADING; ended = false
        changed()
        engine.cancelForSpeak(key)
        val (v, s, r) = Triple(voiceId, sid, speed)
        engine.submit {
            val audio = try {
                engine.synthesize(key, v, s, r, item.text)
            } catch (e: Throwable) {
                Log.e(TAG, "Satz konnte nicht erzeugt werden", e)
                main.post { if (g == gen) fail(e.message ?: e.toString()) }
                return@submit
            }
            main.post { if (g == gen && audio != null) startAudio(g, audio, 0) }
        }
    }

    private fun startAudio(g: Int, audio: Audio, startFrame: Int) {
        playingAudio = audio
        state = State.PLAYING; ended = false
        changed()
        player.play(audio, startFrame) { ok -> main.post { if (g == gen && ok) advance(g) } }
        prefetchAhead()
    }

    private fun advance(g: Int) {
        if (g != gen) return
        val gap = (queue.getOrNull(index)?.gapMs ?: 160).coerceIn(0, 2000)
        if (index + 1 >= queue.size) { finish(); return }
        index++
        val g2 = ++gen
        main.postDelayed({ if (g2 == gen) speakCurrent(0) }, gap.toLong())
    }

    private fun fail(msg: String) {
        gen++
        player.stop()
        playingAudio = null; pausedAudio = null; pausedIndex = -1
        state = State.PAUSED
        abandonFocus()
        changed()
        listeners.forEach { runCatching { it.onReaderError(msg) } }
    }

    private fun prefetchAhead() {
        val (v, s, r) = Triple(voiceId, sid, speed)
        for (k in 1..PREFETCH) {
            val item = queue.getOrNull(index + k) ?: break
            val key = engine.cacheKey(v, s, r, item.text)
            if (engine.cached(key) != null) continue
            val pg = engine.prefetchGen
            engine.submit {
                if (pg != engine.prefetchGen) return@submit
                runCatching { engine.synthesize(key, v, s, r, item.text, prefetch = true) }
                    .onFailure { Log.w(TAG, "Vorausberechnung fehlgeschlagen", it) }
            }
        }
    }

    // ── Hörfokus, Wachhalten, Dienst ──

    private fun requestFocus() {
        if (hasFocus) return
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(Player.ATTRS)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ onFocusChange(it) }, main)
            .build().also { focusReq = it }
        hasFocus = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) Log.w(TAG, "Hörfokus nicht erhalten")
    }

    private fun abandonFocus() {
        resumeOnFocus = false
        if (!hasFocus) return
        focusReq?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        hasFocus = false
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false; resumeOnFocus = false
                pauseImpl(releaseFocus = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Anruf, Navigationsansage: anhalten und danach von selbst weiterlesen
                hasFocus = false
                val was = state == State.PLAYING || state == State.LOADING
                if (was) pauseImpl(releaseFocus = false)
                resumeOnFocus = was
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnFocus) { resumeOnFocus = false; resume() }
            }
        }
    }

    private fun changed() {
        val active = state == State.PLAYING || state == State.LOADING
        runCatching {
            if (active) wakeLock.acquire(WAKE_MS) else if (wakeLock.isHeld) wakeLock.release()
        }.onFailure { Log.w(TAG, "WakeLock", it) }
        if (state != State.IDLE && !serviceStarted) {
            serviceStarted = true
            runCatching { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java)) }
                .onFailure { Log.w(TAG, "Wiedergabedienst konnte nicht gestartet werden", it) }
        }
        if (state == State.IDLE) serviceStarted = false
        listeners.forEach { runCatching { it.onReaderChanged(this) }.onFailure { Log.w(TAG, "Zuhörer", it) } }
    }
}
