package com.snowyghost.ovenlight

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    }

    /** Written as iOS writes the gate, for Report a Problem's Opening line. */
    val logDescription: String get() = name.replaceFirstChar { it.lowercase() }

    fun next(event: Event): AppLoadGate = when {
        event == Event.Retry -> Waiting
        this == Ready && event == Event.LoadFailed -> Waiting
        this == Unreachable && event == Event.Woke -> Ready
        // A loaded page stays put through node restarts; a gate that gave up waits for Retry.
        this == Ready || this == Unreachable -> this
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

        /** Between quiet retries of an app whose computer is listed offline. */
        val quietRetryInterval: Duration = 3.seconds

        /**
         * The connector answered for the app, but the app itself isn't running on its computer:
         * every released connector sends a plain-text 502 with the code `unavailable`. The probe
         * can't tell, since the connector answers it.
         */
        fun appIsDown(status: Int?, header: String?): Boolean =
            status == 502 && ConnectorError(header, ByteArray(0)) == ConnectorError.Unavailable

        /**
         * Before each new try of a first page whose app is down; then Ovenlight says so itself,
         * rather than showing the connector's text.
         */
        val appDownRetries: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds)
    }
}

/**
 * Words for connection states that anyone may see. None of them names Tailscale or the tailnet,
 * so a guest, who never signs in, can be shown every one. Copy that asks the owner to act on
 * their Tailscale account lives only in the sign-in card, Settings and the approval prompt over
 * the owner's apps.
 */
object ConnectionCopy {
    const val connecting = "Connecting…"
    const val stopped = "Ovenlight lost its connection."
    const val couldNotStart = "Ovenlight couldn't connect."

    fun connecting(to: String) = "Connecting to $to…"
    fun cantReach(app: String) = "Can't reach $app"
    const val cantReachDetail = "Its computer may be asleep or offline. Try again in a moment."
    fun notResponding(app: String) = "$app isn't responding on its computer right now. Try again in a moment."
    const val pageFailed = "This page couldn't load. Try again in a moment."
    fun signIn(toOpen: String) = "Sign in to open $toOpen"

    // Apps someone shared, and joining them. Guests see all of these.

    fun sharedBy(owner: String) = "from $owner"
    fun joinTitle(owner: String, app: String) = "Join $owner's $app?"
    fun joinDetail(owner: String, app: String, to: String): String {
        val who = if (to.isEmpty()) "$owner invited you." else "$owner invited you as $to."
        return "$who $app runs on $owner's computer. You don't need an account."
    }
    fun appAddress(host: String) = "Address: $host"
    fun privateServer(server: String, owner: String) =
        "This invite uses $server, a connection server $owner chose. Join only if you know this invite came from $owner."
    fun joining(owner: String, app: String) = "Joining $owner's $app…"
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
    fun feedbackSentDetail(to: String?) = to?.let { "$it will see it." } ?: "It's in your feedback in People & Sharing."
    const val feedbackFailed = "Couldn't send feedback"
    const val feedbackTooMany = "That's a lot of feedback for one hour. Try again later."
    const val feedbackNetwork = "Ovenlight couldn't reach the app's computer. Try again in a moment."
    const val reportProblem = "Report a problem"
    fun leaveApps(owner: String) = "Leave $owner's apps"
    fun leaveTitle(owner: String) = "Leave $owner's apps?"
    fun leaveDetail(owner: String) =
        "$owner's apps and everything they stored on this phone will be removed. You'll need a new invite from $owner to open them again."
    fun removeSharedDetail(owner: String) =
        "This removes the app and everything it stored on this phone. You'll need a new invite from $owner to open it again."

    /** "A", "A and B", "A, B, and C", as iOS's list formatter writes English. */
    private fun listed(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }
}
