package com.mivisor.play.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.mivisor.play.Graph
import com.mivisor.play.R
import com.mivisor.play.data.Kind
import com.mivisor.play.torrent.StreamServer
import com.mivisor.play.torrent.TorrentEngine
import com.mivisor.play.torrent.TorrentPlayback.Phase
import com.mivisor.play.torrent.Util
import com.mivisor.tv.ui.design.MvIcons
import kotlinx.coroutines.delay

private enum class TPanel { AUDIO, SUBS, INFO }

/** Torrent o magnet: se ve mientras se descarga, en el mismo reproductor (Media3). */
@Composable
fun TorrentPlayerScreen(graph: Graph, open: Open, onClose: () -> Unit, onLog: () -> Unit) {
    val ui by graph.torrents.ui.collectAsState()
    val context = LocalContext.current
    var controls by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<TPanel?>(null) }
    var fit by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var audioWarned by remember(open.key) { mutableStateOf(false) }
    val catcher = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val kind = if (open.kind == Kind.MAGNET) "Magnet" else "Torrent"
    val name = graph.displayName(open.key)

    LaunchedEffect(open.key) {
        graph.applyTorrentSettings()
        if (open.kind == Kind.MAGNET) graph.torrents.openMagnet(open.key, name) else graph.torrents.openFile(open.key, name)
    }
    DisposableEffect(open.key) { onDispose { graph.torrents.stop() } }

    // El nombre real del vídeo, en cuanto se sabe (si no le pusiste uno).
    LaunchedEffect(ui.title, ui.phase) {
        if (ui.phase == Phase.CONNECTING || ui.phase == Phase.PLAYING) ui.title.takeIf { it.isNotBlank() }?.let {
            graph.store.autoName(open.key, it.substringBeforeLast('.'))
        }
    }

    LaunchedEffect(controls, touch, panel) {
        if (controls && panel == null) { delay(5_000); controls = false }
    }
    LaunchedEffect(controls, ui.phase) {
        delay(80)
        // Solo con imagen: en la carga o en un error el foco es de sus botones.
        if (ui.phase == Phase.PLAYING) runCatching { if (controls) playFocus.requestFocus() else catcher.requestFocus() }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(1_600); toast = null } }
    LaunchedEffect(ui.audioUnsupported) {
        if (ui.audioUnsupported && !audioWarned) { audioWarned = true; controls = true; touch++ }
    }

    fun openExternal() {
        val url = ui.streamUrl ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), StreamServer.mimeFor(TorrentEngine.fileName)).putExtra("title", ui.title)
        try {
            graph.externalPlayback = true
            graph.torrents.player?.pause()
            context.startActivity(Intent.createChooser(i, "Abrir con…"))
        } catch (e: Exception) {
            graph.externalPlayback = false
            toast = "No hay otro reproductor instalado (por ejemplo, VLC)"
        }
    }

    fun seek(dir: Int) {
        graph.torrents.seekBy(dir * 10_000L)
        toast = if (dir < 0) "« 10 s" else "10 s »"
    }

    BackHandler {
        when {
            panel != null -> panel = null
            controls -> controls = false
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize()) {
        VideoSurface(graph.torrents.player, fit, graph.store.subtitleSize, keepOn = ui.active)
        KeyCatcher(
            focus = catcher,
            onShowControls = { if (ui.phase == Phase.PLAYING) { controls = true; touch++ } },
            onToggleControls = { if (ui.phase == Phase.PLAYING) { controls = !controls; touch++ } },
            onPlayPause = { graph.torrents.togglePause() },
            onLeftRight = { d -> if (ui.phase == Phase.PLAYING) seek(d) },
            onDoubleTapSide = { d -> if (ui.phase == Phase.PLAYING) seek(d) }
        )

        when (ui.phase) {
            Phase.FAILED -> FailOverlay("No se pudo ver «$name»", ui.message ?: "Algo falló al abrir el torrent.") {
                val f = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Reintentar", onClick = {
                        if (open.kind == Kind.MAGNET) graph.torrents.openMagnet(open.key, name) else graph.torrents.openFile(open.key, name)
                    }, icon = MvIcons.Refresh, primary = true, focusRequester = f)
                    if (ui.streamUrl != null) ActionButton("Otro reproductor", onClick = { openExternal() }, icon = R.drawable.ic_open_external)
                    ActionButton("Ver registro", onClick = onLog, icon = MvIcons.List)
                    ActionButton("Volver", onClick = onClose, icon = MvIcons.Back)
                }
                FocusOnStart(f, ui.phase)
            }
            Phase.CHOOSE -> FileChooser(ui.title, ui.files, onPick = { graph.torrents.choose(it) }, onCancel = onClose)
            Phase.PLAYING -> Unit
            else -> LoadingOverlay(
                title = ui.title.ifBlank { name },
                kind = kind,
                steps = listOf("Leer el torrent", "Conectar", "Preparar el vídeo"),
                current = when (ui.phase) { Phase.IDLE, Phase.METADATA -> 0; else -> if (ui.peers == 0) 1 else 2 },
                message = ui.message,
                hint = when {
                    ui.phase == Phase.METADATA && open.kind == Kind.MAGNET -> "Un magnet no trae la lista de archivos: se pide a quien lo comparte."
                    ui.phase == Phase.CONNECTING && ui.peers == 0 -> "Si tarda mucho, puede que ahora mismo nadie lo esté compartiendo."
                    else -> null
                },
                stats = if (ui.phase == Phase.CONNECTING) "↓ ${Util.speed(ui.downBps)} · ${ui.peers} usuarios (${ui.seeds} con todo)" else null,
                progress = if (ui.phase == Phase.CONNECTING && ui.peers > 0) ui.prepare else null
            ) {
                val f = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Cancelar", onClick = onClose, icon = MvIcons.Close, focusRequester = f)
                    ActionButton("Ver registro", onClick = onLog, icon = MvIcons.List)
                }
                FocusOnStart(f)
            }
        }

        if (ui.phase == Phase.PLAYING && (ui.buffering || ui.recovering)) {
            Box(Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spinner(52.dp)
                    Spacer(Modifier.height(10.dp))
                    T(if (ui.recovering) "Recuperando una parte ya borrada…" else "Descargando… ${Util.speed(ui.downBps)} · ${ui.peers} usuarios", Pt.SmallStrong)
                }
            }
        }

        if (controls && ui.phase == Phase.PLAYING) {
            Box(Modifier.fillMaxSize()) {
                ChromeScrims()
                TopInfo(
                    title = ui.title.substringBeforeLast('.'),
                    kind = kind,
                    live = false,
                    detail = "↓ ${Util.speed(ui.downBps)} · ${ui.peers} usuarios · en el aparato: ${Util.bytes(ui.diskBytes)}"
                )
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Pd.SafeX, vertical = Pd.SafeY)) {
                    if (ui.audioUnsupported) {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)) {
                            Pill("Este aparato no puede reproducir el audio de este vídeo: usa «Otro reproductor»", Pc.Text, Pc.Warn.copy(alpha = 0.3f), dot = Pc.Warn)
                        }
                    }
                    SeekBar(
                        position = ui.positionMs, duration = ui.durationMs, buffered = ui.buffered,
                        onSeekBy = { graph.torrents.seekBy(it); touch++ },
                        onSeekTo = { graph.torrents.seekToFraction(it); touch++ },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                        RoundButton(R.drawable.ic_replay10, "−10 s", onClick = { seek(-1); touch++ })
                        RoundButton(if (ui.paused) MvIcons.Play else R.drawable.ic_pause, if (ui.paused) "Seguir" else "Pausa",
                            onClick = { graph.torrents.togglePause(); touch++ }, focusRequester = playFocus)
                        RoundButton(R.drawable.ic_forward10, "+10 s", onClick = { seek(1); touch++ })
                        RoundButton(MvIcons.Volume, "Audio", onClick = { panel = TPanel.AUDIO }, active = ui.audioUnsupported)
                        RoundButton(MvIcons.Subtitles, "Subtítulos", onClick = { panel = TPanel.SUBS })
                        RoundButton(MvIcons.Grid, FITS[fit].second, onClick = { fit = (fit + 1) % FITS.size; touch++ })
                        RoundButton(R.drawable.ic_open_external, "Otro reproductor", onClick = { openExternal() }, active = ui.audioUnsupported)
                        RoundButton(MvIcons.Info, "Información", onClick = { panel = TPanel.INFO })
                    }
                }
            }
        }

        toast?.let { t ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = 60.dp)) { Pill(t, Pc.Text, Pc.Surface2.copy(alpha = 0.92f)) }
        }

        when (panel) {
            TPanel.AUDIO -> TrackSheet(
                "Audio",
                ui.tracks.filter { it.type == C.TRACK_TYPE_AUDIO }.map { t ->
                    TrackChoice(t.label, if (t.supported) null else "Este aparato no puede reproducirla", t.selected, t.supported) { graph.torrents.selectTrack(t) }
                },
                "Este vídeo tiene una sola pista de audio.",
                onDismiss = { panel = null; touch++ }
            )
            TPanel.SUBS -> {
                val subs = ui.tracks.filter { it.type == C.TRACK_TYPE_TEXT }
                TrackSheet(
                    "Subtítulos",
                    if (subs.isEmpty()) emptyList() else listOf(TrackChoice("Desactivados", null, subs.none { it.selected }, true) { graph.torrents.disableSubtitles() }) +
                        subs.map { t -> TrackChoice(t.label, null, t.selected, t.supported) { graph.torrents.selectTrack(t) } },
                    "Este vídeo no trae subtítulos.",
                    onDismiss = { panel = null; touch++ }
                )
            }
            TPanel.INFO -> Sheet("Información", onDismiss = { panel = null; touch++ }) {
                val f = remember { FocusRequester() }
                InfoLine("Archivo", ui.title)
                InfoLine("Tamaño", Util.bytes(TorrentEngine.fileSize))
                InfoLine("Bajada", Util.speed(ui.downBps))
                InfoLine("Usuarios", "${ui.peers} (${ui.seeds} con el archivo entero)")
                InfoLine("Ocupa en el aparato", Util.bytes(ui.diskBytes))
                InfoLine("Borrar lo ya visto", when {
                    !TorrentEngine.spaceSaver -> "Desactivado (se guarda entero)"
                    ui.punchUnsupported -> "Este aparato no lo permite"
                    else -> "Activado · caché de ${graph.store.cacheMb} MB"
                })
                Spacer(Modifier.height(14.dp))
                ActionButton("Cerrar", onClick = { panel = null }, focusRequester = f)
                FocusOnStart(f)
            }
            null -> Unit
        }
    }
}

/** Un torrent con varios vídeos: cuál quieres ver. */
@Composable
private fun FileChooser(title: String, files: List<com.mivisor.play.torrent.TorrentPlayback.FileOption>, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Sheet("¿Qué quieres ver?", onDismiss = onCancel, subtitle = title, width = 620.dp) {
            val f = remember { FocusRequester() }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(files.size) { i ->
                    val o = files[i]
                    ChoiceRow(o.name, onClick = { onPick(o.index) }, detail = Util.bytes(o.size), icon = MvIcons.Play,
                        focusRequester = if (i == 0) f else null)
                }
            }
            FocusOnStart(f)
        }
    }
}
