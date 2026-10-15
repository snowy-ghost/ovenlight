package com.snowyghost.ovenlight

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ovenlight's brand, as iOS's asset catalog has it: the ember accent on warm paper, and
 * the oven light. The colors are fixed, never the wallpaper's: against a blue accent the
 * light stops reading as an oven's.
 */
@Immutable
data class OvenColors(
    val ember: Color,
    val ovenInterior: Color,
    val lampCore: Color,
    val lampGlow: Color,
    val groupedBackground: Color,
    val groupedRow: Color,
    val pageBackground: Color,
    val success: Color,
)

private val lightOven = OvenColors(
    ember = Color(0xFFA8400C),
    ovenInterior = Color(0xFFFBF7F3),
    lampCore = Color(0xFFFFBB77),
    lampGlow = Color(0xFFFF9E3D),
    groupedBackground = Color(0xFFF4EBDF),
    groupedRow = Color(0xFFFFFFFF),
    pageBackground = Color(0xFFFFFFFF),
    success = Color(0xFF2E7D32),
)

private val darkOven = OvenColors(
    ember = Color(0xFFDC6C28),
    ovenInterior = Color(0xFF0D0907),
    lampCore = Color(0xFFFFE3B5),
    lampGlow = Color(0xFFFFB55C),
    groupedBackground = Color(0xFF21170F),
    groupedRow = Color(0xFF2C2119),
    pageBackground = Color(0xFF000000),
    success = Color(0xFF81C995),
)

/** The ember when the system's contrast setting is medium or high. */
private val highContrastEmber = Color(0xFF7F2E06) to Color(0xFFE27430)

// Every role is set: Compose fills any left out from its purple baseline.
private val lightScheme = lightColorScheme(
    primary = Color(0xFFA8400C), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC), onPrimaryContainer = Color(0xFF3A1000),
    inversePrimary = Color(0xFFFFB693),
    secondary = Color(0xFF765749), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2D8CA), onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF7A5A1E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA8), onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF7F3), onBackground = Color(0xFF1F1A16),
    surface = Color(0xFFFBF7F3), onSurface = Color(0xFF1F1A16),
    surfaceVariant = Color(0xFFEFE0D5), onSurfaceVariant = Color(0xFF52463D),
    surfaceTint = Color(0xFFA8400C),
    inverseSurface = Color(0xFF352F2B), inverseOnSurface = Color(0xFFF9EEE6),
    outline = Color(0xFF85746A), outlineVariant = Color(0xFFD8C2B6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF7F3), surfaceDim = Color(0xFFE3D8CF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F1EA),
    surfaceContainer = Color(0xFFF4EBDF), surfaceContainerHigh = Color(0xFFEEE4D8),
    surfaceContainerHighest = Color(0xFFE8DDD1),
    primaryFixed = Color(0xFFFFDBCC), primaryFixedDim = Color(0xFFFFB693),
    onPrimaryFixed = Color(0xFF3A1000), onPrimaryFixedVariant = Color(0xFF7A2E07),
    secondaryFixed = Color(0xFFF2D8CA), secondaryFixedDim = Color(0xFFE7BDAB),
    onSecondaryFixed = Color(0xFF2C160D), onSecondaryFixedVariant = Color(0xFF5D4034),
    tertiaryFixed = Color(0xFFFFDEA8), tertiaryFixedDim = Color(0xFFE9C16C),
    onTertiaryFixed = Color(0xFF271900), onTertiaryFixedVariant = Color(0xFF5C4200),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFFDC6C28), onPrimary = Color(0xFF2A0F02),
    primaryContainer = Color(0xFF7A2E07), onPrimaryContainer = Color(0xFFFFDBCC),
    inversePrimary = Color(0xFFA8400C),
    secondary = Color(0xFFE7BDAB), onSecondary = Color(0xFF442A1F),
    secondaryContainer = Color(0xFF5D4034), onSecondaryContainer = Color(0xFFFFDBCD),
    tertiary = Color(0xFFE9C16C), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200), onTertiaryContainer = Color(0xFFFFDEA8),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D0907), onBackground = Color(0xFFECE0D8),
    surface = Color(0xFF0D0907), onSurface = Color(0xFFECE0D8),
    surfaceVariant = Color(0xFF52443B), onSurfaceVariant = Color(0xFFD8C2B6),
    surfaceTint = Color(0xFFDC6C28),
    inverseSurface = Color(0xFFECE0D8), inverseOnSurface = Color(0xFF352F2B),
    outline = Color(0xFFA08D81), outlineVariant = Color(0xFF52443B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A2F27), surfaceDim = Color(0xFF0D0907),
    surfaceContainerLowest = Color(0xFF080504), surfaceContainerLow = Color(0xFF1A120C),
    surfaceContainer = Color(0xFF21170F), surfaceContainerHigh = Color(0xFF2C2119),
    surfaceContainerHighest = Color(0xFF372B22),
    // The fixed roles keep their light tones in both modes.
    primaryFixed = Color(0xFFFFDBCC), primaryFixedDim = Color(0xFFFFB693),
    onPrimaryFixed = Color(0xFF3A1000), onPrimaryFixedVariant = Color(0xFF7A2E07),
    secondaryFixed = Color(0xFFF2D8CA), secondaryFixedDim = Color(0xFFE7BDAB),
    onSecondaryFixed = Color(0xFF2C160D), onSecondaryFixedVariant = Color(0xFF5D4034),
    tertiaryFixed = Color(0xFFFFDEA8), tertiaryFixedDim = Color(0xFFE9C16C),
    onTertiaryFixed = Color(0xFF271900), onTertiaryFixedVariant = Color(0xFF5C4200),
)

/** The Material scheme for a mode, with the high-contrast ember when asked for. */
fun ovenColorScheme(dark: Boolean, highContrast: Boolean): ColorScheme {
    val scheme = if (dark) darkScheme else lightScheme
    if (!highContrast) return scheme
    val ember = if (dark) highContrastEmber.second else highContrastEmber.first
    return scheme.copy(primary = ember, surfaceTint = ember)
}

fun ovenColors(dark: Boolean, highContrast: Boolean): OvenColors {
    val colors = if (dark) darkOven else lightOven
    if (!highContrast) return colors
    return colors.copy(ember = if (dark) highContrastEmber.second else highContrastEmber.first)
}

/** Android 14's contrast setting at medium or above, iOS's increased contrast. */
fun prefersHighContrast(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 34 && context.getSystemService(UiModeManager::class.java).contrast >= 0.5f

val LocalOvenColors = staticCompositionLocalOf { lightOven }

/** Web app icons take Apple's icon corner, since manifests draw their icons full bleed. */
val AppIconShape = RoundedCornerShape(percent = 22)

private val ovenShapes = Shapes(extraLarge = RoundedCornerShape(28.dp))

private val ovenTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    )
}

// Source Serif 4 (SIL OFL 1.1, licenses/source-serif-4.txt), the variable roman from
// google/fonts at 08dc85d, subset to Latin with its weight and optical size axes. Each preset
// takes the optical size drawn for its use. Glyphs outside the subset fall back to the
// system font.
@OptIn(ExperimentalTextApi::class)
private fun serif(opticalSize: Float) = FontFamily(
    Font(
        R.font.source_serif_4,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("opsz", opticalSize)),
    ),
)

/** The wordmark, display titles and monograms of 18 sp and up. */
val SerifDisplay = serif(36f)

/** Monograms under 18 sp. */
val SerifText = serif(12f)

/** The wordmark, at 34 sp, and display titles at 22 sp, in the serif. */
object OvenType {
    val wordmark = TextStyle(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp)
    val displayTitle = TextStyle(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
}

@Composable
fun OvenlightTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val highContrast = prefersHighContrast(LocalContext.current)
    androidx.compose.runtime.CompositionLocalProvider(LocalOvenColors provides ovenColors(dark, highContrast)) {
        MaterialTheme(colorScheme = ovenColorScheme(dark, highContrast), typography = ovenTypography, shapes = ovenShapes, content = content)
    }
}
