package com.snowyghost.ovenlight

import android.app.UiModeManager
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import kotlin.math.min
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
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

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

internal val lightOven = OvenColors(
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

/** The screen edge the light pours from, and where on it, in the window's physical pixels. */
data class LightSource(val edge: Edge, val x: Float, val y: Float) {
    enum class Edge { TOP, LEFT, RIGHT }

    /** A camera cutout's bounds, in the window's physical pixels. */
    data class Cutout(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    companion object {
        /**
         * The camera is the lamp: the light leaves a cutout centered on the top edge, or on a
         * side edge of a phone on its side. A corner hole would light one side of the launcher
         * and leave the other dim, so then, and with no cutout, it leaves the top center, half
         * the status bar down. The rects are physical, so right to left can't move the light.
         */
        fun of(width: Float, height: Float, top: Cutout?, left: Cutout?, right: Cutout?, statusBarHeight: Float): LightSource {
            fun middleThird(v: Float, length: Float) = v > length / 3 && v < length * 2 / 3
            top?.takeIf { middleThird(it.centerX, width) }?.let { return LightSource(Edge.TOP, it.centerX, it.centerY) }
            left?.takeIf { middleThird(it.centerY, height) }?.let { return LightSource(Edge.LEFT, it.centerX, it.centerY) }
            right?.takeIf { middleThird(it.centerY, height) }?.let { return LightSource(Edge.RIGHT, it.centerX, it.centerY) }
            return LightSource(Edge.TOP, width / 2, statusBarHeight / 2)
        }
    }
}

/** When the lamp came on, once per process, so the lock and the launcher share one warm-up. */
private object LampWarmUp {
    const val DURATION_MS = 900L
    var litAt: Long? = null

    fun progress(now: Long): Float = litAt?.let { ((now - it).toFloat() / DURATION_MS).coerceIn(0f, 1f) } ?: 0f
}

/**
 * The oven light: a cone of warm light falls from the camera across the screen, with a soft
 * halo where it leaves. The lock screen and the launcher are the same room lit to different
 * levels: 0.35 at rest on the lock, 1 while unlocking, 0.6 on the launcher.
 * It ignores insets, sits behind everything and takes no touches. Nothing is drawn for the
 * camera itself.
 */
@Composable
fun LampLight(level: Float, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    // Read so a rotation or a fold, which change the cutout, draw the light again.
    WindowInsets.displayCutout.getTop(density)
    val statusBar = WindowInsets.statusBars.getTop(density).toFloat()
    // Android says which edge each cutout is on; an empty rect means none there.
    val cutout = view.rootWindowInsets?.displayCutout
    fun Rect?.asCutout() = this?.takeUnless { it.isEmpty }?.let { LightSource.Cutout(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    val top = cutout?.boundingRectTop.asCutout()
    val left = cutout?.boundingRectLeft.asCutout()
    val right = cutout?.boundingRectRight.asCutout()
    val dark = isSystemInDarkTheme()
    val oven = LocalOvenColors.current
    val lit by animateFloatAsState(level / 0.6f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "lamp")
    val warm = remember { Animatable(LampWarmUp.progress(SystemClock.uptimeMillis())) }
    LaunchedEffect(Unit) {
        val now = SystemClock.uptimeMillis()
        if (LampWarmUp.litAt == null) LampWarmUp.litAt = now
        val remaining = ((1 - LampWarmUp.progress(now)) * LampWarmUp.DURATION_MS).toInt()
        if (remaining > 0) warm.animateTo(1f, tween(remaining, easing = LinearEasing)) else warm.snapTo(1f)
    }
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { alpha = warm.value }.clearAndSetSemantics {}) {
        val source = LightSource.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), top, left, right, statusBar)
        val sideways = source.edge != LightSource.Edge.TOP
        Canvas(Modifier.fillMaxSize()) {
            turned(source) { originX, originY, across, along ->
                val start = (if (sideways) 132.dp else 96.dp).toPx()
                val spread = across * (if (sideways) 0.30f else 0.22f)
                val beam = Brush.linearGradient(
                    0f to oven.lampCore.copy(alpha = min((if (dark) 0.62f else 0.8f) * lit, 1f)),
                    0.48f to oven.lampGlow.copy(alpha = ((if (dark) 0.30f else 0.34f) * lit).coerceIn(0f, 1f)),
                    1f to oven.lampGlow.copy(alpha = 0f),
                    start = Offset(originX, originY), end = Offset(originX, along),
                )
                // The cone's sides and top are soft, as a blur of this sigma would leave them,
                // but drawn as a gradient across each edge, which is dithered: a blur's layer has
                // 8 bits a channel and no dithering, so the faint light would fall in bands. The
                // light has faded out by the bottom. Walking each edge, the light is on the left.
                val soft = (if (sideways) 34.dp else 15.dp).toPx()
                val edges = listOf(
                    Offset(across + spread, along), Offset(originX + start / 2, originY),
                    Offset(originX - start / 2, originY), Offset(-spread, along),
                ).zipWithNext()
                val light = edges.fold(beam) { light, (from, to) ->
                    val inward = (to - from).let { Offset(it.y, -it.x) * (2.5f * soft / it.getDistance()) }
                    Brush.composite(light, Brush.linearGradient(*SoftEdge, start = from - inward, end = from + inward), BlendMode.DstIn)
                }
                drawRect(light, size = Size(across, along))
                // A soft halo where it leaves.
                val halo = 150.dp.toPx()
                drawCircle(
                    Brush.radialGradient(
                        0f to oven.lampCore.copy(alpha = ((if (dark) 0.55f else 0.42f) * lit).coerceIn(0f, 1f)),
                        0.4f to oven.lampGlow.copy(alpha = (0.18f * lit).coerceIn(0f, 1f)),
                        1f to oven.lampGlow.copy(alpha = 0f),
                        center = Offset(originX, originY), radius = halo,
                    ),
                    radius = halo, center = Offset(originX, originY),
                )
            }
        }
    }
}

/**
 * How a blur fades an edge, from transparent 2.5 sigmas outside it to opaque 2.5 inside: the
 * integral of a gaussian.
 */
private val SoftEdge = arrayOf(
    0f to Color.Transparent, 0.2f to Color.Black.copy(alpha = 0.067f), 0.35f to Color.Black.copy(alpha = 0.227f),
    0.5f to Color.Black.copy(alpha = 0.5f), 0.65f to Color.Black.copy(alpha = 0.773f), 0.8f to Color.Black.copy(alpha = 0.933f),
    1f to Color.Black,
)

/**
 * Draws with the canvas turned so the light runs down its own y axis, passing where it
 * leaves and the screen's size across and along the light.
 */
private inline fun DrawScope.turned(source: LightSource, draw: DrawScope.(Float, Float, Float, Float) -> Unit) {
    val sideways = source.edge != LightSource.Edge.TOP
    val across = if (sideways) size.height else size.width
    val along = if (sideways) size.width else size.height
    val (originX, originY) = when (source.edge) {
        LightSource.Edge.TOP -> source.x to source.y
        LightSource.Edge.LEFT -> (size.height - source.y) to source.x
        LightSource.Edge.RIGHT -> source.y to (size.width - source.x)
    }
    withTransform({
        when (source.edge) {
            LightSource.Edge.TOP -> Unit
            LightSource.Edge.LEFT -> { translate(0f, size.height); rotate(-90f, Offset.Zero) }
            LightSource.Edge.RIGHT -> { translate(size.width, 0f); rotate(90f, Offset.Zero) }
        }
    }) { draw(originX, originY, across, along) }
}

/** The oven rack: a thin ember line the light falls on, under the wordmark and the welcome. */
@Composable
fun OvenRack(width: Dp, modifier: Modifier = Modifier) {
    val oven = LocalOvenColors.current
    val alpha = if (isSystemInDarkTheme()) 0.6f else 0.45f
    Box(modifier.size(width, 3.dp).clip(CircleShape).background(oven.ember.copy(alpha = alpha)).clearAndSetSemantics {})
}

/** Whether an app's computer is reachable, for its launcher tile. */
enum class AppReachability {
    Offline,
    ;

    val label: String get() = "Offline"

    companion object {
        /**
         * Tiles only speak up when something is wrong: an app reached through Ovenlight's node
         * whose computer didn't answer the last probe, or wasn't online to be probed. Nothing
         * before the node is ready (the launcher shows that) or before the first probe.
         */
        fun forTile(host: String, nodeState: NodeState, answers: Map<String, Boolean>?): AppReachability? {
            if (nodeState != NodeState.Ready || answers == null) return null
            return if (answers[Discovery.normalizedHost(host)] == true) null else Offline
        }
    }
}

/**
 * A full-screen state centered in the safe area, that scrolls only when the largest text sizes
 * don't fit. [modifier] reaches behind the bars, for a background.
 */
@Composable
fun CenteredScroll(modifier: Modifier = Modifier, contentPadding: Dp = 0.dp, spacing: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterVertically),
            content = content,
        )
    }
}

/** The window of the dialog this composable is in, if it's in one. */
fun View.dialogWindow(): Window? = generateSequence(parent) { it.parent }.firstNotNullOfOrNull { (it as? DialogWindowProvider)?.window }

/**
 * For a full-screen dialog that draws behind the bars (`decorFitsSystemWindows = false`): its
 * window sets the bars' icons for the theme, as the activity's does, or light mode would get
 * white icons on cream.
 */
@Composable
fun FullScreenDialogBars() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(view, dark) {
        view.dialogWindow()?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose {}
    }
}

/**
 * A field's warning, in its supporting text: below the field, away from TalkBack's focus, so it's
 * announced as it appears.
 */
@Composable
fun FieldWarning(message: String) {
    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(message)
    }
}
