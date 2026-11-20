package com.mivisor.play.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mivisor.tv.ui.design.MvIcon
import com.mivisor.tv.ui.design.TvClickable
import com.mivisor.tv.ui.design.rememberLoop

// ---------------------------------------------------------------------------------------------- Básicos

@Composable
fun T(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, color: Color? = null) {
    BasicText(
        text = text,
        style = if (color != null) style.copy(color = color) else style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun Icon(@DrawableRes id: Int, size: Dp = 20.dp, tint: Color = Pc.Text, modifier: Modifier = Modifier) =
    MvIcon(id, modifier = modifier, size = size, tint = tint)

/** Marca de MiVisor Play: un cuadrado redondeado con degradado, una órbita y el triángulo de reproducir. */
@Composable
fun LogoMark(size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Pc.BrandBrush), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            drawCircle(Color.White.copy(alpha = 0.22f), radius = w * 0.34f, style = Stroke(width = w * 0.05f))
            val p = Path().apply {
                moveTo(w * 0.42f, w * 0.32f)
                lineTo(w * 0.70f, w * 0.50f)
                lineTo(w * 0.42f, w * 0.68f)
                close()
            }
            drawPath(p, Color.White)
            drawCircle(Pc.Mint, radius = w * 0.06f, center = Offset(w * 0.80f, w * 0.24f))
        }
    }
}

/** Indicador de carga que gira con el reloj de pantalla (en teles con animaciones desactivadas también se mueve). */
@Composable
fun Spinner(size: Dp = 56.dp, color: Color = Pc.Accent) {
    val t by rememberLoop(1100)
    Canvas(Modifier.size(size)) {
        val stroke = this.size.width * 0.09f
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(stroke), topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke))
        rotate(t * 360f) {
            drawArc(color, -90f, 100f, false, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke))
        }
    }
}

@Composable
fun Pill(text: String, color: Color = Pc.Text, background: Color = Pc.Surface2, dot: Color? = null) {
    Row(
        Modifier.clip(CircleShape).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        T(text, Pt.Caption, color = color, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------- Botones

/** Botón con texto (y opcional icono). Mando: foco con anillo; táctil: toque. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    height: Dp = 44.dp
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = CircleShape,
        color = if (primary) Pc.Accent else Pc.Surface2,
        focusedColor = if (primary) Pc.AccentFocus else Pc.SurfaceFocus,
        focusRequester = focusRequester,
        ringColor = Pc.Ring,
        contentAlignment = Alignment.Center
    ) { _ ->
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (primary) Color(0xFF14102E) else Pc.Text
            if (icon != null) {
                Icon(icon, 18.dp, tint)
                Spacer(Modifier.width(8.dp))
            }
            T(text, Pt.Button, color = tint, maxLines = 1)
        }
    }
}

/** Botón redondo solo con icono (barra del reproductor). [label] sale debajo cuando tiene el foco. */
@Composable
fun RoundButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
    size: Dp = 52.dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        TvClickable(
            onClick = onClick,
            modifier = Modifier.size(size),
            enabled = enabled,
            shape = CircleShape,
            color = if (active) Pc.Accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f),
            focusedColor = Color.White.copy(alpha = 0.28f),
            focusRequester = focusRequester,
            ringColor = Pc.Ring,
            contentAlignment = Alignment.Center
        ) { _ ->
            Icon(icon, size * 0.42f, if (active) Pc.AccentFocus else Pc.Text)
        }
        Spacer(Modifier.height(6.dp))
        T(label, Pt.Caption, color = Pc.Text2, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------- Entrada de texto

/**
 * Campo para pegar o escribir un enlace. En la tele: el mando lo enfoca y OK abre el teclado.
 * [onSubmit] con «Ir» del teclado.
 */
@Composable
fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(Pd.Radius)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Pt.BodyStrong,
        cursorBrush = SolidColor(Pc.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); onSubmit() }),
        modifier = modifier
            .heightIn(min = 52.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                if (n.action == AndroidKeyEvent.ACTION_UP &&
                    (n.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == AndroidKeyEvent.KEYCODE_ENTER)
                ) {
                    keyboard?.show(); true
                } else false
            }
            .clip(shape)
            .background(if (focused) Pc.SurfaceFocus else Pc.Surface2)
            .border(if (focused) Pd.Ring else 1.dp, if (focused) Pc.Ring else Pc.Stroke, shape),
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(com.mivisor.tv.ui.design.MvIcons.Link, 18.dp, Pc.Text3)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) T(placeholder, Pt.Body, color = Pc.Text3, maxLines = 1)
                    inner()
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------------------------- Paneles

/**
 * Panel encima de lo que haya (el atrás del mando o del móvil lo cierra). [focus] recibe el foco al abrirse.
 */
@Composable
fun Sheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Pc.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = width).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(Pd.RadiusLarge))
                .background(Pc.Bg2)
                .border(1.dp, Pc.Stroke, RoundedCornerShape(Pd.RadiusLarge))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(22.dp)
        ) {
            T(title, Pt.Heading)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                T(subtitle, Pt.Small)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** Fila elegible dentro de un [Sheet] (pistas, opciones). */
@Composable
fun ChoiceRow(
    title: String,
    onClick: () -> Unit,
    detail: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    focusRequester: FocusRequester? = null
) {
    TvClickable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        enabled = enabled,
        shape = RoundedCornerShape(Pd.Radius),
        color = if (selected) Pc.Accent.copy(alpha = 0.16f) else Color.Transparent,
        focusedColor = Pc.SurfaceFocus,
        focusRequester = focusRequester,
        ringColor = Pc.Ring
    ) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, 18.dp, if (selected) Pc.AccentFocus else Pc.Text2)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                T(title, Pt.BodyStrong, maxLines = 1, color = if (selected) Pc.AccentFocus else Pc.Text)
                if (detail != null) T(detail, Pt.Caption, maxLines = 2)
            }
            if (selected) Icon(com.mivisor.tv.ui.design.MvIcons.Check, 18.dp, Pc.AccentFocus)
        }
    }
}

/** Pone el foco en [requester] en cuanto se dibuja (paneles, pantallas nuevas). */
@Composable
fun FocusOnStart(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(60)
        runCatching { requester.requestFocus() }
    }
}

/** Encabezado de sección con contador opcional. */
@Composable
fun SectionTitle(text: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T(text, Pt.Heading)
        if (count != null && count > 0) Pill(count.toString(), Pc.Text2)
    }
}
