package de.honeybalecta.vorlesepult

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Verbindung zwischen Oberfläche (JavaScript, Objekt `VP`) und App.
 * Rückrufe laufen über `VPApp.*` in der Seite. Das Vorlesen selbst erledigt [Reader];
 * die Seite liefert nur die Satzliste und zeigt den Zustand an.
 */
class Bridge(private val act: MainActivity, private val app: App) : Reader.Listener {
    companion object { private const val TAG = "VP.Bridge" }

    private val engine get() = app.engine
    private val reader get() = app.reader
    private val store get() = app.store
    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false
    private val pending = ArrayList<String>()

    /** JavaScript in der Seite ausführen; vor dem Laden der Seite wird gepuffert. */
    fun js(code: String) = main.post {
        if (pageReady) act.web.evaluateJavascript(code, null) else pending.add(code)
    }
    private fun q(s: String?) = JSONObject.quote(s ?: "")

    /** Der WebView wurde neu aufgebaut: Seite gilt wieder als nicht bereit. */
    fun pageGone() = main.post { pageReady = false; pending.clear() }

    // ── Vorleser → Seite ──
    override fun onReaderChanged(reader: Reader) { js("VPApp.onState(${reader.snapshot()})") }
    override fun onReaderError(message: String) { js("VPApp.onReaderError(${q(message)})") }

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

    // ── Diagnose ──
    @JavascriptInterface fun lastCrash(): String = runCatching { Diagnostics.lastCrashSummary(act.applicationContext) }.getOrDefault("")
    @JavascriptInterface fun diagnostics(): String = runCatching { Diagnostics.collect(act.applicationContext) }.getOrElse { "Diagnose fehlgeschlagen: $it" }
    @JavascriptInterface fun clearCrash() = Diagnostics.clear(act.applicationContext)
    @JavascriptInterface fun copy(text: String) = main.post { act.copyToClipboard(text) }

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

    // ── Vorlesen (Zustand kommt über VPApp.onState zurück) ──
    private fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.e(TAG, "$what fehlgeschlagen", e); js("VPApp.onReaderError(${q(e.message ?: e.toString())})") }
    }

    /** Satzliste (JSON) übernehmen und ab Satz [index] vorlesen. */
    @JavascriptInterface
    fun play(queue: String, index: Int, title: String, version: Int) = guarded("play") {
        val items = reader.parseQueue(queue)
        main.post { act.ensureNotificationPermission(); reader.play(items, index, title, version) }
    }

    /** Satzliste übernehmen und Satz [index] markieren, ohne zu lesen. */
    @JavascriptInterface
    fun prepare(queue: String, index: Int, title: String, version: Int) = guarded("prepare") {
        val items = reader.parseQueue(queue)
        reader.prepare(items, index, title, version)
    }

    /** Satzliste nach einer Bearbeitung austauschen; [index] ist der gerade gelesene Satz in der neuen Liste. */
    @JavascriptInterface
    fun setQueue(queue: String, index: Int, version: Int) = guarded("setQueue") {
        val items = reader.parseQueue(queue)
        reader.setQueue(items, index, version)
    }

    @JavascriptInterface fun resume() = guarded("resume") { main.post { act.ensureNotificationPermission(); reader.resume() } }
    @JavascriptInterface fun pause() = guarded("pause") { reader.pause() }
    @JavascriptInterface fun stop() = guarded("stop") { reader.stop() }
    @JavascriptInterface fun next() = guarded("next") { reader.next() }
    @JavascriptInterface fun prev() = guarded("prev") { reader.prev() }
    @JavascriptInterface fun seek(index: Int) = guarded("seek") { reader.seek(index) }
    @JavascriptInterface fun setVoice(voiceId: String, sid: Int, speed: Float) = guarded("setVoice") { reader.setVoice(voiceId, sid, speed) }
    @JavascriptInterface fun setTitle(title: String) = guarded("setTitle") { reader.setTitle(title) }

    /** Aktueller Zustand des Vorlesers, z. B. nach dem Neuladen der Seite. */
    @JavascriptInterface fun state(): String = reader.snapshot().toString()

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
