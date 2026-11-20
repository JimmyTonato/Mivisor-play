package com.mivisor.play.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mivisor.play.BuildConfig
import com.mivisor.play.Graph
import com.mivisor.play.data.AppLog
import com.mivisor.play.torrent.TorrentEngine
import com.mivisor.play.torrent.Util
import com.mivisor.tv.engine.EngineDiagnostics
import com.mivisor.tv.outpace.EngineManager
import com.mivisor.tv.ui.design.MvIcons
import com.mivisor.tv.ui.design.TvClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ScreenFrame(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val back = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = Pd.SafeX, vertical = Pd.SafeY)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Volver", onClick = onBack, icon = MvIcons.Back, focusRequester = back)
            Spacer(Modifier.width(16.dp))
            T(title, Pt.Title)
        }
        Spacer(Modifier.height(18.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp), content = content)
    }
    FocusOnStart(back)
}

/** Fila de ajuste: título, explicación y su valor (o un interruptor). OK / toque la cambia. */
@Composable
private fun SettingRow(title: String, detail: String?, value: String? = null, switch: Boolean? = null, onClick: () -> Unit) {
    TvClickable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Pd.Radius),
        color = Pc.Surface,
        focusedColor = Pc.SurfaceFocus,
        ringColor = Pc.Ring
    ) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(title, Pt.BodyStrong)
                if (detail != null) T(detail, Pt.Small)
            }
            Spacer(Modifier.width(16.dp))
            if (value != null) T(value, Pt.SmallStrong, color = Pc.AccentFocus, maxLines = 1)
            if (switch != null) Switch(switch)
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier.size(width = 46.dp, height = 26.dp).clip(CircleShape).background(if (on) Pc.Accent else Pc.Stroke).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun Group(text: String) {
    T(text.uppercase(), Pt.Eyebrow, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
fun SettingsScreen(graph: Graph, onBack: () -> Unit, onLog: () -> Unit, onLicenses: () -> Unit) {
    val s = graph.store
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Contador para volver a dibujar al cambiar un ajuste (los ajustes viven en SharedPreferences).
    var rev by remember { mutableIntStateOf(0) }
    var stored by remember { mutableStateOf<Long?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rev) { stored = withContext(Dispatchers.IO) { TorrentEngine.storedBytes(context) } }

    fun cycle(list: List<Int>, current: Int) = list[(list.indexOf(current) + 1).let { if (it <= 0 || it >= list.size) 0 else it }]

    rev.let { _ ->
        ScreenFrame("Ajustes", onBack) {
            if (notice != null) item { T(notice!!, Pt.SmallStrong, color = Pc.Mint) }

            item { Group("Torrents y magnet") }
            item {
                SettingRow("Borrar lo ya visto", "Solo se guarda en el aparato una caché alrededor de lo que estás viendo: un vídeo de 7 GB no ocupa 7 GB.",
                    switch = s.spaceSaver) { s.spaceSaver = !s.spaceSaver; graph.applyTorrentSettings(); rev++ }
            }
            item {
                SettingRow("Tamaño de la caché", "Más caché permite volver más atrás sin descargar otra vez.", value = "${s.cacheMb} MB") {
                    s.cacheMb = cycle(listOf(300, 500, 1000, 2000), s.cacheMb); graph.applyTorrentSettings(); rev++
                }
            }
            item {
                SettingRow("Espera antes de empezar", "Lo que se descarga antes de que salga la imagen. Más espera, menos cortes.", value = "${s.bufferMb} MB") {
                    s.bufferMb = cycle(listOf(5, 10, 20, 40), s.bufferMb); graph.applyTorrentSettings(); rev++
                }
            }
            item {
                SettingRow("Límite de subida", "Lo que compartes con los demás mientras ves.", value = if (s.uploadKbps == 0) "Sin límite" else "${s.uploadKbps} KB/s") {
                    s.uploadKbps = cycle(listOf(0, 50, 200, 1000), s.uploadKbps); graph.applyTorrentSettings(); rev++
                }
            }
            item {
                SettingRow("Descargas guardadas", "Se borran solas al salir del reproductor; esto limpia lo que haya quedado.",
                    value = stored?.let { Util.bytes(it) } ?: "…") {
                    if (TorrentEngine.handle != null) notice = "Cierra antes el vídeo que se está viendo."
                    else { TorrentEngine.clearAllDownloads(context); notice = "Descargas borradas."; rev++ }
                }
            }

            item { Group("Ace Stream (motor Outpace)") }
            item {
                SettingRow("P2P completo", "Además de conectarte a otros, deja que otros se conecten a ti (como Ace Stream oficial). Ayuda en emisiones con poca gente. Vale al reiniciar el motor.",
                    switch = s.fullP2p) {
                    s.fullP2p = !s.fullP2p
                    EngineManager.fullP2p = s.fullP2p
                    scope.launch { EngineManager.stop() }
                    notice = "El motor se reinicia con el próximo vídeo."
                    rev++
                }
            }
            item {
                SettingRow("Volver solo al directo", "Si la imagen se queda atrás tras un corte, se pone al día sola.", switch = s.autoLive) { s.autoLive = !s.autoLive; rev++ }
            }
            item {
                SettingRow("Reiniciar el motor", "Por si una emisión se queda atascada.", value = null) {
                    scope.launch { EngineManager.stop() }; notice = "Motor parado: arrancará con el próximo vídeo."
                }
            }

            item { Group("Audio y subtítulos") }
            item {
                SettingRow("Subtítulos al empezar", "Si la emisión o el vídeo los trae.", switch = s.subtitlesByDefault) { s.subtitlesByDefault = !s.subtitlesByDefault; rev++ }
            }
            item {
                val sizes = listOf("normal", "grande", "muy-grande")
                SettingRow("Tamaño de los subtítulos", null, value = when (s.subtitleSize) { "grande" -> "Grande"; "muy-grande" -> "Muy grande"; else -> "Normal" }) {
                    s.subtitleSize = sizes[(sizes.indexOf(s.subtitleSize) + 1) % sizes.size]; rev++
                }
            }

            item { Group("Pantalla") }
            item {
                val opts = listOf("auto", "tv", "tablet", "phone")
                SettingRow("Tipo de aparato", "Cámbialo si la app se ve muy grande o muy pequeña. Vale al volver a abrir la app.",
                    value = when (s.deviceOverride) { "tv" -> "Tele"; "tablet" -> "Tablet"; "phone" -> "Móvil"; else -> "Automático" }) {
                    s.deviceOverride = opts[(opts.indexOf(s.deviceOverride) + 1) % opts.size]; rev++
                }
            }

            item { Group("Datos y diagnóstico") }
            item {
                SettingRow("Borrar recientes", "Los favoritos se quedan.", value = null) { s.clearRecents(); notice = "Recientes borrados."; rev++ }
            }
            item {
                SettingRow("Registro detallado del motor", "Más información en el registro para averiguar fallos.", switch = s.verboseLog) {
                    s.verboseLog = !s.verboseLog; EngineDiagnostics.enabled = s.verboseLog; rev++
                }
            }
            item { SettingRow("Ver el registro", "Lo que ha pasado en la app y en los motores. Se puede copiar.", value = null, onClick = onLog) }

            item { Group("Acerca de") }
            item {
                SettingRow("MiVisor Play ${BuildConfig.VERSION_NAME}", "Outpace ${BuildConfig.OUTPACE_COMMIT.take(10)} · libtorrent · Media3", value = null) { }
            }
            item { SettingRow("Licencias", "Outpace (AGPL-3.0), Inter y las demás piezas de la app.", value = null, onClick = onLicenses) }
        }
    }
}

@Composable
fun LogScreen(graph: Graph, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · ${BuildConfig.VERSION_NAME}"
    val lines = remember(rev) { AppLog.snapshot().map { "app  $it" } + EngineDiagnostics.snapshot().map { "ace  $it" } }
    ScreenFrame("Registro", onBack) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(if (copied) "Copiado" else "Copiar todo", onClick = {
                    clipboard.setText(AnnotatedString(AppLog.report(device))); copied = true
                }, icon = MvIcons.Check, primary = true)
                ActionButton("Actualizar", onClick = { rev++ }, icon = MvIcons.Refresh)
                ActionButton("Vaciar", onClick = { AppLog.clear(); EngineDiagnostics.clear(); rev++ }, icon = MvIcons.Delete)
            }
        }
        item { T(device, Pt.Caption) }
        if (lines.isEmpty()) item { T("Todavía no hay nada en el registro.", Pt.Body) }
        items(lines.size) { i ->
            // Cada línea es enfocable para poder recorrer el registro con el mando.
            TvClickable(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = Color.Transparent,
                focusedColor = Pc.Surface2, focusedScale = 1f, ringColor = Pc.Stroke) { _ ->
                T(lines[lines.size - 1 - i], Pt.Mono, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val texts = remember {
        runCatching {
            context.assets.list("licenses").orEmpty().sorted().map { f ->
                f to context.assets.open("licenses/$f").bufferedReader().use { it.readText() }
            }
        }.getOrDefault(emptyList())
    }
    ScreenFrame("Licencias", onBack) {
        item {
            T("MiVisor Play usa Outpace, un motor libre bajo la licencia AGPL-3.0. Su código y los cambios de MiVisor están en el repositorio del proyecto (tools/outpace-patches).", Pt.Body)
        }
        texts.forEach { (name, body) ->
            item { T(name, Pt.Heading, modifier = Modifier.padding(top = 16.dp)) }
            // Por párrafos: cada bloque se puede enfocar y la lista avanza con el mando.
            val parts = body.split("\n\n").filter { it.isNotBlank() }
            items(parts.size) { i ->
                TvClickable(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = Color.Transparent,
                    focusedColor = Pc.Surface, focusedScale = 1f, ringColor = Pc.Stroke) { _ ->
                    T(parts[i].trim(), Pt.Mono, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
