package com.snowyghost.ovenlight

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.doOnAttach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown over an app when its connection needs someone: the owner's sign-in or device approval,
 * or a failure with a retry. Plain waiting is the app's launch placeholder. A guest never sees
 * sign-in, approval or Tailscale. The overlay itself comes with the app host.
 */
object NodeActionOverlay {
    sealed class Action {
        data object SignIn : Action()
        data object OwnerApproval : Action()
        data class Failed(val message: String) : Action()
    }

    fun action(state: NodeState, app: WebApp, ownerEnabled: Boolean): Action? {
        if (app.isShared) {
            // A lost login means the share ended; Ovenlight removes the app.
            return if (state is NodeState.Failed) Action.Failed(state.message) else null
        }
        return when {
            state is NodeState.NeedsLogin -> Action.SignIn
            state == NodeState.Idle && !ownerEnabled -> Action.SignIn
            state == NodeState.AwaitingApproval -> Action.OwnerApproval
            state is NodeState.Failed -> Action.Failed(state.message)
            else -> null
        }
    }
}

/**
 * One open app, full screen: a band at the top in the page's header color, behind the status bar
 * and the chrome's handle, and below it the web view and, until its first page loads, the overlay
 * its connection needs, Can't Reach, or its launch placeholder. A page that asks for
 * `viewport-fit=cover` runs to the bottom and side edges; one that doesn't sits inside the cutout,
 * and runs under the gesture handle as on iOS, or above three-button navigation on its
 * background color. Back goes back in the page, then closes the app, shrinking toward [origin],
 * where its icon is (iOS's AppHostView).
 */
@Composable
fun AppHostView(model: AppModel, app: WebApp, origin: TransformOrigin = TransformOrigin.Center, onClose: () -> Unit) {
    val apps by model.registry.appsFlow.collectAsState()
    // As listed now, so a rename reaches the open app. One removed while it closes (Leave,
    // access ended) gets no new view: the pool would make its profile again, and load it.
    val listed = apps.firstOrNull { it.id == app.id } ?: return Box(Modifier.fillMaxSize().background(LocalOvenColors.current.pageBackground))
    // The same live page every time, unless the app's route changed since it was made.
    val revision by model.pool.revision.collectAsState()
    val page = remember(app.id, revision) { model.pool.page(app) }
    key(page) { AppPageView(model, page, listed, origin, onClose) }
}

/** The [page] of [app], as the registry lists it now. */
@Composable
private fun AppPageView(model: AppModel, page: AppPage, app: WebApp, origin: TransformOrigin, onClose: () -> Unit) {
    val activity = LocalContext.current
    val loaded by page.hasLoadedOnce.collectAsState()
    val error by page.loadError.collectAsState()
    val gate by page.gate.collectAsState()
    val ownerEnabled by model.nodes.ownerEnabled.collectAsState()
    val nodeState = page.node?.state?.collectAsState()?.value
    val iconRevisions by model.registry.iconRevisions.collectAsState()
    val icon = rememberIcon(app, iconRevisions[app.id] ?: 0) { IconDecoder.load(model.registry.iconFile(it)) }
    val cantReach by page.cantReachAlert.collectAsState()
    val neverConnected by page.neverConnected.collectAsState()
    val look by page.look.collectAsState()

    DisposableEffect(page) {
        model.pool.onScreen = app.id
        // Reopening an app that couldn't reach its computer, or couldn't load, tries again.
        page.reopened()
        onDispose {
            if (model.pool.onScreen == app.id) model.pool.onScreen = null
            (page.view.parent as? ViewGroup)?.removeView(page.view)
            page.detach()
        }
    }
    val back = rememberAppBack(canGoBack = page.view::canGoBack, goBack = page.view::goBack, close = onClose)
    val tint = rememberStatusBarTint(app)

    val plain = LocalOvenColors.current.pageBackground
    val color = { css: String? -> css?.let(StatusBarTint::opaqueHex)?.let(::themeColor) }
    // The band under the status bar and the handle: the page's header, or until it loads the one from its last load.
    val header = if (loaded) color(look.header) else tint
    val band by animateColorAsState(header ?: plain, tween(250), label = "band")
    val appNode: MembershipNode? = page.node
    val retryNode: () -> Unit = { appNode?.let { node -> model.scope.launch { node.restart() } } }
    val action = appNode?.let { nodeState?.let { NodeActionOverlay.action(it, app, ownerEnabled) } }
    // What each cover says goes with it, so one fading out keeps its words.
    val showing = when {
        error != null -> Cover.Error(error.orEmpty())
        loaded -> Cover.None
        gate == AppLoadGate.Offline -> Cover.Offline
        action != null -> Cover.Node(action)
        gate == AppLoadGate.Unreachable -> Cover.CantReach(ConnectionCopy.cantReachDetail(app, refused = appNode == null, neverConnected = neverConnected))
        else -> Cover.Placeholder
    }
    StatusBarIcons(band)
    Box(Modifier.backShrink(back, origin).fillMaxSize().background(color(look.background) ?: plain)) {
        // No page runs under the status bar or the handle, which lives in this band.
        Box(Modifier.fillMaxWidth().background(band).windowInsetsPadding(chromeTop).height(ChromeHandleHeight))
        Box(Modifier.fillMaxSize().windowInsetsPadding(chromeTop).padding(top = ChromeHandleHeight)) {
            AndroidView(
                // The activity's context before the view joins its window, where WebView registers its
                // component callbacks through it: they come off through the same context as it leaves,
                // or the application would hold every page ever shown. Focused as it joins, so a
                // keyboard reaches the page without a tap first.
                factory = { page.attach(activity); page.view.also { (it.parent as? ViewGroup)?.removeView(it); it.doOnAttach(View::requestFocus) } },
                modifier = if (look.cover) Modifier.fillMaxSize() else Modifier.fillMaxSize().windowInsetsPadding(insideBars),
            )
            // The page takes no touches until it has loaded; the placeholder or overlay over it does.
            // Its background keeps an empty web view's white from showing as the covers fade.
            if (!loaded) Box(Modifier.fillMaxSize().background(LocalOvenColors.current.pageBackground).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } })
            AnimatedContent(
                targetState = showing,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "app cover",
            ) { cover ->
                // As on the launcher: nothing above gives a content color, which would leave the covers' text black.
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    when (cover) {
                        Cover.None -> Unit
                        Cover.Placeholder -> LaunchPlaceholder(app, icon)
                        // A refused app has no way in that Try again could find.
                        is Cover.CantReach -> LoadErrorView(
                            R.drawable.ic_wifi_off, ConnectionCopy.cantReach(app.name), cover.detail, page::reload.takeIf { appNode != null }, onClose,
                        )
                        Cover.Offline -> LoadErrorView(R.drawable.ic_wifi_off, ConnectionCopy.offline, ConnectionCopy.offlineDetail, page::reload, onClose)
                        is Cover.Error -> LoadErrorView(R.drawable.ic_warning, stringResource(R.string.app_cant_open, app.name), cover.message, page::reload, onClose)
                        is Cover.Node -> {
                            NodeActionCard(
                                app, cover.action, deviceName = model.nodes.deviceName,
                                connect = {
                                    when (val state = model.nodes.owner.state.value) {
                                        is NodeState.NeedsLogin -> model.router.presentTab(RootSheet.Login(state.url))
                                        NodeState.AwaitingApproval -> model.router.presentTab(RootSheet.Approve)
                                        else -> if (!ownerEnabled) model.nodes.enableOwner()
                                    }
                                },
                                retry = retryNode,
                                close = onClose,
                            )
                        }
                    }
                }
            }
        }
        AppChromeHost(model, page, app, icon, band, onClose)
        // Last, so full screen covers everything else and its Back comes first.
        PageEdgesLayer(page.edges)
    }
    if (cantReach) {
        AlertDialog(
            onDismissRequest = { page.cantReachAlert.value = false },
            title = { Text(ConnectionCopy.cantReach(app.name)) },
            text = { Text(ConnectionCopy.cantReachDetail(app, neverConnected = neverConnected)) },
            confirmButton = { TextButton(onClick = { page.cantReachAlert.value = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    CapturePrompt(page, app.name)
    DownloadSheet(model, page)
}

private sealed interface Cover {
    data object None : Cover
    data object Placeholder : Cover
    data class CantReach(val detail: String) : Cover
    data object Offline : Cover
    data class Error(val message: String) : Cover
    data class Node(val action: NodeActionOverlay.Action) : Cover
}

/** The status bar's icons over [band], the color Ovenlight paints behind them. */
@Composable
private fun StatusBarIcons(band: Color) {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    // enableEdgeToEdge sets the icons again on a configuration change, so they're set after it.
    val configuration = LocalConfiguration.current
    val light = band.isLight()
    DisposableEffect(light, dark, configuration) {
        val bars = view.context.componentActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = light
        onDispose { bars?.isAppearanceLightStatusBars = !dark }
    }
}

/**
 * Where a page that doesn't cover the screen sits below the band: inside the cutout and side bars,
 * and above navigation buttons. Only three-button navigation has a tappable bottom; the gesture
 * handle has none.
 */
private val insideBars: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
        .union(WindowInsets.tappableElement.only(WindowInsetsSides.Bottom))

/**
 * What an app shows below the band until its first page loads: its icon in the middle, and a
 * spinner with "Connecting…" only after a beat, so fast loads never show one.
 */
@Composable
private fun LaunchPlaceholder(app: WebApp, icon: Bitmap?) {
    var spinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(700)
        spinner = true
    }
    val label = ConnectionCopy.connecting(to = app.name)
    Column(
        Modifier.fillMaxSize().background(LocalOvenColors.current.pageBackground).clearAndSetSemantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIconView(app, icon, 96.dp)
        val alpha by animateFloatAsState(if (spinner) 1f else 0f, tween(250), label = "spinner")
        Column(Modifier.padding(top = 28.dp).alpha(alpha), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Text(
                ConnectionCopy.connecting, Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Can't Reach, or a page that couldn't load, on the page's background as the placeholder before
 * it: Try again, and the way back to the apps. Its title is the join screen's display title.
 */
@Composable
private fun LoadErrorView(icon: Int, title: String, message: String, retry: (() -> Unit)?, close: () -> Unit) {
    // A pane, so TalkBack says it as it replaces the placeholder.
    CenteredScroll(Modifier.background(LocalOvenColors.current.pageBackground).semantics { paneTitle = title }, contentPadding = 32.dp) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, Modifier.padding(top = 16.dp).semantics { heading() }, style = OvenType.displayTitle, textAlign = TextAlign.Center)
        Text(message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        retry?.let {
            Button(onClick = it, Modifier.padding(top = 24.dp).widthIn(max = 640.dp).fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.try_again), style = MaterialTheme.typography.titleMedium)
            }
        }
        TextButton(onClick = close, Modifier.padding(top = if (retry == null) 24.dp else 8.dp)) { Text(stringResource(R.string.app_back_to_apps)) }
    }
}

/**
 * The owner's sign-in or device approval, or a failure, in the launcher's card for the same step,
 * with the way back to the apps. A guest sees only a failure.
 */
@Composable
private fun NodeActionCard(app: WebApp, action: NodeActionOverlay.Action, deviceName: String, connect: () -> Unit, retry: () -> Unit, close: () -> Unit) {
    CenteredScroll(Modifier.background(LocalOvenColors.current.pageBackground)) {
        val card = Modifier.padding(24.dp).widthIn(max = 480.dp)
        // On the page's white the launcher's white card would show only its shadow: a step off white.
        val container = if (isSystemInDarkTheme()) cardColor else MaterialTheme.colorScheme.surfaceContainerLow
        val back: @Composable ColumnScope.() -> Unit = {
            TextButton(onClick = close, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.app_back_to_apps)) }
        }
        when (action) {
            NodeActionOverlay.Action.SignIn ->
                LauncherCard(R.drawable.ic_lan, ConnectionCopy.signIn(toOpen = app.name), null, stringResource(R.string.app_sign_in), card, container, back, connect)
            NodeActionOverlay.Action.OwnerApproval -> LauncherCard(
                R.drawable.ic_verified_user, stringResource(R.string.approve_this_phone), approveBody(deviceName), stringResource(R.string.launcher_open_machines),
                card, container, back, connect,
            )
            is NodeActionOverlay.Action.Failed -> LauncherCard(R.drawable.ic_wifi_off, action.message, null, stringResource(R.string.try_again), card, container, back, retry)
        }
    }
}
