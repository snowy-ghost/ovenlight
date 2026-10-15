package com.snowyghost.ovenlight

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) model.didBecomeActive() },
        )
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
        log = { Log.i(TAG, it) }, debugBuild = BuildConfig.DEBUG,
    )
    val registry = AppRegistry(
        ovenlight, session = nodes::fetch, webData = WebProfiles(context),
        iconPNG = { withContext(Dispatchers.Default) { IconDecoder.png(it) } }, scope = scope,
    )
    val guests = GuestManager(nodes, registry, router, scope, SystemClock::elapsedRealtime, log = { Log.i(TAG, it) })

    init {
        scope.launch {
            registry.appsFlow.drop(1).collect { apps ->
                router.appsChanged(apps)
                guests.collectOrphans()
            }
        }
        scope.launch { nodes.owner.discovered.collect { registry.mergeDiscovered(it) } }
        // Opening an app, from anywhere, clears its new dot.
        scope.launch { router.openApp.collect { app -> app?.let { registry.markOpened(it.id) } } }
        // Signed in, but the tailnet approves each new device: the sign-in page stays, so the
        // owner can carry on in Tailscale's pages, and closes once the phone is approved.
        scope.launch {
            nodes.owner.state.collect { state ->
                if (state !is NodeState.NeedsLogin && state != NodeState.AwaitingApproval && router.sheet.value is RootSheet.Login) router.sheet.value = null
            }
        }
        // An app a link asked for while locked opens once Ovenlight unlocks.
        scope.launch { lock.isLocked.drop(1).collect { locked -> if (!locked) router.appsChanged(registry.apps) } }
        scope.launch {
            // Leftovers from apps removed while their web view was still closing.
            delay(5.seconds)
            registry.sweepUnusedDataStores()
        }
    }

    /** Ovenlight came to the front. */
    fun didBecomeActive() {
        // A launch before the first unlock after a reboot couldn't read the saved lists.
        registry.reloadIfFailed()
        nodes.memberships.reloadIfFailed()
        scope.launch {
            nodes.didBecomeActive()
            guests.resumePending()
            // The launcher has no pull to refresh; Ovenlight looks for new apps each time it
            // comes to the front instead.
            nodes.rediscover()
            registry.refreshAllMissingIcons()
        }
    }

    private companion object {
        const val TAG = "Ovenlight"
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

    /**
     * Also called on unlock, for an app a link asked for while locked. True when it opens an app
     * other than the one open.
     */
    fun appsChanged(apps: List<WebApp>): Boolean {
        if (isLocked()) return false
        val name = pendingName ?: return false
        val app = apps.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        pendingName = null
        val another = openApp.value?.id != app.id
        open(app)
        return another
    }
}

/** The sheets the root presents. */
sealed class RootSheet {
    abstract val id: String

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
