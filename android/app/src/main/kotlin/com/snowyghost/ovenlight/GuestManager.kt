package com.snowyghost.ovenlight

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guest memberships from invites: joining, claiming, resuming an interrupted join, and taking
 * apps away when their owner stops sharing them. Main thread only, like the registry; [scope]
 * runs on it.
 */
class GuestManager(
    private val nodes: NodeManager,
    private val registry: AppRegistry,
    private val router: Router,
    private val scope: CoroutineScope,
    /** Milliseconds on a monotonic clock, for the claim's deadline. */
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    private val mutablePhases = MutableStateFlow<Map<String, JoinPhase>>(emptyMap())

    /** Invites being accepted this session, by invite ID. */
    val phases: StateFlow<Map<String, JoinPhase>> = mutablePhases
    val notice = MutableStateFlow<AccessEndedNotice?>(null)

    private val inFlight = mutableSetOf<String>()

    /** The last join queued for each membership; each waits for the one before. */
    private val joins = mutableMapOf<String, Job>()
    private val verifying = mutableSetOf<UUID>()

    /**
     * Memberships being left, so two orphan sweeps at once (an ended share and the app's view
     * closing) log one out once.
     */
    private val leaving = mutableSetOf<String>()
    private val store: MembershipStore get() = nodes.memberships

    init {
        nodes.configureGuest = ::wire
    }

    private fun setPhase(inviteID: String, phase: JoinPhase?) {
        mutablePhases.value = if (phase == null) mutablePhases.value - inviteID else mutablePhases.value + (inviteID to phase)
    }

    private fun wire(node: MembershipNode) {
        val id = node.id
        node.guestHosts = { GuestCleanup.guestHosts(store[id], id, registry.apps) }
        node.onNotInvited = { hosts ->
            store[id]?.let { membership ->
                for (app in GuestCleanup.revokedApps(membership, registry.apps, hosts)) scope.launch { verifyAccess(app) }
            }
        }
        // The connector can't say "not invited" through a path that's gone: the owner stopped
        // sharing an app with a guest who keeps another (see GuestProbe.absentHosts).
        node.onPeersGone = { hosts ->
            store[id]?.let { membership ->
                val gone = GuestCleanup.revokedApps(membership, registry.apps, hosts)
                if (gone.isNotEmpty()) {
                    log("$id: ${hosts.sorted().joinToString(", ")} left the peers")
                    accessEnded(gone)
                }
            }
        }
        node.onLoginLost = {
            val membership = store[id]
            if (membership != null && GuestCleanup.isRemoved(membership)) {
                log("$id: the owner removed this device")
                store.update(id) { it.copy(pendingClaims = emptyList()) }
                val apps = registry.apps.filter { it.membershipID == id }
                if (apps.isEmpty()) scope.launch { nodes.removeGuest(id) } else accessEnded(apps)
            }
        }
    }

    // Joining

    /** What accepting this invite would do, for the confirmation sheet. */
    fun preflight(invite: InviteLink): Preflight {
        val status = nodes.owner.status.value
        val ownerSuffix = if (nodes.ownerEnabled.value && status?.account != null) status.tailnet?.magicDNSSuffix else null
        return Preflight.of(invite, ownerSuffix, registry.apps, store::get)
    }

    fun membershipID(inviteID: String): String? =
        store.memberships.firstOrNull { m -> m.pendingClaims.any { it.invite.invite == inviteID } }?.id

    /**
     * Joins the owner's network with the invite's key (or reuses the node already there for
     * this owner), claims the invite at the app's connector, and adds the app.
     */
    suspend fun accept(invite: InviteLink) {
        if (preflight(invite) == Preflight.OwnApp) {
            setPhase(invite.invite, JoinPhase.Failed(JoinError.OwnApp))
            return
        }
        // Claimed again even for an app already here: after the owner removed it, a new
        // invite must reach the connector (an old link just fails quietly). An existing
        // membership keeps its names until this invite's claim succeeds.
        val membership = (store.membership(invite) ?: Membership(invite)).awaiting(invite)
        store.upsert(membership)
        run(invite.invite, membership.id)
    }

    /** Try Again after a network failure. */
    suspend fun retry(inviteID: String) {
        val id = membershipID(inviteID) ?: return
        run(inviteID, id)
    }

    /**
     * Withdraws from an invite that hasn't finished. A membership left with nothing leaves;
     * one that keeps another invite stops a node registering with this one's key (the next
     * start registers with the next invite's).
     */
    suspend fun cancel(inviteID: String) {
        setPhase(inviteID, null)
        val id = membershipID(inviteID) ?: return
        val membership = store[id] ?: return
        store.update(id) { m -> m.copy(pendingClaims = m.pendingClaims.filter { it.invite.invite != inviteID }) }
        collectOrphans()
        if (store[id] != null && GuestCleanup.isRegistering(membership, inviteID)) nodes.guestNode(id)?.stop()
    }

    /** Finishes joins interrupted by a relaunch or a lost connection. */
    fun resumePending() {
        for (membership in store.memberships) {
            for (claim in membership.pendingClaims) {
                val inviteID = claim.invite.invite
                if (inviteID in inFlight) continue
                val phase = mutablePhases.value[inviteID]
                if (phase is JoinPhase.Failed && !phase.error.isRetryable) continue
                scope.launch { run(inviteID, membership.id) }
            }
        }
    }

    /** Runs to the end even when its caller is canceled, as iOS's join does in a task of its own. */
    private suspend fun run(inviteID: String, membershipID: String) = withContext(NonCancellable) {
        if (!inFlight.add(inviteID)) return@withContext
        try {
            // One invite at a time per membership: a node that hasn't joined registers with one
            // key, and the next invite needs the node started over with its own.
            setPhase(inviteID, JoinPhase.Joining)
            inOrder(membershipID) { join(inviteID, membershipID) }
        } finally {
            inFlight.remove(inviteID)
        }
    }

    private suspend fun inOrder(membershipID: String, work: suspend () -> Unit) {
        val previous = joins[membershipID]
        val job = scope.launch {
            previous?.join()
            work()
        }
        joins[membershipID] = job
        job.join()
        if (joins[membershipID] === job) joins.remove(membershipID)
    }

    private suspend fun join(inviteID: String, membershipID: String) {
        val invite = pendingInvite(inviteID, membershipID)
        val node = nodes.guestNode(membershipID)
        if (invite == null || node == null) {
            setPhase(inviteID, null)
            return
        }
        // Put this invite's key first, and restart a node started with another.
        val membership = store[membershipID]
        if (membership != null && !membership.joined && membership.pendingClaims.firstOrNull()?.invite?.invite != inviteID) {
            store.update(membershipID) { it.puttingFirst(inviteID) }
            node.stop() // after any start under way, which then closes its node
        }

        log("joining ${invite.host} (invite $inviteID, membership $membershipID)")
        val wasJoined = store[membershipID]?.joined == true
        var up: JoinError? = JoinError.Canceled
        // A return to the front can replace a node, which ends this wait; keep waiting on the
        // new node while the invite is still wanted.
        for (attempt in 0 until 5) {
            if (!node.isRunning) node.start()
            up = if (store[membershipID]?.joined == true) {
                waitForRunning(node, timeoutMillis = 30_000)
            } else {
                node.waitUntilUp(timeoutMillis = 45_000)?.let { JoinError(upMessage = it) }
            }
            if (up != JoinError.Canceled || pendingInvite(inviteID, membershipID) == null) break
            delay(500)
        }
        if (pendingInvite(inviteID, membershipID) == null) {
            setPhase(inviteID, null) // canceled by the person
            return
        }
        if (up != null) return fail(inviteID, membershipID, up, node)
        // The key must have put the node in the network the invite names; a link that pairs
        // someone's app with another network's key would otherwise claim there.
        val suffix = node.magicDNSSuffix()
        when (val landing = Claim.landing(invite, suffix, registeredNow = !wasJoined)) {
            Claim.Landing.InNetwork -> Unit
            Claim.Landing.Unknown -> return fail(inviteID, membershipID, JoinError.Network, node)
            is Claim.Landing.Elsewhere -> {
                log("invite $inviteID names ${invite.networkDomain} but the node is in ${suffix.orEmpty()}")
                if (landing.leave) node.logOutAndForget()
                return fail(inviteID, membershipID, JoinError.WrongNetwork, node)
            }
        }
        store.update(membershipID) { it.copy(joined = true) }

        setPhase(inviteID, JoinPhase.Claiming)
        val outcome = claim(invite, node)
        if (pendingInvite(inviteID, membershipID) == null) {
            setPhase(inviteID, null) // canceled while claiming; nothing is added
            return
        }
        when (outcome) {
            is Claim.Step.Claimed -> {
                val names = outcome.response.names(invite)
                val app = registry.addShared(names.appName, invite.host, names.app, membershipID, names.owner)
                store.update(membershipID) { it.claimed(invite, names.owner) }
                log("claimed ${invite.host}")
                setPhase(inviteID, JoinPhase.Joined(app.id))
                node.rediscover()
            }
            is Claim.Step.Failed -> fail(inviteID, membershipID, outcome.error, node)
            Claim.Step.Retry -> fail(inviteID, membershipID, JoinError.Network, node)
        }
    }

    private suspend fun fail(inviteID: String, membershipID: String, error: JoinError, node: MembershipNode) {
        log("invite $inviteID failed: $error")
        val failure = JoinFailure.of(error, pendingInvite(inviteID, membershipID), membershipID, registry.apps)
        setPhase(inviteID, failure.phase)
        // A node that never joined shouldn't sit on a refused or unused key; the next start
        // registers with the next invite's key.
        if (store[membershipID]?.joined == false) node.stop()
        if (!failure.dropsInvite) return
        store.update(membershipID) { m -> m.copy(pendingClaims = m.pendingClaims.filter { it.invite.invite != inviteID }) }
        collectOrphans()
    }

    private fun pendingInvite(inviteID: String, membershipID: String): InviteLink? =
        store[membershipID]?.pendingClaims?.firstOrNull { it.invite.invite == inviteID }?.invite

    /** For a node that already joined: waits until it runs again. Null once it does. */
    private suspend fun waitForRunning(node: MembershipNode, timeoutMillis: Long): JoinError? {
        var waited = 0L
        while (waited < timeoutMillis) {
            if (node.status.value?.backendState == "Running") return null
            delay(500)
            waited += 500
        }
        return JoinError.Network
    }

    /**
     * Posts the invite's key to the app's connector. Right after joining, the connector may not
     * know the device yet, so connection failures and "Unknown caller" retry, for up to
     * [Claim.claimTime] in all.
     */
    private suspend fun claim(invite: InviteLink, node: MembershipNode): Claim.Step {
        val session = node.makeSession(timeoutMillis = 8_000) ?: return Claim.Step.Retry
        val deadline = now() + Claim.claimTime.inWholeMilliseconds
        var attempt = 0
        while (now() < deadline) {
            val response = try {
                session(Claim.request(invite))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (response != null) {
                val step = Claim.interpret(response.status, response.header(ConnectorError.header), response.body)
                if (step != Claim.Step.Retry) return step
                log("claim answered ${response.status}, trying again")
            }
            delay(Claim.retryDelay(attempt))
            attempt += 1
        }
        return Claim.Step.Retry
    }

    // Access ending

    /** Checks with the app's connector, and takes the app away if it answers "not invited". */
    suspend fun verifyAccess(app: WebApp) {
        if (!app.isShared || app.id in verifying) return
        val node = nodes.node(app) ?: return
        verifying += app.id
        try {
            if (node.canReach(app) == Reach.NotInvited) accessEnded(listOf(app))
        } finally {
            verifying -= app.id
        }
    }

    /** Takes apps away whose owner stopped sharing them: closes them, wipes their data, and says so once. */
    fun accessEnded(apps: List<WebApp>) {
        val notice = AccessEndedNotice.of(apps, registry.apps) ?: return
        val ended = apps.filter { app -> registry.apps.any { it.id == app.id } }
        log("access ended for ${ended.joinToString(", ") { it.host }}")
        router.openApp.value?.let { open -> if (ended.any { it.id == open.id }) router.openApp.value = null }
        scope.launch {
            for (app in ended) registry.remove(app.id)
            collectOrphans()
            // The notice waits for the app and any sheet to close.
            router.afterClear { this@GuestManager.notice.value = notice }
        }
    }

    /** Leaves someone's network on purpose: removes their apps and data, and logs out. */
    suspend fun leave(membershipID: String) {
        store.update(membershipID) { it.copy(pendingClaims = emptyList()) }
        for (app in registry.apps.filter { it.membershipID == membershipID }) registry.remove(app.id)
        nodes.removeGuest(membershipID)
    }

    /** Memberships left with no app and no invite waiting log out and delete their state. */
    suspend fun collectOrphans() {
        // With either list unread, or an entry of one, what it names would look orphaned.
        if (!registry.readInFull || !store.readInFull) return
        // A shared app whose membership is gone can't be reached or checked any more.
        for (app in GuestCleanup.stranded(registry.apps, store::get)) registry.remove(app.id)
        for (id in GuestCleanup.orphans(store.memberships, registry.apps)) {
            if (!leaving.add(id)) continue
            log("$id: no apps left, leaving")
            try {
                nodes.removeGuest(id)
            } finally {
                leaving.remove(id)
            }
        }
    }
}
