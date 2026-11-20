package com.mivisor.play.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.tv.ui.design.MvFonts

/**
 * Sistema de diseño de MiVisor Play: «cine de noche».
 *  - Fondo casi negro azulado, superficies que suben de tono con la interacción.
 *  - Un acento violeta para la acción principal y verde menta para lo que va bien (en directo, conectado).
 *  - Tipografía Inter, nada por debajo de 12 sp (legible a 3 m en la tele).
 * Lienzo de la tele: 1024 dp de ancho (la densidad la fija [com.mivisor.tv.model.DeviceClass.density]).
 */
object Pc {
    val Bg = Color(0xFF090B11)
    val Bg2 = Color(0xFF0E1119)
    val Surface = Color(0xFF151925)
    val Surface2 = Color(0xFF1C2131)
    val SurfaceFocus = Color(0xFF2A3150)
    val Stroke = Color(0xFF283049)
    val Text = Color(0xFFF4F5FA)
    val Text2 = Color(0xFFADB4C7)
    val Text3 = Color(0xFF737C93)
    val Accent = Color(0xFF8C7CFF)
    val AccentFocus = Color(0xFFA699FF)
    val AccentDeep = Color(0xFF5B48E8)
    val Mint = Color(0xFF4FE3C1)
    val Live = Color(0xFFFF5C6C)
    val Warn = Color(0xFFFFB547)
    val Ring = Color(0xFFFFFFFF)
    val Scrim = Color(0xD9090B11)

    val BrandBrush = Brush.linearGradient(listOf(Color(0xFF8C7CFF), Color(0xFF4FC9E3)))

    /** Tintes de las tarjetas: cada emisión tiene siempre el mismo (sale de su ID). */
    private val Plates = listOf(
        Color(0xFF3B2F8F) to Color(0xFF1B6E8C),
        Color(0xFF7A2E5C) to Color(0xFF3B2F8F),
        Color(0xFF0F6B5C) to Color(0xFF1E3F8A),
        Color(0xFF8A4A1E) to Color(0xFF7A2E5C),
        Color(0xFF1E4F8A) to Color(0xFF0F6B5C),
        Color(0xFF5C2E8A) to Color(0xFF8A2E3F)
    )
    fun plate(id: String): Brush {
        val (a, b) = Plates[(id.hashCode() and 0x7fffffff) % Plates.size]
        return Brush.linearGradient(listOf(a, b))
    }
}

object Pt {
    private fun s(size: Int, weight: FontWeight, line: Int, color: Color = Pc.Text, spacing: Float = 0f) =
        TextStyle(fontFamily = MvFonts.Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, color = color, letterSpacing = spacing.sp)

    val Hero = s(34, FontWeight.Bold, 40, spacing = -0.5f)
    val Title = s(24, FontWeight.Bold, 30)
    val Heading = s(18, FontWeight.SemiBold, 24)
    val Body = s(15, FontWeight.Normal, 21, Pc.Text2)
    val BodyStrong = s(15, FontWeight.SemiBold, 21)
    val Small = s(13, FontWeight.Normal, 18, Pc.Text2)
    val SmallStrong = s(13, FontWeight.SemiBold, 18)
    val Caption = s(12, FontWeight.Medium, 16, Pc.Text3)
    val Eyebrow = s(12, FontWeight.Bold, 16, Pc.Accent, spacing = 1.4f)
    val Button = s(14, FontWeight.SemiBold, 18)
    val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = Pc.Text2)
}

object Pd {
    val SafeX = 48.dp
    val SafeY = 28.dp
    val Gap = 16.dp
    val Radius = 14.dp
    val RadiusLarge = 20.dp
    val Ring = 3.dp
}
