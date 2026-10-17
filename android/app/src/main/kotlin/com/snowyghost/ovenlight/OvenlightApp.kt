package com.snowyghost.ovenlight

import android.app.Application
import android.content.Context
import android.icu.text.Normalizer2
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OvenlightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The core's proxy listens before any WebView exists.
        TailnetCore.start(this)
        WebViewSetup.start(this)
        model = AppModel(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        model.pool.trimMemory(level, away = model.lifecycle.isAway)
    }

    companion object {
        /** Made once per process, so a configuration change or a restore after process death starts locked. */
        lateinit var model: AppModel
            private set

        val lock: LockManager get() = model.lock
        val router: Router get() = model.router

        /**
         * Whether this phone has kept anything: apps, invites or a node. Apps and invites live
         * in [ovenlight] (filesDir/Ovenlight), each node's state in [nodes]
         * (noBackupFilesDir/tailscale).
         */
        fun hasSavedData(ovenlight: File, nodes: File): Boolean {
            // A failed join, leaving the last network and removing the last app each leave an
            // empty list, `[]`; a file whose size can't be read (0) still counts.
            if (listOf("apps.json", "memberships.json").any { File(ovenlight, it).let { file -> file.exists() && file.length() != 2L } }) {
                return true
            }
            // Stop Using My Own Computers and leaving the last network leave the folder empty;
            // a listing that fails still counts it.
            return nodes.exists() && nodes.list()?.isEmpty() != true
        }
    }
}

/**
 * What the screens share, made once per process: the lock, the router, the nodes, the apps and
 * the guests, and what each does when another changes (iOS's RootView). Main thread only.
 */
class AppModel(context: Context) {
    val scope = MainScope()
    private val preferences = PreferencesStore(context)
    private val ovenlight = File(context.filesDir, "Ovenlight")
    private val nodesDirectory = File(context.noBackupFilesDir, "tailscale")

    /** Lives with the process, never in saved instance state. */
    val lock = LockManager(preferences, hasSavedData = { OvenlightApp.hasSavedData(ovenlight, nodesDirectory) }, now = SystemClock::elapsedRealtime)
    val router = Router(isLocked = { lock.holdsLinks })
    val nodes = NodeManager(
        TailnetCore, preferences, MembershipStore(ovenlight), nodesDirectory, scope, Dispatchers.IO, SystemClock::elapsedRealtime,
        log = { OvenlightLog.i(it) }, debugBuild = BuildConfig.DEBUG,
    )
    val pool = WebViewPool(context, nodes, scope, isLocked = { lock.isLocked.value })
    val registry = AppRegistry(
        ovenlight, session = nodes::fetch, webData = WebProfiles(context, discard = pool::discard),
        iconPNG = { withContext(Dispatchers.Default) { IconDecoder.png(it) } }, scope = scope,
    )
    val guests = GuestManager(nodes, registry, router, scope, SystemClock::elapsedRealtime, log = { OvenlightLog.i(it) })
        .also { pool.guests = it }
    /** The router's sheet showing in a Custom Tab, kept with the process so a recreated activity returns from it. */
    var openTab: RootSheet? = null
    val lifecycle = AppLifecycle(
        scope, SystemClock::elapsedRealtime, setAway = {
            nodes.pollingPaused = it
            pool.timersPaused = it
        }, closeNodes = nodes::closeAll,
        cameToFront = { didBecomeActive(context) },
    )

    init {
        scope.launch {
            registry.appsFlow.drop(1).collect { apps ->
                router.appsChanged(apps)
                launch { guests.collectOrphans() }
            }
        }
        scope.launch { nodes.owner.discovered.collect { registry.mergeDiscovered(it) } }
        // Opening an app, from anywhere, clears its new dot. Closing one drops its Send feedback;
        // the lock, which leaves the app open, doesn't.
        scope.launch {
            router.openApp.collect { app ->
                app?.let { registry.markOpened(it.id) }
                pool.appClosed(open = app?.id)
            }
        }
        // The sign-in page closes once the sign-in is over (see NodeState.endsSignIn).
        scope.launch {
            nodes.owner.state.collect { state ->
                connectBrowser(context, state)
                val sheet = router.sheet.value
                if (state.endsSignIn && sheet is RootSheet.Login) router.sheet.value = null
                // Android only: the Machines page is a Custom Tab over Ovenlight, so once the phone
                // is approved Ovenlight comes back rather than waiting for the tab to close.
                if (state == NodeState.Ready && sheet == RootSheet.Approve) router.sheet.value = null
            }
        }
        scope.launch { lock.isLocked.collect { locked -> if (locked) pool.stopCapture() } }
        // An app a link asked for while locked opens once Ovenlight unlocks.
        scope.launch { lock.isLocked.drop(1).collect { locked -> if (!locked) router.appsChanged(registry.apps) } }
        scope.launch {
            // Leftovers from apps removed while their web view was still closing.
            delay(5.seconds)
            registry.sweepUnusedDataStores()
        }
    }

    /**
     * Connected to the browser while a sign-in or approval tab may come next, and not once the
     * phone is connected (see [CustomTabs.connect]).
     */
    private fun connectBrowser(context: Context, state: NodeState) {
        if (state is NodeState.NeedsLogin || state == NodeState.AwaitingApproval) CustomTabs.connect(context)
        if (state == NodeState.Ready) CustomTabs.disconnect(context)
    }

    /** Ovenlight came to the front (see [AppLifecycle]). */
    private suspend fun didBecomeActive(context: Context) {
        // Again after a browser update ended the connection, which no state change follows.
        connectBrowser(context, nodes.owner.state.value)
        // A launch before the first unlock after a reboot couldn't read the saved lists.
        registry.reloadIfFailed()
        nodes.memberships.reloadIfFailed()
        TailnetCore.reportNetwork()
        nodes.didBecomeActive()
        guests.resumePending()
        // The launcher has no pull to refresh; Ovenlight looks for new apps each time it comes
        // to the front instead.
        nodes.rediscover()
        registry.refreshAllMissingIcons()
    }
}

/**
 * What the root presents: the open app over the launcher, and one sheet at a time. Links
 * (`ovenlight://open?app=<name>` and invites) land here, and show only once Ovenlight is
 * unlocked, since the lock covers everything. An app a link opens waits for the unlock, so its
 * page can't run under the lock.
 *
 * The open app and a sheet never show together, so anything that needs the other one closes it
 * first and waits until it's gone: the screens call [appDismissed] and [sheetDismissed] once
 * they have finished closing, after [openApp] or [sheet] is already null.
 */
class Router(private val isLocked: () -> Boolean = { false }) {
    val openApp = MutableStateFlow<WebApp?>(null)
    val sheet = MutableStateFlow<RootSheet?>(null)

    /** A linked app that isn't listed yet (discovery may add it in a moment), or that waits for Ovenlight to unlock. */
    private var pendingName: String? = null

    /** True from the moment the app or a sheet shows until it has finished closing. */
    private var appOnScreen = false
    private var sheetOnScreen = false

    /** Work waiting for the app and the sheet to be gone. */
    private val whenClear = mutableListOf<() -> Unit>()

    private val isClear: Boolean
        get() = openApp.value == null && sheet.value == null && !appOnScreen && !sheetOnScreen

    /** Opens an app, closing any sheet first. */
    fun open(app: WebApp) {
        if (sheet.value == null && !sheetOnScreen) {
            openApp.value = app
            return
        }
        sheet.value = null
        afterClear { openApp.value = app }
    }

    /** Shows a sheet, closing the open app first. */
    fun present(sheet: RootSheet) {
        if (openApp.value == null && !appOnScreen) {
            this.sheet.value = sheet
            return
        }
        openApp.value = null
        afterClear { this.sheet.value = sheet }
    }

    /**
     * Shows the sign-in or Machines page from inside an open app, which stays open beneath it:
     * those pages are a Custom Tab, not a screen over the app.
     */
    fun presentTab(sheet: RootSheet) {
        check(!sheet.isScreen)
        this.sheet.value = sheet
    }

    /** Runs [action] now if nothing is presented, otherwise once it has all closed. */
    fun afterClear(action: () -> Unit) {
        if (isClear) action() else whenClear += action
    }

    fun appAppeared() {
        appOnScreen = true
    }

    fun sheetAppeared() {
        sheetOnScreen = true
    }

    /** Both may also come when one item replaces another; the new one is still up then. */
    fun appDismissed() {
        if (openApp.value == null) appOnScreen = false
        drain()
    }

    fun sheetDismissed() {
        if (sheet.value == null) sheetOnScreen = false
        drain()
    }

    private fun drain() {
        if (!isClear) return
        val actions = whenClear.toList()
        whenClear.clear()
        actions.forEach { it() }
    }

    /** A link, whichever way it arrived: an invite, or `ovenlight://open?app=<name>`. */
    fun handle(url: URI, apps: List<WebApp>) {
        if (InviteLink.isInvite(url)) {
            present(RootSheet.Join(JoinRequest(url)))
            return
        }
        if (url.scheme?.lowercase() != InviteLink.scheme || url.host?.lowercase() != "open") return
        // The first `app` item's value, decoded as iOS's URLComponents decodes query items.
        val name = url.rawQuery?.split('&')?.map { it.split('=', limit = 2) }
            ?.firstOrNull { removingPercentEncoding(it[0]) == "app" }?.getOrNull(1)?.let(::removingPercentEncoding) ?: return
        pendingName = name
        appsChanged(apps)
    }

    /** Also called on unlock, for an app a link asked for while locked. */
    fun appsChanged(apps: List<WebApp>) {
        if (isLocked()) return
        val name = pendingName ?: return
        val app = apps.firstOrNull { sameName(it.name, name) } ?: return
        pendingName = null
        open(app)
    }
}

/**
 * Names compared as iOS's localizedCaseInsensitiveCompare does, with the phone's own ICU:
 * composed the same way, case folded and with width and compatibility forms set aside, so
 * "Café" written either way, "Straße" and "STRASSE", and "ｃｏａｃｈ" and "coach" match.
 */
internal fun sameName(a: String, b: String): Boolean {
    val fold = Normalizer2.getNFKCCasefoldInstance()
    return fold.normalize(a) == fold.normalize(b)
}

/** The sheets the root presents. */
sealed class RootSheet {
    abstract val id: String

    /**
     * Shown as a screen over the launcher by RootView. The sign-in and Machines pages show in a
     * Custom Tab instead (see MainActivity), and the rest arrive with their phases.
     */
    val isScreen: Boolean get() = this == Settings

    /** An invite link, or the Join with Invite entry. */
    data class Join(val request: JoinRequest) : RootSheet() {
        override val id: String get() = "join ${request.id}"
    }

    /** Tailscale's sign-in page for the owner's node. */
    data class Login(val url: URI) : RootSheet() {
        override val id: String get() = "login $url"
    }

    /** Tailscale's Machines page, where the owner approves this phone. */
    data object Approve : RootSheet() {
        override val id: String get() = "approve"
    }

    /** The owner's People & Sharing. */
    data object Sharing : RootSheet() {
        override val id: String get() = "sharing"
    }

    data object AddApp : RootSheet() {
        override val id: String get() = "add"
    }

    data object Settings : RootSheet() {
        override val id: String get() = "settings"
    }

    data class Invite(val machine: OwnerSharing.Machine, val app: AdminApp) : RootSheet() {
        override val id: String get() = "invite ${machine.id} ${app.slug}"
    }

    data class People(val machineID: String, val slug: String) : RootSheet() {
        override val id: String get() = "people $machineID $slug"
    }
}

/**
 * The launcher, with the open app over it and one screen at a time over both (iOS's RootView).
 * While locked, none of it is composed: the activity shows only the lock screen.
 */
@Composable
fun RootView(model: AppModel) {
    val active by DebugLaunch.active.collectAsState()
    if (active) return DebugLaunch.Content()
    Box(Modifier.fillMaxSize()) {
        LauncherView(model)
        AppLayer(model)
        ScreenLayer(model)
    }
}

/**
 * The open app over the launcher, growing in and shrinking away. It reports when it has
 * appeared and once it has finished closing, as the screens do (see [ScreenLayer]).
 */
@Composable
private fun AppLayer(model: AppModel) {
    val router = model.router
    val open by router.openApp.collectAsState()
    var last by remember { mutableStateOf<WebApp?>(null) }
    if (open != null) last = open
    // An app closed while the lock kept this layer out of composition never ran its own dismissal.
    LaunchedEffect(Unit) { if (router.openApp.value == null) router.appDismissed() }
    AnimatedVisibility(visible = open != null, enter = scaleIn(initialScale = 0.9f) + fadeIn(), exit = scaleOut(targetScale = 0.9f) + fadeOut()) {
        val shown = open ?: last ?: return@AnimatedVisibility
        key(shown.id) {
            DisposableEffect(Unit) {
                router.appAppeared()
                onDispose { router.appDismissed() }
            }
            AppHostView(model, shown, onClose = { if (router.openApp.value?.id == shown.id) router.openApp.value = null })
        }
    }
}

/**
 * The router's screens, one at a time, sliding in from the end. Each reports when it has
 * appeared and once it has finished closing, which the router waits for before showing what
 * comes next.
 */
@Composable
private fun ScreenLayer(model: AppModel) {
    val router = model.router
    val sheet by router.sheet.collectAsState()
    val screen = sheet?.takeIf { it.isScreen }
    var last by remember { mutableStateOf<RootSheet?>(null) }
    if (screen != null) last = screen
    // A screen closed while the lock kept this layer out of composition never ran its own
    // dismissal, which the router would wait on for good.
    LaunchedEffect(Unit) { if (router.sheet.value == null) router.sheetDismissed() }
    val shownState = rememberScreenShown(screen != null)
    rememberTransition(shownState, label = "screen").AnimatedVisibility(
        visible = { it },
        // No fade: as Android's own screens do, the screen slides over what Back reveals.
        enter = slideInHorizontally(screenTween()) { it },
        exit = slideOutHorizontally(screenTween()) { it },
    ) {
        val shown = screen ?: last ?: return@AnimatedVisibility
        val close = { if (router.sheet.value?.id == shown.id) router.sheet.value = null }
        key(shown.id) {
            DisposableEffect(Unit) {
                router.sheetAppeared()
                onDispose { router.sheetDismissed() }
            }
            ScreenBack(shownState, shown = screen != null, back = close)
            when (shown) {
                RootSheet.Settings -> SettingsView(model, onClose = close)
                // The rest arrive with their phases: Add App and Join with Phase 5, sharing with Phase 6.
                else -> Unit
            }
        }
    }
}
