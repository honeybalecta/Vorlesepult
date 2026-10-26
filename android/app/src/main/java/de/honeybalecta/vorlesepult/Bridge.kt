package de.honeybalecta.vorlesepult

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verbindung zwischen Oberfläche (JavaScript, Objekt `VP`) und App.
 * Rückrufe laufen über `VPApp.*` in der Seite.
 */
class Bridge(private val act: MainActivity, private val engine: Engine, private val player: Player, private val store: Store) {
    companion object { private const val TAG = "VP.Bridge" }

    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false
    private val pending = ArrayList<String>()
    private val speakSerial = AtomicInteger()

    /** JavaScript in der Seite ausführen; vor dem Laden der Seite wird gepuffert. */
    fun js(code: String) = main.post {
        if (pageReady) act.web.evaluateJavascript(code, null) else pending.add(code)
    }
    private fun q(s: String?) = JSONObject.quote(s ?: "")

    // ── Seite ──
    @JavascriptInterface
    fun ready() = main.post {
        pageReady = true
        pending.forEach { act.web.evaluateJavascript(it, null) }
        pending.clear()
        act.flushPendingImports()
    }

    @JavascriptInterface
    fun info(): String = JSONObject().apply {
        put("app", "android"); put("version", act.versionName())
        put("android", Build.VERSION.SDK_INT); put("device", Build.MODEL)
        put("cores", Runtime.getRuntime().availableProcessors())
        put("voiceSet", act.voiceSet())
    }.toString()

    // ── Speicher ──
    @JavascriptInterface fun load(key: String): String = store.get(key) ?: ""
    @JavascriptInterface fun has(key: String): Boolean = store.get(key) != null
    @JavascriptInterface fun save(key: String, value: String) = store.set(key, value)
    @JavascriptInterface fun remove(key: String) = store.remove(key)

    // ── Stimmen ──
    @JavascriptInterface
    fun voices(): String = JSONArray(engine.voices.map { it.json }).toString()

    /** Stimme im Hintergrund laden; meldet sich über VPApp.onVoiceReady / onVoiceError. */
    @JavascriptInterface
    fun select(id: String) {
        if (engine.voice(id) == null) { js("VPApp.onVoiceError(${q(id)}, ${q("Unbekannte Stimme")})"); return }
        js("VPApp.onVoiceLoading(${q(id)})")
        engine.submit {
            try {
                val v = engine.load(id)
                js("VPApp.onVoiceReady(${q(v.id)})")
            } catch (e: Throwable) {
                Log.e(TAG, "Stimme $id konnte nicht geladen werden", e)
                js("VPApp.onVoiceError(${q(id)}, ${q(e.message ?: e.toString())})")
            }
        }
    }

    /**
     * Einen Satz vorlesen. Antwortet mit VPApp.onSpeakStart(req), onSpeakDone(req) oder onSpeakError(req, msg).
     * Ein laufender Satz wird abgebrochen.
     */
    @JavascriptInterface
    fun speak(req: String, voiceId: String, sid: Int, speed: Float, text: String) {
        val my = speakSerial.incrementAndGet()
        val key = engine.cacheKey(voiceId, sid, speed, text)
        engine.cancel(keep = key)
        player.stop()
        player.focus()
        engine.submit {
            if (my != speakSerial.get()) return@submit
            val audio = try {
                engine.synthesize(key, voiceId, sid, speed, text)
            } catch (e: Throwable) {
                Log.e(TAG, "Satz konnte nicht erzeugt werden", e)
                if (my == speakSerial.get()) js("VPApp.onSpeakError(${q(req)}, ${q(e.message ?: e.toString())})")
                return@submit
            } ?: return@submit
            if (my != speakSerial.get()) return@submit
            js("VPApp.onSpeakStart(${q(req)}, ${"%.2f".format(java.util.Locale.US, audio.seconds)})")
            player.play(audio) { ok ->
                if (my == speakSerial.get()) {
                    if (ok) js("VPApp.onSpeakDone(${q(req)})") else js("VPApp.onSpeakStopped(${q(req)})")
                }
            }
        }
    }

    /** Einen späteren Satz schon berechnen, damit er ohne Pause folgt. */
    @JavascriptInterface
    fun prefetch(voiceId: String, sid: Int, speed: Float, text: String) {
        val key = engine.cacheKey(voiceId, sid, speed, text)
        if (engine.cached(key) != null) return
        val serial = speakSerial.get()
        engine.submit {
            // nur solange kein neuer Satz angefordert wurde; der laufende Satz spielt derweil
            if (speakSerial.get() != serial) return@submit
            runCatching { engine.synthesize(key, voiceId, sid, speed, text) }
                .onFailure { Log.w(TAG, "Vorausberechnung fehlgeschlagen", it) }
        }
    }

    /** Wiedergabe anhalten (Pause/Stopp aus der Oberfläche). */
    @JavascriptInterface
    fun stop() {
        speakSerial.incrementAndGet()
        engine.cancel()
        player.stop()
        player.unfocus()
    }

    @JavascriptInterface
    fun keepAwake(on: Boolean) = main.post { act.keepAwake(on) }

    // ── Dateien ──
    /** Datei zum Öffnen auswählen; Ergebnis kommt als VPApp.importUrl(url, name, mime). */
    @JavascriptInterface
    fun pick() = main.post { act.pickFile() }

    /** Datei speichern: Android fragt nach Ort und Name. Antwort: VPApp.onSaved(ok, name). */
    @JavascriptInterface
    fun saveFile(name: String, mime: String, base64: String) {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { js("VPApp.onSaved(false, ${q(name)}, ${q("Daten unlesbar")})"); return }
        main.post { act.saveFile(name, mime, bytes) }
    }

    /** Text oder Datei an andere Apps weitergeben. */
    @JavascriptInterface
    fun share(name: String, mime: String, base64: String) {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { return }
        main.post { act.shareFile(name, mime, bytes) }
    }
}
