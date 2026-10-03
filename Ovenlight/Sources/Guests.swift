import Foundation
import os
import TailscaleKit

/// Why joining someone's app didn't work. Every message is neutral: guests see them.
enum JoinError: Error, Equatable {
    /// The control server refused the key because it was used before.
    case keyUsed
    case expired
    /// The control server refused the key for another reason.
    case keyRefused
    /// The connector didn't accept the claim (the invite was canceled or never existed).
    case notInvited
    case usedOnAnotherDevice
    /// The invite is for someone else, and this iPhone is already set up as another guest.
    case someoneElses
    /// Couldn't reach the owner's network or app in time. The invite is kept to retry.
    case network
    case canceled
    /// The invite is for one of this iPhone's own apps.
    case ownApp
    /// The key registered the node in a network other than the one the invite names.
    case wrongNetwork

    /// Reads the message `up()` fails with when the control server refuses the key:
    /// "authkey already used", "authkey expired" (Headscale), "invalid key: ..." (Tailscale).
    init(upError error: Error) {
        let message: String
        if case .internalError(let details) = error as? TailscaleError {
            message = details ?? ""
        } else {
            message = String(describing: error)
        }
        self.init(upMessage: message)
    }

    init(upMessage message: String) {
        let text = message.lowercased()
        if text.contains("expired") {
            self = .expired
        } else if text.contains("already used") || text.contains("used up") {
            self = .keyUsed
        } else if text.contains("invalid key") || text.contains("authkey") || text.contains("auth key") || text.contains("not valid") {
            self = .keyRefused
        } else if text.contains("closed") {
            self = .canceled
        } else {
            self = .network
        }
    }

    /// Kept for Try Again: nothing says the invite itself is bad.
    var isRetryable: Bool { self == .network }

    func title(owner: String, app: String) -> String {
        switch self {
        case .keyUsed: "This Invite Was Already Used"
        // A connector expires an invite's key once it's claimed, so "expired" also means used.
        case .expired: "This Invite No Longer Works"
        case .keyRefused, .notInvited: "This Invite Didn't Work"
        case .usedOnAnotherDevice: "This Invite Was Used on Another iPhone"
        case .someoneElses: "This Invite Is for Someone Else"
        case .network: "Can't Reach \(owner)'s \(app)"
        case .canceled: "Joining Canceled"
        case .ownApp: "\(app) Is Already Yours"
        case .wrongNetwork: "This Invite Leads Somewhere Else"
        }
    }

    func message(owner: String, app: String) -> String {
        switch self {
        case .keyUsed, .usedOnAnotherDevice: "Each invite works once. Ask \(owner) for a new one."
        case .expired: "Invites expire, and each works only once. Ask \(owner) for a new one."
        case .keyRefused, .notInvited: "\(owner) may have canceled it. Ask \(owner) for a new one."
        case .someoneElses: "This iPhone already joined \(owner)'s apps under another name. Ask \(owner) to invite that name instead."
        case .network: "Check that you're online. \(owner)'s computer may also be asleep or offline. Try again in a moment."
        case .canceled: "Nothing was added to Ovenlight."
        case .ownApp: "This invite is for one of your own apps. Open it from your apps instead."
        case .wrongNetwork: "It didn't lead to \(owner)'s \(app), so Ovenlight disconnected and added nothing. Ask \(owner) for a new one."
        }
    }
}

/// Where accepting one invite is.
enum JoinPhase: Equatable {
    case joining
    case claiming
    case joined(UUID)
    case failed(JoinError)
}

/// The connector's answer to `POST /__ovenlight/claim`.
struct ClaimResponse: Decodable, Equatable {
    var app: String?
    var appName: String?
    var owner: String?

    /// The names to save, cleaned like an invite's, with the invite's for any left out.
    func names(for invite: InviteLink) -> (owner: String, appName: String, app: String) {
        (InviteLink.label(owner) ?? invite.owner, InviteLink.label(appName) ?? invite.name, InviteLink.label(app) ?? invite.app)
    }
}

enum Claim {
    enum Step: Equatable {
        case claimed(ClaimResponse)
        /// Not yet: the connector hasn't seen this device, or answered with a server error.
        case retry
        case failed(JoinError)
    }

    static let path = "/__ovenlight/claim"

    enum Landing: Equatable {
        case inNetwork
        /// The node couldn't say (it was just replaced, or didn't answer): try again later.
        case unknown
        /// In another network. `leave`: this join registered the node, so it logs out;
        /// a node that joined before keeps its login and the owner's other apps.
        case elsewhere(leave: Bool)
    }

    /// Where a guest node is, against the network the invite names.
    static func landing(of invite: InviteLink, suffix: String?, registeredNow: Bool) -> Landing {
        guard let suffix, !suffix.isEmpty else { return .unknown }
        return invite.isNetwork(suffix) ? .inNetwork : .elsewhere(leave: registeredNow)
    }

    static func request(for invite: InviteLink) -> URLRequest {
        var request = URLRequest(url: URL(string: "https://\(invite.host)\(path)")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(["key": invite.key])
        return request
    }

    /// Reads one answer. Only the connector's codes are final: `not_invited`,
    /// `used_elsewhere`, `other_person`, and a request it refuses (`bad_request`,
    /// `not_found`). Anything else, `unknown_caller` (its netmap doesn't have the new device
    /// yet), server errors and answers without a code among them, is worth another try.
    static func interpret(status: Int, header: String? = nil, body: Data) -> Step {
        if status == 200 {
            guard let response = try? JSONDecoder().decode(ClaimResponse.self, from: body) else { return .retry }
            return .claimed(response)
        }
        switch ConnectorError(header: header, body: body) {
        case .usedElsewhere: return .failed(.usedOnAnotherDevice)
        case .otherPerson: return .failed(.someoneElses)
        case .notInvited, .badRequest, .notFound: return .failed(.notInvited)
        default: return .retry
        }
    }
}

/// Someone's apps stopped being shared with this iPhone.
struct AccessEndedNotice: Identifiable, Equatable {
    let id = UUID()
    var owner: String
    var apps: [String]

    var title: String { ConnectionCopy.noLongerSharedTitle }
    var message: String { ConnectionCopy.noLongerShared(apps: apps, owner: owner) }
}

/// Guest memberships from invites: joining, claiming, resuming an interrupted join, and
/// taking apps away when their owner stops sharing them.
@MainActor
final class GuestManager: ObservableObject {
    static let shared = GuestManager()

    /// Set once at launch; the launcher's apps.
    weak var registry: AppRegistry?
    weak var router: Router?

    /// Invites being accepted this session, by invite ID.
    @Published private(set) var phases: [String: JoinPhase] = [:]
    @Published var notice: AccessEndedNotice?

    private var inFlight: Set<String> = []
    /// The last join queued for each membership; each waits for the one before.
    private var joins: [String: Task<Void, Never>] = [:]
    private var verifying: Set<UUID> = []
    private var nodes: NodeManager { .shared }
    private var store: MembershipStore { NodeManager.shared.memberships }
    private let log = Logger(subsystem: "com.snowyghost.ovenlight", category: "guests")

    private init() {
        NodeManager.shared.configureGuest = { [weak self] node in self?.wire(node) }
    }

    private func wire(_ node: MembershipNode) {
        let id = node.id
        node.guestHosts = { [weak self] in
            guard let self else { return [] }
            let apps = (registry?.apps ?? []).filter { $0.membershipID == id }.map(\.host)
            return apps + (store[id]?.pendingClaims.map(\.invite.host) ?? [])
        }
        node.onNotInvited = { [weak self] hosts in
            guard let self, let membership = store[id], let apps = registry?.apps else { return }
            for app in GuestCleanup.revokedApps(in: membership, apps: apps, notInvited: hosts) {
                Task { await self.verifyAccess(to: app) }
            }
        }
        // The connector can't say "not invited" through a path that's gone: the owner
        // stopped sharing an app with a guest who keeps another (see `GuestProbe.absentHosts`).
        node.onPeersGone = { [weak self] hosts in
            guard let self, let membership = store[id], let apps = registry?.apps else { return }
            let gone = GuestCleanup.revokedApps(in: membership, apps: apps, notInvited: hosts)
            guard !gone.isEmpty else { return }
            log.info("\(id, privacy: .public): \(hosts.sorted().joined(separator: ", "), privacy: .public) left the peers")
            accessEnded(for: gone)
        }
        node.onLoginLost = { [weak self] in
            guard let self, let membership = store[id],
                  GuestCleanup.isRemoved(membership) else { return }
            log.info("\(id, privacy: .public): the owner removed this device")
            store.update(id) { $0.pendingClaims = [] }
            let apps = (registry?.apps ?? []).filter { $0.membershipID == id }
            Task {
                if apps.isEmpty {
                    await self.nodes.removeGuest(id)
                } else {
                    self.accessEnded(for: apps)
                }
            }
        }
    }

    // MARK: Joining

    enum Preflight: Equatable {
        case ready
        case alreadyAdded(WebApp)
        case ownApp
    }

    /// What accepting this invite would do, for the confirmation sheet.
    func preflight(_ invite: InviteLink) -> Preflight {
        if nodes.ownerEnabled, nodes.status?.account != nil, invite.isNetwork(nodes.status?.tailnet?.magicDNSSuffix) {
            return .ownApp
        }
        if let app = registry?.apps.first(where: { app in
            guard let id = app.membershipID, let membership = store[id] else { return false }
            return membership.matches(invite) && Discovery.normalizedHost(app.host) == invite.host
        }) {
            return .alreadyAdded(app)
        }
        return .ready
    }

    func membershipID(for inviteID: String) -> String? {
        store.memberships.first { $0.pendingClaims.contains { $0.invite.invite == inviteID } }?.id
    }

    /// Joins the owner's network with the invite's key (or reuses the node already there
    /// for this owner), claims the invite at the app's connector, and adds the app.
    func accept(_ invite: InviteLink) async {
        switch preflight(invite) {
        case .ownApp:
            phases[invite.invite] = .failed(.ownApp)
            return
        case .alreadyAdded, .ready:
            // Claimed again even for an app already here: after the owner removed it, a
            // new invite must reach the connector (an old link just fails quietly).
            break
        }
        // An existing membership keeps its names until this invite's claim succeeds.
        var membership = store.membership(for: invite) ?? Membership(invite: invite)
        if !membership.pendingClaims.contains(where: { $0.invite.invite == invite.invite }) {
            membership.pendingClaims.append(PendingClaim(invite: invite, accepted: .now))
        }
        store.upsert(membership)
        await run(invite.invite, in: membership.id)
    }

    /// Try Again after a network failure.
    func retry(_ inviteID: String) async {
        guard let id = membershipID(for: inviteID) else { return }
        await run(inviteID, in: id)
    }

    /// Withdraws from an invite that hasn't finished. A membership left with nothing leaves;
    /// one that keeps another invite stops a node registering with this one's key (the
    /// next start registers with the next invite's).
    func cancel(_ inviteID: String) async {
        phases[inviteID] = nil
        guard let id = membershipID(for: inviteID), let membership = store[id] else { return }
        store.update(id) { $0.pendingClaims.removeAll { $0.invite.invite == inviteID } }
        await collectOrphans()
        if store[id] != nil, GuestCleanup.isRegistering(membership, with: inviteID) { await nodes.guestNode(id)?.stop() }
    }

    /// Finishes joins interrupted by a relaunch or a lost connection.
    func resumePending() async {
        for membership in store.memberships {
            for claim in membership.pendingClaims where !inFlight.contains(claim.invite.invite) {
                if case .failed(let error) = phases[claim.invite.invite], !error.isRetryable { continue }
                Task { await run(claim.invite.invite, in: membership.id) }
            }
        }
    }

    private func run(_ inviteID: String, in membershipID: String) async {
        guard !inFlight.contains(inviteID) else { return }
        inFlight.insert(inviteID)
        defer { inFlight.remove(inviteID) }
        // One invite at a time per membership: a node that hasn't joined registers with
        // one key, and the next invite needs the node started over with its own.
        phases[inviteID] = .joining
        await inOrder(in: membershipID) { await self.join(inviteID, in: membershipID) }
    }

    private func inOrder(in membershipID: String, _ work: @escaping @MainActor () async -> Void) async {
        let previous = joins[membershipID]
        let task = Task { @MainActor in
            await previous?.value
            await work()
        }
        joins[membershipID] = task
        await task.value
        if joins[membershipID] == task { joins[membershipID] = nil }
    }

    private func join(_ inviteID: String, in membershipID: String) async {
        guard let invite = pendingInvite(inviteID, in: membershipID), let node = nodes.guestNode(membershipID) else {
            phases[inviteID] = nil
            return
        }
        // Put this invite's key first, and restart a node started with another.
        if store[membershipID]?.joined == false, store[membershipID]?.pendingClaims.first?.invite.invite != inviteID {
            store.update(membershipID) { membership in
                if let i = membership.pendingClaims.firstIndex(where: { $0.invite.invite == inviteID }) {
                    membership.pendingClaims.insert(membership.pendingClaims.remove(at: i), at: 0)
                }
            }
            await node.stop() // after any start under way, which then closes its node
        }

        log.info("joining \(invite.host, privacy: .public) (invite \(inviteID, privacy: .public), membership \(membershipID, privacy: .public))")
        let wasJoined = store[membershipID]?.joined == true
        var up: Result<Void, JoinError> = .failure(.canceled)
        // A foreground can replace a node whose listener iOS reclaimed, which ends this
        // wait; keep waiting on the new node while the invite is still wanted.
        for _ in 0..<5 {
            if !node.isRunning { await node.start() }
            if store[membershipID]?.joined == true {
                up = await waitForRunning(node, timeout: .seconds(30))
            } else {
                up = await node.waitUntilUp(timeout: .seconds(45))
            }
            guard case .failure(.canceled) = up, pendingInvite(inviteID, in: membershipID) != nil else { break }
            try? await Task.sleep(for: .milliseconds(500))
        }
        guard pendingInvite(inviteID, in: membershipID) != nil else {
            phases[inviteID] = nil // canceled by the person
            return
        }
        if case .failure(let error) = up {
            return await fail(inviteID, in: membershipID, error, node: node)
        }
        // The key must have put the node in the network the invite names; a link that
        // pairs someone's app with another network's key would otherwise claim there.
        let suffix = await node.magicDNSSuffix()
        switch Claim.landing(of: invite, suffix: suffix, registeredNow: !wasJoined) {
        case .inNetwork:
            break
        case .unknown:
            return await fail(inviteID, in: membershipID, .network, node: node)
        case .elsewhere(let leave):
            log.error("invite \(inviteID, privacy: .public) names \(invite.networkDomain, privacy: .public) but the node is in \(suffix ?? "", privacy: .public)")
            if leave { await node.logOutAndForget() }
            return await fail(inviteID, in: membershipID, .wrongNetwork, node: node)
        }
        store.update(membershipID) { $0.joined = true }

        phases[inviteID] = .claiming
        let outcome = await claim(invite, through: node)
        guard pendingInvite(inviteID, in: membershipID) != nil else {
            phases[inviteID] = nil // canceled while claiming; nothing is added
            return
        }
        switch outcome {
        case .claimed(let response):
            let (owner, appName, slug) = response.names(for: invite)
            let app = registry?.addShared(name: appName, host: invite.host, slug: slug, membershipID: membershipID, sharedBy: owner)
            store.update(membershipID) {
                $0.pendingClaims.removeAll { $0.invite.invite == inviteID }
                $0.ownerName = owner
                if !invite.to.isEmpty { $0.guestName = invite.to }
            }
            log.info("claimed \(invite.host, privacy: .public)")
            if let app { phases[inviteID] = .joined(app.id) }
            await node.rediscover()
        case .failed(let error):
            await fail(inviteID, in: membershipID, error, node: node)
        case .retry:
            await fail(inviteID, in: membershipID, .network, node: node)
        }
    }

    private func fail(_ inviteID: String, in membershipID: String, _ error: JoinError, node: MembershipNode) async {
        log.info("invite \(inviteID, privacy: .public) failed: \(String(describing: error), privacy: .public)")
        // An invite for an app already here that couldn't be claimed again (an old link)
        // still leaves the app; say so rather than fail.
        let host = pendingInvite(inviteID, in: membershipID).map { Discovery.normalizedHost($0.host) }
        let existing = error == .wrongNetwork ? nil
            : registry?.apps.first { $0.membershipID == membershipID && Discovery.normalizedHost($0.host) == host }
        phases[inviteID] = existing.map { .joined($0.id) } ?? .failed(error)
        // A node that never joined shouldn't sit on a refused or unused key; the next
        // start registers with the next invite's key.
        if store[membershipID]?.joined == false { await node.stop() }
        if error.isRetryable && existing == nil { return }
        store.update(membershipID) { $0.pendingClaims.removeAll { $0.invite.invite == inviteID } }
        await collectOrphans()
    }

    private func pendingInvite(_ inviteID: String, in membershipID: String) -> InviteLink? {
        store[membershipID]?.pendingClaims.first { $0.invite.invite == inviteID }?.invite
    }

    /// For a node that already joined: wait until it runs again.
    private func waitForRunning(_ node: MembershipNode, timeout: Duration) async -> Result<Void, JoinError> {
        var waited = Duration.zero
        while waited < timeout {
            if node.status?.backendState == "Running" { return .success(()) }
            try? await Task.sleep(for: .milliseconds(500))
            waited += .milliseconds(500)
        }
        return .failure(.network)
    }

    /// Posts the invite's key to the app's connector. Right after joining, the connector
    /// may not know the device yet, so connection failures and "Unknown caller" retry,
    /// for up to `claimTime` in all.
    private func claim(_ invite: InviteLink, through node: MembershipNode) async -> Claim.Step {
        guard let session = node.makeSession(timeout: 8) else { return .retry }
        defer { session.invalidateAndCancel() }
        let deadline = Date.now.addingTimeInterval(Self.claimTime)
        var attempt = 0.0
        while Date.now < deadline {
            if let (data, response) = try? await session.data(for: Claim.request(for: invite)),
               let http = response as? HTTPURLResponse {
                let step = Claim.interpret(status: http.statusCode, header: http.value(forHTTPHeaderField: ConnectorError.header), body: data)
                if step != .retry { return step }
                log.info("claim answered \(http.statusCode), trying again")
            }
            try? await Task.sleep(for: .seconds(min(0.5 * pow(2, attempt), 4)))
            attempt += 1
        }
        return .retry
    }

    static let claimTime: TimeInterval = 25

    // MARK: Access ending

    /// Checks with the app's connector, and takes the app away if it answers "not invited".
    func verifyAccess(to app: WebApp) async {
        guard app.isShared, !verifying.contains(app.id), let node = nodes.node(for: app) else { return }
        verifying.insert(app.id)
        defer { verifying.remove(app.id) }
        if await node.canReach(app) == .notInvited { accessEnded(for: [app]) }
    }

    /// Takes apps away whose owner stopped sharing them: closes them, wipes their data
    /// (a service worker would otherwise keep serving cached pages), and says so once.
    func accessEnded(for apps: [WebApp]) {
        let apps = apps.filter { app in registry?.apps.contains { $0.id == app.id } == true }
        guard let first = apps.first else { return }
        log.info("access ended for \(apps.map(\.host).joined(separator: ", "), privacy: .public)")
        if let open = router?.openApp, apps.contains(where: { $0.id == open.id }) {
            router?.openApp = nil
        }
        let notice = AccessEndedNotice(owner: first.sharedBy ?? "Its owner", apps: apps.map(\.name))
        Task {
            for app in apps { await registry?.remove(app.id) }
            await collectOrphans()
            // An alert can't present over the app's cover or a sheet.
            router?.afterClear { self.notice = notice }
        }
    }

    /// Leaves someone's network on purpose: removes their apps and data, logs out.
    func leave(_ membershipID: String) async {
        store.update(membershipID) { $0.pendingClaims = [] }
        for app in (registry?.apps ?? []) where app.membershipID == membershipID {
            await registry?.remove(app.id)
        }
        await nodes.removeGuest(membershipID)
    }

    /// Memberships left with no app and no invite waiting log out and delete their state.
    func collectOrphans() async {
        // With either list unread, everything would look orphaned.
        guard let registry, !registry.loadFailed, !store.loadFailed else { return }
        // A shared app whose membership is gone can't be reached or checked any more.
        for app in registry.apps where app.isShared && app.membershipID.flatMap({ store[$0] }) == nil {
            await registry.remove(app.id)
        }
        let apps = registry.apps
        for id in GuestCleanup.orphans(store.memberships, apps: apps) {
            log.info("\(id, privacy: .public): no apps left, leaving")
            await nodes.removeGuest(id)
        }
    }
}

extension GuestCleanup {
    /// Whether a node that hasn't joined registers with this invite's key: the first
    /// waiting invite's (see `Membership.joinKey`).
    static func isRegistering(_ membership: Membership, with inviteID: String) -> Bool {
        !membership.joined && membership.pendingClaims.first?.invite.invite == inviteID
    }
}
