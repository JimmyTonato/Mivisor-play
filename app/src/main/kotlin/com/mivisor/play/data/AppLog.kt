package com.mivisor.play.data

import android.content.Context
import com.mivisor.tv.engine.EngineDiagnostics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de la app. Cada línea se guarda también en un archivo al momento, así que sobrevive aunque la app se cierre
 * de golpe: al abrirla otra vez se ve lo que pasó justo antes (Ajustes › Ver el registro).
 */
object AppLog {
    private const val MAX = 600
    private const val MAX_FILE = 400 * 1024L
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    @Volatile private var file: File? = null

    /** Al arrancar la app: carga lo de la sesión anterior y sigue escribiendo en el mismo archivo. */
    @Synchronized
    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "app.log")
        if (f.length() > MAX_FILE) {
            val keep = runCatching { f.readLines().takeLast(300) }.getOrDefault(emptyList())
            runCatching { f.writeText(keep.joinToString("\n", postfix = "\n")) }
        }
        runCatching { f.readLines().takeLast(250).forEach { lines.addLast(it) } }
        file = f
    }

    @Synchronized
    fun add(category: String, text: String) {
        val line = "${fmt.format(Date())} [$category] ${text.take(500)}"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        runCatching { file?.appendText(line + "\n") }
        android.util.Log.i("MiVisorPlay", "[$category] $text")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
    }

    /**
     * La sesión anterior terminó sin cerrarse bien (la app se cayó): se recogen de Android las líneas del cierre
     * (excepciones de Java y fallos nativos como «Fatal signal»), que una app puede leer de su propio registro.
     */
    fun captureLastCrash(packageName: String) {
        val text = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
            p.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val keys = listOf("FATAL EXCEPTION", "Fatal signal", "AndroidRuntime", "Abort message", "libc", "DEBUG", "backtrace", "#0", "Caused by", "\tat ", packageName)
        val hits = text.lineSequence().filter { l -> keys.any { it in l } && ("E/" in l || "F/" in l || "Fatal" in l || "\tat " in l) }.toList().takeLast(60)
        if (hits.isEmpty()) {
            add("Cierre anterior", "La app se cerró la última vez, pero Android no guardó el motivo.")
        } else {
            add("Cierre anterior", "La app se cerró la última vez. Lo que dice Android:")
            hits.forEach { add("Cierre anterior", it.trim()) }
        }
    }

    /** Texto completo para copiar: datos del aparato, la app y los motores. */
    fun report(device: String): String = buildString {
        appendLine("MiVisor Play · $device")
        appendLine()
        appendLine("== App y torrents ==")
        snapshot().forEach { appendLine(it) }
        appendLine()
        appendLine("== Ace Stream (Outpace) ==")
        runCatching { appendLine(EngineDiagnostics.report()) }
    }
}
