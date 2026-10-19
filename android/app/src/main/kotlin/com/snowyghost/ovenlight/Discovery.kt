package com.snowyghost.ovenlight

import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** `/.well-known/ovenlight.json`, served by the Ovenlight connector for each published app. */
@Serializable
data class OvenlightManifest(
    val name: String,
    val slug: String? = null,
    /** Path of the app's icon on its own origin, for example `/icon-512.png`. */
    val icon: String? = null,
    val themeColor: String? = null,
    val version: Int,
) {
    companion object {
        const val path = "/.well-known/ovenlight.json"

        /**
         * The name is cleaned like an invite's labels (see [InviteLink.label]): it names the app
         * on its tile, and a shared app's comes from someone else's computer.
         */
        fun parse(data: ByteArray): OvenlightManifest? {
            val manifest = try {
                ovenlightJSON.decodeFromString(serializer(), strictUTF8(data))
            } catch (_: Exception) {
                return null
            }
            if (manifest.version < 1) return null
            val name = InviteLink.label(manifest.name) ?: return null
            return manifest.copy(name = name)
        }
    }
}

/** An app a connector publishes on one of the tailnet's computers. */
data class DiscoveredApp(val host: String, val manifest: OvenlightManifest) {
    val startURL: URI get() = URI("https://$host/")

    /** The icon, only when it stays on the app's own host (the node can only reach that). */
    val iconURL: URI?
        get() {
            val url = manifest.icon?.let(startURL::resolving) ?: return null
            return if (url.host?.lowercase() == host) url else null
        }
}

/** The tag a connector puts on its app nodes. */
object OvenlightTag {
    /** A connector's shareable app nodes carry `tag:ovenlight-app-<slug>`; only a tailnet admin can apply it. */
    fun isApp(tag: String): Boolean = tag.startsWith("tag:ovenlight-app-")
}

/**
 * One GET through a node, as discovery and a guest's probes ask it: the response, or null (or a
 * thrown exception) when nothing answered, a connection-level failure such as refused, reset or
 * timed out. It suspends rather than blocking its caller's thread, so hosts are asked at once.
 * The managers pass OkHttp through the node's proxy; tests pass a fake.
 */
typealias Fetch = suspend (URI) -> FetchResponse?

/** [fetch]'s answer, with a failure of that one request as no answer, so it never costs the other hosts theirs. */
internal suspend fun fetchOrNull(fetch: Fetch, url: URI): FetchResponse? = try {
    fetch(url)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** What a [Fetch] got back. */
class FetchResponse(val status: Int, val body: ByteArray = ByteArray(0), private val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

object Discovery {
    /**
     * The owner's own computers, as lowercased MagicDNS names: owned by the signed-in user or
     * tagged as a connector's app node, and named on the owner's own tailnet, so not a computer
     * someone shared in. Ovenlight probes, asks and adopts no other.
     */
    fun ownersHosts(status: TailnetStatus?): Set<String> = ownersPeers(status).map { normalizedHost(it.dnsName) }.toSet()

    private fun ownersPeers(status: TailnetStatus?): List<TailnetStatus.Peer> {
        val self = status?.selfNode
        if (status == null || self == null || self.userID == 0L) return emptyList()
        val me = self.userID
        val suffix = status.tailnet?.let { normalizedHost(it.magicDNSSuffix) }
        if (suffix.isNullOrEmpty()) return emptyList()
        // A tagged self node shares its user ID with every tagged node, so no peer is the
        // owner's by user then.
        val byUser = self.tags.isEmpty()
        return status.peers.filter { peer ->
            (byUser && peer.userID == me || peer.tags.any(OvenlightTag::isApp)) &&
                InviteLink.networkDomain(peer.dnsName) == suffix
        }
    }

    /**
     * Among an owner's probed hosts, those whose silence brings another look in a minute:
     * shareable app nodes (by their tag), hosts of apps already found (a private app's node is
     * untagged), and hosts this look sees for the first time. A laptop or TV without a connector
     * misses once, and then no more.
     */
    fun watchedHosts(hosts: List<String>, status: TailnetStatus?, found: Set<String>, seenBefore: Set<String>): Set<String> {
        val tagged = (status?.peers ?: emptyList()).filter { peer -> peer.tags.any(OvenlightTag::isApp) }.map { normalizedHost(it.dnsName) }.toSet()
        return hosts.filter { it in tagged || it in found || it !in seenBefore }.toSet()
    }

    /**
     * Whether a ready node looks at its hosts again: when a computer comes online or goes away
     * (the owner's new apps, and tiles), and a minute after a look that missed a host, as a newly
     * published app's first answer can. Not otherwise: each look wakes every app node's
     * connection, and keeps it out of idle.
     */
    fun lookAgain(hostsChanged: Boolean, lastLookMillis: Long?, missed: Boolean, nowMillis: Long): Boolean =
        hostsChanged || missed && lastLookMillis?.let { nowMillis - it > 60_000 } == true

    /** The owner's online computers that could run a connector. Phones and tablets can't, so they aren't probed. */
    fun candidateHosts(status: TailnetStatus?): List<String> =
        ownersPeers(status)
            .filter { it.online && it.os.lowercase() !in setOf("ios", "android") }
            .map { normalizedHost(it.dnsName) }
            .filter { it.isNotEmpty() }
            .toSet()
            .sorted()

    /**
     * The hosts among [hosts] whose node the network lists as offline: its computer is asleep or
     * off, most likely. A host it doesn't list isn't among them, since the node may not have it
     * among its peers yet.
     */
    fun offlineHosts(hosts: Set<String>, status: TailnetStatus?): Set<String> {
        val offline = (status?.peers ?: emptyList()).filter { !it.online }.map { normalizedHost(it.dnsName) }.toSet()
        return hosts.filter { normalizedHost(it) in offline }.toSet()
    }

    /** Lowercased in every locale, trimmed, without trailing dots. */
    fun normalizedHost(host: String): String = host.lowercase().trimmingWhitespace().trimEnd('.')

    data class Outcome(
        val apps: List<DiscoveredApp> = emptyList(),
        /** Some peer answered over HTTPS, so the node's path to the tailnet works. */
        val reachable: Boolean = false,
        /** Every probed host, and whether it answered. */
        val answered: Map<String, Boolean> = emptyMap(),
    )

    /** Fetches every candidate's manifest concurrently, through [probeEach]. */
    suspend fun probe(hosts: List<String>, fetch: Fetch?, firstAnswer: () -> Unit = {}): Outcome {
        if (fetch == null || hosts.isEmpty()) return Outcome()
        val responses = probeEach(hosts, firstAnswer) { host -> uri("https://$host${OvenlightManifest.path}")?.let { fetchOrNull(fetch, it) } }
        val apps = responses.mapNotNull { (host, response) ->
            response?.takeIf { it.status == 200 }?.body?.let(OvenlightManifest::parse)?.let { DiscoveredApp(host, it) }
        }
        val answered = responses.mapValues { it.value != null }
        return Outcome(apps.sortedBy { it.host }, answered.containsValue(true), answered)
    }

    /**
     * Asks every host at once, each host's answer null when none came. [firstAnswer] runs as the
     * first host answers, which proves the path, without waiting for a computer that drops the probe.
     */
    suspend fun <T : Any> probeEach(hosts: List<String>, firstAnswer: () -> Unit, ask: suspend (String) -> T?): Map<String, T?> = coroutineScope {
        val results = Channel<Pair<String, T?>>(hosts.size)
        for (host in hosts) launch { results.send(host to ask(host)) }
        val answers = mutableMapOf<String, T?>()
        var reached = false
        repeat(hosts.size) {
            val (host, answer) = results.receive()
            if (answer != null && !reached) firstAnswer()
            reached = reached || answer != null
            answers[host] = answer
        }
        answers
    }
}

/**
 * Which node an app's traffic takes. Android has no direct route (plan decision 9): every app
 * goes through a node, and a host on no known tailnet is refused, never looked up in public DNS
 * or loaded directly, as on iPhone. Only the iPhone's debug builds load `localhost` directly.
 */
object Routing {
    enum class Route {
        /** On no tailnet Ovenlight knows: nothing loads, not even a probe. */
        Refused,

        /** Through Ovenlight's embedded node (its local proxy). */
        TailnetNode,
    }

    /**
     * Through the owner's node for names on the owner's own tailnet (its MagicDNS suffix); any
     * other host, another tailnet's public Funnel name among them, is refused. Until that tailnet
     * is known, every MagicDNS name goes through the owner's node, where it waits for a sign-in
     * rather than going to public DNS. Apps someone shared always go through their membership's
     * node instead.
     */
    fun route(host: String, ownerSuffix: String?): Route {
        val normalized = Discovery.normalizedHost(host)
        if (ownerSuffix.isNullOrEmpty()) return if (normalized.endsWith(".ts.net")) Route.TailnetNode else Route.Refused
        return if (isOnOwnersTailnet(normalized, ownerSuffix)) Route.TailnetNode else Route.Refused
    }

    /**
     * Whether [host] is a name on the owner's tailnet. Only then may the owner's node carry it:
     * until that tailnet is known, a `*.ts.net` app routed there waits behind sign-in and loads
     * nothing.
     */
    fun isOnOwnersTailnet(host: String, ownerSuffix: String?): Boolean {
        val suffix = ownerSuffix?.let(Discovery::normalizedHost)
        if (suffix.isNullOrEmpty()) return false
        return Discovery.normalizedHost(host).endsWith(".$suffix")
    }

    /** `NodeManager.node(for:)`, with the owner's node, its tailnet and the guest nodes passed in. Null: refused. */
    fun <Node : Any> node(app: WebApp, owner: Node, ownerSuffix: String?, guest: (String) -> Node?): Node? {
        app.membershipID?.let { return guest(it) }
        return if (route(app.startURL.host ?: "", ownerSuffix) == Route.TailnetNode) owner else null
    }

    /**
     * Whether a web view whose profile was attached to [built] (null: none) may load now that the
     * app's route names [current]. A route is fixed when the view is made, and it can change under
     * it: signing in shows that a `*.ts.net` name routed to the owner's node isn't on the owner's
     * tailnet. Such a view is stale and never loads; it is made again for the route it has now.
     */
    fun mayLoad(built: Any?, current: Any?): Boolean = built === current
}
