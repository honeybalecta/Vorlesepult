package de.honeybalecta.vorlesepult

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/** Eine eingebaute Stimme, wie sie tools/fetch-voices.py in voices.json beschreibt. */
class Voice(val json: JSONObject) {
    val id: String = json.getString("id")
    val name: String = json.getString("name")
    val lang: String = json.optString("lang", "de")          // espeak-Stimme, z. B. de, en-us
    val frontend: String = json.optString("frontend", "piper") // piper (Aussprache über espeak) oder chars (Buchstaben)
    val numSpeakers: Int = json.optInt("numSpeakers", 1)
    val defaultSid: Int = json.optInt("defaultSid", 0)
    val sampleRate: Int = json.optInt("sampleRate", 22050)
    val dir: String = json.optString("dir", "voices/$id")
}

/**
 * Die Sprach-Engine: lädt eine Stimme (sherpa-onnx, VITS/Piper mit espeak-ng für die Aussprache)
 * und erzeugt Audio. Alles läuft auf einem eigenen Arbeits-Thread.
 */
class Engine(private val ctx: Context) {
    companion object {
        private const val TAG = "VP.Engine"
        private const val CACHE_BYTES = 48 * 1024 * 1024
    }

    val voices: List<Voice> = runCatching {
        val arr = JSONArray(ctx.assets.open("www/voices.json").use { String(it.readBytes()) })
        (0 until arr.length()).map { Voice(arr.getJSONObject(it)) }
    }.getOrElse { Log.e(TAG, "voices.json fehlt oder ist defekt", it); emptyList() }

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "vp-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private var tts: OfflineTts? = null
    @Volatile var current: Voice? = null
        private set
    private var espeakDir: String? = null
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    // Fertige Sätze, damit Zurückspringen und Vorausberechnen sofort klingen
    private val cache = object : LruCache<String, Audio>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Audio) = value.samples.size * 4 + 64
    }

    /** Zähler für Abbrüche: laufende Berechnungen prüfen sie zwischendurch und hören auf, wenn sie sich geändert haben. */
    @Volatile private var speakGen = 0
    @Volatile var prefetchGen = 0
        private set
    @Volatile private var wanted: String? = null

    fun voice(id: String): Voice? = voices.firstOrNull { it.id == id }

    fun submit(task: () -> Unit) { exec.execute { runCatching(task).onFailure { Log.e(TAG, "Aufgabe fehlgeschlagen", it) } } }

    fun cacheKey(voiceId: String, sid: Int, speed: Float, text: String) = "$voiceId|$sid|${"%.2f".format(speed)}|$text"

    fun cached(key: String): Audio? = cache.get(key)

    /** Stimme laden (blockierend, auf dem Engine-Thread aufrufen). Gibt die Stimme zurück. */
    fun load(id: String): Voice {
        val v = voice(id) ?: throw IllegalArgumentException("Unbekannte Stimme: $id")
        if (current?.id == id && tts != null) return v
        tts?.let { runCatching { it.free() } }
        tts = null; current = null
        // Piper-Stimmen brauchen die Aussprachedaten von espeak-ng als echten Ordner im Dateisystem
        Diagnostics.step(ctx, "Aussprachedaten für ${v.id} bereitstellen")
        val dataDir = if (v.frontend == "piper") ensureEspeakData() else ""
        Diagnostics.step(ctx, "Stimme ${v.id} laden (Modell ${v.dir}/model.onnx, Daten $dataDir)")
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = "${v.dir}/model.onnx", tokens = "${v.dir}/tokens.txt", dataDir = dataDir),
                numThreads = threads, debug = true, provider = "cpu", // debug: Konfiguration und Modelldaten ins Protokoll (Diagnose)
            ),
            maxNumSentences = 1,
        )
        val t0 = System.currentTimeMillis()
        val t = OfflineTts(assetManager = ctx.assets, config = cfg)
        Log.i(TAG, "Stimme ${v.id} geladen in ${System.currentTimeMillis() - t0} ms, ${t.numSpeakers()} Sprecher, ${t.sampleRate()} Hz")
        tts = t; current = v
        Diagnostics.step(ctx, null)
        return v
    }

    /** Kopiert assets/espeak-ng-data einmalig ins App-Verzeichnis und liefert den Pfad. */
    private fun ensureEspeakData(): String {
        espeakDir?.let { return it }
        val target = File(ctx.filesDir, "espeak-ng-data")
        val marker = File(target, ".vollstaendig-" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode)
        if (!marker.exists()) {
            target.deleteRecursively()
            copyAssetDir("espeak-ng-data", target)
            marker.writeText("ok")
        }
        if (!File(target, "phontab").exists()) throw IllegalStateException("Aussprachedaten (espeak-ng-data) fehlen in der App")
        espeakDir = target.absolutePath
        return target.absolutePath
    }

    private fun copyAssetDir(path: String, dest: File) {
        val entries = ctx.assets.list(path) ?: emptyArray()
        if (entries.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { inp -> dest.outputStream().use { inp.copyTo(it, 64 * 1024) } }
        } else {
            dest.mkdirs()
            for (e in entries) copyAssetDir("$path/$e", File(dest, e))
        }
    }

    /** Alles Laufende abbrechen (Pause, Stopp, Sprung). */
    fun cancelAll() { wanted = null; speakGen++; prefetchGen++ }

    /** Platz machen für einen Satz, der jetzt gebraucht wird: andere Berechnungen abbrechen, [keep] darf weiterlaufen. */
    fun cancelForSpeak(keep: String) { wanted = keep; speakGen++; prefetchGen++ }

    /**
     * Audio für einen Satz liefern (aus dem Zwischenspeicher oder neu berechnet).
     * Blockierend, auf dem Engine-Thread. Gibt null zurück, wenn die Berechnung abgebrochen wurde.
     */
    fun synthesize(key: String, voiceId: String, sid: Int, speed: Float, text: String, prefetch: Boolean = false): Audio? {
        cache.get(key)?.let { return it }
        val v = load(voiceId)
        val t = tts ?: return null
        val g1 = speakGen
        val g2 = prefetchGen
        val cancelled: () -> Boolean = if (prefetch) ({ g2 != prefetchGen && wanted != key }) else ({ g1 != speakGen && wanted != key })
        val clean = text.trim()
        if (clean.isEmpty()) return Audio(FloatArray(0), v.sampleRate)
        val sidOk = sid.coerceIn(0, maxOf(0, t.numSpeakers() - 1))
        val gen = GenerationConfig(sid = sidOk, speed = speed.coerceIn(0.5f, 2.5f))
        val t0 = System.currentTimeMillis()
        Diagnostics.step(ctx, "Satz erzeugen mit ${v.id} (Sprecher $sidOk, Tempo $speed): ${clean.take(120)}")
        val audio = t.generateWithConfigAndCallback(text = clean, config = gen) { _ -> if (cancelled()) 0 else 1 }
        Diagnostics.step(ctx, null)
        if (cancelled()) { Log.i(TAG, "Berechnung abgebrochen"); return null }
        val out = Audio(normalize(audio.samples), audio.sampleRate)
        Log.i(TAG, "Satz (${clean.length} Zeichen) in ${System.currentTimeMillis() - t0} ms, ${"%.1f".format(out.seconds)} s Audio")
        cache.put(key, out)
        return out
    }

    /** Lautstärke der Stimmen angleichen: Spitze auf ~0,8, höchstens dreifache Verstärkung. */
    private fun normalize(s: FloatArray): FloatArray {
        var peak = 0f
        for (x in s) { val a = abs(x); if (a > peak) peak = a }
        if (peak <= 0.0001f) return s
        val gain = min(3f, 0.8f / peak)
        if (abs(gain - 1f) < 0.02f) return s
        return FloatArray(s.size) { (s[it] * gain).coerceIn(-1f, 1f) }
    }

    fun release() {
        exec.execute { tts?.let { runCatching { it.free() } }; tts = null; current = null }
        exec.shutdown()
    }
}
