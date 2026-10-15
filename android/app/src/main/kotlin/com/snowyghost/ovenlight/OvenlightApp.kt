package com.snowyghost.ovenlight

import android.app.Application
import android.os.SystemClock
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow

class OvenlightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The core's proxy listens before any WebView exists.
        TailnetCore.start(this)
        WebViewSetup.start(this)
        // The lock lives with the process, never in saved instance state, so a configuration
        // change or a restore after process death starts locked.
        lock = LockManager(
            PreferencesStore(this),
            hasSavedData = { hasSavedData(File(filesDir, "Ovenlight"), File(noBackupFilesDir, "tailscale")) },
            now = SystemClock::elapsedRealtime,
        )
        router = Router(isLocked = { lock.holdsLinks })
    }

    companion object {
        lateinit var lock: LockManager
            private set

        lateinit var router: Router
            private set

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
