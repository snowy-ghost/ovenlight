import Combine
import Foundation
import Network
import os
import TailscaleKit
import WebKit

/// Where one of Ovenlight's connections is: the owner's own tailnet, or a guest node in
/// someone else's. Drives the launcher's "connecting" state, the login prompt, and when
/// apps may load.
enum NodeState: Equatable {
    case idle
    case starting
    case needsLogin(URL)
    /// Registered, waiting for the network's owner to approve this iPhone.
    case awaitingApproval
    case connecting
    case ready
    case retrying(attempt: Int)
    case failed(String)

    var isBusy: Bool {
        switch self {
        case .starting, .connecting, .retrying: return true
        default: return false
        }
    }

    /// Whether the sign-in sheet closes: the iPhone is approved, or the node failed. A tailnet
    /// that approves each new device keeps it open through the start between the sign-in and
    /// the approval, and the wait for approval, so the owner can carry on in Tailscale's pages.
    /// Idle doesn't close it either: coming back from an authenticator app can restart a node
    /// whose listener died, which passes through idle.
    var endsSignIn: Bool {
        switch self {
        case .ready, .failed: return true
        default: return false
        }
    }

    var summary: String {
        switch self {
        case .idle: return "Off"
        case .starting, .connecting, .retrying: return "Connecting"
        case .needsLogin: return "Not signed in"
        case .awaitingApproval: return "Waiting for approval"
        case .ready: return "Connected"
        case .failed(let message): return message
        }
    }

    /// For the log, which is public: the sign-in link is left out, since whoever opens it
    /// first can sign the node in to their own account. A failure's message is the summary.
    var logDescription: String {
        switch self {
        case .needsLogin: return "needsLogin"
        case .failed: return "failed"
        default: return String(describing: self)
        }
    }
}

/// Inputs to the state machine: lifecycle calls, the node's backend state, and the
/// result of the reachability check that runs once the node is up.
enum NodeEvent: Equatable {
    case start
    case stop
    case backend(state: String, authURL: URL?)
    case reachable
    case unreachable
    case error(String)
}

enum NodeMachine {
    /// Checks after the node is up before Ovenlight stops waiting and lets apps report
    /// their own errors. The first WireGuard handshake after a restart is sometimes lost.
    static let maxRetries = 5

    static func next(_ state: NodeState, _ event: NodeEvent) -> NodeState {
        switch event {
        case .start:
            return .starting
        case .stop:
            return .idle
        case .error(let message):
            return .failed(message)
        case .reachable:
            switch state {
            case .connecting, .retrying, .ready: return .ready
            default: return state
            }
        case .unreachable:
            switch state {
            case .connecting: return .retrying(attempt: 1)
            case .retrying(let attempt): return attempt < maxRetries ? .retrying(attempt: attempt + 1) : .ready
            default: return state
            }
        case .backend(let backend, let authURL):
            switch backend {
            case "NeedsLogin":
                if let authURL { return .needsLogin(authURL) }
                if case .needsLogin = state { return state }
                return .starting
            case "NeedsMachineAuth":
                return .awaitingApproval
            case "Running":
                switch state {
                case .ready, .retrying, .connecting: return state
                default: return .connecting
                }
            case "Stopped":
                return .failed(ConnectionCopy.stopped)
            default: // NoState, Starting
                switch state {
                case .needsLogin, .failed, .awaitingApproval: return .connecting
                default: return state
                }
            }
        }
    }
}

/// The parts of the node's status (ipnstate.Status JSON) Ovenlight uses.
struct TailnetStatus: Decodable, Equatable {
    struct Peer: Decodable, Equatable {
        var hostName: String
        var dnsName: String
        var os: String
        var online: Bool
        var userID: Int64
        /// ACL tags; a tagged node belongs to the tailnet, not to a user.
        var tags: [String]

        enum CodingKeys: String, CodingKey {
            case hostName = "HostName", dnsName = "DNSName", os = "OS", online = "Online", userID = "UserID", tags = "Tags"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            hostName = try c.decodeIfPresent(String.self, forKey: .hostName) ?? ""
            dnsName = try c.decodeIfPresent(String.self, forKey: .dnsName) ?? ""
            os = try c.decodeIfPresent(String.self, forKey: .os) ?? ""
            online = try c.decodeIfPresent(Bool.self, forKey: .online) ?? false
            userID = try c.decodeIfPresent(Int64.self, forKey: .userID) ?? 0
            tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        }
    }

    struct User: Decodable, Equatable {
        var loginName: String
        var displayName: String

        enum CodingKeys: String, CodingKey { case loginName = "LoginName", displayName = "DisplayName" }
    }

    struct Tailnet: Decodable, Equatable {
        var name: String
        var magicDNSSuffix: String

        enum CodingKeys: String, CodingKey { case name = "Name", magicDNSSuffix = "MagicDNSSuffix" }
    }

    var backendState: String
    var authURL: String
    var selfNode: Peer?
    var peers: [Peer]
    var users: [String: User]
    var tailnet: Tailnet?

    enum CodingKeys: String, CodingKey {
        case backendState = "BackendState", authURL = "AuthURL", selfNode = "Self", peers = "Peer"
        case users = "User", tailnet = "CurrentTailnet"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        backendState = try c.decodeIfPresent(String.self, forKey: .backendState) ?? "NoState"
        authURL = try c.decodeIfPresent(String.self, forKey: .authURL) ?? ""
        selfNode = try c.decodeIfPresent(Peer.self, forKey: .selfNode)
        peers = try c.decodeIfPresent([String: Peer].self, forKey: .peers).map { Array($0.values) } ?? []
        users = try c.decodeIfPresent([String: User].self, forKey: .users) ?? [:]
        tailnet = try c.decodeIfPresent(Tailnet.self, forKey: .tailnet)
    }

    static func decode(_ data: Data) -> TailnetStatus? {
        try? JSONDecoder().decode(TailnetStatus.self, from: data)
    }

    /// The signed-in account, for Settings.
    var account: User? {
        guard let id = selfNode?.userID else { return nil }
        return users[String(id)]
    }

    var event: NodeEvent {
        .backend(state: backendState, authURL: authURL.isEmpty ? nil : URL(string: authURL))
    }
}

/// What a probe of one app through a node found.
enum Reach: Equatable {
    case reached
    /// The app's connector answered that this device isn't invited (a guest whose
    /// access ended, or one that hasn't claimed yet).
    case notInvited
    case unreachable
}

/// One embedded node, for one membership: the owner's own tailnet, or a guest device in
/// someone else's, made with TailscaleKit (no VPN). Web views for its apps reach it
/// through the node's loopback SOCKS5 proxy.
///
/// The node stays up across background. iOS can reclaim the loopback listener of a
/// suspended app, so every foreground checks it and starts a fresh node only if it is
/// dead. Login state persists in the membership's own directory in Application Support.
@MainActor
final class MembershipNode: ObservableObject {
    struct Settings {
        var controlURL: String
        /// Registers a node that has no login yet: a debug key for the owner, the invite's
        /// key for a guest that hasn't joined.
        var authKey: String?
        var hostName: String
    }

    let id: String
    var isOwner: Bool { id == Membership.ownerID }

    @Published private(set) var state: NodeState = .idle
    @Published private(set) var status: TailnetStatus?
    /// Apps found on the tailnet in the last reachability check (owner only).
    @Published private(set) var discovered: [DiscoveredApp] = []
    /// Whether each host answered its last probe; nil until the first one. Hosts whose
    /// machine is offline aren't probed, so they are missing here.
    @Published private(set) var answers: [String: Bool]?

    /// For a guest: the app hosts to probe, its apps and the invites being claimed.
    var guestHosts: () -> [String] = { [] }
    /// For a guest: hosts whose connector said "not invited" in a probe.
    var onNotInvited: (Set<String>) -> Void = { _ in }
    /// For a guest: the backend has needed a login for a few polls in a row.
    var onLoginLost: () -> Void = {}
    /// For a guest: app hosts gone from the node's peers for a few minutes (see `PeerAbsence`).
    var onPeersGone: (Set<String>) -> Void = { _ in }

    /// Nil once the membership is gone, so a late start can't bring back a node for it.
    private let settings: () -> Settings?
    private var node: TailscaleNode?
    private var loopback: TailscaleNode.LoopbackConfig?
    private var proxy: ProxyConfiguration?
    private var cachedSession: URLSession?
    private(set) var stores: [UUID: WKWebsiteDataStore] = [:]
    private var pollTask: Task<Void, Never>?
    /// The last start or stop. Each one waits for the one before, so two nodes never
    /// share a directory and a stop never races the start it follows.
    private var lifecycle: Task<Void, Never>?
    private var isCheckingHealth = false
    private var probedHosts: [String]?
    private var lastProbe: Date?
    /// The last look had a host that didn't answer.
    private var lastProbeMissed = false
    /// Every host this node has looked at.
    private var seenHosts: Set<String> = []
    private var loginLostPolls = 0
    private var absence = PeerAbsence()
    private var silence = GuestSilence()
    /// Bumped on every start and stop so work from an older node can't touch the new one.
    private var generation = 0
    private let log: Logger

    init(id: String, settings: @escaping () -> Settings?) {
        self.id = id
        self.settings = settings
        log = Logger(subsystem: "com.snowyghost.ovenlight", category: id == Membership.ownerID ? "node" : "node.guest")
    }

    var isRunning: Bool { node != nil }

    // MARK: Lifecycle

    /// Starts the node unless one is running, and replaces one whose loopback listener
    /// died while Ovenlight was suspended. Face ID prompts make the app briefly inactive
    /// without backgrounding it; a live node survives those and every other foreground.
    func ensureRunning() async {
        if node != nil {
            guard !isCheckingHealth else { return }
            isCheckingHealth = true
            let alive = await loopbackIsAlive()
            isCheckingHealth = false
            if alive { return }
            log.notice("loopback \(self.loopback?.address ?? "?", privacy: .public) is dead, restarting the node")
            await stop()
        }
        await start()
    }

    /// Asks the node's local API over the loopback listener, the same one the web views'
    /// SOCKS5 proxy uses.
    private func loopbackIsAlive() async -> Bool {
        guard let request = localAPIRequest("status", method: "GET", timeout: 1.5) else { return false }
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        guard let (_, response) = try? await session.data(for: request) else { return false }
        return (response as? HTTPURLResponse)?.statusCode == 200
    }

    private func localAPIRequest(_ path: String, method: String, timeout: TimeInterval) -> URLRequest? {
        guard let loopback, let url = URL(string: "http://\(loopback.address)/localapi/v0/\(path)") else { return nil }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        let credential = Data("tsnet:\(loopback.localAPIKey)".utf8).base64EncodedString()
        request.setValue("Basic \(credential)", forHTTPHeaderField: "Authorization")
        request.setValue("localapi", forHTTPHeaderField: "Sec-Tailscale")
        return request
    }

    /// Tries again after a failure.
    func restart() async {
        await stop()
        await start()
    }

    /// Starts the node unless it is running, after any start or stop under way.
    func start() async {
        await inOrder { [self] in
            guard node == nil, let settings = settings() else { return }
            generation += 1
            send(.start)
            await launch(settings, generation: generation)
        }
    }

    func stop() async {
        generation += 1 // a start under way closes the node it opens and returns
        status = nil // so a wait on this node reads the next one's status, not this one's last
        await inOrder { [self] in
            pollTask?.cancel()
            pollTask = nil
            cachedSession?.invalidateAndCancel()
            cachedSession = nil
            proxy = nil
            loopback = nil
            for store in stores.values { store.proxyConfigurations = [Self.closedProxy] }
            send(.stop)
            guard let node else { return }
            // close(), not down(): TailscaleKit's down() calls tailscale_up.
            do { try await node.close() } catch { log.error("close failed: \(String(describing: error), privacy: .public)") }
            self.node = nil
        }
    }

    private func inOrder(_ work: @escaping @MainActor () async -> Void) async {
        let previous = lifecycle
        let task = Task { @MainActor in
            await previous?.value
            await work()
        }
        lifecycle = task
        await task.value
    }

    private func launch(_ settings: Settings, generation current: Int) async {
        do {
            let directory = try NodeManager.makeStateDirectory(for: id)
            let config = Configuration(hostName: settings.hostName, path: directory.path, authKey: settings.authKey,
                                       controlURL: settings.controlURL, ephemeral: false)
            // tailscale_start returns once the node is running; login and connection
            // continue in the background and show up in the status polls.
            let node = try await Task.detached { try TailscaleNode(config: config, logger: GoLogSink()) }.value
            guard current == generation else { try? await node.close(); return }
            let loopback = try await node.loopback()
            guard current == generation else { try? await node.close(); return }
            guard let ip = loopback.ip, let port = loopback.port.flatMap({ NWEndpoint.Port(rawValue: UInt16($0)) }) else {
                throw TailscaleError.invalidProxyAddress
            }
            let proxy = ProxyConfiguration(socksv5Proxy: .hostPort(host: .init(ip), port: port))
            proxy.applyCredential(username: "tsnet", password: loopback.proxyCredential)
            self.node = node
            self.loopback = loopback
            self.proxy = proxy
            cachedSession = nil
            loginLostPolls = 0
            absence = PeerAbsence()
            // Live data stores pick up the new proxy without recreating their web views.
            for store in stores.values { store.proxyConfigurations = [proxy] }
            log.notice("\(self.id, privacy: .public): node started, loopback \(loopback.address, privacy: .public), proxy applied to \(self.stores.count) stores")
            pollTask = Task { [weak self] in await self?.poll(generation: current) }
        } catch {
            log.error("\(self.id, privacy: .public): node start failed: \(String(describing: error), privacy: .public)")
            send(.error(ConnectionCopy.couldNotStart))
        }
    }

    /// Waits for the node to register and come up, which a guest's first join needs:
    /// `up()` reports an invite key the control server refused (used or expired), which
    /// the status never says.
    func waitUntilUp(timeout: Duration) async -> Result<Void, JoinError> {
        guard let node else { return .failure(.network) }
        let current = generation
        let outcome = FirstResult<Result<Void, JoinError>>()
        // tailscale_up can't be canceled; it returns once the node runs, the control
        // server refuses the key, or the node is closed.
        Task.detached {
            do {
                try await node.up()
                outcome.offer(.success(()))
            } catch {
                outcome.offer(.failure(JoinError(upError: error)))
            }
        }
        Task { [weak self] in
            var waited = Duration.zero
            while waited < timeout, !outcome.isSettled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, self.generation == current else { return outcome.offer(.failure(.canceled)) }
                waited += .milliseconds(500)
            }
            outcome.offer(.failure(.network))
        }
        return await outcome.value
    }

    /// The MagicDNS suffix of the owner's tailnet: from the node's status, or as last seen,
    /// so an app opened before the node reports still routes through it. It goes with Sign
    /// Out and Stop Using My Own Computers. Nil for a guest.
    var magicDNSSuffixSeen: String? {
        guard isOwner else { return nil }
        if let suffix = status?.tailnet?.magicDNSSuffix, !suffix.isEmpty { return suffix }
        return UserDefaults.standard.string(forKey: Self.ownerSuffixKey)
    }

    static let ownerSuffixKey = "ownTailnetSuffix"

    /// Remembers the owner's tailnet (nil forgets it). A different one changes routes, so
    /// every view made for the old one goes.
    static func rememberOwnerSuffix(_ suffix: String?) {
        let defaults = UserDefaults.standard
        guard defaults.string(forKey: ownerSuffixKey) != suffix else { return }
        defaults.set(suffix, forKey: ownerSuffixKey)
        WebViewPool.shared.discardStaleRoutes()
    }

    /// The MagicDNS suffix of the network the node is in, read from the node now.
    func magicDNSSuffix() async -> String? {
        guard let node, let data = try? await node.statusJSON() else { return nil }
        return TailnetStatus.decode(data)?.tailnet?.magicDNSSuffix
    }

    /// Logs the node out of its network, closes it and deletes its state. The web views it
    /// carried go with it.
    func logOutAndForget() async {
        WebViewPool.shared.discard(attachedTo: self)
        if let request = localAPIRequest("logout", method: "POST", timeout: 5) {
            _ = try? await URLSession.shared.data(for: request)
        }
        await stop()
        try? FileManager.default.removeItem(at: NodeManager.stateDirectory(for: id))
        UserDefaults.standard.removeObject(forKey: onlineReportedKey)
        answers = nil
        discovered = []
        seenHosts = []
    }

    // MARK: Status and reachability

    private func poll(generation current: Int) async {
        while !Task.isCancelled, current == generation, let node {
            if let data = try? await node.statusJSON(),
               let status = await Task.detached(operation: { TailnetStatus.decode(data) }).value, current == generation {
                // Polls repeat while nothing changes; only a change should redraw.
                if status != self.status {
                    self.status = status
                    if isOwner, let suffix = status.tailnet?.magicDNSSuffix, !suffix.isEmpty {
                        Self.rememberOwnerSuffix(suffix)
                    }
                }
                send(status.event)
                if isOwner {
                    watchOwnerLogin(status)
                } else {
                    watchLogin(status.backendState)
                    watchPeers(status)
                    if !onlineReported, GuestProbe.reportsOnline(status) { UserDefaults.standard.set(true, forKey: onlineReportedKey) }
                }
                if state == .connecting || { if case .retrying = state { return true } else { return false } }() {
                    await checkReachability(generation: current)
                    continue
                }
                if state == .ready, Discovery.lookAgain(hostsChanged: probeHosts != probedHosts, lastLook: lastProbe,
                                                        missed: lastProbeMissed, now: .now) {
                    await rediscover()
                }
            }
            // Quick while the node comes up, so an app opened at launch waits no longer than it
            // must, but not while the phone has no network to come up on.
            let quick = (state == .starting || state == .connecting) && NetworkPath.shared.isSatisfied
            let interval: Duration = state == .ready ? .seconds(10) : quick ? .milliseconds(250) : .seconds(1)
            try? await Task.sleep(for: interval)
        }
    }

    private func watchLogin(_ backend: String) {
        loginLostPolls = backend == "NeedsLogin" ? loginLostPolls + 1 : 0
        if loginLostPolls == 3 { onLoginLost() }
    }

    /// tsnet asks for a sign-in page only as the node starts, so an owner node whose key
    /// expires while it runs would wait at NeedsLogin with no page. After some polls like
    /// that it asks once, and the page shows up in the status as at first sign-in. It
    /// waits longer than a guest's count, since the first sign-in's own page can take a
    /// few seconds.
    private func watchOwnerLogin(_ status: TailnetStatus) {
        let noPage = status.backendState == "NeedsLogin" && status.authURL.isEmpty
        loginLostPolls = noPage ? loginLostPolls + 1 : 0
        guard loginLostPolls == 10, let request = localAPIRequest("login-interactive", method: "POST", timeout: 5) else { return }
        log.notice("owner: needs a login and has no sign-in page, asking for one")
        Task { _ = try? await URLSession.shared.data(for: request) }
    }

    /// Counts polls that found an app host missing from the peers of a connected node; any
    /// poll that can't tell starts the count over.
    private func watchPeers(_ status: TailnetStatus) {
        let hosts = Set(guestHosts().map(Discovery.normalizedHost))
        guard state == .ready, let absent = GuestProbe.absentHosts(hosts, in: status) else {
            absence = PeerAbsence()
            return
        }
        let gone = absence.record(absent)
        if !gone.isEmpty { onPeersGone(gone) }
    }

    private var probeHosts: [String] {
        isOwner ? Discovery.candidateHosts(in: status) : GuestProbe.hostsToProbe(guestHosts(), in: status, onlineReported: onlineReported)
    }

    /// The network lists this app's computer as offline. It may still answer (a peer can be
    /// listed offline for a moment while it works), so this never stops a probe.
    func isListedOffline(_ host: String) -> Bool {
        onlineReported && !Discovery.offlineHosts([host], in: status).isEmpty
    }

    /// Whether a peer's Online in the status can be believed: always for the owner, whose
    /// discovery has always read it, and for a guest once its network has listed a peer
    /// online (see `GuestProbe.reportsOnline`), remembered across launches.
    private var onlineReported: Bool { isOwner || UserDefaults.standard.bool(forKey: onlineReportedKey) }
    private var onlineReportedKey: String { "onlineReported.\(id)" }

    private func probe(_ hosts: [String], firstAnswer: @MainActor () -> Void = {}) async -> Discovery.Outcome {
        lastProbe = .now
        let outcome: Discovery.Outcome
        if isOwner {
            outcome = await Discovery.probe(hosts: hosts, session: session(), firstAnswer: firstAnswer)
        } else {
            // A guest's hosts count as looked at even when none answers: they come back with
            // a change to the list, or in a minute, not every poll.
            probedHosts = hosts
            let guest = await GuestProbe.probe(hosts: hosts, session: session(), firstAnswer: firstAnswer)
            if !guest.notInvited.isEmpty { onNotInvited(guest.notInvited) }
            outcome = guest.base
        }
        // An owner's laptop or TV without a connector never answers: only app hosts count.
        let watched = isOwner
            ? Discovery.watchedHosts(hosts, in: status, found: Set(discovered.map(\.host)), seenBefore: seenHosts)
            : Set(hosts)
        // Seen only by a look whose path worked: one that failed outright says nothing of them.
        if isOwner, outcome.reachable { seenHosts.formUnion(hosts) }
        lastProbeMissed = watched.contains { outcome.answered[$0] != true }
        return outcome
    }

    /// Once the node is up, probe the machines that might serve its apps. Any HTTP answer
    /// proves the path works; only connection-level failures (NSURLError -1000 through
    /// the SOCKS proxy, timeouts) count as "not yet" and are retried with backoff.
    private func checkReachability(generation current: Int) async {
        let hosts = probeHosts
        // The first answer proves the path: apps may load while the rest are still asked.
        let outcome = await probe(hosts) { [self] in if current == generation { send(.reachable) } }
        guard current == generation else { return }
        record(outcome, probed: hosts)
        if outcome.reachable || hosts.isEmpty {
            if isOwner, discovered != outcome.apps { discovered = outcome.apps }
            send(.reachable)
        } else {
            send(.unreachable)
            if case .retrying(let attempt) = state {
                try? await Task.sleep(for: .seconds(min(0.5 * pow(2, Double(attempt - 1)), 4)))
            } else if state == .ready, isOwner, discovered != outcome.apps {
                discovered = outcome.apps
            }
        }
    }

    /// Looks again: newly published apps for the owner (pull to refresh), and for a guest
    /// whether its apps still answer and still admit it.
    func rediscover() async {
        guard state == .ready else { return }
        let current = generation
        let hosts = probeHosts
        let outcome = await probe(hosts)
        guard current == generation else { return }
        record(outcome, probed: hosts)
        if outcome.reachable, isOwner, discovered != outcome.apps { discovered = outcome.apps }
        // A phone with no network at all hears nothing from anyone: that look says nothing.
        guard !isOwner, NetworkPath.shared.isSatisfied, silence.record(probed: hosts, reachable: outcome.reachable) else { return }
        log.notice("\(self.id, privacy: .public): no app has answered for a while, restarting the node")
        Task { await restart() }
    }

    private func record(_ outcome: Discovery.Outcome, probed hosts: [String]) {
        // Only a working path, or no machine online at all, says anything about them.
        guard outcome.reachable || hosts.isEmpty else { return }
        probedHosts = hosts
        let answers = Dictionary(uniqueKeysWithValues: hosts.map { ($0, outcome.answered[$0] ?? false) })
        if answers != self.answers { self.answers = answers }
    }

    /// Checks that this app's host answers through the node's current proxy. Any HTTP
    /// response counts; only connection-level failures mean "not yet". A guest asks the
    /// connector who it is, so an ended share shows up as `.notInvited`.
    func canReach(_ app: WebApp) async -> Reach {
        guard state == .ready, let session = session() else { return .unreachable }
        let host = Discovery.normalizedHost(app.host)
        let reach: Reach
        if isOwner {
            guard let url = URL(string: OvenlightManifest.path, relativeTo: app.startURL)?.absoluteURL else { return .unreachable }
            reach = (try? await session.data(from: url)) != nil ? .reached : .unreachable
        } else {
            reach = await GuestProbe.whoami(host: host, session: session)
        }
        // A probe cut short says nothing about the host.
        guard !Task.isCancelled else { return .unreachable }
        answers?[host] = reach != .unreachable
        // Look again in a minute, as after any look that missed a host.
        if reach == .unreachable { lastProbeMissed = true }
        return reach
    }

    private func send(_ event: NodeEvent) {
        let next = NodeMachine.next(state, event)
        if next != state {
            log.notice("\(self.id, privacy: .public): state \(self.state.logDescription, privacy: .public) -> \(next.logDescription, privacy: .public)")
            state = next
        }
    }

    // MARK: Routing

    /// Routes this data store through the node, now and after every restart.
    func attach(_ store: WKWebsiteDataStore, for appID: UUID) {
        stores[appID] = store
        store.proxyConfigurations = [proxy ?? Self.closedProxy]
    }

    func detach(_ appID: UUID) {
        stores[appID] = nil
    }

    /// For a store that must go through a node that has no proxy yet (or is gone): it
    /// refuses every connection, where no proxy at all would load directly. Failover is
    /// off by default, so it never falls back.
    static let closedProxy = ProxyConfiguration(socksv5Proxy: .hostPort(host: .ipv4(.loopback), port: 1))

    /// A short-lived session through the node for quick checks and page metadata; nil
    /// until the node is up.
    func session() -> URLSession? {
        if let cachedSession { return cachedSession }
        guard let session = makeSession(timeout: 4, resource: 8) else { return nil }
        cachedSession = session
        return session
    }

    /// A session through the node with its own timeouts, for claims, admin calls and
    /// feedback uploads. The caller invalidates it.
    func makeSession(timeout: TimeInterval, resource: TimeInterval? = nil) -> URLSession? {
        guard let proxy else { return nil }
        let config = URLSessionConfiguration.ephemeral
        config.proxyConfigurations = [proxy]
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = resource ?? timeout * 2
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        return URLSession(configuration: config)
    }
}

/// How a guest node checks its apps: `/__ovenlight/whoami` answers 200 for an admitted
/// guest and 403 `not_invited` for anyone else.
enum GuestProbe {
    struct Outcome {
        var base = Discovery.Outcome()
        var notInvited: Set<String> = []
    }

    /// `firstAnswer` as in `Discovery.probe`.
    static func probe(hosts: [String], session: URLSession?, firstAnswer: @MainActor () -> Void = {}) async -> Outcome {
        guard let session, !hosts.isEmpty else { return Outcome() }
        return await withTaskGroup(of: (String, Reach).self) { group in
            for host in hosts {
                group.addTask { (host, await whoami(host: host, session: session)) }
            }
            var outcome = Outcome()
            for await (host, reach) in group {
                let answered = reach != .unreachable
                if answered, !outcome.base.reachable { await firstAnswer() }
                outcome.base.reachable = outcome.base.reachable || answered
                outcome.base.answered[host] = answered
                if reach == .notInvited { outcome.notInvited.insert(host) }
            }
            return outcome
        }
    }

    /// The app hosts worth probing: not those whose computer the network lists as offline,
    /// which can't answer, once the network is known to report Online (`onlineReported`).
    /// Like the owner's offline machines, they are then missing from the answers, so their
    /// tiles show Offline at once.
    static func hostsToProbe(_ hosts: [String], in status: TailnetStatus?, onlineReported: Bool) -> [String] {
        let hosts = Set(hosts.map(Discovery.normalizedHost))
        guard onlineReported else { return hosts.sorted() }
        return hosts.subtracting(Discovery.offlineHosts(hosts, in: status)).sorted()
    }

    /// Whether the network tells this node its peers' Online state. A control server leaves
    /// it out for a node it doesn't tell, and the status then says offline for every peer,
    /// so a guest believes it only once it has listed a peer online.
    static func reportsOnline(_ status: TailnetStatus) -> Bool {
        status.peers.contains(where: \.online)
    }

    static func whoami(host: String, session: URLSession) async -> Reach {
        guard let url = URL(string: "https://\(host)/__ovenlight/whoami"),
              let (data, response) = try? await session.data(from: url),
              let http = response as? HTTPURLResponse else { return .unreachable }
        return classify(status: http.statusCode, header: http.value(forHTTPHeaderField: ConnectorError.header), body: data)
    }

    /// 403 `not_invited` ends the share for an unclaimed or removed guest; any other answer,
    /// even an error page that says "not invited" without the code, means the path works.
    static func classify(status: Int, header: String? = nil, body: Data) -> Reach {
        status == 403 && ConnectorError(header: header, body: body) == .notInvited ? .notInvited : .reached
    }

    /// The app hosts missing from a running node's peers. A node's peers are the nodes its
    /// network's policy lets it or them reach, offline ones included, so an app node leaves
    /// the list when the owner stops sharing that app with a guest who keeps another (the
    /// connector retags the guest's device), and stays in it while its machine is off. Nil
    /// when the list can't say: the node isn't running, or none of the hosts is in it, which
    /// is likelier a partial peer list than every share ended (that removes the device).
    static func absentHosts(_ hosts: Set<String>, in status: TailnetStatus) -> Set<String>? {
        guard status.backendState == "Running" else { return nil }
        let absent = hosts.subtracting(status.peers.map { Discovery.normalizedHost($0.dnsName) })
        return absent.count < hosts.count ? absent : nil
    }
}

/// Looks in a row at a guest's apps, about a minute apart, in which none answered though the
/// network listed some online. A running node never learns that the owner deleted its device
/// (control's refusals only back it off), but a restarted one finds it needs a login, which
/// ends the share (`onLoginLost`). So after a few silent looks the node restarts, then waits
/// twice as long before the next, up to an hour; any answer starts over.
struct GuestSilence {
    static let firstRestart = 3
    static let maxGap = 60
    private var looks = 0
    private var gap = firstRestart

    /// Records one look; true when the node should restart.
    mutating func record(probed hosts: [String], reachable: Bool) -> Bool {
        guard !reachable else {
            self = GuestSilence()
            return false
        }
        // Nothing listed online says nothing: the computers are asleep.
        guard !hosts.isEmpty else { return false }
        looks += 1
        guard looks >= gap else { return false }
        looks = 0
        gap = min(gap * 2, Self.maxGap)
        return true
    }
}

/// Polls in a row that found each app host missing from a guest node's peers. A host
/// missing for `pollsToEnd` polls, a few minutes, is taken as no longer shared; one back in
/// the list starts over.
struct PeerAbsence {
    /// Three minutes at the connected node's poll interval.
    static let pollsToEnd = 18
    private var polls: [String: Int] = [:]

    /// Records one poll's missing hosts; returns those missing for `pollsToEnd` polls or
    /// more, every time, so a host skipped once (still being claimed) is caught later.
    mutating func record(_ absent: Set<String>) -> Set<String> {
        polls = Dictionary(uniqueKeysWithValues: absent.map { ($0, (polls[$0] ?? 0) + 1) })
        return Set(polls.filter { $0.value >= Self.pollsToEnd }.keys)
    }
}

/// Every membership's node: the owner's own tailnet (when the owner uses Ovenlight with their
/// own machines) and one guest node per owner whose invite this iPhone accepted.
@MainActor
final class NodeManager: ObservableObject {
    static let shared = NodeManager()

    let owner: MembershipNode
    @Published private(set) var guests: [String: MembershipNode] = [:]
    let memberships: MembershipStore

    /// The owner set Ovenlight up for their own machines. Until then no owner node starts and
    /// nothing mentions signing in, so a guest who only opens invites never sees it.
    @Published private(set) var ownerEnabled: Bool

    private var observers: [String: AnyCancellable] = [:]
    /// Gives each guest node its hooks (see `GuestManager`), the ones already made included.
    var configureGuest: (MembershipNode) -> Void = { _ in } {
        didSet { guests.values.forEach(configureGuest) }
    }

    private init() {
        memberships = MembershipStore()
        ownerEnabled = UserDefaults.standard.bool(forKey: Self.ownerEnabledKey) || Self.debugAuthKey != nil
        owner = MembershipNode(id: Membership.ownerID) {
            .init(controlURL: NodeManager.ownerControlURL, authKey: NodeManager.debugAuthKey, hostName: NodeManager.hostName)
        }
        observe(owner)
        observers["memberships"] = memberships.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        // Every membership has its node from the moment it's saved, so views only look nodes up.
        observers["guests"] = memberships.$memberships.sink { [weak self] memberships in
            MainActor.assumeIsolated { self?.addGuestNodes(for: memberships) }
        }
    }

    private func observe(_ node: MembershipNode) {
        observers[node.id] = node.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }

    // MARK: The owner's own tailnet (what the launcher and Settings show)

    var state: NodeState { owner.state }
    var status: TailnetStatus? { owner.status }
    /// This iPhone as the tailnet lists it (see `deviceName(in:)`).
    var deviceName: String { Self.deviceName(in: owner.status) }
    var discovered: [DiscoveredApp] { owner.discovered }
    /// The owner's own machines (see `Discovery.ownersHosts`).
    var ownersHosts: Set<String> { Discovery.ownersHosts(in: owner.status) }
    /// The ones online now that could run a connector (see `Discovery.candidateHosts`).
    var ownersOnlineHosts: Set<String> { Set(Discovery.candidateHosts(in: owner.status)) }

    /// The owner can manage sharing: signed in and connected.
    var ownerIsConnected: Bool { ownerEnabled && owner.state == .ready }

    /// Owner mode is on and no account is signed in: the node asks for a login, or it has
    /// never been in a tailnet. A signed-in node has no status while it restarts, but its
    /// tailnet is remembered, so that doesn't count.
    var ownerHasNoAccount: Bool {
        guard ownerEnabled else { return false }
        if case .needsLogin = owner.state { return true }
        return status?.account == nil && owner.magicDNSSuffixSeen == nil
    }

    private static let ownerEnabledKey = "ownTailnetEnabled"

    func enableOwner() {
        ownerEnabled = true
        UserDefaults.standard.set(true, forKey: Self.ownerEnabledKey)
        UserDefaults.standard.removeObject(forKey: Self.signOutPendingKey)
        Task { await owner.ensureRunning() }
    }

    /// The owner's node is forgotten: the apps found on the owner's computers go too (the
    /// app sets this).
    var onOwnerForgotten: () async -> Void = {}

    /// Undoes `enableOwner`: Sign Out, and Stop Using My Own Computers before a sign-in. The
    /// owner's node logs out and is forgotten with its tailnet, the apps found on the owner's
    /// computers go with their data, and the launcher goes back to its welcome when nothing
    /// else is saved. Guest nodes are untouched.
    func disableOwner() async {
        ownerEnabled = false
        UserDefaults.standard.set(true, forKey: Self.signOutPendingKey)
        UserDefaults.standard.removeObject(forKey: Self.ownerEnabledKey)
        await owner.logOutAndForget()
        // Once the node has stopped, so no discovery from before can bring them back.
        await forgetOwner()
    }

    private func forgetOwner() async {
        MembershipNode.rememberOwnerSuffix(nil)
        await onOwnerForgotten()
        UserDefaults.standard.removeObject(forKey: Self.signOutPendingKey)
    }

    /// Set from the start of a Sign Out to its end. Apps found before an earlier version's
    /// Sign Out, which kept them, have no such mark and stay.
    static let signOutPendingKey = "ownerSignOutPending"

    /// Finishes a Sign Out that Ovenlight quit during, at launch: the owner's node state and
    /// the apps found on the owner's computers go. Before the first unlock the mark reads as
    /// unset, and the next launch finishes it.
    func finishSignOut() async {
        guard !ownerEnabled, UserDefaults.standard.bool(forKey: Self.signOutPendingKey) else { return }
        try? FileManager.default.removeItem(at: Self.stateDirectory(for: Membership.ownerID))
        await forgetOwner()
    }

    // MARK: Lifecycle

    private var isRunningTests: Bool { ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil }

    /// Brings every node up, checking each live one's listener (see `MembershipNode`).
    func didBecomeActive() async {
        guard !isRunningTests else { return }
        // A prewarmed launch before the first unlock after a restart reads no defaults.
        if !ownerEnabled, UserDefaults.standard.bool(forKey: Self.ownerEnabledKey) { ownerEnabled = true }
        await withTaskGroup(of: Void.self) { group in
            if ownerEnabled {
                group.addTask { await self.owner.ensureRunning() }
            }
            for node in guests.values {
                group.addTask { await node.ensureRunning() }
            }
        }
    }

    func rediscover() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.owner.rediscover() }
            for node in guests.values {
                group.addTask { await node.rediscover() }
            }
        }
    }

    // MARK: Guest nodes

    /// The node for a guest membership. Nil if there is no such membership (it was removed).
    func guestNode(_ membershipID: String) -> MembershipNode? {
        guests[membershipID]
    }

    /// Makes the node of each membership that has none, as memberships load and are added.
    private func addGuestNodes(for memberships: [Membership]) {
        for membershipID in memberships.map(\.id) where guests[membershipID] == nil {
            let node = MembershipNode(id: membershipID) { [weak self] in
                guard let membership = self?.memberships[membershipID] else { return nil }
                return .init(controlURL: membership.controlURL.absoluteString, authKey: membership.joinKey,
                             hostName: membership.hostName)
            }
            guests[membershipID] = node
            observe(node)
            configureGuest(node)
        }
    }

    /// Removals under way, so a second caller (Leave and the orphan sweep) waits for the first.
    private var removals: [String: Task<Void, Never>] = [:]

    /// Logs a guest node out, deletes its state and forgets the membership.
    func removeGuest(_ membershipID: String) async {
        if let removal = removals[membershipID] { return await removal.value }
        let removal = Task {
            if let node = guests[membershipID] {
                await node.logOutAndForget()
            } else {
                try? FileManager.default.removeItem(at: Self.stateDirectory(for: membershipID))
            }
            guests[membershipID] = nil
            observers[membershipID] = nil
            memberships.delete(membershipID)
            removals[membershipID] = nil
        }
        removals[membershipID] = removal
        await removal.value
    }

    // MARK: Routing

    /// The node an app goes through: its guest membership's, the owner's for hosts on the
    /// owner's own tailnet, or none (refused, or localhost in a debug build).
    func node(for app: WebApp) -> MembershipNode? {
        Routing.node(for: app, owner: owner, ownerSuffix: owner.magicDNSSuffixSeen, guest: guestNode)
    }

    func route(for url: URL) -> Routing.Route {
        Routing.route(host: url.host ?? "", ownerSuffix: owner.magicDNSSuffixSeen)
    }

    /// A session for fetching the app's pages: through its node (nil until the node is
    /// up), the shared session for localhost in a debug build, and nil for a refused app.
    func session(for app: WebApp) -> URLSession? {
        if app.isShared { return node(for: app)?.session() }
        switch route(for: app.startURL) {
        case .tailnetNode: return owner.session()
        case .direct: return URLSession.shared
        case .refused: return nil
        }
    }

    /// A session for posting to an app (feedback), with its own timeouts, through the
    /// app's node, or directly for localhost in a debug build; nil for a shared app whose
    /// node is gone and for a refused app. The caller invalidates it.
    func uploadSession(for app: WebApp, timeout: TimeInterval) -> URLSession? {
        if let node = node(for: app) { return node.makeSession(timeout: timeout) }
        guard !app.isShared, route(for: app.startURL) == .direct else { return nil }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        return URLSession(configuration: config)
    }

    func detach(_ appID: UUID) {
        owner.detach(appID)
        for node in guests.values { node.detach(appID) }
    }

    /// Whether an app's tile shows its machine offline.
    func isOffline(_ app: WebApp) -> Bool {
        guard let node = node(for: app) else { return false }
        return Self.isOffline(host: app.host, nodeState: node.state, answers: node.answers)
    }

    /// Tiles only speak up when something is wrong: a machine that didn't answer the last
    /// probe, or wasn't online to be probed. Nothing before the node is ready (the launcher
    /// shows that) or before the first probe.
    nonisolated static func isOffline(host: String, nodeState: NodeState, answers: [String: Bool]?) -> Bool {
        guard nodeState == .ready, let answers else { return false }
        return answers[Discovery.normalizedHost(host)] != true
    }

    // MARK: Configuration

    static func stateDirectory(for membershipID: String) -> URL {
        SavedJSON.directory.appendingPathComponent("tailscale/\(membershipID)", isDirectory: true)
    }

    /// Creates the node's state directory and keeps all of `tailscale/` out of backups:
    /// node keys belong to this iPhone and must not come back on another. tsnet keeps its
    /// backend log files empty, but builds before 1.0.2 left lines in them, sign-in links
    /// included, so they go before the node starts.
    static func makeStateDirectory(for membershipID: String) throws -> URL {
        let directory = stateDirectory(for: membershipID)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for name in ["tailscaled.log1.txt", "tailscaled.log2.txt"] {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
        var parent = directory.deletingLastPathComponent()
        parent.excludeFromBackup()
        return directory
    }

    /// The name the tailnet gave this node, which is the one it asked for (`hostName`)
    /// unless another device already has it; then the control server adds a suffix.
    static func deviceName(in status: TailnetStatus?) -> String {
        let assigned = status?.selfNode?.dnsName.split(separator: ".").first.map(String.init)
        return assigned.flatMap { $0.isEmpty ? nil : $0 } ?? hostName
    }

    /// The owner's node's name, chosen once so the device keeps it. Guest nodes each
    /// have their own (`Membership.hostName`).
    static var hostName: String {
        if let name = UserDefaults.standard.string(forKey: "tailnetHostName") { return name }
        let name = Membership.randomHostName()
        UserDefaults.standard.set(name, forKey: "tailnetHostName")
        return name
    }

    nonisolated static var ownerControlURL: String {
        #if DEBUG
        // Local testing against Headscale: `-controlURL http://127.0.0.1:8080`.
        if let url = UserDefaults.standard.string(forKey: "controlURL") { return url }
        #endif
        return kDefaultControlURL
    }

    private static var debugAuthKey: String? {
        #if DEBUG
        // Local testing only: `-authKey <preauth key>` skips the interactive login.
        return UserDefaults.standard.string(forKey: "authKey")
        #else
        return nil
        #endif
    }
}

/// Whether the phone has any network path, as NWPathMonitor reports it. Without one an app
/// gives up opening at once (You're Offline) and tries again when one comes back, and a
/// guest's silent looks don't count.
@MainActor
final class NetworkPath: ObservableObject {
    static let shared = NetworkPath()

    /// True until the monitor's first report, which comes a moment after it starts.
    @Published private(set) var isSatisfied = true
    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { path in
            let satisfied = path.status == .satisfied
            MainActor.assumeIsolated {
                if NetworkPath.shared.isSatisfied != satisfied { NetworkPath.shared.isSatisfied = satisfied }
            }
        }
        monitor.start(queue: .main)
    }

    /// Returns once there is a path, or the task is cancelled.
    func satisfied() async {
        for await satisfied in $isSatisfied.values where satisfied { return }
    }
}

/// The first of several racing results; later offers are ignored.
final class FirstResult<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Value?
    private var waiter: CheckedContinuation<Value, Never>?

    var isSettled: Bool { lock.withLock { result != nil } }

    func offer(_ value: Value) {
        let waiter: CheckedContinuation<Value, Never>? = lock.withLock {
            guard result == nil else { return nil }
            result = value
            defer { self.waiter = nil }
            return self.waiter
        }
        waiter?.resume(returning: value)
    }

    var value: Value {
        get async {
            await withCheckedContinuation { continuation in
                let ready: Value? = lock.withLock {
                    if let result { return result }
                    waiter = continuation
                    return nil
                }
                if let ready { continuation.resume(returning: ready) }
            }
        }
    }
}

/// Sends the Go side's log lines to the unified log.
private final class GoLogSink: LogSink, @unchecked Sendable {
    let logFileHandle: Int32? = nil
    private let log = Logger(subsystem: "com.snowyghost.ovenlight", category: "tailscale")

    func log(_ message: String) {
        log.debug("\(message, privacy: .public)")
    }
}
