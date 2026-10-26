package de.honeybalecta.vorlesepult

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File

/** Ein Fenster mit der Oberfläche; Vorlesen, Dateien und Speicher erledigen [Bridge], [Reader], [Engine] in [App]. */
class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "VP.Main" }

    lateinit var web: WebView
        private set
    private val app get() = application as App
    private lateinit var bridge: Bridge
    private val pendingImports = ArrayList<String>() // JavaScript, das nach dem Laden der Seite läuft
    private var pendingSave: Triple<String, String, ByteArray>? = null
    private var keyboardShown = false
    private var notificationAsked = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) bridge.js("VPApp.toast(${JSONObject.quote("Ohne Benachrichtigungen fehlt die Steuerung in der Leiste und auf dem Sperrbildschirm – das Vorlesen läuft trotzdem weiter.")})")
    }

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@registerForActivityResult
        importUri(uri, res.data?.type)
    }
    private val saver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val job = pendingSave; pendingSave = null
        val uri = res.data?.data
        if (job == null) return@registerForActivityResult
        if (uri == null) { bridge.js("VPApp.onSaved(false, ${JSONObject.quote(job.first)}, \"\")"); return@registerForActivityResult }
        Thread {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(job.third) }
            }.onFailure { Log.w(TAG, "Speichern fehlgeschlagen", it) }.isSuccess
            bridge.js("VPApp.onSaved($ok, ${JSONObject.quote(job.first)}, \"\")")
        }.start()
    }

    fun versionName(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")
    fun voiceSet(): String = versionName().substringAfter('-', "komplett")

    private lateinit var root: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.papier))
        setContentView(root)
        // Platz für Status- und Navigationsleiste lassen; Tastatur schiebt die Seite zusammen
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            val shown = ime.bottom > 0
            if (shown != keyboardShown && ::bridge.isInitialized) { keyboardShown = shown; bridge.js("VPApp.onKeyboard($shown)") }
            WindowInsetsCompat.CONSUMED
        }

        bridge = Bridge(this, app)
        app.reader.addListener(bridge)
        createWebView()
        // Zurück schließt erst Menüs; sonst geht die App in den Hintergrund – das Vorlesen läuft dort weiter
        onBackPressedDispatcher.addCallback(this) {
            web.evaluateJavascript("window.VPApp && VPApp.onBack()") { r -> if (r != "true") moveTaskToBack(true) }
        }
        handleIntent(intent)
    }

    /** Ab Android 13 braucht die Wiedergabe-Benachrichtigung (Steuerung in der Leiste) eine Erlaubnis; einmal fragen. */
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || notificationAsked) return
        notificationAsked = true
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure { Log.w(TAG, "Berechtigungsdialog", it) }
    }

    /** WebView aufbauen; wird auch genutzt, wenn der Darstellungsprozess des WebView gestorben ist. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }
        web.addJavascriptInterface(bridge, "VP")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "127.0.0.1") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Ohne diese Behandlung würde Android die ganze App beenden
                Log.e(TAG, "WebView-Prozess beendet (Absturz: ${detail.didCrash()}, Priorität ${detail.rendererPriorityAtExit()})")
                Diagnostics.step(applicationContext, "WebView-Darstellungsprozess beendet (Absturz: ${detail.didCrash()})")
                runCatching { root.removeView(view); view.destroy() }
                bridge.pageGone()
                createWebView()
                return true
            }
        }
        web.loadUrl(app.server.base + "index.html")
    }

    fun copyToClipboard(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Vorlesepult", text))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** „Öffnen mit“ und „Teilen“ aus anderen Apps. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { importUri(it, intent.type) }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) importUri(uri, intent.type)
                else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
                        queueJs("VPApp.importText(${JSONObject.quote(text)}, ${JSONObject.quote(subject)})")
                    }
                }
            }
        }
        intent.action = null
    }

    private fun queueJs(code: String) { pendingImports.add(code); flushPendingImports() }

    /** Von [Bridge] aufgerufen, sobald die Seite bereit ist. */
    fun flushPendingImports() {
        if (!::bridge.isInitialized) return
        val list = ArrayList(pendingImports); pendingImports.clear()
        list.forEach { bridge.js(it) }
    }

    /** Datei in den Cache kopieren und der Oberfläche über den lokalen Server anbieten. */
    private fun importUri(uri: Uri, mimeHint: String?) {
        Thread {
            try {
                var name = "Dokument"
                runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) name = it }
                    }
                }
                if (name == "Dokument") uri.lastPathSegment?.substringAfterLast('/')?.let { if (it.isNotBlank()) name = it }
                val mime = contentResolver.getType(uri) ?: mimeHint ?: "application/octet-stream"
                if (!name.contains('.')) {
                    val ext = when {
                        mime.contains("pdf") -> "pdf"; mime.contains("wordprocessingml") -> "docx"
                        mime.contains("markdown") -> "md"; mime.contains("html") -> "html"; else -> "txt"
                    }
                    name = "$name.$ext"
                }
                val dir = File(cacheDir, "import").apply { mkdirs() }
                val f = File(dir, System.currentTimeMillis().toString() + "-" + name.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(80))
                contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                val url = app.server.offer(f, mime)
                runOnUiThread { queueJs("VPApp.importUrl(${JSONObject.quote(url)}, ${JSONObject.quote(name)}, ${JSONObject.quote(mime)})") }
            } catch (e: Exception) {
                Log.w(TAG, "Import fehlgeschlagen", e)
                runOnUiThread { queueJs("VPApp.toast(${JSONObject.quote("Datei konnte nicht gelesen werden: " + (e.message ?: ""))})") }
            }
        }.start()
    }

    fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain", "text/markdown", "text/html", "application/octet-stream"))
        }
        runCatching { picker.launch(i) }.onFailure { bridge.js("VPApp.toast(${JSONObject.quote("Kein Dateimanager gefunden")})") }
    }

    fun saveFile(name: String, mime: String, bytes: ByteArray) {
        pendingSave = Triple(name, mime, bytes)
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { saver.launch(i) }.onFailure {
            pendingSave = null
            bridge.js("VPApp.onSaved(false, ${JSONObject.quote(name)}, ${JSONObject.quote("Kein Speicherdialog verfügbar")})")
        }
    }

    fun shareFile(name: String, mime: String, bytes: ByteArray) {
        runCatching {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val f = File(dir, name.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ' ' }.take(80).ifBlank { "Dokument" })
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, name))
        }.onFailure { Log.w(TAG, "Teilen fehlgeschlagen", it) }
    }

    fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        // Engine, Vorleser und Server gehören zur App und laufen weiter; nur das Fenster verschwindet
        runCatching { app.reader.removeListener(bridge) }
        runCatching { web.destroy() }
        super.onDestroy()
    }
}
