package de.honeybalecta.vorlesepult

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Kleiner HTTP-Server nur für die App selbst (127.0.0.1, Zufallsschlüssel im Pfad).
 * Liefert die Oberfläche aus den Assets und zum Import übergebene Dateien.
 * Fester Port, damit der Browserspeicher (localStorage) über Neustarts erhalten bleibt.
 */
class LocalServer(private val ctx: Context) {
    companion object {
        const val PORT = 47351
        private const val TAG = "VP.Server"
        private val MIME = mapOf(
            "html" to "text/html; charset=utf-8", "js" to "text/javascript", "mjs" to "text/javascript",
            "css" to "text/css", "json" to "application/json", "txt" to "text/plain; charset=utf-8",
            "md" to "text/markdown; charset=utf-8", "svg" to "image/svg+xml", "png" to "image/png",
            "woff2" to "font/woff2", "woff" to "font/woff", "ttf" to "font/ttf", "wasm" to "application/wasm",
            "pdf" to "application/pdf",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
    }

    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server: ServerSocket = try {
        ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
    } catch (e: Exception) {
        Log.w(TAG, "Port $PORT belegt, nehme einen freien")
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    }
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = true
    private val offered = ConcurrentHashMap<String, Pair<File, String>>()

    val base: String get() = "http://127.0.0.1:${server.localPort}/$token/"

    fun start(): LocalServer {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (e: Exception) { if (running) continue else break }
                pool.execute { runCatching { serve(s) }.onFailure { Log.w(TAG, "Anfrage fehlgeschlagen", it) } }
            }
        }, "vp-http").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        pool.shutdownNow()
        offered.values.forEach { runCatching { it.first.delete() } }
    }

    /** Eine Datei kurzzeitig unter einer URL anbieten (für den Import in die Oberfläche). */
    fun offer(file: File, mime: String): String {
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        offered[id] = file to mime
        return base + "open/" + id
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 15000
            val inp = s.getInputStream()
            val first = readLine(inp) ?: return
            while (true) { val l = readLine(inp) ?: return; if (l.isEmpty()) break }
            val parts = first.split(" ")
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) { status(out, 405, "Method Not Allowed"); return }
            val path = parts[1].substringBefore('?')
            val prefix = "/$token/"
            if (!path.startsWith(prefix)) { status(out, 404, "Not Found"); return }
            val rel = path.removePrefix(prefix)
            val head = parts[0] == "HEAD"
            when {
                rel.startsWith("open/") -> {
                    val entry = offered[rel.removePrefix("open/")]
                    if (entry == null || !entry.first.exists()) { status(out, 404, "Not Found"); return }
                    val (file, mime) = entry
                    header(out, 200, "OK", mime, file.length())
                    if (!head) file.inputStream().use { it.copyTo(out) }
                    out.flush()
                }
                else -> {
                    val asset = "www/" + (if (rel.isEmpty() || rel.endsWith("/")) rel + "index.html" else rel)
                    if (asset.contains("..")) { status(out, 404, "Not Found"); return }
                    val bytes = try { ctx.assets.open(asset).use { it.readBytes() } } catch (e: Exception) { status(out, 404, "Not Found"); return }
                    val ext = asset.substringAfterLast('.', "").lowercase()
                    header(out, 200, "OK", MIME[ext] ?: "application/octet-stream", bytes.size.toLong())
                    if (!head) out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    private fun header(out: OutputStream, code: Int, text: String, mime: String, length: Long) {
        out.write(("HTTP/1.1 $code $text\r\nContent-Type: $mime\r\nContent-Length: $length\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").toByteArray())
    }

    private fun status(out: OutputStream, code: Int, text: String) {
        val body = text.toByteArray()
        header(out, code, text, "text/plain", body.size.toLong())
        out.write(body); out.flush()
    }
}
