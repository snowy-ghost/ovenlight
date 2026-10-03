import Foundation

/// `/.well-known/ovenlight.json`, served by the Ovenlight connector for each published app.
struct OvenlightManifest: Decodable, Equatable {
    var name: String
    var slug: String?
    /// Path of the app's icon on its own origin, for example `/icon-512.png`.
    var icon: String?
    var themeColor: String?
    var version: Int

    static let path = "/.well-known/ovenlight.json"

    static func parse(_ data: Data) -> OvenlightManifest? {
        guard let manifest = try? JSONDecoder().decode(OvenlightManifest.self, from: data),
              manifest.version >= 1,
              !manifest.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return manifest
    }
}

/// An app a connector publishes on one of the tailnet's machines.
struct DiscoveredApp: Equatable {
    var host: String
    var manifest: OvenlightManifest

    var startURL: URL { URL(string: "https://\(host)/")! }

    /// The icon, only when it stays on the app's own host (the node can only reach that).
    var iconURL: URL? {
        guard let path = manifest.icon, let url = URL(string: path, relativeTo: startURL)?.absoluteURL,
              url.host?.lowercased() == host else { return nil }
        return url
    }
}

/// The tag a connector puts on its app nodes.
enum OvenlightTag {
    /// A connector's shareable app nodes carry `tag:ovenlight-app-<slug>`; only a tailnet
    /// admin can apply it.
    static func isApp(_ tag: String) -> Bool { tag.hasPrefix("tag:ovenlight-app-") }
}

enum Discovery {
    /// The owner's own machines, as lowercased MagicDNS names: owned by the signed-in user
    /// or tagged as a connector's app node, and named on the owner's own tailnet, so not a
    /// machine someone shared in. Ovenlight probes, asks and adopts no other.
    static func ownersHosts(in status: TailnetStatus?) -> Set<String> {
        Set(ownersPeers(in: status).map { normalizedHost($0.dnsName) })
    }

    private static func ownersPeers(in status: TailnetStatus?) -> [TailnetStatus.Peer] {
        guard let status, let me = status.selfNode?.userID, me != 0,
              let suffix = status.tailnet.map({ normalizedHost($0.magicDNSSuffix) }), !suffix.isEmpty else { return [] }
        // A tagged self node shares its user ID with every tagged node, so no peer is
        // the owner's by user then.
        let byUser = status.selfNode?.tags.isEmpty == true
        return status.peers.filter { peer in
            (byUser && peer.userID == me || peer.tags.contains(where: OvenlightTag.isApp))
                && InviteLink.networkDomain(of: peer.dnsName) == suffix
        }
    }

    /// The owner's online machines that could run a connector. Phones and tablets can't,
    /// so they aren't probed.
    static func candidateHosts(in status: TailnetStatus?) -> [String] {
        let hosts = ownersPeers(in: status)
            .filter { $0.online && !["ios", "android"].contains($0.os.lowercased()) }
            .map { normalizedHost($0.dnsName) }
            .filter { !$0.isEmpty }
        return Array(Set(hosts)).sorted()
    }

    static func normalizedHost(_ host: String) -> String {
        var host = host.lowercased().trimmingCharacters(in: .whitespaces)
        while host.hasSuffix(".") { host.removeLast() }
        return host
    }

    struct Outcome {
        var apps: [DiscoveredApp] = []
        /// Some peer answered over HTTPS, so the node's path to the tailnet works.
        var reachable = false
        /// Every probed host, and whether it answered.
        var answered: [String: Bool] = [:]
    }

    /// Fetches every candidate's manifest concurrently.
    static func probe(hosts: [String], session: URLSession?) async -> Outcome {
        guard let session, !hosts.isEmpty else { return Outcome() }
        return await withTaskGroup(of: (String, Data?, Bool).self) { group in
            for host in hosts {
                group.addTask {
                    guard let url = URL(string: "https://\(host)\(OvenlightManifest.path)") else { return (host, nil, false) }
                    do {
                        let (data, response) = try await session.data(from: url)
                        let ok = (response as? HTTPURLResponse)?.statusCode == 200
                        return (host, ok ? data : nil, true)
                    } catch {
                        return (host, nil, false)
                    }
                }
            }
            var outcome = Outcome()
            for await (host, data, answered) in group {
                outcome.reachable = outcome.reachable || answered
                outcome.answered[host] = answered
                if let data, let manifest = OvenlightManifest.parse(data) {
                    outcome.apps.append(DiscoveredApp(host: host, manifest: manifest))
                }
            }
            outcome.apps.sort { $0.host < $1.host }
            return outcome
        }
    }
}

/// Which network path an app's traffic takes.
enum Routing {
    enum Route: Equatable {
        case direct
        /// Through Ovenlight's embedded node (its loopback SOCKS5 proxy).
        case tailnetNode
    }

    /// Through the owner's node for names on the owner's own tailnet (its MagicDNS
    /// suffix); any other host, another tailnet's public Funnel name among them, loads
    /// directly. Until that tailnet is known, every MagicDNS name goes through the owner's
    /// node, where it waits for a sign-in rather than going to public DNS. Apps someone
    /// shared always go through their membership's node instead.
    static func route(host: String, ownerSuffix: String?) -> Route {
        let host = Discovery.normalizedHost(host)
        guard let suffix = ownerSuffix, !suffix.isEmpty else {
            return host.hasSuffix(".ts.net") ? .tailnetNode : .direct
        }
        return isOnOwnersTailnet(host: host, ownerSuffix: suffix) ? .tailnetNode : .direct
    }

    /// Whether `host` is a name on the owner's tailnet. Only then may the owner's node
    /// carry it: until that tailnet is known, a `*.ts.net` app routed there waits behind
    /// sign-in and loads nothing.
    static func isOnOwnersTailnet(host: String, ownerSuffix: String?) -> Bool {
        guard let suffix = ownerSuffix.map(Discovery.normalizedHost), !suffix.isEmpty else { return false }
        return Discovery.normalizedHost(host).hasSuffix("." + suffix)
    }

    /// `NodeManager.node(for:)`, with the owner's node, its tailnet and the guest nodes
    /// passed in.
    static func node<Node>(for app: WebApp, owner: Node, ownerSuffix: String?, guest: (String) -> Node?) -> Node? {
        if let membershipID = app.membershipID { return guest(membershipID) }
        return route(host: app.startURL.host ?? "", ownerSuffix: ownerSuffix) == .tailnetNode ? owner : nil
    }

    /// Whether a web view whose data store was attached to `built` (nil: direct) may load
    /// now that the app's route names `current`. A route is fixed when the view is made, and
    /// it can change under it: signing in shows that a `*.ts.net` name routed to the
    /// owner's node isn't on the owner's tailnet. Such a view is stale and never loads; it
    /// is made again through the node the route names now.
    static func mayLoad(built: AnyObject?, current: AnyObject?) -> Bool {
        built === current
    }
}
