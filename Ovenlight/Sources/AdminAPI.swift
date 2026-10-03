import Foundation
import os

// The owner's admin API on the connector: HTTPS on port 8443 of every app node, JSON,
// owner only (WhoIs), closed to guests by policy. Changes need `X-Ovenlight-Request: 1`.
// Types mirror the views in connector/wire.go (its testdata/wire files pin them). Only
// what identifies a record is required; the rest falls back, and each list decodes one
// record at a time, so a connector newer or older than this app still fills its screens.

/// One published app, as `GET /v1/apps` lists it.
struct AdminApp: Decodable, Equatable, Identifiable {
    var slug: String
    var name: String
    var url: String?
    /// The app answers on its local port.
    var online: Bool
    var shareable: Bool
    var guests: Int

    var id: String { slug }
    var host: String? { url.flatMap { URL(string: $0)?.host }.map(Discovery.normalizedHost) }
}

extension AdminApp {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        slug = try c.decode(String.self, forKey: .slug)
        name = try c.decode(String.self, forKey: .name)
        url = try? c.decodeIfPresent(String.self, forKey: .url)
        online = (try? c.decodeIfPresent(Bool.self, forKey: .online)) ?? false
        shareable = (try? c.decodeIfPresent(Bool.self, forKey: .shareable)) ?? false
        guests = (try? c.decodeIfPresent(Int.self, forKey: .guests)) ?? 0
    }

    private enum CodingKeys: String, CodingKey { case slug, name, url, online, shareable, guests }
}

struct AdminInvite: Decodable, Equatable, Identifiable {
    static let sent = "sent"

    var id: String
    var to: String
    /// Who the invite admits; see `AdminGuest.person`.
    var person: String
    var app: String
    var state: String
    var expires: Date
    /// Made with `ovenlight share --review` for Apple's App Review.
    var review: Bool?
}

extension AdminInvite {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        app = try c.decode(String.self, forKey: .app)
        state = try c.decode(String.self, forKey: .state)
        expires = try c.decode(Date.self, forKey: .expires)
        to = (try? c.decodeIfPresent(String.self, forKey: .to)) ?? ""
        person = (try? c.decodeIfPresent(String.self, forKey: .person)) ?? ""
        review = try? c.decodeIfPresent(Bool.self, forKey: .review)
    }

    private enum CodingKeys: String, CodingKey { case id, to, person, app, state, expires, review }
}

struct AdminGuest: Decodable, Equatable, Identifiable {
    var deviceId: String
    /// The person the device belongs to, the same on every device and app of theirs.
    var person: String
    var name: String
    var app: String
    var deviceName: String
    var claimedAt: Date
    var removedAt: Date?

    var id: String { "\(deviceId)/\(app)" }
    var isActive: Bool { removedAt == nil }
}

extension AdminGuest {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try c.decode(String.self, forKey: .deviceId)
        person = try c.decode(String.self, forKey: .person)
        app = try c.decode(String.self, forKey: .app)
        claimedAt = try c.decode(Date.self, forKey: .claimedAt)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        deviceName = (try? c.decodeIfPresent(String.self, forKey: .deviceName)) ?? ""
        removedAt = try? c.decodeIfPresent(Date.self, forKey: .removedAt)
    }

    private enum CodingKeys: String, CodingKey { case deviceId, person, name, app, deviceName, claimedAt, removedAt }
}

/// One person's active devices, for listing and removing guests together.
struct GuestPerson: Identifiable, Equatable {
    var id: String
    var name: String
    var guests: [AdminGuest]

    /// Groups active guests by person, in order of who joined first.
    static func all(_ guests: [AdminGuest]) -> [GuestPerson] {
        var out: [GuestPerson] = []
        for guest in guests where guest.isActive {
            if let i = out.firstIndex(where: { $0.id == guest.person }) {
                out[i].guests.append(guest)
                out[i].name = guest.name
            } else {
                out.append(GuestPerson(id: guest.person, name: guest.name, guests: [guest]))
            }
        }
        return out
    }

    /// Everyone shared with or invited on a machine, any app, as the connector counts them
    /// when it checks a name.
    static func everyone(in list: AdminGuestList) -> [GuestPerson] {
        var out = all(list.guests)
        for invite in list.invites where !out.contains(where: { $0.id == invite.person }) {
            out.append(GuestPerson(id: invite.person, name: invite.to, guests: []))
        }
        return out
    }

    /// Those to offer for another invite: everyone but App Review while its review invite
    /// (made in the terminal) is unused. Once used, App Review is a guest like any other,
    /// until the review is over and it's revoked.
    static func invitable(in list: AdminGuestList) -> [GuestPerson] {
        let review = Set(list.invites.filter { $0.review == true }.map(\.person))
        return everyone(in: list).filter { !review.contains($0.id) }
    }

    /// Device names, once each: "sam-iphone, sam-ipad".
    var devices: String {
        var names: [String] = []
        for guest in guests {
            let name = guest.deviceName.isEmpty ? "iPhone" : guest.deviceName
            if !names.contains(name) { names.append(name) }
        }
        return names.joined(separator: ", ")
    }
}

struct AdminGuestList: Decodable, Equatable {
    var guests: [AdminGuest]
    var invites: [AdminInvite]

    init(guests: [AdminGuest] = [], invites: [AdminInvite] = []) {
        self.guests = guests
        self.invites = invites
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guests = (try? c.decodeIfPresent(EachDecoded<AdminGuest>.self, forKey: .guests))?.elements ?? []
        invites = (try? c.decodeIfPresent(EachDecoded<AdminInvite>.self, forKey: .invites))?.elements ?? []
    }

    private enum CodingKeys: String, CodingKey { case guests, invites }
}

/// A minted invite. `link`, the universal link to send, carries the key and is shown once.
struct AdminShareResult: Decodable, Equatable {
    var invite: AdminInvite
    var link: String?
    var appName: String?
    var owner: String?
    /// The connector's wording of the message to send, as `ovenlight share` words it.
    var sentMessage: String?

    private enum CodingKeys: String, CodingKey { case invite, link, appName, owner, sentMessage = "message" }

    /// What the owner sends: the connector's message, or just the link without one.
    var message: String {
        sentMessage.flatMap { $0.isEmpty ? nil : $0 } ?? link ?? ""
    }
}

struct AdminRevokeResult: Decodable, Equatable {
    var removed: [AdminGuest]
    var errors: [String]?

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        removed = (try? c.decodeIfPresent(EachDecoded<AdminGuest>.self, forKey: .removed))?.elements ?? []
        errors = try? c.decodeIfPresent([String].self, forKey: .errors)
    }

    private enum CodingKeys: String, CodingKey { case removed, errors }
}

struct AdminFeedback: Decodable, Equatable, Identifiable {
    var id: String
    var at: Date
    var app: String
    var from: String
    var role: String
    var device: String?
    var pageUrl: String?
    var note: String
    var screenshot: String?

    var hasScreenshot: Bool { !(screenshot ?? "").isEmpty }
    var isFromGuest: Bool { role == "guest" }
}

extension AdminFeedback {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        at = try c.decode(Date.self, forKey: .at)
        app = try c.decode(String.self, forKey: .app)
        from = (try? c.decodeIfPresent(String.self, forKey: .from)) ?? ""
        role = (try? c.decodeIfPresent(String.self, forKey: .role)) ?? ""
        note = (try? c.decodeIfPresent(String.self, forKey: .note)) ?? ""
        device = try? c.decodeIfPresent(String.self, forKey: .device)
        pageUrl = try? c.decodeIfPresent(String.self, forKey: .pageUrl)
        screenshot = try? c.decodeIfPresent(String.self, forKey: .screenshot)
    }

    private enum CodingKeys: String, CodingKey {
        case id, at, app, from, role, device, pageUrl, note, screenshot
    }
}

enum AdminAPI {
    static let port = 8443
    static let header = "X-Ovenlight-Request"

    /// Go's time.Time JSON: RFC 3339 with up to nine fractional digits.
    static func parseDate(_ text: String) -> Date? {
        if let date = try? Date.ISO8601FormatStyle().parse(text) { return date }
        // Foundation's parser takes milliseconds, not Go's nanoseconds: trim the fraction.
        guard let dot = text.firstIndex(of: ".") else { return nil }
        let after = text[text.index(after: dot)...]
        let digits = String(after.prefix { $0.isNumber })
        let rest = String(after.dropFirst(digits.count))
        let trimmed = String(text[..<dot]) + "." + String((digits + "000").prefix(3)) + rest
        return try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(trimmed)
    }

    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            guard let date = parseDate(text) else {
                throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "not a date: \(text)"))
            }
            return date
        }
        return decoder
    }

    static func url(host: String, path: String) -> URL? {
        URL(string: "https://\(host):\(port)\(path)")
    }

    /// Builds a request. Anything that changes something carries the header the
    /// connector requires, which a web page can't add without a CORS preflight.
    static func request(host: String, method: String, path: String, body: [String: String]? = nil) -> URLRequest? {
        guard let url = url(host: host, path: path) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if method != "GET" { request.setValue("1", forHTTPHeaderField: header) }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONEncoder().encode(body)
        }
        return request
    }

    static func path(_ base: String, _ component: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove("/")
        return base + (component.addingPercentEncoding(withAllowedCharacters: allowed) ?? component)
    }

    /// The `{"error": "...", "code": "..."}` the connector answers with.
    static func errorMessage(_ data: Data) -> String? {
        (try? JSONDecoder().decode(ErrorBody.self, from: data))?.error
    }

    fileprivate struct ErrorBody: Decodable {
        var error: String?
        var code: String?
    }
}

/// What went wrong, as the connector names it: `code` in its JSON errors, or the
/// `Ovenlight-Error` header on its plain-text refusals. Callers act only on these, never on
/// an error's words: a missing code, or one this version doesn't know, is an unknown
/// failure that never ends access or deletes anything.
enum ConnectorError: String {
    case notInvited = "not_invited"
    case usedElsewhere = "used_elsewhere"
    case otherPerson = "other_person"
    case ownerOnly = "owner_only"
    case notFound = "not_found"
    case badRequest = "bad_request"
    case rateLimited = "rate_limited"
    case tooLarge = "too_large"

    static let header = "Ovenlight-Error"

    init?(header: String?, body: Data) {
        let code = (try? JSONDecoder().decode(AdminAPI.ErrorBody.self, from: body))?.code ?? header
        guard let known = code.flatMap({ Self(rawValue: $0.trimmingCharacters(in: .whitespaces)) }) else { return nil }
        self = known
    }

    init?(_ response: URLResponse?, body: Data) {
        self.init(header: (response as? HTTPURLResponse)?.value(forHTTPHeaderField: Self.header), body: body)
    }
}

enum AdminError: Error, Equatable {
    case notOwner
    case unreachable
    case failed(String)

    var message: String {
        switch self {
        case .notOwner: "The Ovenlight connector on this computer is set up for another owner. To make it yours, run ovenlight setup-sharing --owner with your login there."
        case .unreachable: "Couldn't reach the computer. It may be asleep or offline."
        case .failed(let message): message.prefix(1).uppercased() + message.dropFirst()
        }
    }
}

/// Talks to one connector's admin API through the owner's own node.
struct AdminClient {
    let host: String
    let session: URLSession

    private static let retryableSafely: Set<Int> = [NSURLErrorBadURL, NSURLErrorCannotConnectToHost, NSURLErrorCannotFindHost]
    /// Not timeouts: a machine that didn't answer in the request's time won't the next.
    private static let retryable: Set<Int> = retryableSafely.union([NSURLErrorNetworkConnectionLost])

    func send<T: Decodable>(_ method: String, _ path: String, body: [String: String]? = nil, as type: T.Type) async throws -> T {
        let data = try await raw(method, path, body: body)
        do {
            return try AdminAPI.decoder.decode(T.self, from: data)
        } catch {
            throw AdminError.failed("The Ovenlight connector on this computer sent a reply this version of the app doesn't understand. Update both to the latest version.")
        }
    }

    /// Sends a request. A lost first WireGuard handshake shows up as a connection
    /// failure; a GET retries those, a change only when it can't have been sent.
    func raw(_ method: String, _ path: String, body: [String: String]? = nil) async throws -> Data {
        guard let request = AdminAPI.request(host: host, method: method, path: path, body: body) else { throw AdminError.unreachable }
        let retryable = method == "GET" ? Self.retryable : Self.retryableSafely
        var attempt = 0
        while true {
            attempt += 1
            do {
                let (data, response) = try await session.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                switch status {
                case 200: return data
                case 403 where ConnectorError(response, body: data) == .ownerOnly:
                    throw AdminError.notOwner
                default: throw AdminError.failed(AdminAPI.errorMessage(data) ?? "The Ovenlight connector on this computer returned an error (\(status)). Run ovenlight doctor there.")
                }
            } catch let error as URLError where retryable.contains(error.code.rawValue) && attempt < 3 {
                try await Task.sleep(for: .milliseconds(600 * attempt))
            } catch is URLError {
                throw AdminError.unreachable
            }
        }
    }

    func apps() async throws -> [AdminApp] { try await send("GET", "/v1/apps", as: EachDecoded<AdminApp>.self).elements }
    func guests() async throws -> AdminGuestList { try await send("GET", "/v1/guests", as: AdminGuestList.self) }
    func feedback(limit: Int = 100) async throws -> [AdminFeedback] {
        try await send("GET", "/v1/feedback?limit=\(limit)", as: EachDecoded<AdminFeedback>.self).elements
    }
    func screenshot(_ id: String) async throws -> Data {
        try await raw("GET", AdminAPI.path("/v1/feedback/", id) + "/screenshot")
    }
    func createInvite(to name: String, app slug: String) async throws -> AdminShareResult {
        try await send("POST", "/v1/invites", body: ["to": name, "app": slug], as: AdminShareResult.self)
    }
    /// An invite for someone already shared with: another app, or another device.
    func createInvite(person: String, app slug: String) async throws -> AdminShareResult {
        try await send("POST", "/v1/invites", body: ["person": person, "app": slug], as: AdminShareResult.self)
    }
    func cancelInvite(_ id: String) async throws -> AdminInvite {
        try await send("DELETE", AdminAPI.path("/v1/invites/", id), as: AdminInvite.self)
    }
    /// Removes a person from one app, every device of theirs.
    func removeGuest(_ person: GuestPerson, app slug: String) async throws -> AdminRevokeResult {
        try await send("DELETE", AdminAPI.path("/v1/guests/", person.id) + "?app=\(slug)&by=person",
                       as: AdminRevokeResult.self)
    }
}

/// What the owner's connectors report, for the Sharing screen and the app menu's Share.
/// One connector serves the same admin API on each of its app nodes, so Ovenlight asks one
/// node per machine once it knows which hosts share a machine (see `load`).
@MainActor
final class OwnerSharing: ObservableObject {
    static let shared = OwnerSharing()

    struct Machine: Identifiable, Equatable {
        /// The app node Ovenlight talks to.
        var adminHost: String
        var apps: [AdminApp]
        var guests = AdminGuestList()
        var feedback: [AdminFeedback] = []
        var id: String { adminHost }
    }

    @Published private(set) var machines: [Machine] = []
    @Published private(set) var isLoading = false
    @Published private(set) var problem: String?
    private var lastRefresh: Date?
    /// The refresh under way, and whether someone asked for another while it ran.
    private var loading: Task<Void, Never>?
    private var again = false
    private var latestApps: [WebApp] = []
    private let log = Logger(subsystem: "com.snowyghost.ovenlight", category: "sharing")

    /// The admin API goes over the owner's node, so it needs the owner connected.
    var isAvailable: Bool { NodeManager.shared.ownerIsConnected }

    func client(for machine: Machine) -> AdminClient? {
        NodeManager.shared.ownerSession(timeout: 30).map { AdminClient(host: machine.adminHost, session: $0) }
    }

    /// The connector's view of one of the owner's apps, by host.
    func info(for app: WebApp) -> (machine: Machine, app: AdminApp)? {
        guard !app.isShared else { return nil }
        let host = Discovery.normalizedHost(app.host)
        for machine in machines {
            if let found = machine.apps.first(where: { $0.host == host }) { return (machine, found) }
        }
        return nil
    }

    /// Candidate hosts: the owner's apps found through connectors, only among `ownersHosts`
    /// (`load` passes the ones online now).
    nonisolated static func candidateHosts(_ apps: [WebApp], discovered: [DiscoveredApp], ownersHosts: Set<String>) -> [String] {
        let hosts = apps.filter { !$0.isShared && $0.discovered }.map { Discovery.normalizedHost($0.host) }
            + discovered.map { Discovery.normalizedHost($0.host) }
        return Array(Set(hosts)).filter(ownersHosts.contains).sorted()
    }

    /// One machine's listing, without apps on hosts it can't vouch for (any but the owner's
    /// own machines: a connector can list any name), and the hosts it covers so no other
    /// machine is asked about them.
    nonisolated static func vouched(_ listed: [AdminApp], ownersHosts: Set<String>) -> (apps: [AdminApp], covered: Set<String>) {
        let covered = Set(listed.compactMap(\.host)).intersection(ownersHosts)
        return (listed.filter { $0.host.map(covered.contains) ?? true }, covered)
    }

    /// The hosts to ask first: each machine that answered last time, through the host it
    /// answered on, then every host none of those listed. Answers are taken in this order,
    /// so a machine keeps its admin host (and the screens showing it) while that answers.
    nonisolated static func firstRound(_ candidates: [String], known: [Machine]) -> [String] {
        let hosts = Set(candidates)
        let kept = known.filter { hosts.contains($0.adminHost) }
        let listed = Set(kept.flatMap { [$0.adminHost] + $0.apps.compactMap(\.host) })
        return kept.map(\.adminHost).sorted() + candidates.filter { !listed.contains($0) }
    }

    /// Lists every machine's apps, guests and feedback. Asked while a refresh runs (after a
    /// change, say), it runs once more after that one, for everyone who asked meanwhile.
    func refresh(apps: [WebApp]) async {
        guard isAvailable else { return }
        latestApps = apps
        if let loading {
            again = true
            return await loading.value
        }
        let task = Task {
            repeat {
                again = false
                await load(latestApps)
            } while again
            loading = nil
            isLoading = false
        }
        loading = task
        isLoading = true
        await task.value
    }

    /// Everything, unless it was read in the last few seconds or is being read: for the
    /// Home Screen's menus and an open app's Share.
    func refreshIfStale(apps: [WebApp]) async {
        if let loading { return await loading.value }
        if let last = lastRefresh, Date.now.timeIntervalSince(last) < 15 { return }
        await refresh(apps: apps)
    }

    /// One pass over the owner's online machines, all at once. A connector lists all its
    /// apps on each app node, so after `firstRound` only hosts no answer listed are asked,
    /// those a machine that didn't answer had listed last time.
    private func load(_ apps: [WebApp]) async {
        let nodes = NodeManager.shared
        guard let session = nodes.ownerSession(timeout: 15) else { return }
        defer { session.finishTasksAndInvalidate() }
        let owners = nodes.ownersHosts
        let candidates = Self.candidateHosts(apps, discovered: nodes.discovered, ownersHosts: nodes.ownersOnlineHosts)
        var found: [Machine] = []
        var covered = Set<String>()
        var asked = Set<String>()
        var failures: [String] = []
        func failed(_ host: String, _ error: AdminError) {
            log.info("admin \(host, privacy: .public): \(String(describing: error), privacy: .public)")
            failures.append(error.message)
        }
        var round = Self.firstRound(candidates, known: machines)
        while !round.isEmpty {
            asked.formUnion(round)
            let listings = await Self.ask(round) { try await AdminClient(host: $0, session: session).apps() }
            var listed: [Machine] = []
            for host in round where !covered.contains(host) {
                switch listings[host] {
                case .success(let apps):
                    let listing = Self.vouched(apps, ownersHosts: owners)
                    covered.insert(host)
                    covered.formUnion(listing.covered)
                    listed.append(Machine(adminHost: host, apps: listing.apps))
                case .failure(let error): failed(host, error)
                case nil: break
                }
            }
            let details = await Self.ask(listed.map(\.adminHost)) { host in
                let client = AdminClient(host: host, session: session)
                async let guests = client.guests()
                async let feedback = client.feedback()
                return try await (guests, feedback)
            }
            for var machine in listed {
                switch details[machine.adminHost] {
                case .success(let (guests, feedback)):
                    machine.guests = guests
                    machine.feedback = feedback
                    found.append(machine)
                case .failure(let error): failed(machine.adminHost, error)
                case nil: break
                }
            }
            round = candidates.filter { !covered.contains($0) && !asked.contains($0) }
        }
        machines = found
        lastRefresh = .now
        problem = found.isEmpty ? failures.first : nil
    }

    /// Sends one request to each host at once.
    private nonisolated static func ask<T: Sendable>(_ hosts: [String], _ request: @escaping @Sendable (String) async throws -> T) async -> [String: Result<T, AdminError>] {
        await withTaskGroup(of: (String, Result<T, AdminError>).self) { group in
            for host in hosts {
                group.addTask {
                    do {
                        return (host, .success(try await request(host)))
                    } catch {
                        return (host, .failure(error as? AdminError ?? .unreachable))
                    }
                }
            }
            var answers: [String: Result<T, AdminError>] = [:]
            for await (host, answer) in group { answers[host] = answer }
            return answers
        }
    }

    #if DEBUG
    func useFixture(_ fixture: [Machine]) { machines = fixture }
    #endif
}
