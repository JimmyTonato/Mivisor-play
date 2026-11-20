package com.mivisor.play.data

import com.mivisor.tv.engine.EngineDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de la app (memoria, como mucho 400 líneas). Junto con el del motor ([EngineDiagnostics]) es lo que se ve en
 * Ajustes › Registro y lo que se copia para averiguar un fallo.
 */
object AppLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(category: String, text: String) {
        lines.addLast("${fmt.format(Date())} [$category] ${text.take(300)}")
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("MiVisorPlay", "[$category] $text")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    /** Texto completo para copiar: datos del aparato, la app y el motor. */
    fun report(device: String): String = buildString {
        appendLine("MiVisor Play · $device")
        appendLine()
        appendLine("== App ==")
        snapshot().forEach { appendLine(it) }
        appendLine()
        appendLine("== Motor ==")
        runCatching { appendLine(EngineDiagnostics.report()) }
    }
}
