package com.mivisor.play

import android.app.Application
import android.content.Context
import com.mivisor.play.data.AppLog
import com.mivisor.play.data.Kind
import com.mivisor.play.torrent.TorrentPlayback
import com.mivisor.play.torrserver.TsConfig
import com.mivisor.play.torrserver.TsLog
import com.mivisor.play.data.SimpleSourceBook
import com.mivisor.play.data.Store
import com.mivisor.tv.catalog.datos.DirectStream
import com.mivisor.tv.engine.EngineDiagnostics
import com.mivisor.tv.outpace.EngineManager
import com.mivisor.tv.playback.PlaySource
import com.mivisor.tv.playback.PlayRequest
import com.mivisor.tv.playback.PlaybackController

class PlayApp : Application() {
    val graph: Graph by lazy { Graph(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { AppLog.add("CIERRE", "${e.javaClass.name}: ${e.message} (hilo ${t.name})") }
            runCatching {
                getSharedPreferences("mivisor_play_crash", MODE_PRIVATE).edit()
                    .putString("last", "${e.javaClass.name}: ${e.message}\n" + e.stackTrace.take(12).joinToString("\n") { "  at $it" })
                    .commit()
            }
            previous?.uncaughtException(t, e)
        }
    }
}

/** Todo lo que vive mientras vive el proceso (sin inyección de dependencias). */
class Graph(context: Context) {
    val app: Context = context.applicationContext
    val store = Store(app)
    val book = SimpleSourceBook(app, store)
    /** Emisiones de Ace Stream: Outpace + Media3 (la reproducción de MiVisor TV, sin cambios). */
    val player = PlaybackController(app, book)
    /** Torrents y magnet: TorrServer (proceso aparte) + VLC, como Ace Stream. */
    val torrents = TorrentPlayback(app, config = { torrentConfig() }, hwDecoding = { store.hwDecoding })
    /** Un torrent se está viendo en otro reproductor: al salir de la app no se para la descarga. */
    @Volatile var externalPlayback = false

    init {
        EngineDiagnostics.enabled = store.verboseLog
        EngineManager.fullP2p = store.fullP2p
        TsLog.sink = { AppLog.add("TorrServer", it) }
        // Cierre inesperado en la sesión anterior: se deja en el registro para poder verlo y copiarlo.
        val crash = app.getSharedPreferences("mivisor_play_crash", Context.MODE_PRIVATE)
        val javaCrash = crash.getString("last", null)
        javaCrash?.let { AppLog.add("Cierre anterior", it) }
        // ¿Se quedó a medias la sesión anterior (la app estaba en pantalla y se cerró sola)?
        val unclean = crash.getBoolean("in_foreground", false)
        crash.edit().remove("last").putBoolean("in_foreground", false).apply()
        if (unclean && javaCrash == null) Thread { AppLog.captureLastCrash(app.packageName) }.start()
        AppLog.add("App", "Abierta · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · ${BuildConfig.VERSION_NAME}")
    }

    /** La app está en pantalla (si se cierra sola así, al abrirla otra vez se recoge el motivo). */
    fun markForeground(on: Boolean) {
        app.getSharedPreferences("mivisor_play_crash", Context.MODE_PRIVATE).edit().putBoolean("in_foreground", on).commit()
    }

    /** Lo que se pide al reproductor para ver una emisión. */
    fun request(contentId: String): PlayRequest {
        val name = displayName(contentId)
        return PlayRequest(
            channelId = DirectStream.channelId(contentId),
            channelName = name,
            sources = listOf(PlaySource(contentId = contentId, providerId = "manual", providerName = "Ace Stream", quality = 0, qualityLabel = null))
        )
    }

    fun displayName(contentId: String): String {
        val s = store.find(contentId)
        s?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return when (s?.kind) {
            Kind.MAGNET -> magnetName(contentId) ?: "Magnet"
            Kind.TORRENT -> java.io.File(contentId).nameWithoutExtension
            else -> "Emisión ${DirectStream.short(contentId)}"
        }
    }

    /** Ajustes de TorrServer: se aplican al arrancar el motor (si cambian, se reinicia con el siguiente torrent). */
    fun torrentConfig() = TsConfig(
        cacheMb = store.cacheMb,
        useDisk = store.cacheOnDisk,
        preloadPercent = store.preloadPercent,
        uploadKbps = store.uploadKbps
    )

    /** Guarda dentro de la app un .torrent elegido en el móvil (para poder abrirlo otra vez desde Recientes). */
    fun importTorrent(uri: android.net.Uri): String? = runCatching {
        var name = "torrent-${System.currentTimeMillis()}.torrent"
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val dir = java.io.File(app.filesDir, "torrent-files").apply { mkdirs() }
        val dest = java.io.File(dir, name.replace('/', '_').replace('\t', ' ').take(120).let { if (it.endsWith(".torrent", true)) it else "$it.torrent" })
        app.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        require(dest.length() in 1..(20L shl 20)) { "El archivo no parece un .torrent" }
        AppLog.add("Abrir", "Torrent guardado: ${dest.name} (${dest.length()} bytes)")
        dest.absolutePath
    }.onFailure { AppLog.add("Abrir", "No se pudo leer el archivo: ${it.message}") }.getOrNull()

    companion object {
        /** El nombre que trae el propio magnet («dn=»), si lo trae. */
        fun magnetName(uri: String): String? = runCatching {
            val q = uri.substringAfter('?', "")
            q.split('&').firstOrNull { it.startsWith("dn=") }?.removePrefix("dn=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
        }.getOrNull()

        fun isMagnet(text: String) = text.trim().startsWith("magnet:?", ignoreCase = true)
    }
}
