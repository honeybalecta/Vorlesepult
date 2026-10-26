package de.honeybalecta.vorlesepult

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sammelt, was nach einem Absturz hilft: Grund des letzten Prozess-Endes (Android 11+),
 * Java-Stacktrace aus dem eigenen Fänger, Markierung des laufenden Engine-Schritts
 * und die letzten Protokollzeilen der App (logcat liefert die eigenen Einträge auch nach Neustart).
 */
object Diagnostics {
    private const val TAG = "VP.Diag"
    private val fmt = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)

    private fun crashFile(ctx: Context) = File(ctx.filesDir, "letzter-absturz.txt")
    private fun stepFile(ctx: Context) = File(ctx.filesDir, "engine-schritt.txt")

    /** Java-Abstürze in eine Datei schreiben, dann wie gewohnt weiterreichen. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile(app).writeText("${fmt.format(Date())} · Thread ${t.name}\n${Log.getStackTraceString(e)}")
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Merkt sich, was die Engine gerade tut; bleibt stehen, wenn der Prozess dabei stirbt. */
    fun step(ctx: Context, what: String?) {
        runCatching { if (what == null) stepFile(ctx).delete() else stepFile(ctx).writeText("${fmt.format(Date())} · $what") }
    }

    /** Kurze Zusammenfassung, falls die App beim letzten Mal unerwartet endete – sonst "". */
    fun lastCrashSummary(ctx: Context): String {
        val parts = ArrayList<String>()
        stepFile(ctx).takeIf { it.exists() }?.let { parts.add("Beendet während: " + it.readText().trim()) }
        crashFile(ctx).takeIf { it.exists() }?.let { parts.add("Java-Fehler: " + it.readText().lineSequence().drop(1).firstOrNull()?.trim().orEmpty()) }
        exitInfos(ctx).firstOrNull()?.let { if (isCrash(it)) parts.add(describeExit(it)) }
        return parts.joinToString("\n")
    }

    /** Markierungen des letzten Absturzes löschen, nachdem sie angezeigt wurden. */
    fun clear(ctx: Context) { runCatching { crashFile(ctx).delete() }; runCatching { stepFile(ctx).delete() } }

    private fun isCrash(i: ApplicationExitInfo) = i.reason in listOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_OTHER)

    private fun exitInfos(ctx: Context): List<ApplicationExitInfo> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
        }.getOrDefault(emptyList())
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (Programm hat sich selbst beendet, z. B. exit() in der Engine)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (Signal)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (zu wenig Speicher)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java-Ausnahme)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (Absturz im nativen Code)"
        ApplicationExitInfo.REASON_ANR -> "ANR (keine Reaktion)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (vom System/Nutzer beendet)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED (abhängiger Prozess, z. B. WebView, gestorben)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "Grund $r"
    }

    private fun describeExit(i: ApplicationExitInfo): String {
        val sb = StringBuilder("${fmt.format(Date(i.timestamp))} · ${reasonName(i.reason)} · Status ${i.status}")
        i.description?.let { if (it.isNotBlank()) sb.append(" · ").append(it) }
        return sb.toString()
    }

    /** Vollständiger Diagnosetext zum Kopieren und Weitergeben. */
    fun collect(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Vorlesepult-Diagnose · ${fmt.format(Date())}\n")
        sb.append("Gerät: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ABIs ${Build.SUPPORTED_ABIS.joinToString()}\n")
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            sb.append("App: ${pi.versionName} (${pi.longVersionCode})\n")
        }
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            sb.append("Speicher: ${mi.availMem / 1048576} MB frei von ${mi.totalMem / 1048576} MB · Seitengröße ${runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)} Bytes\n")
        }
        stepFile(ctx).takeIf { it.exists() }?.let { sb.append("\nEngine-Schritt beim Ende: ").append(it.readText().trim()).append('\n') }
        crashFile(ctx).takeIf { it.exists() }?.let { sb.append("\nLetzter Java-Fehler:\n").append(it.readText().take(6000)).append('\n') }
        val exits = exitInfos(ctx)
        if (exits.isNotEmpty()) {
            sb.append("\nLetzte Prozess-Enden:\n")
            exits.forEach { i ->
                sb.append("- ").append(describeExit(i)).append('\n')
                if (i.reason == ApplicationExitInfo.REASON_CRASH) {
                    runCatching { i.traceInputStream?.use { s -> sb.append(String(s.readBytes()).take(4000)).append('\n') } }
                }
            }
        }
        sb.append("\nProtokoll (logcat, gefiltert):\n")
        sb.append(logcat())
        return sb.toString()
    }

    private val interesting = Regex("sherpa|onnx|espeak|VP\\.|AndroidRuntime|FATAL|Fatal signal|DEBUG|libc|chromium|cr_|WebView|vorlesepult|\\bE/|\\bF/|\\bW/", RegexOption.IGNORE_CASE)

    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val lines = text.lines().filter { interesting.containsMatchIn(it) }
        val keep = lines.takeLast(400)
        if (keep.isEmpty()) "(keine passenden Zeilen)" else keep.joinToString("\n").takeLast(40000)
    }.getOrElse { "(logcat nicht lesbar: ${it.message})" }
}
