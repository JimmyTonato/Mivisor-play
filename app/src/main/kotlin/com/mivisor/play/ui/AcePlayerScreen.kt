package com.mivisor.play.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.mivisor.play.Graph
import com.mivisor.tv.catalog.datos.DirectStream
import com.mivisor.tv.engine.EngineDiagnostics
import com.mivisor.tv.playback.PlaybackController.LoadStage
import com.mivisor.tv.playback.PlaybackController.Phase
import com.mivisor.tv.ui.design.MvIcons
import kotlinx.coroutines.delay

private enum class AcePanel { AUDIO, SUBS, INFO }

/** Emisión de Ace Stream: Content ID → Outpace → HLS → Media3 (la reproducción de MiVisor TV). */
@Composable
fun AcePlayerScreen(graph: Graph, contentId: String, onClose: () -> Unit, onLog: () -> Unit) {
    val ui by graph.player.ui.collectAsState()
    val live by graph.player.live.collectAsState()
    var controls by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<AcePanel?>(null) }
    var fit by remember { mutableIntStateOf(0) }
    val catcher = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val channelId = DirectStream.channelId(contentId)
    val name = graph.displayName(contentId)

    LaunchedEffect(contentId) {
        if (graph.player.ui.value.channelId != channelId || !graph.player.ui.value.active) graph.player.play(graph.request(contentId))
    }
    DisposableEffect(contentId) { onDispose { graph.player.stop() } }

    // El nombre de la emisión llega en su descriptor: se apunta en cuanto el motor lo tiene (si no le pusiste uno).
    LaunchedEffect(contentId, ui.stage, ui.phase) {
        val resolved = ui.stage == LoadStage.PEERS || ui.stage == LoadStage.BUFFERING || ui.phase == Phase.PLAYING
        if (!resolved) return@LaunchedEffect
        DirectStream.titleOfDescriptorLine(EngineDiagnostics.vias.descriptor)?.let { graph.store.autoName(contentId, it) }
    }

    // Los controles se esconden solos a los 5 s sin tocar nada.
    LaunchedEffect(controls, touch, panel) {
        if (controls && panel == null) {
            delay(5_000)
            controls = false
        }
    }
    LaunchedEffect(controls, ui.phase) {
        delay(80)
        // Solo con imagen: en la carga o en un error el foco es de sus botones.
        if (ui.phase == Phase.PLAYING) runCatching { if (controls) playFocus.requestFocus() else catcher.requestFocus() }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            controls -> controls = false
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize()) {
        VideoSurface(graph.player.player, fit, graph.store.subtitleSize, keepOn = ui.phase != Phase.FAILED)
        KeyCatcher(
            focus = catcher,
            onShowControls = { if (ui.phase == Phase.PLAYING) { controls = true; touch++ } },
            onToggleControls = { if (ui.phase == Phase.PLAYING) { controls = !controls; touch++ } },
            onPlayPause = { if (ui.phase == Phase.PLAYING) graph.player.togglePause() }
        )

        when (ui.phase) {
            Phase.FAILED -> FailOverlay(
                title = "No se pudo ver «$name»",
                message = failText(ui.cause, ui.message)
            ) {
                val f = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Reintentar", onClick = { graph.player.retry() }, icon = MvIcons.Refresh, primary = true, focusRequester = f)
                    ActionButton("Ver registro", onClick = onLog, icon = MvIcons.List)
                    ActionButton("Volver", onClick = onClose, icon = MvIcons.Back)
                }
                FocusOnStart(f, ui.phase)
            }
            Phase.PLAYING -> Unit
            else -> {
                val step = when {
                    ui.phase == Phase.IDLE || ui.phase == Phase.STARTING_ENGINE -> 0
                    ui.stage == LoadStage.RESOLVING || ui.stage == LoadStage.NONE -> 1
                    ui.stage == LoadStage.PEERS -> 2
                    else -> 3
                }
                LoadingOverlay(
                    title = name,
                    kind = "Ace Stream",
                    steps = listOf("Motor", "Buscar la emisión", "Encontrar personas", "Preparar imagen"),
                    current = step,
                    message = ui.message,
                    hint = ui.insight?.hint ?: stageHint(ui.stage, ui.stageSecs),
                    stats = if (ui.peers > 0) "${ui.peers} personas conectadas" else null
                ) {
                    val f = remember { FocusRequester() }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton("Cancelar", onClick = onClose, icon = MvIcons.Close, focusRequester = f)
                        ActionButton("Ver registro", onClick = onLog, icon = MvIcons.List)
                    }
                    FocusOnStart(f)
                }
            }
        }

        // Recuperando un corte mientras se veía.
        if (ui.phase == Phase.PLAYING && ui.recovering != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = Pd.SafeY)) {
                Pill("Recuperando la imagen… ${ui.recovering}", Pc.Text, Pc.Surface2.copy(alpha = 0.92f), dot = Pc.Warn)
            }
        }

        if (controls && ui.phase == Phase.PLAYING) {
            Box(Modifier.fillMaxSize()) {
                ChromeScrims()
                TopInfo(
                    title = name,
                    kind = "Ace Stream",
                    live = !live.behind,
                    detail = buildString {
                        append("${ui.peers} personas")
                        live.delayMs?.let { append(" · ${it / 1000} s por detrás del directo") }
                    }
                )
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Pd.SafeX, vertical = Pd.SafeY)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
                        RoundButton(if (ui.paused) MvIcons.Play else com.mivisor.play.R.drawable.ic_pause, if (ui.paused) "Seguir" else "Pausa",
                            onClick = { graph.player.togglePause(); touch++ }, focusRequester = playFocus)
                        RoundButton(MvIcons.Live, "Ir al directo", onClick = { graph.player.goLive(); touch++ }, enabled = live.behind)
                        if (live.canJump15) RoundButton(MvIcons.ChevronRight, "+15 s", onClick = { graph.player.jumpAhead(); touch++ })
                        RoundButton(MvIcons.Volume, "Audio", onClick = { panel = AcePanel.AUDIO })
                        RoundButton(MvIcons.Subtitles, "Subtítulos", onClick = { panel = AcePanel.SUBS })
                        RoundButton(MvIcons.Grid, FITS[fit].second, onClick = { fit = (fit + 1) % FITS.size; touch++ })
                        RoundButton(MvIcons.Info, "Información", onClick = { panel = AcePanel.INFO })
                        RoundButton(MvIcons.Close, "Salir", onClick = onClose)
                    }
                }
            }
        }

        when (panel) {
            AcePanel.AUDIO -> TrackSheet(
                "Audio",
                ui.tracks.filter { it.type == C.TRACK_TYPE_AUDIO }.map { t ->
                    TrackChoice(t.label, t.detail, t.selected, t.supported) { graph.player.selectTrack(t) }
                },
                "Esta emisión tiene una sola pista de audio.",
                onDismiss = { panel = null; touch++ }
            )
            AcePanel.SUBS -> {
                val subs = ui.tracks.filter { it.type == C.TRACK_TYPE_TEXT }
                TrackSheet(
                    "Subtítulos",
                    if (subs.isEmpty()) emptyList() else listOf(TrackChoice("Desactivados", null, subs.none { it.selected }, true) { graph.player.disableSubtitles() }) +
                        subs.map { t -> TrackChoice(t.label, t.detail, t.selected, t.supported) { graph.player.selectTrack(t) } },
                    "Esta emisión no trae subtítulos.",
                    onDismiss = { panel = null; touch++ }
                )
            }
            AcePanel.INFO -> Sheet("Información", onDismiss = { panel = null; touch++ }) {
                val f = remember { FocusRequester() }
                InfoLine("ID", contentId)
                InfoLine("Personas conectadas", ui.peers.toString())
                ui.startMs?.let { InfoLine("Tardó en empezar", "${it / 1000.0} s") }
                live.delayMs?.let { InfoLine("Retraso con el directo", "${it / 1000} s") }
                InfoLine("Entrega", if (ui.direct) "Vídeo directo del motor (plan B)" else "HLS")
                Spacer(Modifier.height(14.dp))
                ActionButton("Cerrar", onClick = { panel = null }, focusRequester = f)
                FocusOnStart(f)
            }
            null -> Unit
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        T(label, Pt.Small, modifier = Modifier.weight(0.45f))
        T(value, Pt.SmallStrong, modifier = Modifier.weight(0.55f), maxLines = 2)
    }
}

private fun stageHint(stage: LoadStage, secs: Int): String? = when (stage) {
    LoadStage.RESOLVING -> if (secs > 10) "Buscar la emisión en el catálogo de Ace Stream puede tardar; no quiere decir que no vaya." else null
    LoadStage.PEERS -> if (secs > 15) "Si nadie la está emitiendo ahora, puede que la emisión esté parada." else null
    LoadStage.BUFFERING -> "Ya llegan datos: preparando la primera imagen."
    LoadStage.NONE -> null
}

private fun failText(cause: String?, message: String?): String = when (cause) {
    "offline" -> "El aparato no tiene Internet ahora mismo. Comprueba la conexión y pulsa Reintentar."
    "engine" -> message ?: "El motor Outpace no responde. Pulsa Reintentar."
    else -> message ?: "La emisión no respondió. Puede que esté parada o que nadie la esté compartiendo ahora."
}
