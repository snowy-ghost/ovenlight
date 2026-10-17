package com.snowyghost.ovenlight

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
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
 * background color. Back goes back in the page, then closes the app (iOS's AppHostView).
 */
@Composable
fun AppHostView(model: AppModel, app: WebApp, onClose: () -> Unit) {
    val apps by model.registry.appsFlow.collectAsState()
    // An app removed while it closes (Leave, access ended) gets no new view: the pool would
    // make its profile again, and load it.
    if (apps.none { it.id == app.id }) return Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    // The same live page every time, unless the app's route changed since it was made.
    val revision by model.pool.revision.collectAsState()
    val page = remember(app.id, revision) { model.pool.page(app) }
    key(page) { AppPageView(model, page, onClose) }
}

@Composable
private fun AppPageView(model: AppModel, page: AppPage, onClose: () -> Unit) {
    val app = page.app
    val activity = LocalContext.current
    val loaded by page.hasLoadedOnce.collectAsState()
    val error by page.loadError.collectAsState()
    val gate by page.gate.collectAsState()
    val ownerEnabled by model.nodes.ownerEnabled.collectAsState()
    val nodeState = page.node?.state?.collectAsState()?.value
    val iconRevisions by model.registry.iconRevisions.collectAsState()
    val icon = rememberIcon(app, iconRevisions[app.id] ?: 0) { IconDecoder.load(model.registry.iconFile(it)) }
    val cantReach by page.cantReachAlert.collectAsState()
    val look by page.look.collectAsState()

    DisposableEffect(page) {
        page.attach(activity)
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

    val plain = MaterialTheme.colorScheme.background
    val color = { css: String? -> css?.let(StatusBarTint::opaqueHex)?.let(::themeColor) }
    // The band under the status bar and the handle: the page's header, or until it loads the one from its last load.
    val header = if (loaded) color(look.header) else tint
    val band by animateColorAsState(header ?: plain, tween(250), label = "band")
    val appNode: MembershipNode? = page.node
    val retryNode: () -> Unit = { appNode?.let { node -> model.scope.launch { node.restart() } } }
    val action = appNode?.let { nodeState?.let { NodeActionOverlay.action(it, app, ownerEnabled) } }
    val showing = when {
        error != null -> Cover.Error
        loaded -> Cover.None
        action != null -> Cover.Node
        gate == AppLoadGate.Unreachable -> Cover.CantReach
        else -> Cover.Placeholder
    }
    StatusBarIcons(header ?: plain)
    Box(Modifier.backShrink(back).fillMaxSize().background(color(look.background) ?: plain)) {
        // No page runs under the status bar or the handle, which lives in this band.
        Box(Modifier.fillMaxWidth().background(band).windowInsetsPadding(topBar).height(ChromeHandleHeight))
        Box(Modifier.fillMaxSize().windowInsetsPadding(topBar).padding(top = ChromeHandleHeight)) {
            AndroidView(
                factory = { page.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = if (look.cover) Modifier.fillMaxSize() else Modifier.fillMaxSize().windowInsetsPadding(insideBars),
            )
            // The page takes no touches until it has loaded; the placeholder or overlay over it does.
            if (!loaded) Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } })
            AnimatedContent(
                targetState = showing,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "app cover",
            ) { cover ->
                when (cover) {
                    Cover.None -> Unit
                    Cover.Placeholder -> LaunchPlaceholder(app, icon)
                    Cover.CantReach -> LoadErrorView(ConnectionCopy.cantReach(app.name), ConnectionCopy.cantReachDetail, page::reload, onClose)
                    Cover.Error -> LoadErrorView(stringResource(R.string.app_cant_open, app.name), error.orEmpty(), page::reload, onClose)
                    Cover.Node -> action?.let {
                        NodeActionCard(
                            app, it, deviceName = model.nodes.deviceName,
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
        AppChromeHost(model, page, icon, header, onClose)
        // Last, so full screen covers everything else and its Back comes first.
        PageEdgesLayer(page.edges)
    }
    if (cantReach) {
        AlertDialog(
            onDismissRequest = { page.cantReachAlert.value = false },
            title = { Text(ConnectionCopy.cantReach(app.name)) },
            text = { Text(ConnectionCopy.cantReachDetail) },
            confirmButton = { TextButton(onClick = { page.cantReachAlert.value = false }) { Text(stringResource(R.string.app_ok)) } },
        )
    }
    CapturePrompt(page)
    DownloadSheet(model, page)
}

private enum class Cover { None, Placeholder, CantReach, Error, Node }

/** The status bar's icons over [band], the color Ovenlight paints behind them. */
@Composable
private fun StatusBarIcons(band: Color) {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    // enableEdgeToEdge sets the icons again on a configuration change, so they're set after it.
    val configuration = LocalConfiguration.current
    DisposableEffect(band, dark, configuration) {
        val bars = view.context.componentActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = band.isLight()
        onDispose { bars?.isAppearanceLightStatusBars = !dark }
    }
}

/** What the band at the top reaches below: the status bar, or the cutout when it's deeper. As the chrome's handle sits. */
private val topBar: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

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

/** Can't Reach, or a page that couldn't load: Try again, and the way back to the apps. */
@Composable
private fun LoadErrorView(title: String, message: String, retry: () -> Unit, close: () -> Unit) {
    CenteredScroll(Modifier.background(MaterialTheme.colorScheme.background), contentPadding = 32.dp) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = retry, Modifier.padding(top = 24.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.app_try_again)) }
        TextButton(onClick = close, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.app_back_to_apps)) }
    }
}

/** The owner's sign-in or device approval, or a failure, with the way back to the apps. A guest sees only a failure. */
@Composable
private fun NodeActionCard(app: WebApp, action: NodeActionOverlay.Action, deviceName: String, connect: () -> Unit, retry: () -> Unit, close: () -> Unit) {
    CenteredScroll(Modifier.background(MaterialTheme.colorScheme.background)) {
        ElevatedCard(Modifier.padding(24.dp).widthIn(max = 480.dp)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (action) {
                    NodeActionOverlay.Action.SignIn -> {
                        Text(ConnectionCopy.signIn(toOpen = app.name), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Button(onClick = connect, Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.app_sign_in)) }
                    }
                    NodeActionOverlay.Action.OwnerApproval -> {
                        Text(stringResource(R.string.launcher_approve_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(
                            stringResource(R.string.launcher_approve_body, deviceName), Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                        Button(onClick = connect, Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.launcher_open_machines)) }
                    }
                    is NodeActionOverlay.Action.Failed -> {
                        Text(action.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        Button(onClick = retry, Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.app_try_again)) }
                    }
                }
                TextButton(onClick = close, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.app_back_to_apps)) }
            }
        }
    }
}
