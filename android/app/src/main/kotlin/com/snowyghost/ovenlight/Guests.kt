package com.snowyghost.ovenlight

import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Why joining someone's app didn't work. Every message is neutral: guests see them. */
enum class JoinError {
    /** The control server refused the key because it was used before. */
    KeyUsed,
    Expired,

    /** The control server refused the key for another reason. */
    KeyRefused,

    /** The connector didn't accept the claim (the invite was canceled or never existed). */
    NotInvited,
    UsedOnAnotherDevice,

    /** The invite is for someone else, and this phone is already set up as another guest. */
    SomeoneElses,

    /** Couldn't reach the owner's network or app in time. The invite is kept to retry. */
    Network,
    Canceled,

    /** The invite is for one of this phone's own apps. */
    OwnApp,

    /** The key registered the node in a network other than the one the invite names. */
    WrongNetwork,
    ;

    /** Kept for Try Again: nothing says the invite itself is bad. */
    val isRetryable: Boolean get() = this == Network

    fun title(owner: String, app: String): String = when (this) {
        KeyUsed -> "This invite was already used"
        // A connector expires an invite's key once it's claimed, so "expired" also means used.
        Expired -> "This invite no longer works"
        KeyRefused, NotInvited -> "This invite didn’t work"
        UsedOnAnotherDevice -> "This invite was used on another phone"
        SomeoneElses -> "This invite is for someone else"
        Network -> "Can’t reach $owner’s $app"
        Canceled -> "Joining canceled"
        OwnApp -> "$app is already yours"
        WrongNetwork -> "This invite leads somewhere else"
    }

    fun message(owner: String, app: String): String = when (this) {
        KeyUsed, UsedOnAnotherDevice -> "Each invite works once. Ask $owner for a new one."
        Expired -> "Invites expire, and each works only once. Ask $owner for a new one."
        KeyRefused, NotInvited -> "$owner may have canceled it. Ask $owner for a new one."
        SomeoneElses -> "This phone already joined $owner's apps under another name. Ask $owner to invite that name instead."
        Network -> "Check that you're online. $owner's computer may also be asleep or offline. Try again in a moment."
        Canceled -> "Nothing was added to Ovenlight."
        OwnApp -> "This invite is for one of your own apps. Open it from your apps instead."
        WrongNetwork -> "It didn't lead to $owner's $app, so Ovenlight disconnected and added nothing. Ask $owner for a new one."
    }

    companion object {
        /**
         * Reads the error the Go core's `up()` fails with, which carries the control server's
         * text unchanged: "authkey already used", "authkey expired" (Headscale), "invalid key:
         * ..." (Tailscale).
         */
        operator fun invoke(upError: Throwable): JoinError = invoke(upMessage = upError.message ?: upError.toString())

        operator fun invoke(upMessage: String): JoinError {
            val text = upMessage.lowercase()
            return when {
                "expired" in text -> Expired
                "already used" in text || "used up" in text -> KeyUsed
                "invalid key" in text || "authkey" in text || "auth key" in text || "not valid" in text -> KeyRefused
                "closed" in text -> Canceled
                else -> Network
            }
        }
    }
}

/** Where accepting one invite is. */
sealed class JoinPhase {
    data object Joining : JoinPhase()
    data object Claiming : JoinPhase()
    data class Joined(val app: UUID) : JoinPhase()
    data class Failed(val error: JoinError) : JoinPhase()
}

/** The connector's answer to `POST /__ovenlight/claim`. */
@Serializable
data class ClaimResponse(val app: String? = null, val appName: String? = null, val owner: String? = null) {
    data class Names(val owner: String, val appName: String, val app: String)

    /** The names to save, cleaned like an invite's, with the invite's for any left out. */
    fun names(invite: InviteLink): Names =
        Names(InviteLink.label(owner) ?: invite.owner, InviteLink.label(appName) ?: invite.name, InviteLink.label(app) ?: invite.app)
}

object Claim {
    sealed class Step {
        data class Claimed(val response: ClaimResponse) : Step()

        /** Not yet: the connector hasn't seen this device, or answered with a server error. */
        data object Retry : Step()
        data class Failed(val error: JoinError) : Step()
    }

    const val path = "/__ovenlight/claim"

    /** How long the manager keeps trying one claim (`GuestManager.claimTime` on iOS). */
    val claimTime: Duration = 25.seconds

    sealed class Landing {
        data object InNetwork : Landing()

        /** The node couldn't say (it was just replaced, or didn't answer): try again later. */
        data object Unknown : Landing()

        /**
         * In another network. [leave]: this join registered the node, so it logs out; a node
         * that joined before keeps its login and the owner's other apps.
         */
        data class Elsewhere(val leave: Boolean) : Landing()
    }

    /** Where a guest node is, against the network the invite names. */
    fun landing(invite: InviteLink, suffix: String?, registeredNow: Boolean): Landing {
        if (suffix.isNullOrEmpty()) return Landing.Unknown
        return if (invite.isNetwork(suffix)) Landing.InNetwork else Landing.Elsewhere(leave = registeredNow)
    }

    fun request(invite: InviteLink): Request {
        val body = buildJsonObject { put("key", invite.key) }.toString()
        return Request.Builder()
            .url("https://${invite.host}$path")
            .post(body.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Reads one answer. Only the connector's codes are final: `not_invited`, `used_elsewhere`,
     * `other_person`, and a request it refuses (`bad_request`, `not_found`). Anything else,
     * `unknown_caller` (its netmap doesn't have the new device yet), server errors and answers
     * without a code among them, is worth another try.
     */
    fun interpret(status: Int, header: String? = null, body: ByteArray): Step {
        if (status == 200) {
            val response = try {
                ovenlightJSON.decodeFromString(ClaimResponse.serializer(), strictUTF8(body))
            } catch (_: Exception) {
                return Step.Retry
            }
            return Step.Claimed(response)
        }
        return when (ConnectorError(header, body)) {
            ConnectorError.UsedElsewhere -> Step.Failed(JoinError.UsedOnAnotherDevice)
            ConnectorError.OtherPerson -> Step.Failed(JoinError.SomeoneElses)
            ConnectorError.NotInvited, ConnectorError.BadRequest, ConnectorError.NotFound -> Step.Failed(JoinError.NotInvited)
            else -> Step.Retry
        }
    }

    /**
     * The wait before claim attempt [attempt] (from 0) is tried again: doubling from half a
     * second, at most 4 (the manager's loop on iOS).
     */
    fun retryDelay(attempt: Int): Duration = min(0.5 * 2.0.pow(attempt), 4.0).seconds
}

/** Someone's apps stopped being shared with this phone. */
data class AccessEndedNotice(val owner: String, val apps: List<String>, val id: UUID = UUID.randomUUID()) {
    val title: String get() = ConnectionCopy.noLongerSharedTitle
    val message: String get() = ConnectionCopy.noLongerShared(apps, owner)

    companion object {
        /**
         * The notice for apps taken away together, named by the first one's owner, or none when
         * every one of them is already gone from [listed], the registry's apps, so two sweeps of
         * the same app say so once (`GuestManager.accessEnded` on iOS).
         */
        fun of(apps: List<WebApp>, listed: List<WebApp>): AccessEndedNotice? {
            val remaining = apps.filter { app -> listed.any { it.id == app.id } }
            val first = remaining.firstOrNull() ?: return null
            return AccessEndedNotice(first.sharedBy ?: "Its owner", remaining.map { it.name })
        }
    }
}

/** What accepting an invite would do, for the confirmation sheet (`GuestManager.Preflight` on iOS). */
sealed class Preflight {
    data object Ready : Preflight()
    data class AlreadyAdded(val app: WebApp) : Preflight()
    data object OwnApp : Preflight()

    companion object {
        /**
         * [ownerSuffix]: the owner's own tailnet, only while owner mode is on and an account is
         * signed in. [membership] looks a membership up by ID.
         */
        fun of(invite: InviteLink, ownerSuffix: String?, apps: List<WebApp>, membership: (String) -> Membership?): Preflight {
            if (ownerSuffix != null && invite.isNetwork(ownerSuffix)) return OwnApp
            val app = apps.firstOrNull { app ->
                val m = app.membershipID?.let(membership) ?: return@firstOrNull false
                m.matches(invite) && Discovery.normalizedHost(app.host) == invite.host
            }
            return if (app != null) AlreadyAdded(app) else Ready
        }
    }
}

/**
 * What a failed join leaves: the phase to show, and whether the invite stops waiting
 * (`GuestManager.fail` on iOS). An invite for an app already here that couldn't be claimed
 * again (an old link) still leaves the app, so it reads as joined rather than failed. Only a
 * retryable failure with no such app keeps the invite for Try Again.
 */
data class JoinFailure(val phase: JoinPhase, val dropsInvite: Boolean) {
    companion object {
        /** [invite]: the failed invite while it's still waiting, else null. */
        fun of(error: JoinError, invite: InviteLink?, membershipID: String, apps: List<WebApp>): JoinFailure {
            val host = invite?.let { Discovery.normalizedHost(it.host) }
            val existing = if (error == JoinError.WrongNetwork) {
                null
            } else {
                apps.firstOrNull { it.membershipID == membershipID && Discovery.normalizedHost(it.host) == host }
            }
            val phase = existing?.let { JoinPhase.Joined(it.id) } ?: JoinPhase.Failed(error)
            return JoinFailure(phase, dropsInvite = !(error.isRetryable && existing == null))
        }
    }
}

/**
 * Whether a node that hasn't joined registers with this invite's key: the first waiting
 * invite's (see [Membership.joinKey]).
 */
fun GuestCleanup.isRegistering(membership: Membership, with: String): Boolean =
    !membership.joined && membership.pendingClaims.firstOrNull()?.invite?.invite == with

/** The hosts a guest node probes: its membership's apps, then the invites still being claimed (`GuestManager.wire` on iOS). */
fun GuestCleanup.guestHosts(membership: Membership?, membershipID: String, apps: List<WebApp>): List<String> =
    apps.filter { it.membershipID == membershipID }.map { it.host } + (membership?.pendingClaims?.map { it.invite.host } ?: emptyList())

/** Shared apps whose membership is gone, which can't be reached or checked any more (`GuestManager.collectOrphans` on iOS). */
fun GuestCleanup.stranded(apps: List<WebApp>, membership: (String) -> Membership?): List<WebApp> =
    apps.filter { app -> app.isShared && app.membershipID?.let(membership) == null }

/** The membership with [invite] waiting, added unless it already is (`GuestManager.accept` on iOS). */
fun Membership.awaiting(invite: InviteLink, accepted: Instant = savedNow()): Membership =
    if (pendingClaims.any { it.invite.invite == invite.invite }) this else copy(pendingClaims = pendingClaims + PendingClaim(invite, accepted))

/**
 * The membership with [inviteID]'s claim first, so a node that hasn't joined registers with
 * its key (`GuestManager.join` on iOS, which does this only while the membership hasn't joined,
 * then restarts a node started with another).
 */
fun Membership.puttingFirst(inviteID: String): Membership {
    val index = pendingClaims.indexOfFirst { it.invite.invite == inviteID }
    if (index <= 0) return this
    val claims = pendingClaims.toMutableList()
    claims.add(0, claims.removeAt(index))
    return copy(pendingClaims = claims)
}

/**
 * The membership once [invite]'s claim succeeded: the invite stops waiting and the owner's
 * name is the one the claim gave; the guest's name changes only when the invite names one
 * (`GuestManager.join` on iOS).
 */
fun Membership.claimed(invite: InviteLink, owner: String): Membership = copy(
    pendingClaims = pendingClaims.filter { it.invite.invite != invite.invite },
    ownerName = owner,
    guestName = invite.to.ifEmpty { guestName },
)
