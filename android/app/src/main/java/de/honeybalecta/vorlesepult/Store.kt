package de.honeybalecta.vorlesepult

import android.content.Context
import java.io.File

/** Einfacher Schlüssel-Wert-Speicher im App-Verzeichnis (Dokument, Einstellungen). */
class Store(ctx: Context) {
    private val dir = File(ctx.filesDir, "store").apply { mkdirs() }
    private fun fileFor(key: String) = File(dir, key.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(64))

    @Synchronized fun get(key: String): String? = fileFor(key).takeIf { it.exists() }?.readText()

    @Synchronized fun set(key: String, value: String) {
        val f = fileFor(key)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized fun remove(key: String) { fileFor(key).delete() }
}
