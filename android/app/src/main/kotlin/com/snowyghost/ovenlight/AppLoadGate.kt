package com.snowyghost.ovenlight

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When an app reached through Ovenlight's node may load its page. Opening the app shows its
 * placeholder at once; the first navigation waits until the node is ready and a probe to that
 * app's host through the node's current proxy answers, so an app that can't be reached shows
 * Can't Reach rather than a failed page.
 */
enum class AppLoadGate {
    /** Ovenlight's own connection isn't ready. */
    Waiting,

    /** Checking that the app's host answers through the current proxy. */
    Probing,

    /** The page may load, and did. */
    Ready,

    /** The host didn't answer in time. */
    Unreachable,

    /** The phone has no network, so nothing was tried; it tries again when one comes. */
    Offline,
    ;

    enum class Event {
        NodeReady,
        NodeNotReady,
        Reached,
        GaveUp,

        /** Try Again, or Reload from the app menu. */
        Retry,

        /** The first navigation failed at the connection level; check the path again. */
        LoadFailed,

        /** The app's computer, listed offline when the gate gave up, answered a quiet retry. */
        Woke,

        /** The phone has no network. */
        PhoneOffline,

        /** The phone has a network again. */
        PhoneOnline,
    }

    /** Written as iOS writes the gate, for Report a Problem's Opening line. */
    val logDescription: String get() = name.replaceFirstChar { it.lowercase() }

    fun next(event: Event): AppLoadGate = when {
        event == Event.Retry -> Waiting
        this == Ready && event == Event.LoadFailed -> Waiting
        this == Unreachable && event == Event.Woke -> Ready
        this == Offline && event == Event.PhoneOnline -> Waiting
        // A loaded page stays put through node restarts; a gate that gave up waits for Retry,
        // and one that found the phone offline for Retry or a network.
        this == Ready || this == Unreachable || this == Offline -> this
        event == Event.PhoneOffline -> Offline
        this == Waiting && event == Event.NodeReady -> Probing
        this == Probing && event == Event.NodeNotReady -> Waiting
        this == Probing && event == Event.Reached -> Ready
        event == Event.GaveUp -> Unreachable
        else -> this
    }

    companion object {
        /** How long the app's host may take to answer once the node is ready. */
        val reachTimeout: Duration = 10.seconds

        /** How long to wait for the node itself before giving up on this app. */
        val nodeTimeout: Duration = 30.seconds

        /**
         * Whether a node not ready in [state] means the phone's own connection: one that's
         * retrying is up, and only the computers didn't answer.
         */
        fun neverConnected(state: NodeState): Boolean = state !is NodeState.Retrying

        /** Between quiet retries of an app whose computer is listed offline. */
        val quietRetryInterval: Duration = 3.seconds

        /**
         * The connector answered for the app, but the app itself isn't running on its computer:
         * every released connector sends a plain-text 502 with the code `unavailable`. The probe
         * can't tell, since the connector answers it.
         */
        fun appIsDown(status: Int, header: String?): Boolean =
            status == 502 && ConnectorError(header, ByteArray(0)) == ConnectorError.Unavailable

        /**
         * Before each new try of a first page whose app is down; then Ovenlight says so itself,
         * rather than showing the connector's text.
         */
        val appDownRetries: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds)

        /** Report a Problem's words for a first page that came back down after its retries ([appIsDown]). */
        const val appDownFailure = "app not responding (502 unavailable)"

        /**
         * Report a Problem's words for a first page that failed to load: WebView's name for the
         * error (its description, such as `net::ERR_TOO_MANY_REDIRECTS`), else its code. Never an
         * address, or any other text.
         */
        fun loadFailure(code: Int, description: String): String = "page failed (${description.takeIf(netError::matches) ?: "error $code"})"

        private val netError = Regex("net::ERR_[A-Z0-9_]+")
    }
}

/**
 * The gate's [AppLoadGate.nodeTimeout], counted only while the node is busy: a sign-in or an
 * approval waits for the owner as long as they take, and the time starts over after one.
 */
class NodeDeadline(private val clock: TimeSource = TimeSource.Monotonic) {
    private var busySince: TimeMark? = null

    /** What's left for a node now in [state]; no limit while it isn't busy. */
    fun left(state: NodeState): Duration {
        if (!state.isBusy) {
            restart()
            return Duration.INFINITE
        }
        val since = busySince ?: clock.markNow().also { busySince = it }
        return AppLoadGate.nodeTimeout - since.elapsedNow()
    }

    fun restart() {
        busySince = null
    }
}

/**
 * Words for connection states that anyone may see. None of them names Tailscale or the tailnet,
 * so a guest, who never signs in, can be shown every one; the owner's own apps get their own
 * words for a few. Copy that asks the owner to act on their Tailscale account lives only in the
 * sign-in card, Settings and the approval prompt over the owner's apps.
 */
object ConnectionCopy {
    const val connecting = "Connecting…"
    const val stopped = "Ovenlight lost its connection."
    const val couldNotStart = "Ovenlight couldn’t connect."

    fun connecting(to: String) = "Connecting to $to…"
    fun cantReach(app: String) = "Can’t reach $app"
    /** For an app someone shared, [owner] is who to tell. */
    fun cantReachDetail(owner: String?) = owner?.let { "$it’s computer may be asleep or offline. Try again in a moment, or let $it know." }
        ?: "Its computer may be asleep or offline. Try again in a moment."

    /**
     * [cantReachDetail], or for the owner's own app what to do: on its computer, or when its route
     * is [refused], on this phone. When the phone's own connection never came up, it says so.
     */
    fun cantReachDetail(app: WebApp, refused: Boolean = false, neverConnected: Boolean = false) = when {
        neverConnected -> notConnected
        app.isShared -> cantReachDetail(app.sharedBy)
        refused -> "${app.name} isn’t on the account you’re signed in to. Sign in with the account it’s on, or remove it."
        else -> "Your computer may be asleep or offline. Wake it, or run ovenlight doctor on it, then try again."
    }

    /** The phone's own connection never came up, so no computer was asked. */
    const val notConnected = "$couldNotStart Check that you’re online, then try again."
    const val offline = "You’re offline"
    const val offlineDetail = "Connect to Wi-Fi or mobile data, and Ovenlight tries again."
    /** For an app someone shared, [owner] is who to tell. */
    fun notResponding(app: String, owner: String?) =
        owner?.let { "$app isn’t responding on $it’s computer right now. Try again in a moment, or let $it know." }
            ?: "$app isn’t responding on its computer right now. Try again in a moment."

    /** [notResponding], or for the owner's own app what to run on its computer. */
    fun notResponding(app: WebApp) =
        if (app.isShared) notResponding(app.name, app.sharedBy) else "${app.name} isn’t running on your computer. Run ovenlight doctor there, then try again."
    const val pageFailed = "This page couldn’t load. Try again in a moment."
    fun signIn(toOpen: String) = "Sign in to open $toOpen"

    // Apps someone shared, and joining them. Guests see all of these.

    fun sharedBy(owner: String) = "from $owner"
    fun joinTitle(owner: String, app: String) = "Join $owner’s $app?"
    fun joinDetail(owner: String, app: String, to: String): String {
        val who = if (to.isEmpty()) "$owner invited you." else "$owner invited you as $to."
        return "$who $app runs on $owner’s computer. You don’t need an account."
    }
    fun appAddress(host: String) = "Address: $host"
    fun privateServer(server: String, owner: String) =
        "This invite uses $server, a connection server $owner chose. Join only if you know this invite came from $owner."
    fun joining(owner: String, app: String) = "Joining $owner’s $app…"
    const val joiningDetail = "This can take up to a minute."
    fun joined(app: String) = "$app is ready"
    const val noLongerSharedTitle = "No longer shared with you"
    fun noLongerShared(apps: List<String>, owner: String): String {
        val them = if (apps.size == 1) "it and everything it" else "them and everything they"
        return "$owner stopped sharing ${listed(apps)} with you, so Ovenlight removed $them stored on this phone."
    }
    const val joinWithInvite = "Join with invite"
    const val invitePrompt = "Paste the invite link you were sent, or scan its code."
    const val feedbackSent = "Feedback sent"
    fun feedbackSentDetail(to: String?) = to?.let { "$it will see it." } ?: "It’s in your feedback in People & sharing."
    const val feedbackFailed = "Couldn’t send feedback"
    const val feedbackTooMany = "That’s a lot of feedback for one hour. Try again later."
    const val feedbackNetwork = "Ovenlight couldn’t reach the app’s computer. Try again in a moment."
    const val feedbackOffline = "You’re offline. Your note is kept, so send it again once you’re connected."
    const val reportProblem = "Report a problem"
    fun leaveApps(owner: String) = "Leave $owner’s apps"
    fun leaveTitle(owner: String) = "Leave $owner’s apps?"
    fun leaveDetail(owner: String) =
        "$owner’s apps and everything they stored on this phone will be removed. You’ll need a new invite from $owner to open them again."
    fun removeSharedDetail(owner: String) =
        "This removes the app and everything it stored on this phone. You’ll need a new invite from $owner to open it again."

    /** "A", "A and B", "A, B, and C", as iOS's list formatter writes English. */
    private fun listed(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }
}
