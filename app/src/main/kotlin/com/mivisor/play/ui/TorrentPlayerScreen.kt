package com.mivisor.play.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mivisor.play.Graph
import com.mivisor.play.R
import com.mivisor.play.data.Kind
import com.mivisor.play.torrent.TorrentPlayback
import com.mivisor.play.torrent.TorrentPlayback.Phase
import com.mivisor.tv.ui.design.MvIcons
import kotlinx.coroutines.delay
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private enum class TPanel { AUDIO, SUBS, SPEED, INFO }

/** Formatos de imagen de VLC que se van alternando con el botón «Formato». */
private val VLC_FITS = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Ajustar",
    MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Llenar (recorta)",
    MediaPlayer.ScaleType.SURFACE_FILL to "Estirar",
    MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3"
)
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Torrent o magnet: TorrServer lo descarga mientras VLC lo reproduce dentro de la app. */
@Composable
fun TorrentPlayerScreen(graph: Graph, open: Open, onClose: () -> Unit, onLog: () -> Unit) {
    val ui by graph.torrents.ui.collectAsState()
    val context = LocalContext.current
    var controls by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<TPanel?>(null) }
    var fit by remember { mutableIntStateOf(0) }
    var speed by remember { mutableIntStateOf(2) }
    var toast by remember { mutableStateOf<String?>(null) }
    val catcher = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val kind = if (open.kind == Kind.MAGNET) "Magnet" else "Torrent"
    val name = graph.displayName(open.key)

    fun start() {
        if (open.kind == Kind.MAGNET) graph.torrents.openMagnet(open.key, name) else graph.torrents.openFile(open.key, name)
    }

    LaunchedEffect(open.key) { start() }
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
    LaunchedEffect(ui.phase) { if (ui.phase == Phase.PLAYING) graph.torrents.setScale(VLC_FITS[fit].first) }

    fun openExternal() {
        val url = ui.streamUrl ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", ui.title)
        try {
            graph.externalPlayback = true
            graph.torrents.pause()
            context.startActivity(Intent.createChooser(i, "Abrir con…"))
        } catch (e: Exception) {
            graph.externalPlayback = false
            toast = "No hay otro reproductor instalado"
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).also { graph.torrents.attach(it) } },
            update = { v ->
                graph.torrents.attach(v)
                v.keepScreenOn = ui.active
            },
            onRelease = { v -> graph.torrents.detach(v) },
            modifier = Modifier.fillMaxSize()
        )
        KeyCatcher(
            focus = catcher,
            onShowControls = { if (ui.phase == Phase.PLAYING) { controls = true; touch++ } },
            onToggleControls = { if (ui.phase == Phase.PLAYING) { controls = !controls; touch++ } },
            onPlayPause = { graph.torrents.togglePause() },
            onLeftRight = { d -> if (ui.phase == Phase.PLAYING) seek(d) },
            onDoubleTapSide = { d -> if (ui.phase == Phase.PLAYING) seek(d) }
        )

        when (ui.phase) {
            Phase.FAILED -> FailOverlay(if (ui.engineDied) "El motor de torrents se cerró" else "No se pudo ver «$name»", ui.message ?: "Algo falló al abrir el torrent.") {
                val f = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Reintentar", onClick = { start() }, icon = MvIcons.Refresh, primary = true, focusRequester = f)
                    if (ui.streamUrl != null && !ui.engineDied) ActionButton("Otro reproductor", onClick = { openExternal() }, icon = R.drawable.ic_open_external)
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
                steps = listOf("Motor", "Leer el torrent", "Conectar", "Preparar el vídeo"),
                current = when {
                    ui.phase == Phase.IDLE -> 0
                    ui.phase == Phase.METADATA && ui.message?.startsWith("Arrancando") == true -> 0
                    ui.phase == Phase.METADATA -> 1
                    ui.peers == 0 -> 2
                    else -> 3
                },
                message = ui.message,
                hint = when {
                    ui.phase == Phase.METADATA && open.kind == Kind.MAGNET -> "Un magnet no trae la lista de archivos: se pide a quien lo comparte."
                    ui.phase == Phase.CONNECTING && ui.totalPeers == 0 -> "Si tarda mucho, puede que ahora mismo nadie lo esté compartiendo."
                    else -> null
                },
                stats = if (ui.peers > 0 || ui.downBps > 0) "↓ ${TorrentPlayback.speed(ui.downBps)} · ${ui.peers} usuarios (${ui.seeds} con todo)" else null,
                progress = if (ui.phase == Phase.CONNECTING && ui.prepare > 0f) ui.prepare else null
            ) {
                val f = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Cancelar", onClick = onClose, icon = MvIcons.Close, focusRequester = f)
                    ActionButton("Ver registro", onClick = onLog, icon = MvIcons.List)
                }
                FocusOnStart(f)
            }
        }

        if (ui.phase == Phase.PLAYING && ui.buffering) {
            Box(Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spinner(52.dp)
                    Spacer(Modifier.height(10.dp))
                    T("Cargando ${ui.bufferPct.toInt()} % · ↓ ${TorrentPlayback.speed(ui.downBps)} · ${ui.peers} usuarios", Pt.SmallStrong)
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
                    detail = "↓ ${TorrentPlayback.speed(ui.downBps)} · ${ui.peers} usuarios · ${TorrentPlayback.bytes(ui.fileSize)}"
                )
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Pd.SafeX, vertical = Pd.SafeY)) {
                    SeekBar(
                        position = ui.positionMs, duration = ui.durationMs, buffered = 0f,
                        onSeekBy = { graph.torrents.seekBy(it); touch++ },
                        onSeekTo = { graph.torrents.seekToFraction(it); touch++ },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                        RoundButton(R.drawable.ic_replay10, "−10 s", onClick = { seek(-1); touch++ })
                        RoundButton(if (ui.paused) MvIcons.Play else R.drawable.ic_pause, if (ui.paused) "Seguir" else "Pausa",
                            onClick = { graph.torrents.togglePause(); touch++ }, focusRequester = playFocus)
                        RoundButton(R.drawable.ic_forward10, "+10 s", onClick = { seek(1); touch++ })
                        RoundButton(MvIcons.Volume, "Audio", onClick = { panel = TPanel.AUDIO })
                        RoundButton(MvIcons.Subtitles, "Subtítulos", onClick = { panel = TPanel.SUBS })
                        RoundButton(MvIcons.Clock, if (SPEEDS[speed] == 1f) "Velocidad" else "${SPEEDS[speed]}x", onClick = { panel = TPanel.SPEED })
                        RoundButton(MvIcons.Grid, VLC_FITS[fit].second, onClick = {
                            fit = (fit + 1) % VLC_FITS.size; graph.torrents.setScale(VLC_FITS[fit].first); touch++
                        })
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
                ui.audio.map { t -> TrackChoice(t.label, null, t.selected, true) { graph.torrents.selectAudio(t.id) } },
                "Este vídeo tiene una sola pista de audio.",
                onDismiss = { panel = null; touch++ }
            )
            TPanel.SUBS -> TrackSheet(
                "Subtítulos",
                ui.subs.map { t -> TrackChoice(t.label, null, t.selected, true) { graph.torrents.selectSub(t.id) } },
                "Este vídeo no trae subtítulos.",
                onDismiss = { panel = null; touch++ }
            )
            TPanel.SPEED -> TrackSheet(
                "Velocidad",
                SPEEDS.mapIndexed { i, r ->
                    TrackChoice(if (r == 1f) "Normal" else "${r}x", null, i == speed, true) { speed = i; graph.torrents.setRate(r) }
                },
                "",
                onDismiss = { panel = null; touch++ }
            )
            TPanel.INFO -> Sheet("Información", onDismiss = { panel = null; touch++ }) {
                val f = remember { FocusRequester() }
                InfoLine("Archivo", ui.title)
                InfoLine("Tamaño", TorrentPlayback.bytes(ui.fileSize))
                InfoLine("Bajada", TorrentPlayback.speed(ui.downBps))
                InfoLine("Usuarios", "${ui.peers} conectados de ${ui.totalPeers} (${ui.seeds} con el archivo entero)")
                InfoLine("Caché", "${graph.store.cacheMb} MB en ${if (graph.store.cacheOnDisk) "el disco" else "memoria"}: lo ya visto se suelta solo")
                InfoLine("Motor", "TorrServer · reproductor VLC")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Cerrar", onClick = { panel = null }, focusRequester = f)
                    ActionButton("Otro reproductor", onClick = { panel = null; openExternal() }, icon = R.drawable.ic_open_external)
                }
                FocusOnStart(f)
            }
            null -> Unit
        }
    }
}

/** Un torrent con varios vídeos: cuál quieres ver. */
@Composable
private fun FileChooser(title: String, files: List<TorrentPlayback.FileOption>, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Pc.Bg), contentAlignment = Alignment.Center) {
        Sheet("¿Qué quieres ver?", onDismiss = onCancel, subtitle = title, width = 620.dp) {
            val f = remember { FocusRequester() }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(files.size) { i ->
                    val o = files[i]
                    ChoiceRow(o.name, onClick = { onPick(o.id) }, detail = TorrentPlayback.bytes(o.size), icon = MvIcons.Play,
                        focusRequester = if (i == 0) f else null)
                }
            }
            FocusOnStart(f)
        }
    }
}
