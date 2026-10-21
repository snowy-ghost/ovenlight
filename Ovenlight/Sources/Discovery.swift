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

    /// The name is cleaned like an invite's labels (see `InviteLink.label`): it names the
    /// app on its tile, and a shared app's comes from someone else's computer.
    static func parse(_ data: Data) -> OvenlightManifest? {
        guard var manifest = try? JSONDecoder().decode(OvenlightManifest.self, from: data),
              manifest.version >= 1, let name = InviteLink.label(manifest.name) else { return nil }
        manifest.name = name
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
        guard let path = manifest.icon, let url = ManifestParser.resolve(path, against: startURL),
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

    /// Among an owner's probed hosts, those whose silence brings another look in a minute:
    /// shareable app nodes (by their tag), hosts of apps already found (a private app's node
    /// is untagged), and hosts this look sees for the first time. Found is the node's last
    /// look or a launcher tile the owner's own discovery added, which a miss doesn't take
    /// away. A laptop or TV without a connector misses once, and then no more.
    static func watchedHosts(_ hosts: [String], in status: TailnetStatus?, found: Set<String>, apps: [WebApp],
                             seenBefore: Set<String>) -> Set<String> {
        let tagged = Set((status?.peers ?? []).filter { $0.tags.contains(where: OvenlightTag.isApp) }.map { normalizedHost($0.dnsName) })
        let found = found.union(apps.filter { $0.discovered && !$0.isShared }.map { normalizedHost($0.host) })
        return Set(hosts).filter { tagged.contains($0) || found.contains($0) || !seenBefore.contains($0) }
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

    /// The hosts among `hosts` whose node the network lists as offline: its computer is
    /// asleep or off, most likely. A host it doesn't list isn't among them, since the node
    /// may not have it among its peers yet.
    static func offlineHosts(_ hosts: Set<String>, in status: TailnetStatus?) -> Set<String> {
        let offline = Set((status?.peers ?? []).filter { !$0.online }.map { normalizedHost($0.dnsName) })
        return hosts.filter { offline.contains(normalizedHost($0)) }
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

    /// Whether a ready node looks at its hosts again: when a machine comes online or goes
    /// away (the owner's new apps, and tiles), and a minute after a look that missed a host,
    /// as a newly published app's first answer can. Not otherwise: each look wakes every app
    /// node's connection, and keeps it out of idle.
    static func lookAgain(hostsChanged: Bool, lastLook: Date?, missed: Bool, now: Date) -> Bool {
        hostsChanged || missed && lastLook.map { now.timeIntervalSince($0) > 60 } == true
    }

    /// Fetches every candidate's manifest concurrently. `firstAnswer` runs as the first host
    /// answers, which proves the path, without waiting for a machine that drops the probe.
    static func probe(hosts: [String], session: URLSession?, firstAnswer: @MainActor () -> Void) async -> Outcome {
        guard let session, !hosts.isEmpty else { return Outcome() }
        return await withTaskGroup(of: (String, Data?, Bool).self) { group in
            for host in hosts {
                group.addTask {
                    guard let url = URL(string: "https://\(host)\(OvenlightManifest.path)") else { return (host, nil, false) }
                    do {
                        let (data, response) = try await session.cappedData(from: url)
                        let ok = (response as? HTTPURLResponse)?.statusCode == 200
                        return (host, ok ? data : nil, true)
                    } catch {
                        return (host, nil, false)
                    }
                }
            }
            var outcome = Outcome()
            for await (host, data, answered) in group {
                if answered, !outcome.reachable { await firstAnswer() }
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
        /// Through Ovenlight's embedded node (its loopback SOCKS5 proxy).
        case tailnetNode
        /// On no tailnet Ovenlight knows: nothing loads, not even a probe.
        case refused
        /// Debug builds only: an app at `localhost`, for screenshots of a local demo. Loopback
        /// IP literals stay blocked (`LocalAddressRules`).
        case direct
    }

    /// Through the owner's node for names on the owner's own tailnet (its MagicDNS
    /// suffix); any other host, an old tailnet's name or another tailnet's public Funnel
    /// name among them, is refused, never looked up in public DNS. Until that tailnet is
    /// known, every MagicDNS name goes through the owner's node, where it waits for a
    /// sign-in. Apps someone shared always go through their membership's node instead.
    static func route(host: String, ownerSuffix: String?) -> Route {
        let host = Discovery.normalizedHost(host)
        #if DEBUG
        if host == "localhost" { return .direct }
        #endif
        guard let suffix = ownerSuffix, !suffix.isEmpty else {
            return host.hasSuffix(".ts.net") ? .tailnetNode : .refused
        }
        return isOnOwnersTailnet(host: host, ownerSuffix: suffix) ? .tailnetNode : .refused
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

    /// Whether a web view whose data store was attached to `built` (nil: no node) may load
    /// now that the app's route names `current`. A route is fixed when the view is made, and
    /// it can change under it: signing in shows that a `*.ts.net` name routed to the
    /// owner's node isn't on the owner's tailnet. Such a view is stale and never loads; it
    /// is made again through the node the route names now.
    static func mayLoad(built: AnyObject?, current: AnyObject?) -> Bool {
        built === current
    }
}
