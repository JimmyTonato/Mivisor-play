package com.mivisor.play.ui

import android.graphics.Color as AColor
import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.mivisor.tv.ui.design.MvIcons

/** Formatos de imagen que se van alternando con el botón «Formato». */
val FITS = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Llenar (recorta)",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Estirar"
)

/** La imagen: el PlayerView de Media3 sin sus controles (los dibuja la app). */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player?, fit: Int, subtitleSize: String, keepOn: Boolean) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(AColor.BLACK)
                setKeepContentOnPlayerReset(false)
                resizeMode = FITS[fit].first
                this.player = player
                subtitleView?.setFractionalTextSize(
                    SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * when (subtitleSize) {
                        "grande" -> 1.3f
                        "muy-grande" -> 1.6f
                        else -> 1f
                    }
                )
            }
        },
        onRelease = { it.player = null },
        update = { view ->
            if (view.player !== player) view.player = player
            val mode = FITS[fit].first
            if (view.resizeMode != mode) view.resizeMode = mode
            view.keepScreenOn = keepOn
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Capa que recoge el mando y los toques cuando los controles están ocultos.
 *  - OK, abajo, MENÚ o INFO: muestran los controles. Play/pausa del mando: pausa.
 *  - [onLeftRight]: flechas laterales (en torrents, retroceder/avanzar).
 *  - Toque: muestra u oculta los controles. Doble toque a un lado: [onDoubleTapSide].
 */
@Composable
fun KeyCatcher(
    focus: FocusRequester,
    onShowControls: () -> Unit,
    onToggleControls: () -> Unit,
    onPlayPause: () -> Unit,
    onLeftRight: ((Int) -> Unit)? = null,
    onDoubleTapSide: ((Int) -> Unit)? = null
) {
    Box(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { e ->
                val n = e.nativeKeyEvent
                val up = n.action == AndroidKeyEvent.ACTION_UP
                when (n.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (up) onShowControls(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (up) onLeftRight?.invoke(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (up) onLeftRight?.invoke(1); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
                    AndroidKeyEvent.KEYCODE_SPACE -> { if (up) onPlayPause(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (up) onLeftRight?.invoke(1); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { if (up) onLeftRight?.invoke(-1); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(onDoubleTapSide) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = if (onDoubleTapSide == null) null else { o -> onDoubleTapSide(if (o.x < size.width / 2f) -1 else 1) }
                )
            }
    )
}

/** Pantalla de espera: qué se está haciendo, en qué paso va y qué puede hacer el usuario. */
@Composable
fun LoadingOverlay(
    title: String,
    kind: String,
    steps: List<String>,
    current: Int,
    message: String?,
    hint: String?,
    stats: String?,
    progress: Float? = null,
    actions: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Pc.Bg2, Pc.Bg))), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 620.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spinner(60.dp)
            Spacer(Modifier.height(22.dp))
            Pill(kind.uppercase(), Pc.Text2, Pc.Surface2)
            Spacer(Modifier.height(10.dp))
            T(title, Pt.Title, maxLines = 2)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { i, s ->
                    val done = i < current
                    val now = i == current
                    Row(
                        Modifier.clip(CircleShape).background(if (now) Pc.Accent.copy(alpha = 0.2f) else Pc.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (done) Pc.Mint else if (now) Pc.Accent else Pc.Stroke))
                        Spacer(Modifier.width(8.dp))
                        T(s, Pt.Caption, color = if (now) Pc.Text else if (done) Pc.Text2 else Pc.Text3, maxLines = 1)
                    }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(18.dp))
                ProgressLine(progress, Modifier.width(320.dp))
            }
            if (message != null) {
                Spacer(Modifier.height(16.dp))
                T(message, Pt.Body, color = Pc.Text)
            }
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                T(hint, Pt.Small)
            }
            if (stats != null) {
                Spacer(Modifier.height(10.dp))
                T(stats, Pt.Caption, color = Pc.Mint)
            }
            Spacer(Modifier.height(22.dp))
            actions()
        }
    }
}

/** Algo salió mal: el motivo en claro y qué hacer. */
@Composable
fun FailOverlay(title: String, message: String, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Pc.Bg), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 600.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Pc.Live.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(MvIcons.Info, 26.dp, Pc.Live)
            }
            Spacer(Modifier.height(16.dp))
            T(title, Pt.Title, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            T(message, Pt.Body)
            Spacer(Modifier.height(22.dp))
            actions()
        }
    }
}

@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier, secondary: Float? = null, height: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(modifier.height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))) {
        if (secondary != null) Box(Modifier.fillMaxHeight().fillMaxWidth(secondary.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.28f)))
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(Pc.Accent))
    }
}

/** Degradados arriba y abajo para que se lean los controles sobre cualquier imagen. */
@Composable
fun BoxScope.ChromeScrims() {
    Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter)
        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
    Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
}

/** Barra superior del reproductor. */
@Composable
fun BoxScope.TopInfo(title: String, kind: String, live: Boolean, detail: String?) {
    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = Pd.SafeX, vertical = Pd.SafeY), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live) Pill("EN DIRECTO", Color.White, Pc.Live.copy(alpha = 0.85f), dot = Color.White)
                Pill(kind.uppercase(), Pc.Text2, Color.Black.copy(alpha = 0.4f))
            }
            Spacer(Modifier.height(8.dp))
            T(title, Pt.Title, maxLines = 1)
            if (detail != null) T(detail, Pt.Small, maxLines = 1, color = Pc.Mint)
        }
    }
}

/** Una pista elegible (audio o subtítulos) para los paneles. */
data class TrackChoice(val label: String, val detail: String?, val selected: Boolean, val enabled: Boolean, val pick: () -> Unit)

@Composable
fun TrackSheet(title: String, choices: List<TrackChoice>, empty: String, onDismiss: () -> Unit) {
    Sheet(title, onDismiss = onDismiss, width = 520.dp) {
        if (choices.isEmpty()) {
            T(empty, Pt.Body)
            Spacer(Modifier.height(14.dp))
            val f = remember { FocusRequester() }
            ActionButton("Cerrar", onClick = onDismiss, focusRequester = f)
            FocusOnStart(f)
        } else {
            val f = remember { FocusRequester() }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(choices.size) { i ->
                    val c = choices[i]
                    ChoiceRow(c.label, onClick = { c.pick(); onDismiss() }, detail = c.detail, selected = c.selected, enabled = c.enabled,
                        focusRequester = if (i == choices.indexOfFirst { it.selected }.coerceAtLeast(0)) f else null)
                }
            }
            FocusOnStart(f)
        }
    }
}

/**
 * Barra de tiempo enfocable (torrents): con el mando, izquierda/derecha mueven 10 s (mantenido, 60 s); con el dedo,
 * un toque salta a ese punto.
 */
@Composable
fun SeekBar(position: Long, duration: Long, buffered: Float, onSeekBy: (Long) -> Unit, onSeekTo: (Float) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    if (n.action != AndroidKeyEvent.ACTION_DOWN) return@onKeyEvent false
                    val step = if (n.repeatCount > 3) 60_000L else 10_000L
                    when (n.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onSeekBy(-step); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onSeekBy(step); true }
                        else -> false
                    }
                }
                .focusable()
                .pointerInput(duration) { detectTapGestures { o -> if (duration > 0) onSeekTo(o.x / size.width) } },
            contentAlignment = Alignment.Center
        ) {
            val f = if (duration > 0) position.toFloat() / duration else 0f
            ProgressLine(f, Modifier.fillMaxWidth(), secondary = maxOf(buffered, f), height = if (focused) 10.dp else 6.dp)
        }
        Row(Modifier.fillMaxWidth()) {
            T(clock(position), Pt.Caption, color = if (focused) Pc.Text else Pc.Text2)
            Spacer(Modifier.weight(1f))
            if (focused) T("◀ ▶ para moverte", Pt.Caption, color = Pc.Text2)
            Spacer(Modifier.weight(1f))
            T(if (duration > 0) clock(duration) else "--:--", Pt.Caption, color = Pc.Text2)
        }
    }
}

fun clock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
