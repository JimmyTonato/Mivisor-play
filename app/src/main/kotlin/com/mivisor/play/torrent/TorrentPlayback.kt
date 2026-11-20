package com.mivisor.play.torrent

import android.content.Context
import android.net.Uri
import com.mivisor.play.data.AppLog
import com.mivisor.play.torrserver.TorrServerApi
import com.mivisor.play.torrserver.TorrServerEngine
import com.mivisor.play.torrserver.TsConfig
import com.mivisor.play.torrserver.TsFile
import com.mivisor.play.torrserver.TsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * Torrents y magnet, como Ace Stream: TorrServer (proceso aparte) descarga y sirve el vídeo por HTTP en 127.0.0.1, y
 * VLC lo reproduce dentro de la app (lee cualquier audio: AC3, DTS…). Se usa siempre desde el hilo principal.
 */
class TorrentPlayback(context: Context, private val config: () -> TsConfig, private val hwDecoding: () -> Boolean) {

    enum class Phase { IDLE, METADATA, CHOOSE, CONNECTING, PLAYING, FAILED }

    data class FileOption(val id: Int, val name: String, val size: Long)

    /** Pista de audio o subtítulos de VLC ([id] es el de VLC; -1 = desactivados). */
    data class Track(val id: Int, val label: String, val selected: Boolean)

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val title: String = "",
        val message: String? = null,
        val files: List<FileOption> = emptyList(),
        /** 0..1 de la precarga antes de que salga la imagen. */
        val prepare: Float = 0f,
        val downBps: Long = 0,
        val peers: Int = 0,
        val totalPeers: Int = 0,
        val seeds: Int = 0,
        val buffering: Boolean = false,
        val bufferPct: Float = 0f,
        val paused: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val audio: List<Track> = emptyList(),
        val subs: List<Track> = emptyList(),
        val engineDied: Boolean = false,
        val streamUrl: String? = null,
        val fileSize: Long = 0
    ) {
        val active: Boolean get() = phase != Phase.IDLE && phase != Phase.FAILED
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var libVlc: LibVLC? = null
    var player: MediaPlayer? = null
        private set
    private var layout: VLCVideoLayout? = null
    private var attached = false
    private var base: String? = null
    private var hash: String? = null
    private var files: List<TsFile> = emptyList()
    private var job: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private var hwInUse = true
    private var triedSoftware = false
    private var voutCount = 0
    private var playingSince = 0L

    // ------------------------------------------------------------------------------------------ Vista

    /** La pantalla da su superficie de vídeo (puede llegar antes o después de crear el reproductor). */
    fun attach(view: VLCVideoLayout) {
        if (layout === view && attached) return
        layout = view
        val p = player ?: return
        if (attached) runCatching { p.detachViews() }
        runCatching { p.attachViews(view, null, true, false); attached = true }
            .onFailure { AppLog.add("Torrent", "No se pudo poner el vídeo en pantalla: ${it.message}") }
    }

    fun detach(view: VLCVideoLayout) {
        if (layout !== view) return
        runCatching { player?.detachViews() }
        attached = false
        layout = null
    }

    // ------------------------------------------------------------------------------------------ Abrir

    fun openFile(path: String, title: String) = begin(title, "Leyendo el .torrent…") { b ->
        TorrServerApi.upload(b, File(path), title)
    }

    fun openMagnet(uri: String, title: String) = begin(title, "Pidiendo la información del magnet a otros usuarios…") { b ->
        TorrServerApi.add(b, uri, title)
    }

    private fun begin(title: String, loading: String, add: (String) -> TsStatus) {
        stop()
        val gen = ++generation
        _ui.value = Ui(phase = Phase.METADATA, title = title, message = "Arrancando el motor de torrents…")
        job = scope.launch {
            try {
                val b = withContext(Dispatchers.IO) { TorrServerEngine.ensureStarted(app, config()) }
                if (gen != generation) return@launch
                base = b
                _ui.value = _ui.value.copy(message = loading)
                val first = withContext(Dispatchers.IO) { add(b) }
                if (gen != generation) return@launch
                val h = first.hash.takeIf { it.isNotBlank() } ?: throw IllegalStateException("El motor no devolvió el torrent")
                hash = h
                AppLog.add("Torrent", "Añadido ${h.take(8)}… «${first.title.ifBlank { first.name }}»")

                // Un magnet tarda en traer la lista de archivos: se espera (hasta 3 min) enseñando qué pasa.
                var st = first
                val t0 = System.currentTimeMillis()
                while (st.files.isEmpty()) {
                    if (gen != generation) return@launch
                    if (!TorrServerEngine.running()) { engineDied(); return@launch }
                    if (System.currentTimeMillis() - t0 > 180_000) throw IllegalStateException("Nadie contestó con la información de este torrent en 3 minutos. Puede que ahora mismo nadie lo comparta.")
                    delay(1_000)
                    st = withContext(Dispatchers.IO) { TorrServerApi.get(b, h) } ?: st
                    _ui.value = _ui.value.copy(
                        peers = st.activePeers, totalPeers = st.totalPeers, seeds = st.seeders, downBps = st.downBps,
                        message = if (st.totalPeers == 0) "$loading Buscando usuarios…" else "$loading ${st.activePeers} usuarios conectados"
                    )
                }
                files = st.files
                val name = st.name.ifBlank { st.title }.ifBlank { title }
                var options = st.files.map { FileOption(it.id, it.name, it.length) }
                val videos = options.filter { isVideo(it.name) }
                if (videos.isNotEmpty()) options = videos
                options = options.sortedByDescending { it.size }
                AppLog.add("Torrent", "«$name»: ${st.files.size} archivos, ${videos.size} de vídeo")
                if (options.size == 1) choose(options[0].id)
                else _ui.value = _ui.value.copy(phase = Phase.CHOOSE, title = name, files = options, message = null)
            } catch (e: Throwable) {
                if (gen == generation) {
                    if (!TorrServerEngine.running() && TorrServerEngine.exitReason != null) engineDied()
                    else fail(e.message ?: "No se pudo abrir el torrent")
                }
            }
        }
    }

    /** Empieza a ver el archivo [id] del torrent. */
    fun choose(id: Int) {
        val b = base ?: return
        val h = hash ?: return
        val f = files.firstOrNull { it.id == id } ?: return
        val url = TorrServerApi.streamUrl(b, h, f)
        AppLog.add("Torrent", "Reproduciendo «${f.name}» (${f.length / (1024 * 1024)} MB)")
        _ui.value = _ui.value.copy(
            phase = Phase.CONNECTING, title = f.name, files = emptyList(), streamUrl = url, fileSize = f.length,
            message = "Preparando el vídeo…"
        )
        triedSoftware = false
        startVlc(url, software = !hwDecoding(), startAtMs = 0)
        startTicker(generation)
    }

    // ------------------------------------------------------------------------------------------ VLC

    private fun startVlc(url: String, software: Boolean, startAtMs: Long) {
        releaseVlc()
        hwInUse = !software
        voutCount = 0
        playingSince = 0
        try {
            val lib = LibVLC(app, arrayListOf("--network-caching=3000", "--http-reconnect", "--audio-time-stretch", "-v"))
            val mp = MediaPlayer(lib)
            libVlc = lib
            player = mp
            mp.setEventListener { e -> onVlcEvent(e) }
            layout?.let { v -> runCatching { mp.attachViews(v, null, true, false); attached = true } }
            val media = Media(lib, Uri.parse(url))
            media.setHWDecoderEnabled(!software, false)
            media.addOption(":network-caching=3000")
            if (startAtMs > 0) media.addOption(":start-time=${startAtMs / 1000.0}")
            mp.setMedia(media)
            media.release()
            mp.play()
            AppLog.add("Torrent", "VLC empieza (aceleración por hardware: ${!software})")
        } catch (e: Throwable) {
            AppLog.add("Torrent", "VLC no arrancó: ${e.javaClass.simpleName}: ${e.message}")
            fail("El reproductor no pudo arrancar: ${e.message}")
        }
    }

    private fun releaseVlc() {
        val mp = player
        player = null
        if (mp != null) runCatching {
            mp.setEventListener(null)
            mp.stop()
            if (attached) mp.detachViews()
            mp.release()
        }
        attached = false
        runCatching { libVlc?.release() }
        libVlc = null
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        val p = player ?: return
        when (e.type) {
            MediaPlayer.Event.Buffering -> _ui.value = _ui.value.copy(buffering = e.buffering < 100f, bufferPct = e.buffering)
            MediaPlayer.Event.Playing -> {
                if (_ui.value.phase != Phase.PLAYING) {
                    AppLog.add("Torrent", "Reproduciendo")
                    playingSince = System.currentTimeMillis()
                }
                _ui.value = _ui.value.copy(phase = Phase.PLAYING, message = null, paused = false)
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> _ui.value = _ui.value.copy(paused = true)
            MediaPlayer.Event.Vout -> voutCount = e.voutCount
            MediaPlayer.Event.LengthChanged -> _ui.value = _ui.value.copy(durationMs = e.lengthChanged)
            MediaPlayer.Event.TimeChanged -> _ui.value = _ui.value.copy(positionMs = e.timeChanged, buffering = false)
            MediaPlayer.Event.EndReached -> _ui.value = _ui.value.copy(paused = true)
            MediaPlayer.Event.EncounteredError -> {
                AppLog.add("Torrent", "VLC dio error con el vídeo")
                when {
                    !TorrServerEngine.running() -> engineDied()
                    hwInUse && !triedSoftware -> retrySoftware("error de VLC", p.time)
                    else -> fail("No se puede reproducir este vídeo. Prueba «Otro reproductor».")
                }
            }
        }
    }

    /** Hay sonido pero no imagen (o error) con la aceleración por hardware: se reintenta sin ella, en el mismo punto. */
    private fun retrySoftware(why: String, atMs: Long) {
        val url = _ui.value.streamUrl ?: return
        triedSoftware = true
        AppLog.add("Torrent", "Reintentando sin aceleración por hardware ($why)")
        scope.launch { startVlc(url, software = true, startAtMs = atMs) }
    }

    private fun refreshTracks() {
        val p = player ?: return
        val audio = runCatching { p.audioTracks?.toList().orEmpty() }.getOrDefault(emptyList())
        val subs = runCatching { p.spuTracks?.toList().orEmpty() }.getOrDefault(emptyList())
        val a = runCatching { p.audioTrack }.getOrDefault(-1)
        val s = runCatching { p.spuTrack }.getOrDefault(-1)
        _ui.value = _ui.value.copy(
            audio = audio.filter { it.id >= 0 }.map { Track(it.id, it.name ?: "Audio ${it.id}", it.id == a) },
            subs = subs.map { Track(it.id, if (it.id < 0) "Desactivados" else (it.name ?: "Subtítulos ${it.id}"), it.id == s) }
        )
    }

    // ------------------------------------------------------------------------------------------ Estado

    private fun startTicker(gen: Int) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && gen == generation) {
                val b = base
                val h = hash
                if (!TorrServerEngine.running()) { engineDied(); return@launch }
                val st = if (b != null && h != null) withContext(Dispatchers.IO) { TorrServerApi.get(b, h) } else null
                val p = player
                if (st != null) {
                    val prep = if (st.preloadSize > 0) (st.preloadedBytes.toFloat() / st.preloadSize).coerceIn(0f, 1f) else 0f
                    _ui.value = _ui.value.copy(
                        downBps = st.downBps, peers = st.activePeers, totalPeers = st.totalPeers, seeds = st.seeders, prepare = prep,
                        message = if (_ui.value.phase == Phase.CONNECTING)
                            (if (st.totalPeers == 0) "Buscando usuarios que lo compartan…" else "Preparando el vídeo…") else _ui.value.message
                    )
                }
                if (p != null) _ui.value = _ui.value.copy(positionMs = p.time, durationMs = p.length.takeIf { it > 0 } ?: _ui.value.durationMs)
                // Con aceleración por hardware, a veces hay sonido y la imagen no sale: se reintenta sin ella.
                if (_ui.value.phase == Phase.PLAYING && hwInUse && !triedSoftware && voutCount == 0 &&
                    playingSince > 0 && System.currentTimeMillis() - playingSince > 5_000 &&
                    runCatching { (p?.videoTracksCount ?: 0) > 0 }.getOrDefault(false)
                ) retrySoftware("no hay imagen", p?.time ?: 0)
                if (_ui.value.phase == Phase.PLAYING && _ui.value.audio.isEmpty()) refreshTracks()
                delay(1_000)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ Controles

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() { player?.pause() }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val len = p.length.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.setTime((p.time + deltaMs).coerceIn(0L, maxOf(0L, len - 1_000L)))
    }

    fun seekToFraction(f: Float) {
        val p = player ?: return
        val len = p.length.takeIf { it > 0 } ?: return
        p.setTime((len * f.coerceIn(0f, 1f)).toLong())
    }

    fun selectAudio(id: Int) { player?.setAudioTrack(id); refreshTracks() }
    fun selectSub(id: Int) { player?.setSpuTrack(id); refreshTracks() }
    fun setRate(rate: Float) { player?.setRate(rate) }
    fun setScale(type: MediaPlayer.ScaleType) { player?.setVideoScale(type) }

    /** Para el vídeo y suelta el torrent (TorrServer borra su caché). El motor sigue listo para el siguiente. */
    fun stop() {
        generation++
        job?.cancel()
        ticker?.cancel()
        releaseVlc()
        val b = base
        val h = hash
        if (b != null && h != null) Thread { TorrServerApi.drop(b, h) }.start()
        hash = null
        files = emptyList()
        _ui.value = Ui()
    }

    /** Al cerrar la app: también el motor. */
    fun release() {
        stop()
        Thread { TorrServerEngine.stop() }.start()
    }

    private fun engineDied() {
        val why = TorrServerEngine.exitReason ?: "dejó de responder"
        AppLog.add("Torrent", "El motor de torrents se cerró: $why")
        ticker?.cancel()
        releaseVlc()
        _ui.value = _ui.value.copy(
            phase = Phase.FAILED, engineDied = true,
            message = "El motor de torrents se cerró ($why). La app sigue funcionando: pulsa Reintentar. Los detalles están en el registro."
        )
    }

    private fun fail(message: String) {
        AppLog.add("Torrent", "Fallo: $message")
        ticker?.cancel()
        _ui.value = _ui.value.copy(phase = Phase.FAILED, message = message)
    }

    companion object {
        private val VIDEO = setOf("mkv", "mp4", "avi", "m4v", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp", "vob")
        fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO

        fun bytes(b: Long): String {
            if (b < 1024) return "$b B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = b / 1024.0
            var i = 0
            while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
            return String.format(java.util.Locale.getDefault(), if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
        }

        fun speed(bps: Long) = bytes(bps) + "/s"
    }
}
