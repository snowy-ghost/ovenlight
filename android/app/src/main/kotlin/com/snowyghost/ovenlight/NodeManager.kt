package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/** One embedded node as the managers see it: the Go core's node, or a fake in tests. Every call blocks. */
interface TailnetNode {
    /** Returns once the backend runs; signing in, or joining with a key, carries on after. */
    fun start()

    /** Waits until Running: tsnet's own error text, "timed out waiting for the node", or "node closed". */
    fun waitUp(timeoutMillis: Long)
    fun statusJSON(): String
    fun loginInteractive()
    fun logout()

    /** Closes it, keeping its state; a closed node never starts again. */
    fun close()
}

/** What the managers need from the Go core: nodes, and fetches through its proxy. */
interface TailnetClients {
    fun newNode(id: String, directory: File, hostName: String, controlURL: String, authKey: String): TailnetNode

    /** Fetches through the proxy with a route key (app-<store> or node-<membership>). */
    fun fetch(routeKey: String, timeoutMillis: Long, totalMillis: Long): Fetch

    /** The membership is gone: its node- key routes nowhere any more. */
    fun forget(membershipID: String)
}

/**
 * One embedded node, for one membership: the owner's own tailnet, or a guest device in
 * someone else's (no VPN). Pages and Ovenlight's own requests reach it through the core's
 * proxy. Its login state stays in its own folder.
 *
 * Its state belongs to [scope]'s dispatcher (the main thread in the app), and the Go calls,
 * which block, run on [io].
 */
class MembershipNode(
    val id: String,
    /** Null once the membership is gone, so a late start can't bring back a node for it. */
    private val settings: () -> Settings?,
    private val clients: TailnetClients,
    private val stateDirectory: (String) -> File,
    private val preferences: KeyValueStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    /** The owner's node saw its tailnet's MagicDNS suffix. */
    private val rememberOwnerSuffix: (String) -> Unit = {},
) {
    data class Settings(
        val controlURL: String,
        /** Registers a node that has no login yet: a debug key for the owner, the invite's key for a guest that hasn't joined. */
        val authKey: String?,
        val hostName: String,
    )

    val isOwner: Boolean get() = id == Membership.ownerID

    private val mutableState = MutableStateFlow<NodeState>(NodeState.Idle)
    val state: StateFlow<NodeState> = mutableState
    private val mutableStatus = MutableStateFlow<TailnetStatus?>(null)
    val status: StateFlow<TailnetStatus?> = mutableStatus

    /** Apps found on the tailnet in the last reachability check (owner only). */
    private val mutableDiscovered = MutableStateFlow<List<DiscoveredApp>>(emptyList())
    val discovered: StateFlow<List<DiscoveredApp>> = mutableDiscovered

    /** Whether each host answered its last probe; null until the first one. Hosts whose computer is offline aren't probed. */
    private val mutableAnswers = MutableStateFlow<Map<String, Boolean>?>(null)
    val answers: StateFlow<Map<String, Boolean>?> = mutableAnswers

    /** For a guest: the app hosts to probe, its apps and the invites being claimed. */
    var guestHosts: () -> List<String> = { emptyList() }

    /** For a guest: hosts whose connector said "not invited" in a probe. */
    var onNotInvited: (Set<String>) -> Unit = {}

    /** For a guest: the backend has needed a login for a few polls in a row. */
    var onLoginLost: () -> Unit = {}

    /** For a guest: app hosts gone from the node's peers for a few minutes (see [PeerAbsence]). */
    var onPeersGone: (Set<String>) -> Unit = {}

    private var node: TailnetNode? = null
    private var cachedFetch: Fetch? = null
    private var pollJob: Job? = null

    /** Each start or stop waits for the one before, so two nodes never share a folder and a stop never races its start. */
    private val lifecycle = Mutex()
    private var probedHosts: List<String>? = null
    private var lastProbe: Long? = null
    private var loginLostPolls = 0
    private var absence = PeerAbsence()
    private val silence = GuestSilence()

    /** Bumped on every start and stop so work from an older node can't touch the new one. */
    private var generation = 0

    val isRunning: Boolean get() = node != null

    /** The proxy's route key for this node's own requests. */
    private val routeKey: String get() = "node-$id"

    // Lifecycle

    /** Starts the node unless one is running. The core's proxy lives as long as the process, so a running node needs no check. */
    suspend fun ensureRunning() {
        if (node == null) start()
    }

    /** Tries again after a failure. */
    suspend fun restart() {
        stop()
        start()
    }

    /** Starts the node unless it is running, after any start or stop under way. */
    suspend fun start() {
        lifecycle.withLock {
            if (node != null) return
            val settings = settings() ?: return
            generation += 1
            send(NodeEvent.Start)
            launch(settings, generation)
        }
    }

    /** Closes the node, keeping its state on disk. */
    suspend fun stop() {
        generation += 1
        mutableStatus.value = null // so a wait on this node reads the next one's status, not this one's last
        lifecycle.withLock {
            pollJob?.cancel()
            pollJob = null
            cachedFetch = null
            send(NodeEvent.Stop)
            val node = node ?: return
            withContext(io) { runCatching { node.close() }.onFailure { log("$id: close failed: ${it.message}") } }
            this.node = null
        }
    }

    private suspend fun launch(settings: Settings, current: Int) {
        try {
            val directory = stateDirectory(id).apply { mkdirs() }
            val node = withContext(io) {
                clients.newNode(id, directory, settings.hostName, settings.controlURL, settings.authKey.orEmpty()).also { it.start() }
            }
            if (current != generation) {
                withContext(io) { runCatching { node.close() } }
                return
            }
            this.node = node
            cachedFetch = null
            loginLostPolls = 0
            absence = PeerAbsence()
            log("$id: node started")
            pollJob = scope.launch { poll(current) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$id: node start failed: ${e.message}")
            send(NodeEvent.Error(ConnectionCopy.couldNotStart))
        }
    }

    /**
     * Waits for the node to register and come up, which a guest's first join needs: the wait
     * reports an invite key the control server refused (used or expired), which the status
     * never says. Null when it came up, else tsnet's text, which JoinError reads.
     */
    suspend fun waitUntilUp(timeoutMillis: Long): String? {
        val node = node ?: return "no node"
        val current = generation
        return try {
            withContext(io) { node.waitUp(timeoutMillis) }
            if (current == generation) null else "node closed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current != generation) "node closed" else e.message ?: "no node"
        }
    }

    /**
     * The MagicDNS suffix of the owner's tailnet: from the node's status, or as last seen, so an
     * app opened before the node reports still routes through it. It outlives Sign Out and goes
     * with Stop Using My Own Computers. Null for a guest.
     */
    val magicDNSSuffixSeen: String?
        get() {
            if (!isOwner) return null
            mutableStatus.value?.tailnet?.magicDNSSuffix?.takeIf { it.isNotEmpty() }?.let { return it }
            return preferences.getString(NodeManager.OWNER_SUFFIX_KEY)
        }

    /** The MagicDNS suffix of the network the node is in, read from the node now. */
    suspend fun magicDNSSuffix(): String? {
        val node = node ?: return null
        return withContext(io) { runCatching { node.statusJSON() }.getOrNull() }?.let(TailnetStatus::decode)?.tailnet?.magicDNSSuffix
    }

    /** Logs the node out of its network, closes it and deletes its state. */
    suspend fun logOutAndForget() {
        node?.let { node -> withContext(io) { runCatching { node.logout() } } }
        stop()
        withContext(io) { stateDirectory(id).deleteRecursively() }
        preferences.remove(onlineReportedKey)
        mutableAnswers.value = null
        mutableDiscovered.value = emptyList()
    }

    // Status and reachability

    private suspend fun poll(current: Int) {
        while (current == generation) {
            val node = node ?: return
            val status = withContext(io) { runCatching { node.statusJSON() }.getOrNull() }?.let(TailnetStatus::decode)
            if (status != null && current == generation) {
                // Polls repeat while nothing changes; only a change should redraw.
                if (status != mutableStatus.value) {
                    mutableStatus.value = status
                    if (isOwner) status.tailnet?.magicDNSSuffix?.takeIf { it.isNotEmpty() }?.let(rememberOwnerSuffix)
                }
                send(status.event)
                if (isOwner) {
                    watchOwnerLogin(status, node)
                } else {
                    watchLogin(status.backendState)
                    watchPeers(status)
                    if (!onlineReported && GuestProbe.reportsOnline(status)) preferences.putBoolean(onlineReportedKey, true)
                }
                val state = mutableState.value
                if (state == NodeState.Connecting || state is NodeState.Retrying) {
                    checkReachability(current)
                    continue
                }
                if (state == NodeState.Ready) {
                    // Look again when a computer comes online or goes away (the owner's new apps,
                    // and tiles), and once a minute regardless: a host that missed one look, as a
                    // newly published app's first answer can, would otherwise wait for the next change.
                    val hostsChanged = probeHosts != probedHosts
                    if (hostsChanged || lastProbe?.let { now() - it > 60_000 } == true) rediscover()
                }
            }
            delay(if (mutableState.value == NodeState.Ready) 10_000 else 1_000)
        }
    }

    private fun watchLogin(backend: String) {
        loginLostPolls = if (backend == "NeedsLogin") loginLostPolls + 1 else 0
        if (loginLostPolls == 3) onLoginLost()
    }

    /**
     * tsnet asks for a sign-in page only as the node starts, so an owner node whose key expires
     * while it runs would wait at NeedsLogin with no page. After some polls like that it asks
     * once, and the page shows up in the status as at first sign-in. It waits longer than a
     * guest's count, since the first sign-in's own page can take a few seconds.
     */
    private fun watchOwnerLogin(status: TailnetStatus, node: TailnetNode) {
        val noPage = status.backendState == "NeedsLogin" && status.authURL.isEmpty()
        loginLostPolls = if (noPage) loginLostPolls + 1 else 0
        if (loginLostPolls != 10) return
        log("owner: needs a login and has no sign-in page, asking for one")
        scope.launch(io) { runCatching { node.loginInteractive() } }
    }

    /** Counts polls that found an app host missing from the peers of a connected node; any poll that can't tell starts the count over. */
    private fun watchPeers(status: TailnetStatus) {
        val hosts = guestHosts().map(Discovery::normalizedHost).toSet()
        val absent = if (mutableState.value == NodeState.Ready) GuestProbe.absentHosts(hosts, status) else null
        if (absent == null) {
            absence = PeerAbsence()
            return
        }
        val gone = absence.record(absent)
        if (gone.isNotEmpty()) onPeersGone(gone)
    }

    private val probeHosts: List<String>
        get() = if (isOwner) Discovery.candidateHosts(mutableStatus.value)
        else GuestProbe.hostsToProbe(guestHosts(), mutableStatus.value, onlineReported)

    /** The network lists this app's computer as offline. It may still answer, so this never stops a probe. */
    fun isListedOffline(host: String): Boolean = onlineReported && Discovery.offlineHosts(setOf(host), mutableStatus.value).isNotEmpty()

    /**
     * Whether a peer's Online in the status can be believed: always for the owner, and for a
     * guest once its network has listed a peer online (see [GuestProbe.reportsOnline]),
     * remembered across launches.
     */
    private val onlineReported: Boolean get() = isOwner || preferences.getBoolean(onlineReportedKey)
    private val onlineReportedKey: String get() = "onlineReported.$id"

    private suspend fun probe(hosts: List<String>, firstAnswer: () -> Unit = {}): Discovery.Outcome {
        lastProbe = now()
        if (isOwner) return Discovery.probe(hosts, fetch(), firstAnswer)
        // A guest's hosts count as looked at even when none answers: they come back with a
        // change to the list, or in a minute, not every poll.
        probedHosts = hosts
        val outcome = GuestProbe.probe(hosts, fetch(), firstAnswer)
        if (outcome.notInvited.isNotEmpty()) onNotInvited(outcome.notInvited)
        return outcome.base
    }

    /**
     * Once the node is up, probes the computers that might serve its apps. Any HTTP answer
     * proves the path works; only failures to connect count as "not yet", retried with backoff.
     */
    private suspend fun checkReachability(current: Int) {
        val hosts = probeHosts
        // The first answer proves the path: apps may load while the rest are still asked.
        val outcome = probe(hosts) { if (current == generation) send(NodeEvent.Reachable) }
        if (current != generation) return
        record(outcome, hosts)
        if (outcome.reachable || hosts.isEmpty()) {
            if (isOwner && mutableDiscovered.value != outcome.apps) mutableDiscovered.value = outcome.apps
            send(NodeEvent.Reachable)
        } else {
            send(NodeEvent.Unreachable)
            val state = mutableState.value
            if (state is NodeState.Retrying) {
                delay(min(500.0 * 2.0.pow(state.attempt - 1), 4_000.0).toLong())
            } else if (state == NodeState.Ready && isOwner && mutableDiscovered.value != outcome.apps) {
                mutableDiscovered.value = outcome.apps
            }
        }
    }

    /** Looks again: newly published apps for the owner, and for a guest whether its apps still answer and still admit it. */
    suspend fun rediscover() {
        if (mutableState.value != NodeState.Ready) return
        val current = generation
        val hosts = probeHosts
        val outcome = probe(hosts)
        record(outcome, hosts)
        if (outcome.reachable && isOwner && mutableDiscovered.value != outcome.apps) mutableDiscovered.value = outcome.apps
        if (isOwner || current != generation || !silence.record(hosts, outcome.reachable)) return
        log("$id: no app has answered for a while, restarting the node")
        scope.launch { restart() }
    }

    private fun record(outcome: Discovery.Outcome, hosts: List<String>) {
        // Only a working path, or no computer online at all, says anything about them.
        if (!outcome.reachable && hosts.isNotEmpty()) return
        probedHosts = hosts
        val answers = hosts.associateWith { outcome.answered[it] ?: false }
        if (answers != mutableAnswers.value) mutableAnswers.value = answers
    }

    /**
     * Checks that this app's host answers through the node. Any HTTP response counts; only
     * failures to connect mean "not yet". A guest asks the connector who it is, so an ended
     * share shows up as [Reach.NotInvited].
     */
    suspend fun canReach(app: WebApp): Reach {
        val fetch = fetch()
        if (mutableState.value != NodeState.Ready || fetch == null) return Reach.Unreachable
        val host = Discovery.normalizedHost(app.host)
        val reach = if (isOwner) {
            val manifest = app.startURL.resolve(OvenlightManifest.path)
            if (runCatching { fetch(manifest) }.getOrNull() != null) Reach.Reached else Reach.Unreachable
        } else {
            GuestProbe.whoami(host, fetch)
        }
        mutableAnswers.value = mutableAnswers.value?.plus(host to (reach != Reach.Unreachable))
        return reach
    }

    private fun send(event: NodeEvent) {
        val next = NodeMachine.next(mutableState.value, event)
        if (next != mutableState.value) {
            log("$id: state ${mutableState.value.logDescription} -> ${next.logDescription}")
            mutableState.value = next
        }
    }

    // Requests through the node

    /** A short fetch through the node for quick checks and page metadata; null until the node is up. */
    fun fetch(): Fetch? {
        if (node == null) return null
        return cachedFetch ?: clients.fetch(routeKey, 4_000, 8_000).also { cachedFetch = it }
    }

    /** A fetch through the node with its own timeouts, for claims, admin calls and feedback uploads. */
    fun makeFetch(timeoutMillis: Long, totalMillis: Long = timeoutMillis * 2): Fetch? =
        if (node == null) null else clients.fetch(routeKey, timeoutMillis, totalMillis)
}

/**
 * Every membership's node: the owner's own tailnet (when the owner uses Ovenlight with their
 * own computers) and one guest node per owner whose invite this phone accepted.
 */
class NodeManager(
    private val clients: TailnetClients,
    private val preferences: KeyValueStore,
    val memberships: MembershipStore,
    /** noBackupFilesDir/tailscale: each node's state, never backed up. */
    private val nodesDirectory: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    /** Debug builds take a control server and an auth key from their launch extras. */
    private val debugBuild: Boolean = false,
) {
    /** A different owner tailnet changes routes, so every view made for the old one goes (the pool sets this). */
    var onOwnerSuffixChanged: () -> Unit = {}

    val owner: MembershipNode = node(Membership.ownerID) {
        MembershipNode.Settings(controlURL = ownerControlURL, authKey = debugAuthKey, hostName = hostName)
    }

    private val mutableGuests = MutableStateFlow<Map<String, MembershipNode>>(emptyMap())
    val guests: StateFlow<Map<String, MembershipNode>> = mutableGuests

    /**
     * The owner set Ovenlight up for their own computers. Until then no owner node starts and
     * nothing mentions signing in, so a guest who only opens invites never sees it.
     */
    private val mutableOwnerEnabled = MutableStateFlow(preferences.getBoolean(OWNER_ENABLED_KEY) || debugAuthKey != null)
    val ownerEnabled: StateFlow<Boolean> = mutableOwnerEnabled

    /** Gives each guest node its hooks (see GuestManager), the ones already made included. */
    var configureGuest: (MembershipNode) -> Unit = {}
        set(value) {
            field = value
            mutableGuests.value.values.forEach(value)
        }

    init {
        syncGuestNodes()
    }

    private fun node(id: String, settings: () -> MembershipNode.Settings?) = MembershipNode(
        id, settings, clients, ::stateDirectory, preferences, scope, io, now, log, rememberOwnerSuffix = ::rememberOwnerSuffix,
    )

    // The owner's own tailnet (what the launcher and Settings show)

    /** This phone as the tailnet lists it (see [deviceName]). */
    val deviceName: String get() = deviceName(owner.status.value, hostName)

    /** The owner's own computers (see [Discovery.ownersHosts]). */
    val ownersHosts: Set<String> get() = Discovery.ownersHosts(owner.status.value)

    /** The ones online now that could run a connector (see [Discovery.candidateHosts]). */
    val ownersOnlineHosts: Set<String> get() = Discovery.candidateHosts(owner.status.value).toSet()

    /** The owner can manage sharing: signed in and connected. */
    val ownerIsConnected: Boolean get() = mutableOwnerEnabled.value && owner.state.value == NodeState.Ready

    /**
     * Owner mode is on and no account is signed in: the node asks for a login, or it has never
     * been in a tailnet. A signed-in node has no status while it restarts, but its tailnet is
     * remembered, so that doesn't count.
     */
    val ownerHasNoAccount: Boolean
        get() {
            if (!mutableOwnerEnabled.value) return false
            if (owner.state.value is NodeState.NeedsLogin) return true
            return owner.status.value?.account == null && owner.magicDNSSuffixSeen == null
        }

    fun enableOwner() {
        mutableOwnerEnabled.value = true
        preferences.putBoolean(OWNER_ENABLED_KEY, true)
        scope.launch { owner.ensureRunning() }
    }

    /**
     * Undoes [enableOwner] while no account is signed in: the owner's node logs out and is
     * forgotten with its tailnet, and the launcher goes back to its welcome when nothing else
     * is saved.
     */
    suspend fun disableOwner() {
        mutableOwnerEnabled.value = false
        preferences.remove(OWNER_ENABLED_KEY)
        owner.logOutAndForget()
        clients.forget(Membership.ownerID)
        rememberOwnerSuffix(null)
    }

    /** Remembers the owner's tailnet (null forgets it). A different one changes routes. */
    fun rememberOwnerSuffix(suffix: String?) {
        if (preferences.getString(OWNER_SUFFIX_KEY) == suffix) return
        if (suffix == null) preferences.remove(OWNER_SUFFIX_KEY) else preferences.putString(OWNER_SUFFIX_KEY, suffix)
        onOwnerSuffixChanged()
    }

    // Lifecycle

    /** Brings every node up: on return to the foreground, and at launch. */
    suspend fun didBecomeActive() = coroutineScope {
        if (mutableOwnerEnabled.value) launch { owner.ensureRunning() }
        for (node in mutableGuests.value.values) launch { node.ensureRunning() }
    }

    /** Closes every node, keeping its state: a minute after Ovenlight leaves. */
    suspend fun closeAll() = coroutineScope {
        launch { owner.stop() }
        for (node in mutableGuests.value.values) launch { node.stop() }
    }

    /** Tries the owner's node again after a failure. */
    suspend fun restart() = owner.restart()

    /** Signs this phone out of the owner's tailnet and forgets its node, then starts over at login. Guest nodes are untouched. */
    suspend fun signOut() {
        owner.logOutAndForget()
        owner.start()
    }

    suspend fun rediscover() {
        owner.rediscover()
        for (node in mutableGuests.value.values) node.rediscover()
    }

    // Guest nodes

    /** The node for a guest membership. Null if there is no such membership (it was removed). */
    fun guestNode(membershipID: String): MembershipNode? = mutableGuests.value[membershipID]

    /** Makes the node of each membership that has none. Call it once memberships are loaded or one is added. */
    fun syncGuestNodes() {
        val made = mutableGuests.value.toMutableMap()
        for (membership in memberships.memberships) {
            if (made.containsKey(membership.id)) continue
            val id = membership.id
            val node = node(id) {
                memberships[id]?.let { MembershipNode.Settings(it.controlURL.toString(), it.joinKey, it.hostName) }
            }
            made[id] = node
            configureGuest(node)
        }
        mutableGuests.value = made
    }

    /** Logs a guest node out, deletes its state and forgets the membership. */
    suspend fun removeGuest(membershipID: String) {
        val node = mutableGuests.value[membershipID]
        if (node != null) node.logOutAndForget() else withContext(io) { stateDirectory(membershipID).deleteRecursively() }
        mutableGuests.value = mutableGuests.value - membershipID
        clients.forget(membershipID)
        memberships.delete(membershipID)
    }

    // Routing

    /** The node an app goes through: its guest membership's, the owner's for hosts on the owner's own tailnet, or none (refused). */
    fun node(app: WebApp): MembershipNode? = Routing.node(app, owner, owner.magicDNSSuffixSeen, ::guestNode)

    fun route(url: URI): Routing.Route = Routing.route(url.host.orEmpty(), owner.magicDNSSuffixSeen)

    /** A fetch for the app's pages and icon, through its node; null until the node is up, and for a refused app. */
    fun fetch(app: WebApp): Fetch? = node(app)?.fetch()

    /** A fetch for posting to an app (feedback), with its own timeouts, through the app's node; null without one. */
    fun uploadFetch(app: WebApp, timeoutMillis: Long): Fetch? = node(app)?.makeFetch(timeoutMillis)

    /** A fetch for the owner's admin API, with its own timeouts; null while the owner's node isn't up. */
    fun ownerFetch(timeoutMillis: Long): Fetch? = owner.makeFetch(timeoutMillis)

    /** What an app's launcher tile shows about its computer. */
    fun reachability(app: WebApp): AppReachability? {
        val node = node(app) ?: return null
        return AppReachability.forTile(app.host, Routing.Route.TailnetNode, node.state.value, node.answers.value)
    }

    // Configuration

    fun stateDirectory(membershipID: String): File = File(nodesDirectory, membershipID)

    /** The owner's node's name, chosen once so the device keeps it. Guest nodes each have their own. */
    val hostName: String
        get() = preferences.getString(HOST_NAME_KEY) ?: Membership.randomHostName().also { preferences.putString(HOST_NAME_KEY, it) }

    /** Tailscale's control server, or in debug builds the one a launch extra named (a local Headscale). Empty means Tailscale's. */
    private val ownerControlURL: String
        get() = (if (debugBuild) preferences.getString(CONTROL_URL_KEY) else null).orEmpty()

    /** Debug builds only: a preauth key that skips the interactive login. */
    private val debugAuthKey: String?
        get() = if (debugBuild) preferences.getString(AUTH_KEY_KEY) else null

    companion object {
        const val OWNER_SUFFIX_KEY = "ownTailnetSuffix"
        const val OWNER_ENABLED_KEY = "ownTailnetEnabled"
        const val HOST_NAME_KEY = "tailnetHostName"
        const val CONTROL_URL_KEY = "controlURL"
        const val AUTH_KEY_KEY = "authKey"

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
