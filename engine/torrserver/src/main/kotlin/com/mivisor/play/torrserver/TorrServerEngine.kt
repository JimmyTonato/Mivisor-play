package com.mivisor.play.torrserver

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL

/** Registro del motor de torrents: la app decide dónde va (su registro de diagnóstico). */
object TsLog {
    @Volatile var sink: (String) -> Unit = {}
    private val tail = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        tail.addLast(line.take(400))
        while (tail.size > 80) tail.removeFirst()
        android.util.Log.i("TorrServer", line)
        runCatching { sink(line) }
    }

    /** Las últimas líneas (para explicar un fallo al arrancar o un cierre). */
    @Synchronized
    fun last(n: Int = 12): List<String> = tail.toList().takeLast(n)
}

/** Ajustes del motor que la app fija al arrancarlo (TorrServer no debe cambiarlos con un torrent en marcha). */
data class TsConfig(
    /** Caché en MB: TorrServer guarda solo la ventana por delante de lo que se ve y lo reciente, y suelta el resto. */
    val cacheMb: Int = 128,
    /** true: la caché va al disco de la app (permite cachés grandes); false: en memoria (no ocupa disco). */
    val useDisk: Boolean = false,
    /** Lo que se precarga antes de empezar, en % de la caché. */
    val preloadPercent: Int = 30,
    val connectionsLimit: Int = 50,
    /** KB/s; 0 = sin límite. */
    val uploadKbps: Int = 0
)

/**
 * TorrServer (GPL-3.0) como PROCESO APARTE, igual que Outpace y que hace Ace Stream: el binario `libtorrserver.so`
 * (lo compila la CI) se lanza desde nativeLibraryDir y escucha solo en 127.0.0.1. Si el motor se cae, la app sigue
 * viva: lo nota, lo explica y lo vuelve a arrancar al siguiente torrent.
 */
object TorrServerEngine {
    const val BINARY = "libtorrserver.so"
    private const val HOST = "127.0.0.1"

    @Volatile private var process: Process? = null
    @Volatile var port: Int = 0; private set
    @Volatile private var appliedConfig: TsConfig? = null
    /** Por qué terminó el motor la última vez que lo hizo por su cuenta (null = sigue vivo o se paró a propósito). */
    @Volatile var exitReason: String? = null; private set
    @Volatile private var stopping = false

    val baseUrl: String get() = "http://$HOST:$port"

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, BINARY)

    fun isSupported(ctx: Context): Boolean = binary(ctx).isFile

    fun running(): Boolean = process?.let { alive(it) } == true

    /**
     * Arranca el motor si hace falta y devuelve su URL base. Con otros ajustes que los aplicados, lo reinicia.
     * Bloquea (llamar fuera del hilo principal). Lanza una excepción con el motivo si no arranca.
     */
    @Synchronized
    fun ensureStarted(ctx: Context, config: TsConfig): String {
        val app = ctx.applicationContext
        if (running() && appliedConfig == config && health()) return baseUrl
        if (running()) {
            TsLog.add("Ajustes nuevos: se reinicia el motor")
            stopLocked()
        }
        val bin = binary(app)
        if (!bin.isFile) throw IllegalStateException("El motor de torrents no está incluido para este aparato (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})")

        val home = File(app.filesDir, "torrserver").apply { mkdirs() }
        killOrphan(File(home, "pid"), bin.absolutePath)
        val cacheDir = File(app.filesDir, "torrserver-cache").apply { mkdirs() }
        // La caché en disco de la sesión anterior ya no sirve.
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        port = freePort()
        exitReason = null
        stopping = false

        val pb = ProcessBuilder(bin.absolutePath, "--port", port.toString(), "--path", home.absolutePath, "--ip", HOST)
            .directory(home)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", home.absolutePath)
            put("TMPDIR", app.cacheDir.absolutePath)
            put("GODEBUG", "netdns=cgo")
        }
        TsLog.add("Arrancando TorrServer en el puerto $port")
        val p = pb.start()
        process = p
        pidOf(p)?.let { pid -> runCatching { File(home, "pid").writeText("$pid\n${bin.absolutePath}") } }
        readOutput(p)
        watchExit(p)

        // Espera a que conteste (hasta 15 s).
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15_000) {
            if (!alive(p)) break
            if (health()) break
            Thread.sleep(250)
        }
        if (!alive(p) || !health()) {
            val why = exitReason ?: "no contestó en 15 s"
            stopLocked()
            throw IllegalStateException("El motor de torrents no arrancó ($why). " + TsLog.last(4).joinToString(" · "))
        }
        TsLog.add("TorrServer listo: ${TorrServerApi.echo(baseUrl) ?: "?"} (${System.currentTimeMillis() - t0} ms)")
        runCatching { TorrServerApi.applySettings(baseUrl, config, cacheDir.absolutePath) }
            .onSuccess { appliedConfig = config }
            .onFailure { TsLog.add("No se pudieron aplicar los ajustes: ${it.message}") }
        return baseUrl
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val p = process ?: return
        stopping = true
        TsLog.add("Parando TorrServer")
        runCatching { p.destroy() }
        // waitFor(tiempo) y destroyForcibly son de Android 8+: se espera a mano (como mucho 2 s).
        val t0 = System.currentTimeMillis()
        while (alive(p) && System.currentTimeMillis() - t0 < 2_000) Thread.sleep(50)
        process = null
        appliedConfig = null
    }

    fun health(): Boolean = TorrServerApi.echo(baseUrl, 800) != null

    /** Un TorrServer nuestro que sobrevivió a la app anterior (Android la cerró): se cierra si el pid sigue siendo él. */
    private fun killOrphan(pidFile: File, path: String) {
        val lines = runCatching { pidFile.readLines() }.getOrNull() ?: return
        val pid = lines.firstOrNull()?.trim()?.toIntOrNull()
        val cmd = pid?.let { runCatching { File("/proc/$it/cmdline").readText().replace('\u0000', ' ') }.getOrNull() }
        if (pid != null && cmd != null && cmd.contains(path)) {
            TsLog.add("Cerrando un TorrServer anterior que quedó vivo (pid $pid)")
            runCatching { android.os.Process.killProcess(pid) }
            val until = System.currentTimeMillis() + 1_500
            while (File("/proc/$pid").exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        }
        runCatching { pidFile.delete() }
    }

    /** pid del proceso (campo «pid» en Android). */
    private fun pidOf(p: Process): Int? = runCatching {
        p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
    }.getOrNull()

    private fun readOutput(p: Process) {
        Thread({
            runCatching {
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { TsLog.add(it) } }
            }
        }, "TorrServer-log").apply { isDaemon = true }.start()
    }

    private fun watchExit(p: Process) {
        Thread({
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (!stopping && process === p) {
                exitReason = describeExit(code)
                TsLog.add("TorrServer se cerró solo: $exitReason")
                process = null
                appliedConfig = null
            }
        }, "TorrServer-exit").apply { isDaemon = true }.start()
    }

    private fun describeExit(code: Int): String = when (code) {
        134 -> "código 134: abortó"
        137 -> "código 137: lo cerró el sistema (falta de memoria)"
        139 -> "código 139: fallo de memoria en el motor"
        2 -> "código 2: error de Go (pánico)"
        else -> "código $code"
    }

    private fun alive(p: Process): Boolean = try { p.exitValue(); false } catch (e: IllegalThreadStateException) { true }

    private fun freePort(): Int {
        for (candidate in listOf(8090, 8095, 18090)) {
            runCatching { ServerSocket(candidate, 1, InetAddress.getByName(HOST)).use { return candidate } }
        }
        return ServerSocket(0, 1, InetAddress.getByName(HOST)).use { it.localPort }
    }

    internal fun open(url: String, method: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
        }
}
