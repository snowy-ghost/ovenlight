package com.snowyghost.ovenlight

import java.net.URI
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
                ovenlightJSON.decodeFromString(serializer(), data.decodeToString())
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
 * One GET through a node, as discovery and a guest's probes ask it: the response, or null when
 * nothing answered (a connection-level failure: refused, reset or timed out). The managers pass
 * OkHttp through the node's proxy; tests pass a fake.
 */
typealias Fetch = suspend (URI) -> FetchResponse?

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

    /**
     * Fetches every candidate's manifest concurrently. [firstAnswer] runs as the first host
     * answers, which proves the path, without waiting for a computer that drops the probe.
     */
    suspend fun probe(hosts: List<String>, fetch: Fetch?, firstAnswer: () -> Unit = {}): Outcome {
        if (fetch == null || hosts.isEmpty()) return Outcome()
        return coroutineScope {
            val results = Channel<Triple<String, ByteArray?, Boolean>>(hosts.size)
            for (host in hosts) {
                launch {
                    val response = uri("https://$host${OvenlightManifest.path}")?.let { fetch(it) }
                    results.send(Triple(host, response?.takeIf { it.status == 200 }?.body, response != null))
                }
            }
            val apps = mutableListOf<DiscoveredApp>()
            var reachable = false
            val answered = mutableMapOf<String, Boolean>()
            repeat(hosts.size) {
                val (host, data, didAnswer) = results.receive()
                if (didAnswer && !reachable) firstAnswer()
                reachable = reachable || didAnswer
                answered[host] = didAnswer
                data?.let(OvenlightManifest::parse)?.let { apps += DiscoveredApp(host, it) }
            }
            Outcome(apps.sortedBy { it.host }, reachable, answered)
        }
    }
}

/**
 * Which node an app's traffic takes. Android has no direct route (plan decision 9): every app
 * goes through a node, and a host on no known tailnet is refused, never looked up in public DNS
 * or loaded directly, where iOS loads it directly.
 */
object Routing {
    enum class Route {
        /** On no tailnet Ovenlight knows: nothing loads. iOS's `direct`. */
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
