package com.snowyghost.ovenlight

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBarIgnoringVisibility
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.view.drawToBitmap
import java.net.URI
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** What the chrome can do for the open app. Null leaves an item out of the menu. */
data class AppChromeActions(
    val home: () -> Unit = {},
    val reload: () -> Unit = {},
    /** The owner's own shareable apps only: invite someone to this app. */
    val share: (() -> Unit)? = null,
    /** Apps served by an Ovenlight connector, once loaded: a note and a screenshot to whoever runs it. */
    val sendFeedback: (() -> Unit)? = null,
    /** Every app: an email to the team behind Ovenlight. */
    val reportProblem: () -> Unit = {},
    /** Apps someone shared: leave all of that person's apps. */
    val leave: Leave? = null,
    val lock: () -> Unit = {},
) {
    data class Leave(val owner: String, val action: () -> Unit)
}

/**
 * Whether the chrome shows, kept outside the view so tests can drive it. An app opens with the
 * pill tucked away, except while its one-time tip is due: then it shows with the tip until the
 * tip is closed, and tucks away as the page appears, or at once if it already has. Main thread
 * only.
 */
@Stable
class ChromeController(private val store: KeyValueStore) {
    var isShown by mutableStateOf(false)
        private set
    var tipShowing by mutableStateOf(!store.getBoolean(TIP_CLOSED_KEY))
        private set

    /** Hides on its own once the app loads, until the person takes charge of the chrome. */
    var autoHides by mutableStateOf(true)
        private set

    /** Off for the frame a feedback snapshot takes, so the page is all it shows. */
    var hiddenForSnapshot by mutableStateOf(false)

    init {
        // While the tip is due, the chrome shows, so the tip can point at it.
        if (tipShowing) isShown = true
    }

    /**
     * The handle, or the page pulled down at its top. No haptic either way: design.md keeps them
     * for actions, not for revealing the pill.
     */
    fun reveal(fromHandle: Boolean = true) {
        isShown = true
        if (fromHandle) closeTip()
        autoHides = false
    }

    fun hide() {
        if (!isShown || tipShowing) return
        isShown = false
        autoHides = false
    }

    /** The person used the pill: it stays until they scroll or touch the page. */
    fun used() {
        autoHides = false
    }

    /** The tip was closed, or the handle it teaches was used: it never shows again. */
    fun closeTip() {
        if (!tipShowing) return
        tipShowing = false
        store.putBoolean(TIP_CLOSED_KEY, true)
    }

    companion object {
        const val TIP_CLOSED_KEY = "chromeTipClosed"
    }
}

/**
 * Turns the page's scrolling and touches into the chrome following them, without taking any
 * (iOS's WebViewObserver): scrolling down 12 dp or tapping the page hides it, pulling down 40 dp
 * past the top of the page brings it back, and scrolling up leaves it as it is. Only the
 * person's own scrolling counts: while a finger is down, and during the fling a drag ends with.
 * Distances are in pixels, times in milliseconds. WebView has no rubber band: a pull past the
 * top is a downward drag that WebView reports as overscrolling the top, which it does only for
 * movement the page left to it, so a map, a canvas or an inner scroller keeps its own drags, as
 * on iOS.
 */
class ChromeGestures(
    density: Float,
    private val touchSlop: Float,
    private val onPull: () -> Unit,
    private val onScrollDown: () -> Unit,
    private val onTap: () -> Unit,
) {
    private val downThreshold = 12 * density
    private val pullThreshold = 40 * density
    private var travel = 0f
    private var touching = false
    private var dragged = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var pullFrom: Float? = null
    private var pulled = false
    private var releasedAt: Long? = null

    fun touched(action: Int, x: Float, y: Float, time: Long) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                dragged = false
                pulled = false
                downX = x
                downY = y
                lastY = y
                pullFrom = null
                travel = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                lastY = y
                if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) dragged = true
                val from = pullFrom ?: return
                if (!pulled && y - from > pullThreshold) {
                    pulled = true
                    travel = 0f
                    onPull()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                pullFrom = null
                releasedAt = time
                if (action == MotionEvent.ACTION_UP && !dragged) onTap()
            }
        }
    }

    /** WebView overscrolled the top of the page: the pull counts from where the finger is now. */
    fun overscrolledTop() {
        if (touching && pullFrom == null) pullFrom = lastY
    }

    fun scrolled(old: Int, new: Int, time: Long) {
        // Off the top again, a pull starts over.
        if (new > 0) pullFrom = null
        val flinging = dragged && releasedAt?.let { time - it < FLING_MILLIS } == true
        if (!touching && !flinging) {
            travel = 0f
            return
        }
        if (new <= 0) return
        val delta = (new - old).toFloat()
        // Scrolling up starts the count again.
        travel = if (delta > 0) travel + delta else 0f
        if (travel > downThreshold) {
            travel = 0f
            onScrollDown()
        }
    }

    private companion object {
        /** About as long as a fling scrolls on after the finger lifts. */
        const val FLING_MILLIS = 1_500L
    }
}

/**
 * An app's web view, which also says when it overscrolls the top of its page (see
 * [ChromeGestures]): WebView reports that only through this override.
 */
class PageWebView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : WebView(context, attributes) {
    var onOverscrollTop: (() -> Unit)? = null

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) onOverscrollTop?.invoke()
    }
}

/**
 * The color each app paints behind the status bar, remembered per appearance from its last
 * load, so the next launch starts with the right header color. Before the first load the
 * manifest's theme color stands in, in light mode only, since pages often darken it.
 */
class StatusBarTint(private val store: KeyValueStore) {
    fun color(app: WebApp, dark: Boolean): Color? = store.getString(key(app, dark))?.let(::themeColor) ?: if (dark) null else themeColor(app.themeColorHex)

    /** Saves the header color [PageLook] read from the page, when it is a color that is mostly opaque. */
    fun remember(css: String?, app: WebApp, dark: Boolean) {
        val hex = css?.let(::opaqueHex) ?: return
        store.putString(key(app, dark), hex)
    }

    fun forget(app: WebApp) {
        for (dark in listOf(false, true)) store.remove(key(app, dark))
    }

    companion object {
        private fun key(app: WebApp, dark: Boolean) = "statusBarTint.${app.id.toString().uppercase()}.${if (dark) "dark" else "light"}"

        private val rgb = Regex("""^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$""")

        /** `#rrggbb` for a CSS color as Chromium writes one (`#rrggbb`, `rgb()` or `rgba()`) with alpha over 0.5, else null. */
        fun opaqueHex(css: String): String? {
            val text = css.trim().lowercase()
            if (Regex("^#[0-9a-f]{6}$").matches(text)) return text
            val match = rgb.matchEntire(text) ?: return null
            val alpha = match.groupValues[4].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
            if (alpha <= 0.5) return null
            val channels = (1..3).map { match.groupValues[it].toDoubleOrNull()?.toInt()?.coerceIn(0, 255) ?: return null }
            return "#" + channels.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * What Ovenlight reads of a page, in one script: whether its viewport asks for
 * `viewport-fit=cover` (iOS's WebCoordinator.coverScript), its header color and its background.
 * CSS colors, as Chromium writes them.
 */
data class PageLook(val cover: Boolean = true, val header: String? = null, val background: String? = null) {
    companion object {
        /**
         * The viewport is the last viewport meta's. The header is what WebKit gives iOS as the
         * page's theme color, else its background: the first `theme-color` whose `media`
         * matches, read through a canvas so any CSS color comes back as `#rrggbb` or `rgba()`
         * without touching the page's DOM. The background is the computed one of `html`, or of
         * `body`, which paints the page when `html` has none.
         */
        const val script = """(() => {
  const ctx = document.createElement("canvas").getContext("2d");
  let header = null;
  for (const meta of document.querySelectorAll('meta[name="theme-color" i]')) {
    const media = meta.getAttribute("media");
    if (media && !matchMedia(media).matches) continue;
    ctx.fillStyle = "#010203"; ctx.fillStyle = meta.content.trim();
    if (ctx.fillStyle !== "#010203" || /^#010203$/i.test(meta.content.trim())) { header = ctx.fillStyle; break; }
  }
  const background = [document.documentElement, document.body].map(e => e && getComputedStyle(e).backgroundColor)
    .find(c => c && c !== "transparent" && !/^rgba\(.*,\s*0\)$/.test(c)) ?? null;
  const viewport = [...document.querySelectorAll('meta[name="viewport" i]')].pop();
  return { cover: /viewport-fit\s*=\s*cover/i.test(viewport?.content ?? ""), header: header ?? background, background };
})()"""

        /** The script's answer, a JSON object; anything else is a page that didn't ask to cover the screen. */
        fun decoded(result: String?): PageLook {
            val fields = result?.let { runCatching { ovenlightJSON.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return PageLook(cover = false)
            fun text(name: String) = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return PageLook((fields["cover"] as? JsonPrimitive)?.booleanOrNull == true, text("header"), text("background"))
        }
    }
}

/** Whether dark marks read better than light ones on this color: lighter than where black and white contrast with it equally. */
fun Color.isLight(): Boolean = luminance() > 0.179f

/** The app's remembered header color in the current appearance, for the band at the top until its first page loads. */
@Composable
fun rememberStatusBarTint(app: WebApp): Color? {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return remember(app.id, dark) { StatusBarTint(PreferencesStore(context)).color(app, dark) }
}

/**
 * Back in an open app goes back in the page while it can. Otherwise the app closes, shrinking
 * toward the launcher and rounding its corners as the gesture moves (predictive back); a
 * cancelled gesture springs back. The returned progress, 0 to 1, drives [backShrink].
 */
@Composable
fun rememberAppBack(canGoBack: () -> Boolean, goBack: () -> Unit, close: () -> Unit): () -> Float {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler { events ->
        if (canGoBack()) {
            events.collect {}
            goBack()
            return@PredictiveBackHandler
        }
        try {
            events.collect { progress.snapTo(LinearOutSlowInEasing.transform(it.progress)) }
            // Stays shrunk: the app layer's own exit carries it on into the launcher.
            close()
        } catch (cancelled: CancellationException) {
            scope.launch { progress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            throw cancelled
        }
    }
    return { progress.value }
}

/** The open app as a back gesture shrinks it: toward 0.9, its corners rounding to 28 dp. */
fun Modifier.backShrink(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress()
    scaleX = 1 - 0.1f * p
    scaleY = 1 - 0.1f * p
    shape = RoundedCornerShape(28.dp * p)
    clip = p > 0
}

/**
 * Whether a screen is shown, as a transition Back can scrub: shows and hides with [shown] along
 * [screenCurve], and [ScreenBack] seeks it while a back gesture moves.
 */
@Composable
fun rememberScreenShown(shown: Boolean): SeekableTransitionState<Boolean> {
    val state = remember { SeekableTransitionState(shown) }
    LaunchedEffect(shown) { state.animateTo(shown, screenCurve) }
    return state
}

/**
 * Back on a screen while it's [shown], with the predictive back preview Navigation gives its
 * screens: the gesture runs the screen's own exit as it moves, letting go calls [back] (which
 * hides it, finishing that exit), and a cancelled gesture brings it back. A screen closed some
 * other way mid-gesture also cancels it, and then finishes going.
 */
@Composable
fun ScreenBack(state: SeekableTransitionState<Boolean>, shown: Boolean, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stillShown by rememberUpdatedState(shown)
    PredictiveBackHandler(shown) { events ->
        try {
            events.collect { state.seekTo(it.progress, targetState = false) }
            back()
        } catch (cancelled: CancellationException) {
            scope.launch { state.animateTo(stillShown, screenCurve) }
            throw cancelled
        }
    }
}

private const val SCREEN_MILLIS = 350

/**
 * How a screen slides: linear in its transition, so a back gesture scrubbing it keeps the
 * screen under the finger. Opening, closing and letting go run that transition along
 * [screenCurve] instead.
 */
fun <T> screenTween(): FiniteAnimationSpec<T> = tween(SCREEN_MILLIS, easing = LinearEasing)

private val screenCurve: FiniteAnimationSpec<Float> = tween(SCREEN_MILLIS, easing = FastOutSlowInEasing)

/** Whether TalkBack (or another touch exploration service) is on, kept current. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The open app's only native chrome: a small floating toolbar at the top with the way home and
 * the app's menu, tucked up under the status bar while the person uses the app, leaving a small
 * handle in the band just below it. With TalkBack on it stays put and the handle never shows.
 * Remove animations zeroes Compose's durations, so then it comes and goes at once.
 */
@Composable
fun AppChrome(
    app: WebApp,
    icon: Bitmap?,
    isLoaded: Boolean,
    actions: AppChromeActions,
    controller: ChromeController,
    /** The color under the handle, which takes the tone that reads on it. */
    header: Color,
    touchExploration: Boolean = rememberTouchExploration(),
) {
    // Tucked away the moment the page appears, so it never sits over the page on its own.
    LaunchedEffect(isLoaded, controller.autoHides, controller.tipShowing) {
        if (isLoaded && controller.autoHides && !controller.tipShowing) controller.hide()
    }
    // Gone at once for the frame a snapshot takes, mid-animation or not. Back, it shows as it
    // was without animating in: AnimatedVisibility doesn't animate what is visible from the start.
    if (controller.hiddenForSnapshot) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(chromeTop.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)))) {
        AnimatedVisibility(
            visible = controller.isShown || touchExploration,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp, start = 16.dp, end = 16.dp),
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { -it } + fadeIn() +
                scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = slideOutVertically { -it } + fadeOut() + scaleOut(targetScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 0f)),
        ) {
            ChromePill(app, icon, actions, controller)
        }
        AnimatedVisibility(
            visible = !controller.isShown && !touchExploration,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ChromeHandle(header) { controller.reveal() }
        }
    }
}

/** The pill, with the tip pointing at it while the tip is due. The tip and the menu keep the reader's text size; the pill caps it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChromePill(app: WebApp, icon: Bitmap?, actions: AppChromeActions, controller: ChromeController) {
    val tip = rememberTooltipState(isPersistent = true)
    val reader = LocalDensity.current
    LaunchedEffect(controller.tipShowing) { if (controller.tipShowing) tip.show() else tip.dismiss() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            RichTooltip(
                title = { Text(stringResource(R.string.chrome_tip_title)) },
                action = { TextButton(onClick = controller::closeTip) { Text(stringResource(R.string.chrome_tip_action)) } },
            ) { Text(stringResource(R.string.chrome_tip_body)) }
        },
        state = tip,
        onDismissRequest = controller::closeTip,
        // Holds focus, as iOS's popover does: TalkBack and a keyboard reach the tip, and Back closes it.
        focusable = true,
        enableUserInput = false,
        hasAction = true,
    ) {
        CappedFontScale { Pill(app, icon, actions, controller, reader) }
    }
}

@Composable
private fun Pill(app: WebApp, icon: Bitmap?, actions: AppChromeActions, controller: ChromeController, reader: Density) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainer,
        shadowElevation = 3.dp,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .height(48.dp)
            // Any touch on the pill means the person has it: it no longer hides on its own.
            .pointerInput(controller) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).also { controller.used() } } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.home) {
                Icon(painterResource(R.drawable.ic_apps), contentDescription = stringResource(R.string.chrome_all_apps))
            }
            VerticalDivider(Modifier.height(18.dp), color = colors.outlineVariant)
            Box(Modifier.weight(1f, fill = false)) {
                val options = stringResource(R.string.chrome_options, app.name)
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { menu = true }
                        .semantics { contentDescription = options }
                        .heightIn(min = 48.dp)
                        .padding(start = 10.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconView(app, icon, 22.dp)
                    Text(
                        app.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
                    )
                    Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = colors.onSurfaceVariant)
                }
                CompositionLocalProvider(LocalDensity provides reader) {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        AppMenuItems(app, actions, close = { menu = false })
                    }
                }
            }
        }
    }
}

/** The app menu, in iOS's sections: getting around, then this app, then the lock. */
@Composable
private fun AppMenuItems(app: WebApp, actions: AppChromeActions, close: () -> Unit) {
    AppMenuItem(close, stringResource(R.string.chrome_all_apps), R.drawable.ic_apps, action = actions.home)
    AppMenuItem(close, stringResource(R.string.chrome_reload), R.drawable.ic_refresh, action = actions.reload)
    // Open in browser never applies: every Android app goes through Ovenlight's own connection.
    HorizontalDivider()
    actions.share?.let { AppMenuItem(close, stringResource(R.string.chrome_share), R.drawable.ic_share, action = it) }
    actions.sendFeedback?.let {
        // A guest's feedback goes to the owner; Report a problem goes to Ovenlight.
        val label = app.sharedBy?.let { owner -> stringResource(R.string.chrome_send_feedback_to, owner) } ?: stringResource(R.string.chrome_send_feedback)
        AppMenuItem(close, label, R.drawable.ic_feedback, action = it)
    }
    AppMenuItem(close, ConnectionCopy.reportProblem, R.drawable.ic_flag, action = actions.reportProblem)
    actions.leave?.let { AppMenuItem(close, ConnectionCopy.leaveApps(it.owner), R.drawable.ic_logout, destructive = true, action = it.action) }
    HorizontalDivider()
    AppMenuItem(close, stringResource(R.string.chrome_lock), R.drawable.ic_lock, action = actions.lock)
}

/** The handle's height, which the band at the top of an open app holds below [chromeTop] (AppHostView). */
val ChromeHandleHeight = 20.dp

/**
 * Where the band at the top of an open app and the handle in it start: below the status bar, the
 * cutout or a desktop window's caption, whichever is deepest. The same with the bars hidden for a
 * page's full screen, so the page behind it doesn't move.
 */
val chromeTop: WindowInsets
    @OptIn(ExperimentalLayoutApi::class)
    @Composable get() = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.captionBarIgnoringVisibility)
        .union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)

/**
 * What stays of the chrome while it's tucked away: a small handle at the top center, in the band
 * just below the status bar, which takes its own touches (they pull down the shade). It works on
 * any page, however the page scrolls.
 */
@Composable
private fun ChromeHandle(header: Color, reveal: () -> Unit) {
    val label = stringResource(R.string.chrome_show_menu)
    Box(
        Modifier
            .size(width = 120.dp, height = ChromeHandleHeight)
            .clickable(role = Role.Button, onClick = reveal)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val mark = if (header.isLight()) Color.Black else Color.White
        Box(Modifier.size(width = 32.dp, height = 4.dp).background(mark.copy(alpha = 0.3f), CircleShape))
    }
}

/**
 * Watches the page without taking it over: its scrolling through the view's scroll listener, and
 * touches through a listener that never consumes them.
 */
@SuppressLint("ClickableViewAccessibility") // The listener only watches and returns false, so the page's own clicks run as before.
@Composable
private fun WatchPage(view: PageWebView, controller: ChromeController) {
    val density = LocalDensity.current.density
    DisposableEffect(view, controller) {
        val gestures = ChromeGestures(
            density, ViewConfiguration.get(view.context).scaledTouchSlop.toFloat(),
            onPull = { controller.reveal(fromHandle = false) },
            onScrollDown = controller::hide,
            onTap = controller::hide,
        )
        view.setOnScrollChangeListener { _, _, y, _, oldY -> gestures.scrolled(oldY, y, SystemClock.uptimeMillis()) }
        view.setOnTouchListener { _, event ->
            gestures.touched(event.actionMasked, event.x, event.y, event.eventTime)
            false
        }
        view.onOverscrollTop = gestures::overscrolledTop
        onDispose {
            view.setOnScrollChangeListener(null)
            view.setOnTouchListener(null)
            view.onOverscrollTop = null
        }
    }
}

/** A snapshot of the view as the screen shows it, through PixelCopy, or drawn in software when that fails. */
private suspend fun snapshot(view: View): Bitmap? {
    if (view.width == 0 || view.height == 0) return null
    val window = view.context.componentActivity()?.window
    if (window != null) {
        val bitmap = createBitmap(view.width, view.height)
        val at = IntArray(2).also(view::getLocationInWindow)
        val copied = suspendCancellableCoroutine { done ->
            try {
                PixelCopy.request(
                    window, Rect(at[0], at[1], at[0] + view.width, at[1] + view.height), bitmap,
                    { result -> if (done.isActive) done.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()),
                )
            } catch (_: IllegalArgumentException) {
                // A window with no surface yet.
                done.resume(false)
            }
        }
        if (copied) return bitmap
        bitmap.recycle()
    }
    return runCatching { view.drawToBitmap() }.getOrNull()
}

/** The page's script result, a JSON value as text, or null when the view goes first. */
private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { done ->
    evaluateJavascript(script) { if (done.isActive) done.resume(it) }
}

/**
 * The chrome over one open app, from the model: the pill and its menu, the page watched for the
 * pill to follow, the header color remembered as each load reads it, Send feedback, Report a
 * problem and Leave.
 */
@Composable
fun AppChromeHost(model: AppModel, page: AppPage, icon: Bitmap?, header: Color, onClose: () -> Unit) {
    val app = page.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by page.hasLoadedOnce.collectAsState()
    val memberships by model.nodes.memberships.membershipsFlow.collectAsState()
    val controller = remember(page) { ChromeController(PreferencesStore(context)) }
    val feedback by page.feedback.collectAsState()
    var noMailApp by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<Membership?>(null) }
    val density = LocalDensity.current.density
    val dark = isSystemInDarkTheme()

    WatchPage(page.view, controller)
    // The header for the next launch, saved under the appearance it was read in: from each load
    // while the app is open, never from the look already there, which an app reopened after a
    // switch read in the other.
    val tint = remember { StatusBarTint(PreferencesStore(context)) }
    LaunchedEffect(page, dark) { page.look.drop(1).collect { tint.remember(it.header, app, dark) } }
    // Read again in the other appearance, and on opening an app that's loaded, which may have
    // switched while it was closed. Saved here too, since a look equal to the last one doesn't
    // reach the collector.
    LaunchedEffect(page, dark) {
        if (!page.hasLoadedOnce.value) return@LaunchedEffect
        val read = PageLook.decoded(page.view.evaluate(PageLook.script))
        page.look.value = read
        tint.remember(read.header, app, dark)
    }

    val membership = memberships.firstOrNull { it.id == app.membershipID }
    AppChrome(
        app, icon, loaded,
        AppChromeActions(
            home = onClose,
            reload = page::reload,
            // Share… comes with the owner's sharing info in Phase 6 (iOS's shareAction).
            share = null,
            sendFeedback = if (app.hasConnector && loaded) {
                {
                    scope.launch {
                        // The pill and handle leave for the frame the snapshot takes.
                        controller.hiddenForSnapshot = true
                        val image = try {
                            repeat(2) { withFrameNanos {} }
                            snapshot(page.view)
                        } finally {
                            controller.hiddenForSnapshot = false
                        }
                        page.feedback.value = FeedbackDraft(image, page.view.url?.let { runCatching { URI(it) }.getOrNull() })
                    }
                }
            } else {
                null
            },
            reportProblem = { if (!MailDraft.problemReport(context, model.nodes, model.pool, app).send(context)) noMailApp = true },
            leave = membership?.let { owner -> AppChromeActions.Leave(owner.ownerName) { leaving = owner } },
            lock = model.lock::lock,
        ),
        controller,
        header,
    )

    feedback?.let { draft ->
        FeedbackSheet(
            app, draft, density,
            send = { FeedbackSender.send(it, app, model.nodes, model.guests) },
            onDismiss = { if (page.feedback.value === draft) page.feedback.value = null },
            scope = model.scope,
        )
    }
    if (noMailApp) NoEmailAppDialog(onDismiss = { noMailApp = false })
    leaving?.let { membership ->
        LeaveConfirmation(membership.ownerName, onDismiss = { leaving = null }) {
            // As iOS: the app closes first, then its owner's apps go, this one among them.
            onClose()
            model.scope.launch { model.guests.leave(membership.id) }
        }
    }
}
