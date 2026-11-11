package dev.ghar.watch.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import dev.ghar.watch.R

/**
 * GHAR DESIGN LANGUAGE (one system for every screen)
 *  - True black background (OLED pixels off = battery). Surfaces #1C1C1E.
 *  - Colour = metric, everywhere (ring, label, unit, chart, notification).
 *  - Numbers: bold white. Units / goals: smaller, in the metric colour.
 *  - Shapes: rounded rectangles (20 dp) = information; capsules = actions.
 *  - Type scale: Hero 40 · Title 20 · Body 16 · Caption 13. Nothing else.
 *  - Three templates only: Summary (rings), Detail (number + 24 h chart), List (cards/buttons).
 *  Font: Inter (SIL OFL 1.1).
 */
object G {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val Surface2 = Color(0xFF2C2C2E)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8E8E93)
    val Dim = Color(0xFF48484A)

    val Move = Color(0xFFFA114F)       // active kcal
    val Exercise = Color(0xFF92E82A)   // exercise minutes
    val Hours = Color(0xFF1EEAEF)      // active hours
    val Steps = Hours                  // steps live under active hours
    val Heart = Color(0xFFFF453A)
    val Sleep = Color(0xFF7D7AFF)
    val Water = Color(0xFF64D2FF)
    val Body = Color(0xFFFFD60A)
    val Accent = Color(0xFF0A84FF)
    val Warn = Color(0xFFFF9F0A)
    val Good = Color(0xFF30D158)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object GType {
    val Hero = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-1).sp, lineHeight = 42.sp)
    val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp)
    val Label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.6.sp, lineHeight = 16.sp)
}

private val GColors = Colors(
    primary = G.Accent,
    primaryVariant = G.Accent,
    secondary = G.Surface2,
    secondaryVariant = G.Surface2,
    background = G.Black,
    surface = G.Surface,
    error = G.Heart,
    onPrimary = G.Text,
    onSecondary = G.Text,
    onBackground = G.Text,
    onSurface = G.Text,
    onSurfaceVariant = G.Muted,
    onError = G.Text,
)

@Composable
fun GharTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = GColors, typography = Typography(defaultFontFamily = Inter), content = content)
}
