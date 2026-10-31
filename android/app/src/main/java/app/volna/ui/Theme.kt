package app.volna.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Только чёрное и белое: те же токены, что в docs/design.md. */
object Palette {
    val Bg = Color(0xFF000000)
    val Fg = Color(0xFFFFFFFF)
    val Muted = Color(0x94FFFFFF) // белый 58 %
    val Faint = Color(0x29FFFFFF) // белый 16 %
    val Hover = Color(0x12FFFFFF)
    val Veil = Color(0x9E000000) // чёрный 62 %
}

private val scheme = darkColorScheme(
    primary = Palette.Fg,
    onPrimary = Palette.Bg,
    secondary = Palette.Fg,
    onSecondary = Palette.Bg,
    background = Palette.Bg,
    onBackground = Palette.Fg,
    surface = Palette.Bg,
    onSurface = Palette.Fg,
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Bg,
    surfaceContainerLow = Palette.Bg,
    surfaceContainerHigh = Color(0xFF0B0B0B),
    outline = Palette.Faint,
    outlineVariant = Palette.Faint,
)

private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 12.sp, letterSpacing = 1.4.sp),
)

@Composable
fun VolnaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // без Surface цвет текста по умолчанию чёрный: задаём белый для всего приложения
        CompositionLocalProvider(LocalContentColor provides Palette.Fg, content = content)
    }
}
