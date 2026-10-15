package io.github.yomikomi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
internal data class YomiColors(
    val background: Color,
    val surfaceLow: Color,
    val surface: Color,
    val inset: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
)

private val DarkColors = YomiColors(
    background = Color(0xFF181817),
    surfaceLow = Color(0xFF1E1E1C),
    surface = Color(0xFF252522),
    inset = Color(0xFF2E2E29),
    ink = Color(0xFFEEECE5),
    muted = Color(0xFFAAA79D),
    line = Color(0xFF3B3A34),
    accent = Color(0xFFD99A7E),
    accentSoft = Color(0xFF392B24),
)

private val LocalYomiColors = staticCompositionLocalOf { DarkColors }
internal val yomiColors: YomiColors
    @Composable get() = LocalYomiColors.current

@Composable
fun YomikomiTheme(content: @Composable () -> Unit) {
    val colors = DarkColors
    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )
    CompositionLocalProvider(LocalYomiColors provides colors) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.background,
                primaryContainer = colors.accentSoft,
                onPrimaryContainer = colors.accent,
                background = colors.background,
                onBackground = colors.ink,
                surface = colors.surface,
                onSurface = colors.ink,
                surfaceVariant = colors.inset,
                onSurfaceVariant = colors.muted,
                outline = colors.muted,
                outlineVariant = colors.line,
                surfaceContainer = colors.surface,
                surfaceContainerHigh = colors.surface,
                surfaceContainerHighest = colors.inset,
                surfaceContainerLow = colors.surfaceLow,
                surfaceContainerLowest = colors.background,
                surfaceTint = Color.Transparent,
            ),
            typography = Typography(
                bodyLarge = body,
                bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                titleLarge = body.copy(fontSize = 24.sp, lineHeight = 32.sp),
                titleMedium = body.copy(fontWeight = FontWeight.Medium),
                labelLarge = body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                labelMedium = body.copy(fontSize = 12.sp, lineHeight = 16.sp),
            ),
            content = content,
        )
    }
}
