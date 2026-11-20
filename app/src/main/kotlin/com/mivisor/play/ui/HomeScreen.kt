package com.mivisor.play.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.mivisor.play.Graph
import com.mivisor.play.data.Kind
import com.mivisor.play.data.Saved
import com.mivisor.tv.catalog.datos.DirectStream
import com.mivisor.tv.ui.design.LocalUiForm
import com.mivisor.tv.ui.design.MvIcons

@Composable
fun HomeScreen(graph: Graph, onOpen: (Open) -> Unit, onSettings: () -> Unit) {
    val saved by graph.store.saved.collectAsState()
    val favorites = saved.filter { it.favorite }
    val recents = saved.filterNot { it.favorite }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<Saved?>(null) }
    var renaming by remember { mutableStateOf<Saved?>(null) }
    val clipboard = LocalClipboardManager.current
    val touch = LocalUiForm.current.touch
    val fieldFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = graph.importTorrent(uri)
            if (path != null) onOpen(Open(Kind.TORRENT, path)) else error = "Ese archivo no se pudo leer como .torrent."
        }
    }

    fun submit(raw: String = text) {
        val t = raw.trim()
        when {
            t.isEmpty() -> error = "Pega o escribe primero un enlace."
            Graph.isMagnet(t) -> { error = null; text = ""; onOpen(Open(Kind.MAGNET, t, Graph.magnetName(t).orEmpty())) }
            DirectStream.parse(t) != null -> { error = null; text = ""; onOpen(Open(Kind.ACE, DirectStream.parse(t)!!)) }
            else -> error = "No reconozco el enlace. Vale un ID de Ace Stream (40 caracteres), acestream://… o magnet:?…"
        }
    }

    FocusOnStart(if (saved.isEmpty()) fieldFocus else playFocus)

    Box(Modifier.fillMaxSize()) {
        // Luz de fondo de la marca, muy suave.
        Box(
            Modifier.fillMaxWidth().height(360.dp)
                .background(Brush.verticalGradient(listOf(Pc.AccentDeep.copy(alpha = 0.22f), Color.Transparent)))
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Pd.SafeX, vertical = Pd.SafeY),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            item("header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(46.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        T("MiVisor Play", Pt.Title)
                        T("Ace Stream, torrents y magnet en un solo reproductor", Pt.Small)
                    }
                    ActionButton("Ajustes", onClick = onSettings, icon = MvIcons.Settings)
                }
            }

            item("open") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Pd.RadiusLarge))
                        .background(Brush.linearGradient(listOf(Pc.Surface2, Pc.Surface)))
                        .border(1.dp, Pc.Stroke, RoundedCornerShape(Pd.RadiusLarge))
                        .padding(24.dp)
                ) {
                    T("ABRIR", Pt.Eyebrow)
                    Spacer(Modifier.height(6.dp))
                    T("¿Qué quieres ver?", Pt.Hero)
                    Spacer(Modifier.height(6.dp))
                    T("Pega un enlace de Ace Stream o un magnet, o elige un archivo .torrent.", Pt.Body)
                    Spacer(Modifier.height(18.dp))
                    LinkField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        placeholder = "acestream://…   ·   ID de 40 caracteres   ·   magnet:?xt=…",
                        onSubmit = { submit() },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = fieldFocus
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        T(error!!, Pt.Small, color = Pc.Warn)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton("Reproducir", onClick = { submit() }, icon = MvIcons.Play, primary = true,
                            focusRequester = if (saved.isEmpty()) null else playFocus)
                        ActionButton("Pegar", onClick = {
                            val clip = clipboard.getText()?.text.orEmpty()
                            if (clip.isBlank()) error = "No hay nada copiado." else { text = clip.trim(); submit(clip) }
                        }, icon = MvIcons.Edit)
                        ActionButton("Archivo .torrent", onClick = {
                            try { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }
                            catch (e: ActivityNotFoundException) { error = "Este aparato no tiene selector de archivos. Abre el .torrent desde otra app con «Abrir con… MiVisor Play»." }
                        }, icon = MvIcons.Box)
                        Spacer(Modifier.weight(1f))
                        if (!touch) T("También puedes abrir enlaces desde otras apps", Pt.Caption)
                    }
                }
            }

            if (favorites.isNotEmpty()) item("favs") {
                Shelf("Favoritos", favorites, graph, onOpen, onOptions = { options = it })
            }
            if (recents.isNotEmpty()) item("recents") {
                Shelf("Recientes", recents, graph, onOpen, onOptions = { options = it })
            }
            if (saved.isEmpty()) item("help") { HowTo() }
            item("foot") {
                T(if (touch) "Mantén pulsada una tarjeta para marcarla como favorita, renombrarla o quitarla."
                  else "Mantén OK sobre una tarjeta (o pulsa MENÚ) para marcarla como favorita, renombrarla o quitarla.", Pt.Caption)
            }
        }

        options?.let { s ->
            Sheet(graph.displayName(s.contentId), onDismiss = { options = null }, subtitle = kindLabel(s.kind)) {
                val first = remember { FocusRequester() }
                ChoiceRow("Reproducir", onClick = { options = null; onOpen(Open(s.kind, s.contentId)) }, icon = MvIcons.Play, focusRequester = first)
                ChoiceRow(if (s.favorite) "Quitar de favoritos" else "Añadir a favoritos", onClick = {
                    graph.store.setFavorite(s.contentId, !s.favorite); options = null
                }, icon = MvIcons.Star)
                ChoiceRow("Cambiar el nombre", onClick = { options = null; renaming = s }, icon = MvIcons.Edit)
                ChoiceRow("Quitar de la lista", onClick = { graph.store.remove(s.contentId); options = null }, icon = MvIcons.Delete)
                FocusOnStart(first)
            }
        }

        renaming?.let { s ->
            var name by remember(s.contentId) { mutableStateOf(graph.displayName(s.contentId)) }
            Sheet("Cambiar el nombre", onDismiss = { renaming = null }) {
                val f = remember { FocusRequester() }
                LinkField(name, { name = it }, "Nombre", onSubmit = { graph.store.rename(s.contentId, name); renaming = null },
                    modifier = Modifier.fillMaxWidth(), focusRequester = f)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Guardar", onClick = { graph.store.rename(s.contentId, name); renaming = null }, primary = true)
                    ActionButton("Cancelar", onClick = { renaming = null })
                }
                FocusOnStart(f)
            }
        }
    }
}

@Composable
private fun Shelf(title: String, list: List<Saved>, graph: Graph, onOpen: (Open) -> Unit, onOptions: (Saved) -> Unit) {
    Column {
        SectionTitle(title, list.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(list, key = { it.contentId }) { s ->
                ItemCard(s, graph.displayName(s.contentId), onClick = { onOpen(Open(s.kind, s.contentId)) }, onLong = { onOptions(s) })
            }
        }
    }
}

@Composable
private fun ItemCard(s: Saved, name: String, onClick: () -> Unit, onLong: () -> Unit) {
    com.mivisor.tv.ui.design.TvClickable(
        onClick = onClick,
        onLongClick = onLong,
        modifier = Modifier.width(228.dp).height(128.dp),
        shape = RoundedCornerShape(Pd.Radius),
        color = Pc.Surface,
        focusedColor = Pc.Surface,
        focusedScale = 1.04f,
        ringColor = Pc.Ring
    ) { focused ->
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Pd.Radius)).background(Pc.plate(s.contentId)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(kindLabel(s.kind).uppercase(), Color.White, Color.Black.copy(alpha = 0.35f), dot = if (s.kind == Kind.ACE) Pc.Live else Pc.Mint)
                Spacer(Modifier.weight(1f))
                if (s.favorite) Icon(MvIcons.Star, 16.dp, Color(0xFFFFD36B))
            }
            Spacer(Modifier.weight(1f))
            T(name, Pt.BodyStrong, maxLines = 2, color = Color.White)
            T(ago(s.lastMs), Pt.Caption, color = Color.White.copy(alpha = if (focused) 0.9f else 0.65f))
        }
    }
}

@Composable
private fun HowTo() {
    Column {
        SectionTitle("Cómo empezar")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Step("1", "Copia un enlace", "Un acestream://, un ID de 40 caracteres o un magnet:? de donde lo tengas.", Modifier.weight(1f))
            Step("2", "Pégalo arriba", "O abre el enlace o el .torrent desde otra app con «Abrir con… MiVisor Play».", Modifier.weight(1f))
            Step("3", "Disfruta", "Los torrents se ven mientras se descargan y lo ya visto se borra solo para no llenar el aparato.", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Step(n: String, title: String, text: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(Pd.Radius)).background(Pc.Surface).padding(18.dp)) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Pc.Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            T(n, Pt.SmallStrong, color = Pc.AccentFocus)
        }
        Spacer(Modifier.height(12.dp))
        T(title, Pt.BodyStrong)
        Spacer(Modifier.height(4.dp))
        T(text, Pt.Small)
    }
}

fun kindLabel(k: Kind) = when (k) {
    Kind.ACE -> "Ace Stream"
    Kind.MAGNET -> "Magnet"
    Kind.TORRENT -> "Torrent"
}

fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "Ahora mismo"
        m < 60 -> "Hace $m min"
        m < 60 * 24 -> "Hace ${m / 60} h"
        m < 60 * 24 * 30 -> "Hace ${m / (60 * 24)} días"
        else -> "Hace más de un mes"
    }
}
