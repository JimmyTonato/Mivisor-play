package com.mivisor.play.torrent

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.mivisor.play.data.AppLog
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
import org.libtorrent4j.TorrentInfo
import java.io.File

/**
 * Torrents y magnet: motor de torrents ([TorrentEngine]) → servidor local ([StreamServer]) → Media3 (el mismo
 * reproductor que las emisiones de Ace Stream). Se usa siempre desde el hilo principal.
 */
@OptIn(UnstableApi::class)
class TorrentPlayback(context: Context) {

    enum class Phase { IDLE, METADATA, CHOOSE, CONNECTING, PLAYING, FAILED }

    data class FileOption(val index: Int, val name: String, val size: Long)

    data class Track(val type: Int, val group: Int, val track: Int, val label: String, val selected: Boolean, val supported: Boolean)

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val title: String = "",
        val message: String? = null,
        val files: List<FileOption> = emptyList(),
        /** 0..1 del buffer inicial (antes de que empiece la imagen). */
        val prepare: Float = 0f,
        val downBps: Int = 0,
        val peers: Int = 0,
        val seeds: Int = 0,
        val diskBytes: Long = 0,
        /** 0..1 hasta dónde hay datos seguidos desde lo que se está viendo. */
        val buffered: Float = 0f,
        val buffering: Boolean = false,
        val paused: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val tracks: List<Track> = emptyList(),
        /** El vídeo trae audio pero ninguna pista se puede reproducir en este aparato (AC3/DTS en muchos móviles). */
        val audioUnsupported: Boolean = false,
        val recovering: Boolean = false,
        val punchUnsupported: Boolean = false,
        val streamUrl: String? = null
    ) {
        val active: Boolean get() = phase != Phase.IDLE && phase != Phase.FAILED
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    var player: ExoPlayer? = null
        private set
    private var server: StreamServer? = null
    private var info: TorrentInfo? = null
    private var job: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private var playerStarted = false
    private var retries = 0

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (_ui.value.phase != Phase.PLAYING) AppLog.add("Torrent", "Imagen en pantalla")
            _ui.value = _ui.value.copy(phase = Phase.PLAYING, message = null)
            retries = 0
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _ui.value = _ui.value.copy(buffering = playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY && _ui.value.phase == Phase.CONNECTING && player?.videoSize?.width == 0) {
                // Solo audio (o aún sin vídeo): también cuenta como reproduciendo.
                _ui.value = _ui.value.copy(phase = Phase.PLAYING, message = null)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            _ui.value = _ui.value.copy(paused = !isPlaying && !p.playWhenReady)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val list = trackList(tracks)
            val audio = list.filter { it.type == C.TRACK_TYPE_AUDIO }
            val unsupported = audio.isNotEmpty() && audio.none { it.supported }
            if (unsupported && !_ui.value.audioUnsupported) AppLog.add("Torrent", "Ninguna pista de audio se puede reproducir aquí: ${audio.joinToString { it.label }}")
            _ui.value = _ui.value.copy(tracks = list, audioUnsupported = unsupported)
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.add("Torrent", "Error del reproductor: ${error.errorCodeName} · ${error.message}")
            val p = player ?: return
            // Los datos llegan mientras se descargan: un corte de lectura se reintenta en el mismo punto.
            if (retries < 5 && TorrentEngine.handle != null) {
                retries++
                val pos = p.currentPosition
                scope.launch {
                    delay(1500L * retries)
                    player?.let { it.seekTo(pos); it.prepare(); it.play() }
                }
            } else {
                fail("No se puede reproducir este vídeo (${error.errorCodeName}). Prueba «Abrir con otro reproductor».")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ API

    /** Abre un archivo .torrent ya guardado en la app. */
    fun openFile(path: String, title: String) = begin(title) {
        TorrentEngine.ensureStarted(app)
        TorrentInfo(File(path))
    }

    /** Abre un enlace magnet (primero descarga su información de los demás usuarios). */
    fun openMagnet(uri: String, title: String) = begin(title) {
        TorrentEngine.ensureStarted(app)
        withContext(Dispatchers.Main) { _ui.value = _ui.value.copy(message = "Pidiendo la información del magnet a otros usuarios… (hasta 1,5 min)") }
        TorrentEngine.fetchMagnet(app, uri) ?: throw IllegalStateException("Nadie contestó con la información de este magnet. Prueba más tarde.")
    }

    private fun begin(title: String, load: suspend () -> TorrentInfo) {
        stop()
        val gen = ++generation
        _ui.value = Ui(phase = Phase.METADATA, title = title, message = "Leyendo el torrent…")
        job = scope.launch {
            try {
                val ti = withContext(Dispatchers.IO) { load() }
                if (gen != generation) return@launch
                info = ti
                val fs = ti.files()
                var files = (0 until fs.numFiles()).map { FileOption(it, fs.fileName(it), fs.fileSize(it)) }
                val videos = files.filter { Util.isVideo(it.name) }
                if (videos.isNotEmpty()) files = videos
                files = files.sortedByDescending { it.size }
                AppLog.add("Torrent", "«${ti.name()}»: ${fs.numFiles()} archivos, ${Util.bytes(ti.totalSize())}")
                if (files.size == 1) choose(files[0].index)
                else _ui.value = _ui.value.copy(phase = Phase.CHOOSE, title = ti.name(), files = files, message = null)
            } catch (e: Throwable) {
                if (gen == generation) fail(e.message ?: "El torrent no es válido")
            }
        }
    }

    /** Empieza a ver el archivo [index] del torrent. */
    fun choose(index: Int) {
        val ti = info ?: return
        val gen = generation
        val name = ti.files().fileName(index)
        _ui.value = _ui.value.copy(phase = Phase.CONNECTING, title = name, files = emptyList(), message = "Conectando con otros usuarios…")
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { TorrentEngine.startStream(app, ti, index) }
                val s = withContext(Dispatchers.IO) { StreamServer().start() }
                if (gen != generation) { s.stop(); return@launch }
                server = s
                _ui.value = _ui.value.copy(streamUrl = s.url())
                AppLog.add("Torrent", "Servidor local en el puerto ${s.port}")
                startTicker(gen)
            } catch (e: Throwable) {
                if (gen == generation) fail(e.message ?: "No se pudo empezar la descarga")
            }
        }
    }

    private fun startTicker(gen: Int) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && gen == generation) {
                val st = TorrentEngine.stats()
                val p = player
                _ui.value = _ui.value.copy(
                    downBps = st?.down ?: 0,
                    peers = st?.peers ?: 0,
                    seeds = st?.seeds ?: 0,
                    diskBytes = TorrentEngine.diskUsage(),
                    buffered = TorrentEngine.bufferedAheadFraction(),
                    recovering = TorrentEngine.rechecking,
                    punchUnsupported = TorrentEngine.punchUnsupported,
                    positionMs = p?.currentPosition ?: 0,
                    durationMs = p?.duration?.takeIf { it > 0 } ?: 0
                )
                if (!playerStarted) {
                    val prep = TorrentEngine.initialBufferProgress()
                    _ui.value = _ui.value.copy(
                        prepare = prep,
                        message = if ((st?.peers ?: 0) == 0) "Buscando usuarios que lo compartan…" else "Preparando el vídeo… ${(prep * 100).toInt()} %"
                    )
                    if (prep >= 1f) startPlayer()
                }
                delay(1000)
            }
        }
    }

    private fun startPlayer() {
        val url = _ui.value.streamUrl ?: return
        playerStarted = true
        AppLog.add("Torrent", "Buffer inicial listo: empieza la reproducción")
        val renderers = DefaultRenderersFactory(app)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .build()
        val p = ExoPlayer.Builder(app, renderers).setLoadControl(load).build()
        // El servidor espera a que llegue cada pieza: lecturas largas permitidas.
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(120_000)
        val source = ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(url))
        p.addListener(listener)
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        player = p
        _ui.value = _ui.value.copy(message = "Abriendo el vídeo…")
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        _ui.value = _ui.value.copy(paused = !p.playWhenReady)
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, maxOf(0L, dur - 1_000L)))
    }

    fun seekToFraction(f: Float) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: return
        p.seekTo((dur * f.coerceIn(0f, 1f)).toLong())
    }

    fun selectTrack(t: Track) {
        val p = player ?: return
        val group = p.currentTracks.groups.getOrNull(t.group) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(t.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t.track))
            .build()
    }

    fun disableSubtitles() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    /** Para todo: reproductor, servidor y descarga (lo descargado se borra). */
    fun stop() {
        generation++
        job?.cancel()
        ticker?.cancel()
        player?.let { it.removeListener(listener); it.release() }
        player = null
        playerStarted = false
        retries = 0
        server?.stop()
        server = null
        info = null
        val app = app
        if (TorrentEngine.handle != null) Thread { runCatching { TorrentEngine.stop(app, deleteFiles = true) } }.start()
        _ui.value = Ui()
    }

    fun release() = stop()

    private fun fail(message: String) {
        AppLog.add("Torrent", "Fallo: $message")
        ticker?.cancel()
        _ui.value = _ui.value.copy(phase = Phase.FAILED, message = message)
    }

    private fun trackList(tracks: Tracks): List<Track> {
        val out = mutableListOf<Track>()
        var audioN = 0
        var subN = 0
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                val n = if (g.type == C.TRACK_TYPE_AUDIO) ++audioN else ++subN
                val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
                val codec = f.sampleMimeType?.substringAfter('/')?.uppercase()
                val channels = if (f.channelCount > 0) " · ${f.channelCount} canales" else ""
                val base = f.label ?: lang ?: if (g.type == C.TRACK_TYPE_AUDIO) "Audio $n" else "Subtítulos $n"
                val label = if (g.type == C.TRACK_TYPE_AUDIO && codec != null) "$base ($codec$channels)" else base
                out += Track(g.type, gi, ti, label, g.isTrackSelected(ti), g.isTrackSupported(ti))
            }
        }
        return out
    }
}
