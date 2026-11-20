package com.mivisor.play.torrent

import android.content.Context
import android.system.Os
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Motor de streaming sobre libtorrent.
 *
 * MODO AHORRO DE ESPACIO (caché deslizante):
 *  - Solo se descarga una "ventana" de piezas alrededor de lo que se está viendo
 *    (75 % por delante, 25 % por detrás del punto de reproducción).
 *  - Las piezas que quedan fuera de la ventana se BORRAN del disco al momento
 *    (se perfora el archivo con fallocate PUNCH_HOLE: el archivo "mide" 7 GB
 *    pero en disco solo ocupa el tamaño de la caché).
 *  - Si saltas hacia atrás a una parte ya borrada, se vuelve a descargar.
 */
/** Ajustes del motor de torrents (los pone la app desde sus Ajustes). */
data class TorrentConfig(
    /** Borrar del disco lo ya visto (caché deslizante). */
    val spaceSaver: Boolean = true,
    /** Tamaño de la caché en MB (75 % por delante, 25 % por detrás). */
    val cacheMb: Int = 500,
    /** Lo que se descarga antes de empezar a reproducir, en MB. */
    val bufferMb: Int = 10,
    val maxConnections: Int = 200,
    /** KB/s; 0 = sin límite. */
    val uploadKbps: Int = 0
)

/** Registro del motor de torrents: la app decide dónde va (su registro de diagnóstico). */
object TLog {
    @Volatile var sink: (String) -> Unit = {}
    fun i(tag: String, msg: String) = emit("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = emit("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = emit("E", tag, msg, t)
    private fun emit(level: String, tag: String, msg: String, t: Throwable?) {
        val line = "$level/$tag: $msg" + (t?.let { " · ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        android.util.Log.println(if (level == "E") android.util.Log.ERROR else if (level == "W") android.util.Log.WARN else android.util.Log.INFO, "Torrent", line)
        runCatching { sink(line) }
    }
}

object TorrentEngine {

    private const val TAG = "Torrent"

    @Volatile var config = TorrentConfig()

    private var session: SessionManager? = null
    @Volatile private var addLatch: CountDownLatch? = null
    @Volatile private var expectingAdd = false
    private val strayHandles = java.util.concurrent.CopyOnWriteArrayList<TorrentHandle>()
    private val alertCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val LOGGED_ALERTS = setOf(
        "ADD_TORRENT", "METADATA_RECEIVED", "METADATA_FAILED", "TORRENT_ERROR", "FILE_ERROR",
        "LISTEN_FAILED", "LISTEN_SUCCEEDED", "DHT_BOOTSTRAP", "TORRENT_REMOVED", "TORRENT_CHECKED",
        "SESSION_ERROR", "TRACKER_ERROR", "HASH_FAILED", "TORRENT_DELETE_FAILED", "FASTRESUME_REJECTED"
    )
    @Volatile var handle: TorrentHandle? = null
        private set

    var info: TorrentInfo? = null; private set
    var fileIndex = -1; private set
    var fileName = ""; private set
    var fileSize = 0L; private set
    var saveDir: File? = null; private set

    private var fileOffset = 0L
    private var pieceLength = 1
    private var firstPiece = 0
    private var lastPiece = 0
    private var initialPieces: List<Int> = emptyList()
    @Volatile private var lastFocus = -1

    // --- Caché deslizante ---
    var spaceSaver = true; private set
    private var aheadPieces = 0
    private var behindPieces = 0
    private var evicted = BooleanArray(0)
    private var pinned: Set<Int> = emptySet()          // principio y final del archivo (cabeceras/índices)
    @Volatile private var windowCenter = -1
    @Volatile var rechecking = false; private set
    @Volatile var punchUnsupported = false; private set
    @Volatile var readPiece = 0; private set
    private val lock = Any()

    // ---------- Sesión ----------

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (session != null) return
        val s = SessionManager()
        s.addListener(object : AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) {
                try {
                    val type = alert.type().name
                    if (type in LOGGED_ALERTS) {
                        val n = (alertCounts[type] ?: 0) + 1
                        alertCounts[type] = n
                        if (n <= 25) TLog.i("lt", "$type: ${alert.message()}")
                    }
                    if (alert is AddTorrentAlert) {
                        val err = alert.error()
                        val failed = err != null && err.isError
                        if (failed) TLog.w(TAG, "Error al añadir torrent: ${err.message()}")
                        if (expectingAdd) {
                            if (!failed) handle = alert.handle()
                            addLatch?.countDown()
                        } else if (!failed) {
                            // Torrent temporario creado al leer un magnet: lo quitamos después
                            strayHandles.add(alert.handle())
                        }
                    }
                } catch (e: Throwable) {
                    TLog.w(TAG, "Error procesando alerta", e)
                }
            }
        })
        TLog.i(TAG, "Iniciando sesión libtorrent")
        s.start()
        session = s
        applySettings(ctx)
        TLog.i(TAG, "Sesión iniciada")
    }

    fun applySettings(ctx: Context) {
        val s = session ?: return
        val conn = config.maxConnections
        val up = config.uploadKbps
        try {
            s.applySettings(SettingsPack().connectionsLimit(conn).uploadRateLimit(up * 1024))
        } catch (e: Throwable) {
            TLog.w(TAG, "No se pudieron aplicar ajustes", e)
        }
    }

    /** Memoria interna de la app: su sistema de archivos (ext4/f2fs) permite liberar fragmentos. */
    fun baseDir(ctx: Context): File = File(ctx.filesDir, "torrents").apply { mkdirs() }

    fun fetchMagnet(ctx: Context, uri: String, timeoutSec: Int = 90): TorrentInfo? {
        ensureStarted(ctx)
        val s = session!!
        TLog.i(TAG, "Magnet: ${uri.take(120)}")
        var waited = 0
        while (!s.isDhtRunning && waited < 15) { Thread.sleep(1000); waited++ }
        TLog.i(TAG, "DHT activa=${s.isDhtRunning} tras ${waited}s; pidiendo metadatos (máx ${timeoutSec}s)")
        val dir = File(ctx.cacheDir, "magnet").apply { mkdirs() }
        val bytes = try {
            s.fetchMagnet(uri, timeoutSec, dir)
        } finally {
            removeStrays()
        }
        if (bytes == null) { TLog.w(TAG, "Magnet: no llegaron metadatos"); return null }
        TLog.i(TAG, "Magnet: metadatos recibidos (${bytes.size} bytes)")
        return TorrentInfo.bdecode(bytes)
    }

    /** Quita los torrents temporales y espera a que libtorrent los cierre del todo. */
    private fun removeStrays() {
        for (h in strayHandles) removeAndWait(h)
        strayHandles.clear()
    }

    private fun isValid(h: TorrentHandle?): Boolean = try { h != null && h.isValid } catch (e: Throwable) { false }

    private fun removeAndWait(h: TorrentHandle) {
        if (!isValid(h)) return
        try { session?.remove(h) } catch (e: Throwable) { TLog.w(TAG, "remove", e); return }
        val t0 = System.currentTimeMillis()
        while (isValid(h) && System.currentTimeMillis() - t0 < 8000) Thread.sleep(100)
        TLog.i(TAG, "Torrent quitado (${System.currentTimeMillis() - t0} ms, válido=${isValid(h)})")
    }

    // ---------- Streaming ----------

    @Synchronized
    fun startStream(ctx: Context, ti: TorrentInfo, index: Int) {
        ensureStarted(ctx)
        stop(ctx, deleteFiles = false)
        removeStrays()
        TLog.i(TAG, "Stream: '${ti.name()}' archivo #$index de ${ti.numFiles()}, piezas=${ti.numPieces()} x ${ti.pieceLength()} B")

        val cfg = config
        val s = session!!
        val fs = ti.files()
        info = ti
        fileIndex = index
        fileName = fs.fileName(index)
        fileSize = fs.fileSize(index)
        fileOffset = fs.fileOffset(index)
        pieceLength = ti.pieceLength()
        firstPiece = pieceAt(0)
        lastPiece = pieceAt(fileSize - 1)
        lastFocus = -1
        windowCenter = -1
        readPiece = firstPiece
        rechecking = false
        punchUnsupported = false

        // Configuración de la caché
        spaceSaver = cfg.spaceSaver
        val cacheBytes = cfg.cacheMb.toLong() * 1024 * 1024
        val cachePieces = max(8, (cacheBytes / pieceLength).toInt())
        aheadPieces = max(6, cachePieces * 3 / 4)
        behindPieces = max(2, cachePieces - aheadPieces)
        evicted = BooleanArray(ti.numPieces())

        val dir = File(baseDir(ctx), safeName(ti.name())).apply { mkdirs() }
        saveDir = dir

        addLatch = CountDownLatch(1)
        handle = null
        expectingAdd = true
        try {
            s.download(ti, dir)
            addLatch?.await(20, TimeUnit.SECONDS)
        } finally {
            expectingAdd = false
        }
        val th = handle
        if (th == null || !isValid(th)) {
            handle = null
            TLog.e(TAG, "libtorrent no devolvió un torrent válido")
            throw IllegalStateException("No se pudo añadir el torrent. Mira el registro de errores.")
        }
        TLog.i(TAG, "Torrent añadido. Archivo: $fileName (${Util.bytes(fileSize)}), ahorro=${cfg.spaceSaver}, caché=${cfg.cacheMb} MB")

        val prios = Array(ti.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE }
        th.prioritizeFiles(prios)
        th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

        // Buffer inicial: principio + final del archivo
        val bufferBytes = cfg.bufferMb.toLong() * 1024 * 1024
        val headCount = min(aheadPieces, max(2, (bufferBytes / pieceLength).toInt()))
        val head = (firstPiece..min(lastPiece, firstPiece + headCount - 1)).toList()
        val tail = listOf(max(firstPiece, lastPiece - 1), lastPiece)
        initialPieces = (head + tail).distinct()
        pinned = (listOf(firstPiece, min(lastPiece, firstPiece + 1)) + tail).toSet()

        if (spaceSaver) updateWindow(firstPiece, force = true)
        initialPieces.forEachIndexed { i, p ->
            th.piecePriority(p, Priority.TOP_PRIORITY)
            th.setPieceDeadline(p, 500 + i * 50)
        }
        th.resume()
    }

    fun pieceAt(posInFile: Long): Int = ((fileOffset + posInFile) / pieceLength).toInt()
    fun pieceEndInFile(piece: Int): Long = (piece + 1L) * pieceLength - fileOffset

    fun filePath(): File? {
        val ti = info ?: return null
        val dir = saveDir ?: return null
        return File(dir, ti.files().filePath(fileIndex))
    }

    private fun rawHave(p: Int): Boolean = try {
        val h = handle
        h != null && h.isValid && h.havePiece(p)
    } catch (e: Throwable) { false }

    /** ¿Están los datos de la pieza realmente en disco? (descargada y no borrada) */
    fun haveData(p: Int): Boolean = !rechecking && p in evicted.indices && !evicted[p] && rawHave(p)

    fun initialBufferProgress(): Float {
        if (initialPieces.isEmpty()) return 0f
        return initialPieces.count { haveData(it) }.toFloat() / initialPieces.size
    }

    /** Hasta qué posición del archivo hay datos seguidos desde donde se está leyendo (0..1). */
    fun bufferedAheadFraction(): Float {
        if (fileSize <= 0) return 0f
        var p = readPiece
        while (p <= lastPiece && haveData(p)) p++
        return (min(fileSize, pieceEndInFile(p - 1)).toFloat() / fileSize).coerceIn(0f, 1f)
    }

    /** El reproductor está leyendo la pieza [piece]: prioriza lo que viene y mueve la caché. */
    fun focus(piece: Int) {
        val th = handle ?: return
        if (!isValid(th)) return
        readPiece = piece
        if (spaceSaver && evicted.getOrNull(piece) == true) { recover(piece); return }
        if (piece == lastFocus) return
        val jumped = lastFocus < 0 || piece < lastFocus || piece > lastFocus + 4
        lastFocus = piece

        // clearPieceDeadlines deja las piezas en prioridad 1: hay que llamarlo ANTES de fijar la ventana
        if (jumped) try { th.clearPieceDeadlines() } catch (e: Throwable) { TLog.w(TAG, "clearPieceDeadlines", e) }
        if (spaceSaver) updateWindow(piece, force = jumped)

        val window = min(aheadPieces, max(6, ((16L * 1024 * 1024) / pieceLength).toInt()))
        try {
            var i = 0
            for (p in piece..min(lastPiece, piece + window)) {
                if (!rawHave(p)) { th.setPieceDeadline(p, 300 + i * 120); i++ }
            }
        } catch (e: Throwable) {
            TLog.w(TAG, "focus", e)
        }
    }

    // ---------- Caché deslizante ----------

    private fun inWindow(p: Int, center: Int) =
        p in pinned || (p >= center - behindPieces && p <= center + aheadPieces)

    /** Descarga solo la ventana y borra del disco lo que queda fuera. */
    private fun updateWindow(center: Int, force: Boolean) {
        val th = handle ?: return
        val ti = info ?: return
        synchronized(lock) {
            if (!force && windowCenter >= 0 && kotlin.math.abs(center - windowCenter) < 2) return
            windowCenter = center
            try {
                val prios = Array(ti.numPieces()) { p ->
                    if (p in firstPiece..lastPiece && inWindow(p, center)) Priority.DEFAULT else Priority.IGNORE
                }
                th.prioritizePieces(prios)
            } catch (e: Throwable) {
                TLog.w(TAG, "prioritizePieces", e)
            }
            if (!rechecking && !punchUnsupported) evictOutside(center)
        }
    }

    private fun evictOutside(center: Int) {
        val path = filePath()?.absolutePath ?: return
        if (!File(path).exists()) return
        for (p in firstPiece..lastPiece) {
            if (evicted[p] || inWindow(p, center) || !rawHave(p)) continue
            // Solo la parte de la pieza que pertenece a nuestro archivo
            val start = max(0L, p.toLong() * pieceLength - fileOffset)
            val end = min(fileSize, (p + 1L) * pieceLength - fileOffset)
            val r = HolePuncher.punch(path, start, end - start)
            if (r == 0) {
                evicted[p] = true
                if (p % 50 == 0) TLog.i(TAG, "Liberado hasta pieza $p · en disco ${Util.bytes(diskUsage())}")
            } else {
                TLog.w(TAG, "Este sistema no permite liberar fragmentos (errno ${-r})")
                punchUnsupported = true
                return
            }
        }
    }

    /**
     * Se ha saltado a una parte ya borrada. libtorrent cree que la tiene, así que
     * forzamos una comprobación: las piezas borradas pasan a "faltan" y, como solo
     * la nueva ventana tiene prioridad, solo se vuelve a descargar esa parte.
     */
    private fun recover(piece: Int) {
        val th = handle ?: return
        synchronized(lock) {
            if (rechecking) return
            rechecking = true
        }
        TLog.i(TAG, "Salto a parte borrada (pieza $piece): recomprobando")
        Thread {
            try {
                updateWindow(piece, force = true)
                th.forceRecheck()
                var seenChecking = false
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 120_000) {
                    val st = th.status().state()
                    val checking = st == TorrentStatus.State.CHECKING_FILES ||
                            st == TorrentStatus.State.CHECKING_RESUME_DATA
                    if (checking) seenChecking = true
                    if (seenChecking && !checking) break
                    if (!seenChecking && System.currentTimeMillis() - t0 > 5000) break
                    Thread.sleep(200)
                }
                synchronized(lock) { java.util.Arrays.fill(evicted, false) }
                th.resume()
            } catch (e: Throwable) {
                TLog.w(TAG, "recover", e)
            } finally {
                rechecking = false
                lastFocus = -1
                windowCenter = -1
                focus(piece)
            }
        }.start()
    }

    /** Espacio que ocupa de verdad el vídeo en disco. */
    fun diskUsage(): Long = try {
        val f = filePath()
        if (f != null && f.exists()) Os.stat(f.absolutePath).st_blocks * 512L else 0L
    } catch (e: Throwable) { 0L }

    data class Stats(val down: Int, val up: Int, val peers: Int, val seeds: Int, val progress: Float)

    fun stats(): Stats? {
        val th = handle ?: return null
        if (!isValid(th)) return null
        return try {
            val st = th.status()
            Stats(st.downloadRate(), st.uploadRate(), st.numPeers(), st.numSeeds(), st.progress())
        } catch (e: Throwable) { null }
    }

    /**
     * Para un torrent. [which] permite que una pantalla antigua solo cierre SU torrent
     * y no el que haya abierto otra pantalla después.
     */
    @Synchronized
    fun stop(ctx: Context, deleteFiles: Boolean, which: TorrentHandle? = handle, dir: File? = saveDir) {
        if (which != null && which === handle) {
            // Primero se suelta, para que nadie use el handle mientras se cierra
            handle = null
            info = null
            initialPieces = emptyList()
            saveDir = null
        }
        if (which != null) {
            TLog.i(TAG, "Parando torrent (borrar=$deleteFiles)")
            removeAndWait(which)
        }
        if (deleteFiles && dir != null) {
            Thread {
                Thread.sleep(1500)
                if (saveDir?.absolutePath != dir.absolutePath) dir.deleteRecursively()
            }.start()
        }
    }

    fun clearAllDownloads(ctx: Context) {
        if (handle == null) baseDir(ctx).deleteRecursively()
    }

    /** Espacio que ocupan ahora todas las descargas guardadas. */
    fun storedBytes(ctx: Context): Long = baseDir(ctx).walkTopDown().filter { it.isFile }.sumOf { f ->
        runCatching { Os.stat(f.absolutePath).st_blocks * 512L }.getOrDefault(f.length())
    }

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
}
