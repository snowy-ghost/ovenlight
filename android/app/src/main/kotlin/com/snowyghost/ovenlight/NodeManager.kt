package com.snowyghost.ovenlight

import java.net.URI
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Where one of Ovenlight's connections is: the owner's own tailnet, or a guest node in someone
 * else's. Drives the launcher's "connecting" state, the login prompt, and when apps may load.
 */
sealed class NodeState {
    data object Idle : NodeState()
    data object Starting : NodeState()
    data class NeedsLogin(val url: URI) : NodeState()

    /** Registered, waiting for the network's owner to approve this phone. */
    data object AwaitingApproval : NodeState()
    data object Connecting : NodeState()
    data object Ready : NodeState()
    data class Retrying(val attempt: Int) : NodeState()
    data class Failed(val message: String) : NodeState()

    val isBusy: Boolean get() = this == Starting || this == Connecting || this is Retrying

    val summary: String
        get() = when (this) {
            Idle -> "Off"
            Starting, Connecting, is Retrying -> "Connecting"
            is NeedsLogin -> "Not signed in"
            AwaitingApproval -> "Waiting for approval"
            Ready -> "Connected"
            is Failed -> message
        }

    /**
     * For the log, which is public: the sign-in link is left out, since whoever opens it first
     * can sign the node in to their own account. Written as iOS writes its states.
     */
    val logDescription: String
        get() = when (this) {
            Idle -> "idle"
            Starting -> "starting"
            is NeedsLogin -> "needsLogin"
            AwaitingApproval -> "awaitingApproval"
            Connecting -> "connecting"
            Ready -> "ready"
            is Retrying -> "retrying(attempt: $attempt)"
            is Failed -> "failed(${swiftQuoted(message)})"
        }

    /** The text as Swift's `String(describing:)` quotes it inside an enum's payload. */
    private fun swiftQuoted(text: String): String = buildString {
        append('"')
        text.codePoints().forEach { c ->
            when {
                c == '\\'.code -> append("\\\\")
                c == '"'.code -> append("\\\"")
                c == '\''.code -> append("\\'")
                c == '\n'.code -> append("\\n")
                c == '\r'.code -> append("\\r")
                c == '\t'.code -> append("\\t")
                c == 0 -> append("\\0")
                Character.getType(c) == Character.CONTROL.toInt() -> append("\\u{").append(Integer.toHexString(c)).append('}')
                else -> appendCodePoint(c)
            }
        }
        append('"')
    }
}

/**
 * Inputs to the state machine: lifecycle calls, the node's backend state, and the result of the
 * reachability check that runs once the node is up.
 */
sealed class NodeEvent {
    data object Start : NodeEvent()
    data object Stop : NodeEvent()
    data class Backend(val state: String, val authURL: URI?) : NodeEvent()
    data object Reachable : NodeEvent()
    data object Unreachable : NodeEvent()
    data class Error(val message: String) : NodeEvent()
}

object NodeMachine {
    /**
     * Checks after the node is up before Ovenlight stops waiting and lets apps report their own
     * errors. The first WireGuard handshake after a restart is sometimes lost.
     */
    const val maxRetries = 5

    fun next(state: NodeState, event: NodeEvent): NodeState = when (event) {
        NodeEvent.Start -> NodeState.Starting
        NodeEvent.Stop -> NodeState.Idle
        is NodeEvent.Error -> NodeState.Failed(event.message)
        NodeEvent.Reachable -> when (state) {
            NodeState.Connecting, is NodeState.Retrying, NodeState.Ready -> NodeState.Ready
            else -> state
        }
        NodeEvent.Unreachable -> when (state) {
            NodeState.Connecting -> NodeState.Retrying(1)
            is NodeState.Retrying -> if (state.attempt < maxRetries) NodeState.Retrying(state.attempt + 1) else NodeState.Ready
            else -> state
        }
        is NodeEvent.Backend -> when (event.state) {
            "NeedsLogin" -> when {
                event.authURL != null -> NodeState.NeedsLogin(event.authURL)
                state is NodeState.NeedsLogin -> state
                else -> NodeState.Starting
            }
            "NeedsMachineAuth" -> NodeState.AwaitingApproval
            "Running" -> when (state) {
                NodeState.Ready, is NodeState.Retrying, NodeState.Connecting -> state
                else -> NodeState.Connecting
            }
            "Stopped" -> NodeState.Failed(ConnectionCopy.stopped)
            // NoState, Starting
            else -> when (state) {
                is NodeState.NeedsLogin, is NodeState.Failed, NodeState.AwaitingApproval -> NodeState.Connecting
                else -> state
            }
        }
    }
}

/** The parts of the node's status (the Go core's ipnstate.Status JSON) Ovenlight uses. */
@Serializable
data class TailnetStatus(
    @SerialName("BackendState") val backendState: String = "NoState",
    @SerialName("AuthURL") val authURL: String = "",
    @SerialName("Self") val selfNode: Peer? = null,
    @SerialName("Peer") private val peersByKey: Map<String, Peer> = emptyMap(),
    @SerialName("User") val users: Map<String, User> = emptyMap(),
    @SerialName("CurrentTailnet") val tailnet: Tailnet? = null,
) {
    @Serializable
    data class Peer(
        @SerialName("HostName") val hostName: String = "",
        @SerialName("DNSName") val dnsName: String = "",
        @SerialName("OS") val os: String = "",
        @SerialName("Online") val online: Boolean = false,
        @SerialName("UserID") val userID: Long = 0,
        /** ACL tags; a tagged node belongs to the tailnet, not to a user. */
        @SerialName("Tags") val tags: List<String> = emptyList(),
    )

    @Serializable
    data class User(@SerialName("LoginName") val loginName: String, @SerialName("DisplayName") val displayName: String)

    @Serializable
    data class Tailnet(@SerialName("Name") val name: String, @SerialName("MagicDNSSuffix") val magicDNSSuffix: String)

    @Transient
    val peers: List<Peer> = peersByKey.values.toList()

    /** The signed-in account, for Settings. */
    val account: User? get() = selfNode?.let { users[it.userID.toString()] }

    val event: NodeEvent get() = NodeEvent.Backend(backendState, if (authURL.isEmpty()) null else uri(authURL))

    companion object {
        fun decode(json: String): TailnetStatus? = try {
            ovenlightJSON.decodeFromString(serializer(), json)
        } catch (_: Exception) {
            null
        }
    }
}

/** What a probe of one app through a node found. */
enum class Reach {
    Reached,

    /** The app's connector answered that this device isn't invited (a guest whose access ended, or one that hasn't claimed yet). */
    NotInvited,
    Unreachable,
}

/**
 * Every membership's node. Android's manager comes with the app host; for now this holds the
 * rules it shares with iOS's.
 */
class NodeManager {
    companion object {
        /**
         * The name the tailnet gave this node, which is the one it asked for ([hostName]) unless
         * another device already has it; then the control server adds a suffix.
         */
        fun deviceName(status: TailnetStatus?, hostName: String): String =
            status?.selfNode?.dnsName?.split('.')?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: hostName
    }
}

/** How a guest node checks its apps: `/__ovenlight/whoami` answers 200 for an admitted guest and 403 `not_invited` for anyone else. */
object GuestProbe {
    data class Outcome(val base: Discovery.Outcome = Discovery.Outcome(), val notInvited: Set<String> = emptySet())

    /** [firstAnswer] as in [Discovery.probe]. */
    suspend fun probe(hosts: List<String>, fetch: Fetch?, firstAnswer: () -> Unit = {}): Outcome {
        if (fetch == null || hosts.isEmpty()) return Outcome()
        return coroutineScope {
            val results = Channel<Pair<String, Reach>>(hosts.size)
            for (host in hosts) launch { results.send(host to whoami(host, fetch)) }
            var reachable = false
            val answered = mutableMapOf<String, Boolean>()
            val notInvited = mutableSetOf<String>()
            repeat(hosts.size) {
                val (host, reach) = results.receive()
                val didAnswer = reach != Reach.Unreachable
                if (didAnswer && !reachable) firstAnswer()
                reachable = reachable || didAnswer
                answered[host] = didAnswer
                if (reach == Reach.NotInvited) notInvited += host
            }
            Outcome(Discovery.Outcome(reachable = reachable, answered = answered), notInvited)
        }
    }

    /**
     * The app hosts worth probing: not those whose computer the network lists as offline, which
     * can't answer, once the network is known to report Online ([onlineReported]). Like the
     * owner's offline computers, they are then missing from the answers, so their tiles show
     * Offline at once.
     */
    fun hostsToProbe(hosts: List<String>, status: TailnetStatus?, onlineReported: Boolean): List<String> {
        val normalized = hosts.map(Discovery::normalizedHost).toSet()
        if (!onlineReported) return normalized.sorted()
        return (normalized - Discovery.offlineHosts(normalized, status)).sorted()
    }

    /**
     * Whether the network tells this node its peers' Online state. A control server leaves it out
     * for a node it doesn't tell, and the status then says offline for every peer, so a guest
     * believes it only once it has listed a peer online.
     */
    fun reportsOnline(status: TailnetStatus): Boolean = status.peers.any { it.online }

    suspend fun whoami(host: String, fetch: Fetch): Reach {
        val response = uri("https://$host/__ovenlight/whoami")?.let { fetchOrNull(fetch, it) } ?: return Reach.Unreachable
        return classify(response.status, response.header(ConnectorError.header), response.body)
    }

    /**
     * 403 `not_invited` ends the share for an unclaimed or removed guest; any other answer, even
     * an error page that says "not invited" without the code, means the path works.
     */
    fun classify(status: Int, header: String? = null, body: ByteArray): Reach =
        if (status == 403 && ConnectorError(header, body) == ConnectorError.NotInvited) Reach.NotInvited else Reach.Reached

    /**
     * The app hosts missing from a running node's peers. A node's peers are the nodes its
     * network's policy lets it or them reach, offline ones included, so an app node leaves the
     * list when the owner stops sharing that app with a guest who keeps another (the connector
     * retags the guest's device), and stays in it while its computer is off. Null when the list
     * can't say: the node isn't running, or none of the hosts is in it, which is likelier a
     * partial peer list than every share ended (that removes the device).
     */
    fun absentHosts(hosts: Set<String>, status: TailnetStatus): Set<String>? {
        if (status.backendState != "Running") return null
        val absent = hosts - status.peers.map { Discovery.normalizedHost(it.dnsName) }.toSet()
        return if (absent.size < hosts.size) absent else null
    }
}

/**
 * Looks in a row at a guest's apps, about a minute apart, in which none answered though the
 * network listed some online. A running node never learns that the owner deleted its device
 * (control's refusals only back it off), but a restarted one finds it needs a login, which ends
 * the share (`onLoginLost`). So after a few silent looks the node restarts, then waits twice as
 * long before the next, up to an hour; any answer starts over.
 */
class GuestSilence {
    private var looks = 0
    private var gap = firstRestart

    /** Records one look; true when the node should restart. */
    fun record(probed: List<String>, reachable: Boolean): Boolean {
        if (reachable) {
            looks = 0
            gap = firstRestart
            return false
        }
        // Nothing listed online says nothing: the computers are asleep.
        if (probed.isEmpty()) return false
        looks += 1
        if (looks < gap) return false
        looks = 0
        gap = minOf(gap * 2, maxGap)
        return true
    }

    companion object {
        const val firstRestart = 3
        const val maxGap = 60
    }
}

/**
 * Polls in a row that found each app host missing from a guest node's peers. A host missing for
 * [pollsToEnd] polls, a few minutes, is taken as no longer shared; one back in the list starts
 * over.
 */
class PeerAbsence {
    private var polls: Map<String, Int> = emptyMap()

    /**
     * Records one poll's missing hosts; returns those missing for [pollsToEnd] polls or more,
     * every time, so a host skipped once (still being claimed) is caught later.
     */
    fun record(absent: Set<String>): Set<String> {
        polls = absent.associateWith { (polls[it] ?: 0) + 1 }
        return polls.filterValues { it >= pollsToEnd }.keys
    }

    companion object {
        /** Three minutes at the connected node's poll interval. */
        const val pollsToEnd = 18
    }
}
