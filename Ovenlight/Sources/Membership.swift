import Combine
import UIKit

/// Ovenlight's place in someone else's network: a guest device, one per owner whose invite
/// was accepted, with its own node, state directory and loopback proxy. The owner's own
/// network is not a stored membership; it is always `Membership.ownerID`.
struct Membership: Codable, Identifiable, Equatable {
    /// The owner's own node.
    static let ownerID = "owner"

    var id: String
    /// The person who shared ("Riley").
    var ownerName: String
    var controlURL: URL
    /// The part of the app names after the first label; with the control URL it tells
    /// one owner's network from another.
    var networkDomain: String
    /// What the owner called this person, as the latest invite said.
    var guestName: String
    var created: Date
    /// The node registered with an invite key at least once. From then on it restarts
    /// from its saved state, and losing its login means the owner removed it.
    var joined: Bool
    /// Invites accepted but not claimed yet, with their keys.
    var pendingClaims: [PendingClaim]
    /// This iPhone's name in the owner's network, its own per membership so owners who
    /// compare notes can't tell it's the same phone.
    var hostName: String

    init(id: String = UUID().uuidString, invite: InviteLink, created: Date = .now) {
        self.id = id
        ownerName = invite.owner
        controlURL = invite.control
        networkDomain = invite.networkDomain
        guestName = invite.to
        self.created = created
        joined = false
        pendingClaims = []
        hostName = Self.randomHostName()
    }

    /// `ovenlight-` plus a short random suffix.
    static func randomHostName() -> String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyz0123456789")
        return "ovenlight-" + String((0..<6).map { _ in alphabet.randomElement()! })
    }

    func matches(_ invite: InviteLink) -> Bool {
        controlURL == invite.control && networkDomain == invite.networkDomain
    }

    /// The key a node that hasn't joined yet registers with.
    var joinKey: String? { joined ? nil : pendingClaims.first?.invite.key }
}

extension Membership {
    /// Only what the node runs on is required; the rest falls back, so a file another
    /// version wrote still loads.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        controlURL = try c.decode(URL.self, forKey: .controlURL)
        networkDomain = try c.decode(String.self, forKey: .networkDomain)
        joined = try c.decode(Bool.self, forKey: .joined)
        hostName = try c.decode(String.self, forKey: .hostName)
        ownerName = (try? c.decodeIfPresent(String.self, forKey: .ownerName)) ?? ""
        guestName = (try? c.decodeIfPresent(String.self, forKey: .guestName)) ?? ""
        created = (try? c.decodeIfPresent(Date.self, forKey: .created)) ?? .distantPast
        pendingClaims = (try? c.decodeIfPresent(EachDecoded<PendingClaim>.self, forKey: .pendingClaims))?.elements ?? []
    }
}

/// An accepted invite waiting for its claim at the owner's connector.
struct PendingClaim: Codable, Equatable {
    var invite: InviteLink
    var accepted: Date
}

/// The guest memberships, saved as JSON in Application Support, protected until the first
/// unlock after a restart like the node state beside them (pending claims hold invite
/// keys until they are spent).
@MainActor
final class MembershipStore: ObservableObject {
    @Published private(set) var memberships: [Membership] = []
    /// The file is there but couldn't be read or decoded. Nothing is saved over it, and
    /// nothing may clean up after the empty list, until a reload reads it.
    private(set) var loadFailed = false
    private let fileURL: URL
    private var reloadOnUnlock: AnyCancellable?

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Ovenlight", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        fileURL = base.appendingPathComponent("memberships.json")
        load()
        reloadOnUnlock = SavedJSON.whenUnlocked { [weak self] in self?.reloadIfFailed() }
    }

    /// Reads the file again if it couldn't be read before.
    func reloadIfFailed() {
        if loadFailed { load() }
    }

    private func load() {
        do {
            memberships = try SavedJSON.read([Membership].self, from: fileURL) ?? []
            loadFailed = false
        } catch {
            loadFailed = true
        }
    }

    subscript(id: String) -> Membership? { memberships.first { $0.id == id } }

    func membership(for invite: InviteLink) -> Membership? { memberships.first { $0.matches(invite) } }

    func upsert(_ membership: Membership) {
        if let index = memberships.firstIndex(where: { $0.id == membership.id }) {
            memberships[index] = membership
        } else {
            memberships.append(membership)
        }
        save()
    }

    func update(_ id: String, _ change: (inout Membership) -> Void) {
        guard let index = memberships.firstIndex(where: { $0.id == id }) else { return }
        change(&memberships[index])
        save()
    }

    func delete(_ id: String) {
        memberships.removeAll { $0.id == id }
        save()
    }

    private func save() {
        guard !loadFailed, let data = try? JSONEncoder().encode(memberships) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        // An atomic write replaces the file, and with it the flag.
        var url = fileURL
        url.excludeFromBackup()
    }
}

/// Ovenlight's saved lists. A missing file is an empty list; a file that is there but can't
/// be read (before the first unlock after a restart, say) or decoded throws, and must never
/// be taken for an empty one: the next save would write that over it.
enum SavedJSON {
    static func read<Element: Decodable>(_: [Element].Type, from url: URL) throws -> [Element]? {
        guard let data = try read(url) else { return nil }
        return try JSONDecoder().decode(EachDecoded<Element>.self, from: data).elements
    }

    static func read(_ url: URL) throws -> Data? {
        do {
            return try Data(contentsOf: url)
        } catch CocoaError.fileReadNoSuchFile {
            return nil
        }
    }

    /// Calls `reload` each time protected files become readable.
    static func whenUnlocked(_ reload: @escaping @MainActor () -> Void) -> AnyCancellable {
        NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)
            .receive(on: RunLoop.main)
            .sink { _ in MainActor.assumeIsolated { reload() } }
    }
}

/// A JSON array decoded one element at a time: an entry this version can't read is
/// skipped rather than costing the rest. Anything but an array still fails.
struct EachDecoded<Element: Decodable>: Decodable {
    var elements: [Element] = []

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        while !container.isAtEnd {
            if let element = try? container.decode(Element.self) {
                elements.append(element)
            } else {
                _ = try container.decode(Skipped.self)
            }
        }
    }

    /// Decodes from any value, to step past one.
    private struct Skipped: Decodable {
        init(from decoder: Decoder) throws {}
    }
}

extension URL {
    /// Keeps this file, or this directory and everything in it, out of backups.
    mutating func excludeFromBackup() {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? setResourceValues(values)
    }
}

/// What to clean up when access ends. Pure, so the rules are tested apart from WebKit.
enum GuestCleanup {
    /// Memberships with no app left and no invite waiting: their node leaves.
    static func orphans(_ memberships: [Membership], apps: [WebApp]) -> [String] {
        memberships.filter { m in
            m.pendingClaims.isEmpty && !apps.contains { $0.membershipID == m.id }
        }.map(\.id)
    }

    /// The apps to take away after a membership's probe: those whose connector answered
    /// "not invited". Hosts still being claimed are left alone; before the claim every
    /// guest is "not invited".
    static func revokedApps(in membership: Membership, apps: [WebApp], notInvited: Set<String>) -> [WebApp] {
        let claiming = Set(membership.pendingClaims.map { Discovery.normalizedHost($0.invite.host) })
        return apps.filter {
            $0.membershipID == membership.id
                && notInvited.contains(Discovery.normalizedHost($0.host))
                && !claiming.contains(Discovery.normalizedHost($0.host))
        }
    }

    /// Whether a guest node that lost its login means the owner removed this iPhone: only
    /// once it had joined, since a first join passes through NeedsLogin briefly. The caller
    /// asks only after the state held for a moment.
    static func isRemoved(_ membership: Membership) -> Bool {
        membership.joined
    }
}
