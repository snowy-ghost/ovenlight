import UIKit
import XCTest
@testable import Ovenlight

/// A link in the connector's encoding (url.Values.Encode: spaces as "+", sorted keys), with
/// the escapes its golden file (connector/testdata/wire/invite-created.json) doesn't have.
private let connectorLink = "ovenlight://join?app=echo-board&control=https%3A%2F%2Fcontrolplane.tailscale.com&host=echo-board.taildef456.ts.net"
    + "&invite=3f9a1c2b7e&key=tskey-auth-kX1abc-DEF%2Bghi&name=Echo+Board&owner=Riley&to=Sam+Lee&v=1"

/// The same invite as the universal link the connector sends: the fields in the fragment.
private let universalLink = connectorLink.replacingOccurrences(of: "ovenlight://join?", with: "https://ovenlight.app/join#")

final class InviteLinkTests: XCTestCase {
    private func parse(_ text: String, local: Bool = false) throws -> InviteLink {
        try InviteLink.parse(URL(string: text)!, allowLocalHTTP: local)
    }

    private func error(_ text: String, local: Bool = false) -> InviteLink.ParseError? {
        do {
            _ = try InviteLink.parse(URL(string: text)!, allowLocalHTTP: local)
            return nil
        } catch {
            return error
        }
    }

    /// The connector's own link is read in `testDecodesEveryGoldenFileFromTheConnector`.
    func testReadsTheConnectorsEscapes() throws {
        let link = try parse(connectorLink)
        XCTAssertEqual(link.key, "tskey-auth-kX1abc-DEF+ghi", "%2B is a literal plus")
        XCTAssertEqual(link.to, "Sam Lee", "+ is a space")
        XCTAssertEqual(link.networkDomain, "taildef456.ts.net")
        XCTAssertEqual(link.appURL.absoluteString, "https://echo-board.taildef456.ts.net/")
        XCTAssertEqual(try InviteLink.parse(link.url, allowLocalHTTP: false), link, "round trip")
    }

    func testReadsTheUniversalLink() throws {
        let link = try parse(connectorLink)
        XCTAssertEqual(try parse(universalLink), link)
        for variant in ["https://Ovenlight.APP/join#", "https://ovenlight.app/join.html#", "https://ovenlight.app/join/#"] {
            let text = universalLink.replacingOccurrences(of: "https://ovenlight.app/join#", with: variant)
            XCTAssertEqual(try parse(text), link, variant)
        }

        // What Ovenlight sends: the universal link, with nothing for the website but the path.
        let sent = link.universalURL
        XCTAssertEqual(sent.absoluteString.components(separatedBy: "#").first, "https://ovenlight.app/join")
        XCTAssertNil(sent.query)
        XCTAssertTrue(sent.fragment?.contains("key=tskey-auth-kX1abc-DEF") == true)
        XCTAssertEqual(try InviteLink.parse(sent, allowLocalHTTP: false), link, "round trip")
        XCTAssertEqual(link.url.query, sent.fragment, "both forms carry the same fields")
        XCTAssertTrue(InviteLink.isInvite(sent) && InviteLink.isInvite(link.url))
    }

    func testRefusesUniversalLinksFromElsewhere() {
        let fields = String(universalLink.split(separator: "#", maxSplits: 1)[1])
        for text in ["https://evil.example/join#", "https://ovenlight.app.evil.example/join#",
                     "https://www.ovenlight.app/join#", "http://ovenlight.app/join#", "https://ovenlight.app:8443/join#",
                     "https://ovenlight.app/ovenlight/join#", "https://ovenlight.app/joined#",
                     "https://ovenlight.app/privacy#"] {
            XCTAssertEqual(error(text + fields), .notAnInvite, text)
        }
        XCTAssertEqual(error("https://ovenlight.app/join"), .notAnInvite, "the page itself, without an invite")
        XCTAssertEqual(error("https://ovenlight.app/join?" + fields), .notAnInvite, "fields the server would see")
        XCTAssertFalse(InviteLink.isInvite(URL(string: "https://ovenlight.app/privacy")!))
        // The old address is no longer a universal link, in a message or on its own.
        for old in ["https://snowyghost.com/ovenlight/join#", "https://www.snowyghost.com/ovenlight/join#"] {
            XCTAssertEqual(error(old + fields), .notAnInvite, old)
            XCTAssertThrowsError(try InviteLink.parse(text: "Open this link: \(old + fields)", allowLocalHTTP: false))
        }
    }

    func testFindsTheLinkInsideAMessage() throws {
        let message = "Riley shared Echo Board with you in Ovenlight. Install Ovenlight on your iPhone, then open this link there: \(connectorLink)\n"
        XCTAssertEqual(try InviteLink.parse(text: message, allowLocalHTTP: false).invite, "3f9a1c2b7e")
        let universal = "Riley shared Echo Board with you in Ovenlight. Open this link on your iPhone: \(universalLink)\n"
        XCTAssertEqual(try InviteLink.parse(text: universal, allowLocalHTTP: false).invite, "3f9a1c2b7e")
        XCTAssertThrowsError(try InviteLink.parse(text: "no link here", allowLocalHTTP: false))
    }

    func testChecksVersionControlAndRequiredFields() {
        XCTAssertEqual(error("ovenlight://open?app=Coach"), .notAnInvite)
        XCTAssertEqual(error("https://example.com/join?v=1"), .notAnInvite)
        XCTAssertEqual(error(connectorLink.replacingOccurrences(of: "&v=1", with: "&v=2")), .unsupportedVersion)
        XCTAssertEqual(error(connectorLink.replacingOccurrences(of: "&v=1", with: "")), .unsupportedVersion)
        XCTAssertEqual(error(connectorLink.replacingOccurrences(of: "https%3A%2F%2F", with: "http%3A%2F%2F")), .insecureControl)
        for field in ["key", "app", "invite", "host", "control"] {
            let without = connectorLink.replacingOccurrences(of: #"(^|[?&])\#(field)=[^&]*"#, with: "$1", options: .regularExpression)
            XCTAssertNotNil(error(without), "missing \(field)")
        }
        XCTAssertEqual(error(connectorLink.replacingOccurrences(of: "key=tskey-auth-kX1abc-DEF%2Bghi", with: "key=")),
                       .missingField("key"))
    }

    func testRefusesHostsThatArentNames() {
        for host in ["10.0.0.1", "echo", "echo.ts.net%2Fpath", "echo.ts.net%3A8443", "-echo.ts.net", "ec%20ho.ts.net"] {
            XCTAssertEqual(error(connectorLink.replacingOccurrences(of: "echo-board.taildef456.ts.net", with: host)),
                           .invalidField("host"), host)
        }
        XCTAssertEqual(error(connectorLink.replacingOccurrences(of: "app=echo-board", with: "app=..%2Fetc")), .invalidField("app"))
    }

    func testLocalHTTPControlOnlyForDevelopmentOnThisMachine() throws {
        let local = connectorLink.replacingOccurrences(of: "https%3A%2F%2Fcontrolplane.tailscale.com", with: "http%3A%2F%2F127.0.0.1%3A18092")
        XCTAssertEqual(error(local), .insecureControl)
        XCTAssertEqual(try parse(local, local: true).control.port, 18092)
        let remote = connectorLink.replacingOccurrences(of: "https%3A%2F%2Fcontrolplane.tailscale.com", with: "http%3A%2F%2Fevil.example")
        XCTAssertEqual(error(remote, local: true), .insecureControl)
    }

    func testLabelsAreCleanedAndBounded() throws {
        let long = String(repeating: "x", count: 100)
        let link = try parse(connectorLink.replacingOccurrences(of: "owner=Riley", with: "owner=%0ARi%07ley+")
            .replacingOccurrences(of: "to=Sam+Lee", with: "to=\(long)")
            .replacingOccurrences(of: "name=Echo+Board", with: "name="))
        XCTAssertEqual(link.owner, "Riley")
        XCTAssertEqual(link.to.count, InviteLink.maxLabel)
        XCTAssertEqual(link.name, "echo-board", "the slug stands in for a missing name")
    }

    func testControlServerAndNetwork() throws {
        let link = try parse(connectorLink)
        XCTAssertTrue(link.usesTailscaleControl)
        let other = try parse(connectorLink.replacingOccurrences(of: "controlplane.tailscale.com", with: "hs.example.com"))
        XCTAssertFalse(other.usesTailscaleControl)

        XCTAssertTrue(link.isNetwork("Taildef456.ts.net."))
        XCTAssertFalse(link.isNetwork("tail0000.ts.net"), "the key led to another network")
        XCTAssertFalse(link.isNetwork(""))
        XCTAssertFalse(link.isNetwork(nil))
    }

    func testAJoinLeavesOnlyANetworkItJustJoinedAndProvablyWrong() throws {
        let link = try parse(connectorLink)
        XCTAssertEqual(Claim.landing(of: link, suffix: "taildef456.ts.net", registeredNow: true), .inNetwork)
        XCTAssertEqual(Claim.landing(of: link, suffix: "tail0000.ts.net", registeredNow: true), .elsewhere(leave: true))
        XCTAssertEqual(Claim.landing(of: link, suffix: "tail0000.ts.net", registeredNow: false), .elsewhere(leave: false),
                       "a node that joined before keeps its login and the owner's other apps")
        for registered in [true, false] {
            XCTAssertEqual(Claim.landing(of: link, suffix: nil, registeredNow: registered), .unknown, "a node that couldn't say retries")
            XCTAssertEqual(Claim.landing(of: link, suffix: "", registeredNow: registered), .unknown)
        }
        XCTAssertTrue(JoinError.network.isRetryable)
    }

    func testParseErrorsHaveNeutralMessages() {
        let errors: [InviteLink.ParseError] = [.notAnInvite, .unsupportedVersion, .insecureControl, .missingField("key"), .invalidField("host")]
        for message in errors.map(\.message) { CopyRules.assertNeutral(message) }
    }
}

@MainActor
final class MembershipTests: XCTestCase {
    private func invite(_ host: String = "echo-board.taildef456.ts.net", id: String = "inv1",
                        control: String = "https://controlplane.tailscale.com") -> InviteLink {
        InviteLink(control: URL(string: control)!, key: "tskey-\(id)", owner: "Riley", app: "echo-board", invite: id,
                   host: host, name: "Echo Board", to: "Sam")
    }

    func testPersistsAndReloads() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        var membership = Membership(invite: invite())
        membership.pendingClaims = [PendingClaim(invite: invite(), accepted: Date(timeIntervalSince1970: 1_000))]
        let store = MembershipStore(directory: dir)
        store.upsert(membership)
        store.update(membership.id) { $0.joined = true }

        let reopened = MembershipStore(directory: dir)
        XCTAssertEqual(reopened.memberships.count, 1)
        let file = dir.appendingPathComponent("memberships.json")
        XCTAssertEqual(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        XCTAssertNotNil(reopened[membership.id]?.hostName)
        XCTAssertNotEqual(Membership(invite: invite()).hostName, membership.hostName, "each membership its own name")
        XCTAssertEqual(reopened[membership.id]?.joined, true)
        XCTAssertEqual(reopened[membership.id]?.pendingClaims.first?.invite.key, "tskey-inv1")
        XCTAssertEqual(reopened.membership(for: invite("notes.taildef456.ts.net", id: "inv2"))?.id, membership.id,
                       "another app of the same owner reuses the node")
        XCTAssertNil(reopened.membership(for: invite("echo.tail0000.ts.net")), "another network")
        XCTAssertNil(reopened.membership(for: invite(control: "https://headscale.example.com")), "another control server")
        reopened.delete(membership.id)
        XCTAssertEqual(MembershipStore(directory: dir).memberships, [])
    }

    func testJoinKeyOnlyUntilJoined() {
        var membership = Membership(invite: invite())
        XCTAssertNil(membership.joinKey)
        membership.pendingClaims = [PendingClaim(invite: invite(), accepted: .now)]
        XCTAssertEqual(membership.joinKey, "tskey-inv1")
        membership.joined = true
        XCTAssertNil(membership.joinKey, "a joined node restarts from its state, never the spent key")
    }

    func testSharedWebAppSurvivesSaving() throws {
        let app = WebApp(name: "Coach", startURL: URL(string: "https://coach.tailabc123.ts.net/")!)
        XCTAssertFalse(app.isShared)
        var shared = app
        shared.membershipID = "m1"
        shared.sharedBy = "Riley"
        let again = try JSONDecoder().decode(WebApp.self, from: JSONEncoder().encode(shared))
        XCTAssertEqual(again.sharedBy, "Riley")
        XCTAssertTrue(again.isShared && again.hasConnector)
    }

    func testSharedAppsAreAddedOnceAndNeverMergedWithOwnApps() async {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let registry = AppRegistry(directory: dir, session: { _ in nil })
        let first = registry.addShared(name: "Echo Board", host: "Echo-Board.taildef456.ts.net.", slug: "echo-board",
                                       membershipID: "m1", sharedBy: "Riley")
        let again = registry.addShared(name: "Echo Board", host: "echo-board.taildef456.ts.net", slug: "echo-board",
                                       membershipID: "m1", sharedBy: "Riley")
        XCTAssertEqual(first.id, again.id)
        XCTAssertEqual(first.startURL.absoluteString, "https://echo-board.taildef456.ts.net/")
        let found = DiscoveredApp(host: "echo-board.taildef456.ts.net", manifest: OvenlightManifest(name: "Mine", version: 1))
        await registry.mergeDiscovered([found])
        XCTAssertEqual(registry.apps.count, 2, "discovery never adopts a shared app's tile")
        XCTAssertEqual(registry.apps.first { $0.isShared }?.name, "Echo Board")

        await registry.remove(first.id)
        await registry.mergeDiscovered([])
        XCTAssertEqual(registry.addShared(name: "Echo Board", host: "echo-board.taildef456.ts.net", slug: "echo-board",
                                          membershipID: "m1", sharedBy: "Riley").name, "Echo Board",
                       "removing a shared app doesn't dismiss its host")
    }

    func testEachClaimGivesTheOwnersAppsTheirCurrentName() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let registry = AppRegistry(directory: dir, session: { _ in nil })
        registry.addShared(name: "Echo Board", host: "echo-board.taildef456.ts.net", slug: "echo-board", membershipID: "m1", sharedBy: "Riley")
        registry.addShared(name: "Notes", host: "notes.tail0000.ts.net", slug: "notes", membershipID: "m2", sharedBy: "Kim")
        registry.addShared(name: "Notes", host: "notes.taildef456.ts.net", slug: "notes", membershipID: "m1", sharedBy: "Riley Q")
        XCTAssertEqual(registry.apps.map(\.sharedBy), ["Riley Q", "Kim", "Riley Q"], "every app of that owner, no one else's")
        XCTAssertEqual(AppRegistry(directory: dir, session: { _ in nil }).apps.map(\.sharedBy), ["Riley Q", "Kim", "Riley Q"])
    }
}

final class GuestCleanupTests: XCTestCase {
    private let echo = "echo-board.taildef456.ts.net"
    private let notes = "notes.taildef456.ts.net"

    private func membership(_ id: String, claiming hosts: [String] = [], joined: Bool = true) -> Membership {
        var m = Membership(id: id, invite: InviteLink(control: URL(string: "https://c.example")!, key: "k", owner: "Riley",
                                                      app: "a", invite: "i", host: echo, name: "Echo", to: "Sam"))
        m.joined = joined
        m.pendingClaims = hosts.enumerated().map { index, host in
            PendingClaim(invite: InviteLink(control: m.controlURL, key: "k\(index)", owner: "Riley", app: "a", invite: "i\(index)",
                                            host: host, name: "App", to: "Sam"), accepted: .now)
        }
        return m
    }

    private func app(_ host: String, in membershipID: String?) -> WebApp {
        var app = WebApp(name: host, startURL: URL(string: "https://\(host)/")!)
        app.membershipID = membershipID
        return app
    }

    func testOrphansHaveNoAppsAndNoInviteWaiting() {
        let memberships = [membership("a"), membership("b", claiming: [notes]), membership("c")]
        let apps = [app(echo, in: "a"), app("coach.tailabc123.ts.net", in: nil)]
        XCTAssertEqual(GuestCleanup.orphans(memberships, apps: apps), ["c"])
    }

    func testRevokesOnlyClaimedAppsTheConnectorRefused() {
        let m = membership("a", claiming: [notes])
        let apps = [app(echo, in: "a"), app(notes, in: "a"), app("other.taildef456.ts.net", in: "a"), app(echo, in: "b")]
        let revoked = GuestCleanup.revokedApps(in: m, apps: apps, notInvited: [echo, notes])
        XCTAssertEqual(revoked.map(\.host), [echo], "not the app being claimed, not another membership's")
        XCTAssertEqual(GuestCleanup.revokedApps(in: m, apps: apps, notInvited: ["Echo-Board.taildef456.ts.net"]).count, 0,
                       "hosts arrive normalized from the probe")
    }

    func testLostLoginMeansRemovedOnlyAfterJoining() {
        XCTAssertTrue(GuestCleanup.isRemoved(membership("a")))
        XCTAssertTrue(GuestCleanup.isRemoved(membership("a", claiming: [notes])),
                      "a second invite in progress doesn't hide the removal")
        XCTAssertFalse(GuestCleanup.isRemoved(membership("a", joined: false)),
                       "a first join passes through NeedsLogin")
    }

    func testWhoamiAndClaimAnswers() {
        func refusal(_ code: String, _ words: String = "refused") -> Data { Data(#"{"error":"\#(words)","code":"\#(code)"}"#.utf8) }
        XCTAssertEqual(GuestProbe.classify(status: 403, header: "unknown_caller", body: Data("Unknown caller.".utf8)), .reached)
        XCTAssertEqual(GuestProbe.classify(status: 200, body: Data(#"{"role":"guest"}"#.utf8)), .reached)
        XCTAssertEqual(GuestProbe.classify(status: 502, body: Data()), .reached, "any answer proves the path")

        let ok = Data(#"{"name":"Sam","userId":"guest:7","app":"echo-board","appName":"Echo Board","owner":"Riley"}"#.utf8)
        XCTAssertEqual(Claim.interpret(status: 200, body: ok),
                       .claimed(ClaimResponse(app: "echo-board", appName: "Echo Board", owner: "Riley")))
        XCTAssertEqual(Claim.interpret(status: 403, header: "unknown_caller", body: Data("Unknown caller.".utf8)), .retry,
                       "the connector hasn't seen the device yet")
        XCTAssertEqual(Claim.interpret(status: 500, body: refusal("internal")), .retry)
        XCTAssertEqual(Claim.interpret(status: 500, body: Data()), .retry)
        XCTAssertEqual(Claim.interpret(status: 200, body: Data("<html>".utf8)), .retry)

        // Each refusal is named by its code, in the JSON or the header, whatever the words.
        XCTAssertEqual(Claim.interpret(status: 403, body: refusal("used_elsewhere")), .failed(.usedOnAnotherDevice))
        XCTAssertEqual(Claim.interpret(status: 403, body: refusal("other_person", "not invited: another guest")), .failed(.someoneElses))
        XCTAssertEqual(Claim.interpret(status: 403, body: refusal("not_invited")), .failed(.notInvited))
        XCTAssertEqual(Claim.interpret(status: 400, body: refusal("bad_request")), .failed(.notInvited))
        XCTAssertEqual(Claim.interpret(status: 404, header: "not_found", body: Data("404 page not found".utf8)), .failed(.notInvited))
        XCTAssertEqual(Claim.interpret(status: 403, body: refusal("rate_limited", "not invited")), .retry)
        XCTAssertEqual(Claim.interpret(status: 403, header: "used_elsewhere", body: Data("Refused.".utf8)), .failed(.usedOnAnotherDevice))
        XCTAssertEqual(GuestProbe.classify(status: 403, header: "not_invited", body: Data("Refused.".utf8)), .notInvited)
        XCTAssertEqual(GuestProbe.classify(status: 403, header: "owner_only", body: Data("You're not invited.".utf8)), .reached)

        // Without a code this version knows, nothing ends access or deletes anything,
        // whatever the words say: the answer is an unknown failure, tried again.
        for words in [refusal("from_the_future", "not invited"), Data(#"{"error":"not invited"}"#.utf8),
                      Data("You're not invited to this app.\n".utf8),
                      Data(#"{"error":"not invited: this device already belongs to another guest"}"#.utf8),
                      Data(#"{"error":"this invite was already used by another device"}"#.utf8)] {
            for status in [400, 403, 404] {
                XCTAssertEqual(Claim.interpret(status: status, body: words), .retry, "\(status) \(String(decoding: words, as: UTF8.self))")
                XCTAssertEqual(GuestProbe.classify(status: status, body: words), .reached)
            }
        }
        XCTAssertEqual(ConnectorError(header: nil, body: refusal("owner_only")), .ownerOnly)
        XCTAssertEqual(AdminAPI.errorMessage(refusal("owner_only", "for the owner only")), "for the owner only")

        let request = Claim.request(for: InviteLink(control: URL(string: "https://c.example")!, key: "tskey-1", owner: "R", app: "a",
                                                    invite: "i", host: echo, name: "E", to: ""))
        XCTAssertEqual(request.url?.absoluteString, "https://\(echo)/__ovenlight/claim")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: request.httpBody!), ["key": "tskey-1"])
    }

    func testJoinErrorsFromTheControlServer() {
        XCTAssertEqual(JoinError(upMessage: "tsnet.Up: backend: authkey already used"), .keyUsed)
        XCTAssertEqual(JoinError(upMessage: "tsnet.Up: backend: authkey expired"), .expired)
        XCTAssertEqual(JoinError(upMessage: "tsnet.Up: backend: invalid key: unable to validate API key"), .keyRefused)
        XCTAssertEqual(JoinError(upMessage: "tsnet.Up: use of closed network connection"), .canceled)
        XCTAssertEqual(JoinError(upMessage: "dial tcp: connection refused"), .network)
        XCTAssertTrue(JoinError.network.isRetryable)
        XCTAssertFalse(JoinError.keyUsed.isRetryable)
    }

    func testCancelingStopsOnlyTheNodeRegisteringWithThatInvite() {
        let m = membership("a", claiming: [echo, notes], joined: false)
        XCTAssertTrue(GuestCleanup.isRegistering(m, with: "i0"), "the first waiting invite's key registers the node")
        XCTAssertFalse(GuestCleanup.isRegistering(m, with: "i1"), "a queued invite leaves the node alone")
        XCTAssertFalse(GuestCleanup.isRegistering(membership("a", claiming: [echo]), with: "i0"), "a joined node keeps the owner's apps")
    }

    func testClaimAnswerNamesAreCleanedLikeTheInvites() {
        let invite = InviteLink(control: URL(string: "https://c.example")!, key: "k", owner: "Riley", app: "echo-board", invite: "i",
                                host: echo, name: "Echo Board", to: "Sam")
        let long = String(repeating: "x", count: 100)
        let names = ClaimResponse(app: " echo\u{7}", appName: long, owner: "\nRi\u{0}ley ").names(for: invite)
        XCTAssertEqual(names.owner, "Riley")
        XCTAssertEqual(names.appName.count, InviteLink.maxLabel)
        XCTAssertEqual(names.app, "echo")
        let blank = ClaimResponse(app: nil, appName: "  ", owner: "").names(for: invite)
        XCTAssertEqual([blank.owner, blank.appName, blank.app], ["Riley", "Echo Board", "echo-board"], "the invite's where the answer has none")
    }

    /// A guest node's status, as the node reports it, with these peers online or not.
    private func status(_ backend: String = "Running", peers: [String: Bool]) throws -> TailnetStatus {
        let list = peers.map { #""\#($0.key)":{"DNSName":"\#($0.key).","Online":\#($0.value)}"# }.joined(separator: ",")
        return try XCTUnwrap(TailnetStatus.decode(Data(#"{"BackendState":"\#(backend)","Peer":{\#(list)}}"#.utf8)))
    }

    func testAnAppNodeGoneFromThePeersIsNoLongerShared() throws {
        let hosts: Set<String> = [echo, notes]
        XCTAssertEqual(GuestProbe.absentHosts(hosts, in: try status(peers: [echo: true, notes: false])), [], "offline is still listed")
        XCTAssertEqual(GuestProbe.absentHosts(hosts, in: try status(peers: [echo: true])), [notes])
        XCTAssertNil(GuestProbe.absentHosts(hosts, in: try status(peers: [:])), "every app gone is a partial list, not a revoke")
        XCTAssertNil(GuestProbe.absentHosts(hosts, in: try status("Starting", peers: [echo: true])))

        var absence = PeerAbsence()
        for _ in 1..<PeerAbsence.pollsToEnd { XCTAssertEqual(absence.record([notes]), []) }
        XCTAssertEqual(absence.record([notes]), [notes], "missing for every poll of a few minutes")
        XCTAssertEqual(absence.record([notes]), [notes], "again while still missing, for a host skipped while being claimed")

        absence = PeerAbsence()
        for _ in 1..<PeerAbsence.pollsToEnd { _ = absence.record([notes]) }
        _ = absence.record([])
        XCTAssertEqual(absence.record([notes]), [], "back in the list starts over")
    }

    func testGuestsNeverGetSignInOrOwnerApprovalCopy() {
        var shared = app(echo, in: "a")
        shared.sharedBy = "Riley"
        let login = NodeState.needsLogin(URL(string: "https://login.example/a")!)
        XCTAssertNil(NodeActionOverlay.action(for: login, app: shared, ownerEnabled: false))
        XCTAssertNil(NodeActionOverlay.action(for: .idle, app: shared, ownerEnabled: false))
        XCTAssertNil(NodeActionOverlay.action(for: .awaitingApproval, app: shared, ownerEnabled: true))
        let own = app("coach.tailabc123.ts.net", in: nil)
        XCTAssertEqual(NodeActionOverlay.action(for: login, app: own, ownerEnabled: true), .signIn)
        XCTAssertEqual(NodeActionOverlay.action(for: .idle, app: own, ownerEnabled: false), .signIn)
        XCTAssertEqual(NodeActionOverlay.action(for: .awaitingApproval, app: own, ownerEnabled: true), .ownerApproval)
    }
}

final class FeedbackTests: XCTestCase {
    func testBuildsThePayloadTheConnectorExpects() throws {
        let png = Data([0x89, 0x50, 0x4E, 0x47])
        let payload = try FeedbackPayload.make(note: "  the button overlaps \n", pageURL: URL(string: "https://e.ts.net/page"), png: png)
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(payload)) as? [String: String]
        XCTAssertEqual(json, ["note": "the button overlaps", "pageUrl": "https://e.ts.net/page", "screenshotPngBase64": "iVBORw=="])

        let noteOnly = try FeedbackPayload.make(note: "hi", pageURL: nil, png: nil)
        let keys = try JSONSerialization.jsonObject(with: JSONEncoder().encode(noteOnly)) as? [String: String]
        XCTAssertEqual(keys, ["note": "hi", "pageUrl": ""], "no screenshot key at all")
    }

    func testRefusesWhatTheConnectorWould() {
        XCTAssertThrowsError(try FeedbackPayload.make(note: "  ", pageURL: nil, png: nil)) { XCTAssertEqual($0 as? FeedbackPayload.Problem, .empty) }
        XCTAssertThrowsError(try FeedbackPayload.make(note: String(repeating: "é", count: 4001), pageURL: nil, png: nil)) {
            XCTAssertEqual($0 as? FeedbackPayload.Problem, .noteTooLong)
        }
        XCTAssertThrowsError(try FeedbackPayload.make(note: "", pageURL: nil, png: Data(count: FeedbackPayload.maxScreenshot + 1))) {
            XCTAssertEqual($0 as? FeedbackPayload.Problem, .screenshotTooLarge)
        }
        XCTAssertNoThrow(try FeedbackPayload.make(note: "", pageURL: nil, png: Data(count: 10)), "a screenshot alone is fine")
        let long = URL(string: "https://e.ts.net/" + String(repeating: "a", count: 3000))
        XCTAssertEqual(try FeedbackPayload.make(note: "x", pageURL: long, png: nil).pageUrl, "", "an overlong page URL is dropped")
    }

    func testShrinksLargeScreenshotsUnderTheLimit() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        let image = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 800), format: format).image { context in
            for y in stride(from: 0, to: 800, by: 2) {
                UIColor(hue: CGFloat(y) / 800, saturation: 1, brightness: 1, alpha: 1).setFill()
                context.fill(CGRect(x: CGFloat(y % 7), y: CGFloat(y), width: 400, height: 1))
            }
        }
        let full = try XCTUnwrap(FeedbackPayload.png(from: image, limit: .max))
        let limited = try XCTUnwrap(FeedbackPayload.png(from: image, limit: full.count - 1))
        XCTAssertLessThan(limited.count, full.count)
    }

    func testScreenshotsAreDecodedSmall() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 2000), format: format).image { context in
            UIColor.orange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 3000, height: 2000))
        }
        let png = try XCTUnwrap(image.pngData())
        let thumbnail = try XCTUnwrap(Screenshot.image(from: png, maxPixels: ScreenshotCache.Size.thumbnail.rawValue))
        XCTAssertEqual(thumbnail.cgImage.map { [$0.width, $0.height] }, [240, 160])
        XCTAssertNil(Screenshot.image(from: Data("not an image".utf8), maxPixels: 240))
    }

    func testAnswers() {
        XCTAssertEqual(FeedbackResult.interpret(status: 200, body: Data(#"{"id":"a1"}"#.utf8)), .sent)
        XCTAssertEqual(FeedbackResult.interpret(status: 429, body: Data()), .failed(ConnectionCopy.feedbackTooMany))
        XCTAssertEqual(FeedbackResult.interpret(status: 400, body: Data(#"{"error":"the screenshot must be a PNG"}"#.utf8)),
                       .failed("The screenshot must be a PNG."))
    }
}

final class AdminAPITests: XCTestCase {
    /// The connector's own examples of everything Ovenlight reads (connector/wire.go writes
    /// them): each must decode in full, and a new one needs a line here.
    func testDecodesEveryGoldenFileFromTheConnector() throws {
        let wire = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("connector/testdata/wire")
        let files = try FileManager.default.contentsOfDirectory(at: wire, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(files.isEmpty)
        func count(_ data: Data, _ key: String? = nil) throws -> Int {
            let json = try JSONSerialization.jsonObject(with: data)
            let list: Any? = if let key { (json as? [String: Any])?[key] } else { json }
            return (list as? [Any])?.count ?? 0
        }
        for file in files {
            let name = file.deletingPathExtension().lastPathComponent
            let data = try Data(contentsOf: file)
            switch name {
            case "apps":
                let apps = try AdminAPI.decoder.decode(EachDecoded<AdminApp>.self, from: data).elements
                XCTAssertEqual(apps.count, try count(data))
                XCTAssertEqual(apps.map(\.host), ["coach.tail1.ts.net", nil])
            case "guests":
                let list = try AdminAPI.decoder.decode(AdminGuestList.self, from: data)
                XCTAssertEqual(list.guests.count, try count(data, "guests"))
                XCTAssertEqual(list.invites.count, try count(data, "invites"))
            case "invite-canceled":
                _ = try AdminAPI.decoder.decode(AdminInvite.self, from: data)
            case "invite-created":
                var result = try AdminAPI.decoder.decode(AdminShareResult.self, from: data)
                let sent = try XCTUnwrap(result.sentMessage)
                XCTAssertEqual(result.message, sent)
                let link = try XCTUnwrap(result.link)
                XCTAssertTrue(sent.hasSuffix(link))
                result.sentMessage = nil
                XCTAssertEqual(result.message, link, "without the connector's message, just the link")

                // The links the connector writes read back as the invite it made.
                let invite = try InviteLink.parse(XCTUnwrap(URL(string: link)), allowLocalHTTP: false)
                let appLink = try InviteLink.parse(XCTUnwrap(URL(string: XCTUnwrap(
                    (JSONSerialization.jsonObject(with: data) as? [String: Any])?["appLink"] as? String))), allowLocalHTTP: false)
                XCTAssertEqual(appLink, invite, "the universal link and the app link carry the same invite")
                XCTAssertEqual(invite.invite, result.invite.id)
                XCTAssertEqual(invite.app, result.invite.app)
                XCTAssertEqual(invite.to, result.invite.to)
                XCTAssertEqual(invite.name, result.appName)
                XCTAssertEqual(invite.owner, result.owner)
                XCTAssertEqual(invite.host, "coach.tail1.ts.net")
                XCTAssertEqual(invite.control.absoluteString, "https://controlplane.tailscale.com")
                XCTAssertEqual(invite.key, "tskey-auth-kExample1CNTRL-ExampleSecretNotReal")
                XCTAssertEqual(try InviteLink.parse(invite.universalURL, allowLocalHTTP: false), invite, "round trip")
                XCTAssertEqual(try InviteLink.parse(invite.url, allowLocalHTTP: false), invite, "round trip")
            case "guest-removed", "guest-removed-with-errors":
                let result = try AdminAPI.decoder.decode(AdminRevokeResult.self, from: data)
                XCTAssertEqual(result.removed.count, try count(data, "removed"))
            case "feedback":
                let items = try AdminAPI.decoder.decode(EachDecoded<AdminFeedback>.self, from: data).elements
                XCTAssertEqual(items.map(\.hasScreenshot), [true, false])
            case "claim":
                guard case .claimed = Claim.interpret(status: 200, body: data) else { return XCTFail(name) }
            case "whoami-guest", "whoami-owner":
                XCTAssertEqual(GuestProbe.classify(status: 200, body: data), .reached)
            case "feedback-sent":
                // The phone reads only the status; the answer stays an object with the item's ID.
                let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                XCTAssertNotNil(json["id"] as? String)
            case "site-manifest":
                let manifest = try XCTUnwrap(OvenlightManifest.parse(data))
                let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                XCTAssertEqual(manifest.name, json["name"] as? String)
                XCTAssertEqual(manifest.slug, json["slug"] as? String)
                XCTAssertEqual(manifest.icon, json["icon"] as? String)
                XCTAssertEqual(manifest.themeColor, json["themeColor"] as? String)
                XCTAssertNotNil(DiscoveredApp(host: "coach.tail1.ts.net", manifest: manifest).iconURL, "the icon is on the app's own host")
            case _ where name.hasPrefix("error-"):
                XCTAssertEqual(ConnectorError(header: nil, body: data)?.rawValue, String(name.dropFirst("error-".count)))
                XCTAssertNotNil(AdminAPI.errorMessage(data))
            default:
                XCTFail("\(name).json: decode it here")
            }
        }
    }

    func testOneUnreadableRecordDoesntCostTheRest() throws {
        let apps = #"[{"slug":"coach","name":"Coach"},{"name":"No slug"},{"slug":"notes","name":"Notes","guests":"many","newField":{}}]"#
        let decoded = try AdminAPI.decoder.decode(EachDecoded<AdminApp>.self, from: Data(apps.utf8)).elements
        XCTAssertEqual(decoded.map(\.slug), ["coach", "notes"])
        XCTAssertEqual(decoded.map(\.guests), [0, 0], "what isn't essential falls back")

        let guests = #"{"guests":[{"deviceId":"7","person":"p1","app":"echo","claimedAt":"2026-09-28T17:05:00Z"},{"deviceId":"8"}],"invites":null}"#
        let list = try AdminAPI.decoder.decode(AdminGuestList.self, from: Data(guests.utf8))
        XCTAssertEqual(list.guests.map(\.deviceId), ["7"])
        XCTAssertEqual(list.invites, [])
    }

    func testRequestsCarryTheHeaderOnlyForChanges() throws {
        let get = try XCTUnwrap(AdminAPI.request(host: "echo.e2e.ovenlight.test", method: "GET", path: "/v1/apps"))
        XCTAssertEqual(get.url?.absoluteString, "https://echo.e2e.ovenlight.test:8443/v1/apps")
        XCTAssertNil(get.value(forHTTPHeaderField: AdminAPI.header))
        let post = try XCTUnwrap(AdminAPI.request(host: "echo.e2e.ovenlight.test", method: "POST", path: "/v1/invites",
                                                  body: ["to": "Sam", "app": "echo"]))
        XCTAssertEqual(post.value(forHTTPHeaderField: "X-Ovenlight-Request"), "1")
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: post.httpBody!), ["to": "Sam", "app": "echo"])
        let delete = try XCTUnwrap(AdminAPI.request(host: "h.test", method: "DELETE", path: AdminAPI.path("/v1/guests/", "Sam Lee/x")))
        XCTAssertEqual(delete.url?.path(percentEncoded: true), "/v1/guests/Sam%20Lee%2Fx")
        XCTAssertEqual(delete.value(forHTTPHeaderField: AdminAPI.header), "1")
        XCTAssertEqual(AdminAPI.errorMessage(Data(#"{"error":"the admin API is for the owner only"}"#.utf8)), "the admin API is for the owner only")
    }

    func testAsksOnlyOnlineMachines() throws {
        let json = """
        {"BackendState":"Running","CurrentTailnet":{"Name":"owen@","MagicDNSSuffix":"tailabc123.ts.net"},
         "Self":{"DNSName":"phone.tailabc123.ts.net.","OS":"iOS","UserID":1},
         "Peer":{"a":{"DNSName":"coach.tailabc123.ts.net.","OS":"linux","Online":true,"Tags":["tag:ovenlight-app-coach"]},
                 "b":{"DNSName":"notes.tailabc123.ts.net.","OS":"linux","Online":false,"Tags":["tag:ovenlight-app-notes"]}}}
        """
        let status = try XCTUnwrap(TailnetStatus.decode(Data(json.utf8)))
        let apps = ["coach", "notes"].map { WebApp(name: $0, startURL: URL(string: "https://\($0).tailabc123.ts.net/")!, discovered: true) }
        XCTAssertEqual(OwnerSharing.candidateHosts(apps, discovered: [], ownersHosts: Set(Discovery.candidateHosts(in: status))),
                       ["coach.tailabc123.ts.net"], "an offline machine can only time out")
    }

    func testFirstRoundAsksEachKnownMachineOnce() {
        func app(_ slug: String) -> AdminApp {
            AdminApp(slug: slug, name: slug, url: "https://\(slug).t.ts.net/", online: true, shareable: true, guests: 0)
        }
        let candidates = ["coach", "echo", "notes", "web"].map { "\($0).t.ts.net" }
        XCTAssertEqual(OwnerSharing.firstRound(candidates, known: []), candidates, "nothing known: every host")
        let known = [OwnerSharing.Machine(adminHost: "notes.t.ts.net", apps: [app("coach"), app("notes")]),
                     OwnerSharing.Machine(adminHost: "gone.t.ts.net", apps: [app("echo")])]
        XCTAssertEqual(OwnerSharing.firstRound(candidates, known: known), ["notes.t.ts.net", "echo.t.ts.net", "web.t.ts.net"],
                       "the host a machine answered on first, and hosts no machine still online listed")
    }

    func testGoTimestamps() {
        XCTAssertNotNil(AdminAPI.parseDate("2026-09-28T17:03:00Z"))
        XCTAssertNotNil(AdminAPI.parseDate("2026-09-28T17:03:00-04:00"))
        XCTAssertEqual(AdminAPI.parseDate("2026-09-28T21:03:00.999999999Z")!.timeIntervalSince1970, 1_790_629_380.999, accuracy: 0.001)
        XCTAssertEqual(AdminAPI.parseDate("2026-09-28T21:03:00.5Z")!.timeIntervalSince1970, 1_790_629_380.5, accuracy: 0.001)
        XCTAssertNil(AdminAPI.parseDate("yesterday"))
    }
}

enum CopyRules {
    static func assertNeutral(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(text.localizedCaseInsensitiveContains("tailscale"), text, file: file, line: line)
        XCTAssertFalse(text.localizedCaseInsensitiveContains("tailnet"), text, file: file, line: line)
        XCTAssertFalse(text.contains("\u{2014}") || text.contains("\u{2013}"), "no em or en dashes: \(text)", file: file, line: line)
    }
}

final class GuestCopyTests: XCTestCase {
    func testEverythingAGuestCanSeeIsNeutral() {
        var copy = [
            ConnectionCopy.sharedBy("Riley"), ConnectionCopy.joinTitle(owner: "Riley", app: "Echo"),
            ConnectionCopy.joinDetail(owner: "Riley", app: "Echo", to: "Sam"), ConnectionCopy.joinDetail(owner: "Riley", app: "Echo", to: ""),
            ConnectionCopy.joining(owner: "Riley", app: "Echo"), ConnectionCopy.joiningDetail, ConnectionCopy.joined("Echo"),
            ConnectionCopy.noLongerSharedTitle, ConnectionCopy.noLongerShared(apps: ["Echo", "Notes"], owner: "Riley"),
            ConnectionCopy.joinWithInvite, ConnectionCopy.invitePrompt, ConnectionCopy.feedbackSent,
            ConnectionCopy.appAddress("echo.taildef456.ts.net"), ConnectionCopy.privateServer("hs.example.com", owner: "Riley"),
            ConnectionCopy.feedbackSentDetail(to: "Riley"), ConnectionCopy.feedbackSentDetail(to: nil), ConnectionCopy.feedbackFailed,
            ConnectionCopy.feedbackTooMany, ConnectionCopy.feedbackNetwork,
            ConnectionCopy.reportProblem, ConnectionCopy.leaveApps("Riley"), ConnectionCopy.leaveTitle("Riley"),
            ConnectionCopy.leaveDetail("Riley"),
            AppAddress.signInFirst, AppAddress.notYourComputer,
        ]
        let report = MailDraft.problemReport(appName: "Echo", host: "echo.example.com", sharedBy: "Riley",
                                             connection: NodeState.ready.summary, version: "1.0 (3)", iOS: "26.5")
        copy += [report.subject, report.body]
        let errors: [JoinError] = [.keyUsed, .expired, .keyRefused, .notInvited, .usedOnAnotherDevice, .someoneElses, .network, .canceled, .ownApp, .wrongNetwork]
        copy += errors.flatMap { [$0.title(owner: "Riley", app: "Echo"), $0.message(owner: "Riley", app: "Echo")] }
        for text in copy { CopyRules.assertNeutral(text) }
    }
}

final class SupportMailTests: XCTestCase {
    func testProblemReportForASharedApp() {
        let draft = MailDraft.problemReport(appName: "Echo Board", host: "echo-board.taildef456.ts.net", sharedBy: "Riley",
                                            connection: "Connected", version: "0.2.0 (7)", iOS: "26.5")
        XCTAssertEqual(draft.to, "team@snowyghost.com")
        XCTAssertEqual(draft.subject, "Ovenlight report: Echo Board")
        XCTAssertEqual(draft.body, """
            What happened:



            App: Echo Board
            Address: echo-board.taildef456.ts.net
            Shared by: Riley
            Connection: Connected
            Ovenlight: 0.2.0 (7)
            iOS: 26.5
            """)
    }

    func testProblemReportForOwnAppLoadedDirectly() {
        let draft = MailDraft.problemReport(appName: "Coach", host: "coach.example.com", sharedBy: nil, connection: nil,
                                            version: "1.0 (1)", iOS: "26.5")
        XCTAssertFalse(draft.body.contains("Shared by"))
        XCTAssertFalse(draft.body.contains("Connection"), "no node, no connection to report")
        XCTAssertTrue(draft.body.hasPrefix("What happened:"))
        XCTAssertTrue(draft.body.hasSuffix("App: Coach\nAddress: coach.example.com\nOvenlight: 1.0 (1)\niOS: 26.5"))
    }

    func testMailtoFallbackCarriesSubjectAndBody() throws {
        let draft = MailDraft.problemReport(appName: "Echo & Notes+", host: "echo.example.com", sharedBy: "Riley", connection: nil,
                                            version: "1.0 (1)", iOS: "26.5")
        let url = try XCTUnwrap(draft.mailtoURL)
        XCTAssertEqual(url.scheme, "mailto")
        let parts = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(parts.path, "team@snowyghost.com")
        XCTAssertEqual(parts.queryItems?.first { $0.name == "subject" }?.value, "Ovenlight report: Echo & Notes+")
        XCTAssertEqual(parts.queryItems?.first { $0.name == "body" }?.value, draft.body)
    }

    func testPrivacyPolicyIsHTTPS() {
        XCTAssertEqual(AppLinks.privacyPolicy.scheme, "https")
    }
}

@MainActor
final class RouterTests: XCTestCase {
    private func join(_ router: Router) -> JoinRequest? {
        if case .join(let request) = router.sheet { return request }
        return nil
    }

    func testInviteOpensTheJoinSheet() throws {
        let router = Router()
        router.handle(URL(string: universalLink)!, apps: [])
        XCTAssertEqual(try join(router)?.invite?.get().invite, "3f9a1c2b7e")
        // Another page on the site isn't an invite and opens nothing.
        router.sheet = nil
        router.sheetDismissed()
        router.handle(URL(string: "https://ovenlight.app/privacy")!, apps: [])
        XCTAssertNil(router.sheet)
    }

    func testInviteOverAnOpenAppWaitsForItToClose() throws {
        let router = Router()
        router.open(WebApp(name: "Coach", startURL: URL(string: "https://coach.example.com/")!))
        router.appAppeared()
        router.handle(URL(string: connectorLink)!, apps: [])
        XCTAssertNil(router.openApp)
        XCTAssertNil(router.sheet, "a sheet can't present while the cover is closing")
        router.appDismissed()
        XCTAssertEqual(try join(router)?.invite?.get().invite, "3f9a1c2b7e")
    }

    func testOpeningAnAppFromASheetWaitsForItToClose() {
        let router = Router()
        let app = WebApp(name: "Coach", startURL: URL(string: "https://coach.example.com/")!)
        router.present(.settings)
        router.sheetAppeared()
        router.open(app)
        XCTAssertNil(router.sheet)
        XCTAssertNil(router.openApp)
        router.sheetDismissed()
        XCTAssertEqual(router.openApp?.id, app.id)
    }

    func testAppReplacedByAppStaysOnScreen() {
        let router = Router()
        router.open(WebApp(name: "Coach", startURL: URL(string: "https://coach.example.com/")!))
        router.appAppeared()
        router.open(WebApp(name: "Notes", startURL: URL(string: "https://notes.example.com/")!))
        // The replaced app's dismissal leaves the new one up.
        router.appDismissed()
        var ran = false
        router.afterClear { ran = true }
        XCTAssertFalse(ran)
    }

    func testALinkedAppWaitsForTheUnlock() {
        var locked = true
        let router = Router(isLocked: { locked })
        let app = WebApp(name: "Coach", startURL: URL(string: "https://coach.example.com/")!)
        router.handle(URL(string: "ovenlight://open?app=coach")!, apps: [app])
        XCTAssertNil(router.openApp, "nothing opens under the lock screen")
        locked = false
        XCTAssertTrue(router.appsChanged([app]), "the app it opens shows its own files")
        XCTAssertEqual(router.openApp?.id, app.id)
        locked = true
        router.handle(URL(string: "ovenlight://open?app=coach")!, apps: [app])
        locked = false
        XCTAssertFalse(router.appsChanged([app]), "already open: the unlock shows its files")
    }

    /// On a return from the background, a link arrives before the lock does.
    func testALinkedAppWaitsWhileTheLockIsDue() {
        var now = ContinuousClock.now
        var saved = false
        let lock = LockManager(hasSavedData: { saved }, now: { now })
        let previous = lock.lockAfterSeconds
        defer { lock.lockAfterSeconds = previous }
        lock.lockAfterSeconds = 60
        saved = true
        // Stands in for Face ID, which a test can't pass; the rest is the app's own check.
        var unlocked = false
        let router = Router(isLocked: { !unlocked && lock.holdsLinks })
        let app = WebApp(name: "Coach", startURL: URL(string: "https://coach.example.com/")!)
        lock.didEnterBackground()
        now += .seconds(60)
        router.handle(URL(string: "ovenlight://open?app=coach")!, apps: [app])
        XCTAssertNil(router.openApp, "nothing loads before the lock screen covers it")
        lock.willBecomeActive()
        XCTAssertTrue(lock.isLocked)
        router.appsChanged([app])
        XCTAssertNil(router.openApp)
        unlocked = true
        router.appsChanged([app])
        XCTAssertEqual(router.openApp?.id, app.id)
    }

    func testSheetsReplaceEachOther() {
        let router = Router()
        router.present(.settings)
        router.sheetAppeared()
        router.present(.addApp)
        XCTAssertEqual(router.sheet?.id, RootSheet.addApp.id)
        var ran = false
        router.afterClear { ran = true }
        // The replaced sheet's dismissal leaves the new one up.
        router.sheetDismissed()
        XCTAssertFalse(ran)
        router.sheet = nil
        router.sheetDismissed()
        XCTAssertTrue(ran)
    }
}

final class AppMenuPlanTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_790_000_000)

    private func admin(shareable: Bool) -> AdminApp {
        AdminApp(slug: "coach", name: "Coach", url: "https://coach.tail1.ts.net/", online: true, shareable: shareable, guests: 0)
    }

    private func guest(_ name: String, app: String = "coach", removed: Bool = false) -> AdminGuest {
        AdminGuest(deviceId: "n\(name)", person: "p\(name)", name: name, app: app, deviceName: "\(name)-iphone", claimedAt: t0,
                   removedAt: removed ? t0 : nil)
    }

    private func invite(_ to: String, app: String = "coach") -> AdminInvite {
        AdminInvite(id: "i\(to)", to: to, person: "p\(to)", app: app, state: AdminInvite.sent, expires: t0)
    }

    func testGroupsDevicesByPerson() {
        var pad = guest("Sam")
        pad.deviceId = "nPad"
        let sam = guest("Sam")
        let people = GuestPerson.all([sam, guest("Kim"), pad, guest("Sam", app: "notes"), guest("Lee", removed: true)])
        XCTAssertEqual(people.map(\.name), ["Sam", "Kim"])
        XCTAssertEqual(people[0].devices, "2 devices")
        XCTAssertEqual(people[1].devices, "1 device")
        XCTAssertEqual(people[1].id, "pKim")
    }

    func testOwnerShareableAppCountsActiveGuestsOfThatApp() {
        let app = WebApp(name: "Coach", startURL: URL(string: "https://coach.tail1.ts.net/")!)
        let guests = AdminGuestList(guests: [guest("Sam"), guest("Kim"), guest("Lee", removed: true), guest("Max", app: "notes")])
        let plan = AppMenuPlan.plan(for: app, admin: (admin(shareable: true), guests))
        XCTAssertEqual(plan, AppMenuPlan(share: .available, people: 2))
        XCTAssertEqual(AppMenuPlan.peopleTitle(2), "People (2)")
        XCTAssertEqual(AppMenuPlan.peopleTitle(0), "People")
    }

    func testOwnerAppNotShareableOffersNoPeople() {
        let app = WebApp(name: "Coach", startURL: URL(string: "https://coach.tail1.ts.net/")!)
        let plan = AppMenuPlan.plan(for: app, admin: (admin(shareable: false), AdminGuestList(guests: [guest("Sam")])))
        XCTAssertEqual(plan, AppMenuPlan(share: .notShareable))
        CopyRules.assertNeutral(AppMenuPlan.notShareableDetail)
    }

    func testAppWithoutConnectorOffersNothing() {
        let app = WebApp(name: "Example", startURL: URL(string: "https://example.com/")!)
        XCTAssertEqual(AppMenuPlan.plan(for: app, admin: nil), AppMenuPlan())
    }

    func testGuestNeverSeesShareOrPeople() {
        var app = WebApp(name: "Echo", startURL: URL(string: "https://echo.tail5.ts.net/")!)
        app.membershipID = "m1"
        app.sharedBy = "Riley"
        // Even if the owner's view of a same-host app were at hand.
        let plan = AppMenuPlan.plan(for: app, admin: (admin(shareable: true), AdminGuestList()))
        XCTAssertEqual(plan, AppMenuPlan(reportProblem: true, leaveOwner: "Riley"))
    }

    func testInviteChoicesLeaveOutAppReview() {
        var review = invite("App Review")
        review.review = true
        let list = AdminGuestList(guests: [guest("Sam")], invites: [invite("Kim", app: "notes"), review])
        XCTAssertEqual(GuestPerson.invitable(in: list).map(\.name), ["Sam", "Kim"])
        XCTAssertEqual(GuestPerson.everyone(in: list).map(\.name), ["Sam", "Kim", "App Review"], "still a name taken")
    }
}

@MainActor
final class LockManagerTests: XCTestCase {
    func testLocksOnlyOnceSomethingIsSaved() {
        var saved = false
        let lock = LockManager { saved }
        let previous = lock.lockAfterSeconds
        defer { lock.lockAfterSeconds = previous }
        lock.lockAfterSeconds = 0
        XCTAssertFalse(lock.isLocked, "a new install has nothing to protect")
        lock.didEnterBackground()
        lock.willBecomeActive()
        XCTAssertFalse(lock.isLocked)
        saved = true
        lock.didEnterBackground()
        lock.willBecomeActive()
        XCTAssertTrue(lock.isLocked)
    }
}

@MainActor
final class DialogAnswerTests: XCTestCase {
    func testAnswersOnceAndFallsBackWhenLetGoUnanswered() async {
        var answers: [String?] = []
        let answer = DialogAnswer<String?>(fallback: nil) { answers.append($0) }
        answer("Sam")
        answer("again")
        XCTAssertEqual(answers, ["Sam"])

        let fellBack = expectation(description: "a dialog taken away still answers")
        _ = DialogAnswer(fallback: false) { confirmed in
            XCTAssertFalse(confirmed)
            fellBack.fulfill()
        }
        await fulfillment(of: [fellBack], timeout: 1)
    }
}
