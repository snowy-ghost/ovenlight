import SwiftUI
import WebKit
import XCTest
@testable import Ovenlight

final class ManifestParserTests: XCTestCase {
    private let base = URL(string: "https://alex-mac.example.ts.net:8443/")!

    func testFindsManifestAndTouchIconInAnyAttributeOrder() {
        let html = """
        <head><link href="manifest.webmanifest" rel="manifest">
        <link rel='apple-touch-icon' href='/apple-touch-icon.png'></head>
        """
        let links = ManifestParser.links(inHTML: html, baseURL: base)
        XCTAssertEqual(links.manifest?.absoluteString, "https://alex-mac.example.ts.net:8443/manifest.webmanifest")
        XCTAssertEqual(links.touchIcon?.absoluteString, "https://alex-mac.example.ts.net:8443/apple-touch-icon.png")
    }

    func testPicksLargestRasterIconAndSkipsSVG() {
        let manifest = """
        {"name":"Interview Coach","theme_color":"#0e6b66","icons":[
          {"src":"icon-192.png","sizes":"192x192"},
          {"src":"icon-512.png","sizes":"512x512"},
          {"src":"icon.svg","sizes":"any","type":"image/svg+xml"}]}
        """
        let meta = ManifestParser.metadata(fromManifest: Data(manifest.utf8),
                                           manifestURL: base.appendingPathComponent("manifest.webmanifest"))
        XCTAssertEqual(meta.name, "Interview Coach")
        XCTAssertEqual(meta.themeColorHex, "#0e6b66")
        XCTAssertEqual(meta.iconURL?.lastPathComponent, "icon-512.png")
    }

    func testTitleFallback() {
        XCTAssertEqual(ManifestParser.title(inHTML: "<title>\n Coach </title>"), "Coach")
        XCTAssertNil(ManifestParser.title(inHTML: "<p>none</p>"))
    }
}

final class NavigationPolicyTests: XCTestCase {
    private let host = "alex-mac.example.ts.net"

    private func decide(_ text: String, mainFrame: Bool = true, tapped: Bool = false) -> NavigationPolicy.Decision {
        NavigationPolicy.decide(url: URL(string: text)!, appHost: host, isMainFrame: mainFrame, tapped: tapped)
    }

    func testSameHostStaysInApp() {
        XCTAssertEqual(decide("https://alex-mac.example.ts.net:8443/cards"), .allow)
    }

    func testOnlyTheAppsOwnPagesPopUpAPost() {
        var post = URLRequest(url: URL(string: "https://alex-mac.example.ts.net/api/notes")!)
        post.httpMethod = "POST"
        post.httpBody = Data("x".utf8)
        let own = NavigationPolicy.popupRequest(post, fromScheme: "https", host: "Alex-Mac.example.ts.net.", appHost: host)
        XCTAssertEqual(own.httpMethod, "POST")
        for (scheme, other) in [("https", "widget.example.com"), ("http", host), (nil, nil)] as [(String?, String?)] {
            let sent = NavigationPolicy.popupRequest(post, fromScheme: scheme, host: other, appHost: host)
            XCTAssertEqual(sent.httpMethod, "GET")
            XCTAssertNil(sent.httpBody)
            XCTAssertEqual(sent.url, post.url)
        }
    }

    func testOtherHostLeavesAppButIframesLoad() {
        let url = URL(string: "https://accounts.google.com/o/oauth2")!
        XCTAssertEqual(decide(url.absoluteString), .openExternally(url))
        XCTAssertEqual(decide(url.absoluteString, mainFrame: false), .allow)
    }

    func testOtherAppsOpenOnlyFromATapInTheMainFrame() {
        let mail = URL(string: "mailto:me@example.com")!
        XCTAssertEqual(decide(mail.absoluteString, tapped: true), .openExternally(mail))
        XCTAssertEqual(decide(mail.absoluteString), .refuse, "a script can't open other apps")
        XCTAssertEqual(decide(mail.absoluteString, mainFrame: false, tapped: true), .refuse, "nor can a frame")
        XCTAssertEqual(decide("about:blank"), .allow)
    }

    func testThePageShowsOnlyTheAppsOwnBlobs() {
        XCTAssertEqual(decide("blob:https://Alex-Mac.example.ts.net:8443/6f1c"), .allow)
        XCTAssertEqual(decide("blob:https://evil.example.com/6f1c"), .refuse, "a frame of another site made it")
        XCTAssertEqual(decide("blob:null/6f1c"), .refuse)
        XCTAssertEqual(decide("blob:https://evil.example.com/6f1c", mainFrame: false), .allow, "a frame shows its own")
    }

    func testPagesNeverOpenOvenlightsOwnLinks() throws {
        for link in ["ovenlight://join?v=1&key=k", "Ovenlight://join?v=1", "ovenlight://open?app=Coach"] {
            XCTAssertEqual(decide(link, tapped: true), .refuse, link)
        }
        let types = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]])
        let schemes = types.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        XCTAssertEqual(schemes, [InviteLink.scheme])
    }

    func testOvenlightAsksBeforeOpeningAnyAppIOSDoesnt() {
        for link in ["sms:+15550100", "shortcuts://run-shortcut?name=x", "mailto:me@example.com"] {
            XCTAssertTrue(NavigationPolicy.asksBeforeOpening(URL(string: link)!), link)
        }
        XCTAssertFalse(NavigationPolicy.asksBeforeOpening(URL(string: "tel:+15550100")!), "iOS asks before a call")
        XCTAssertFalse(NavigationPolicy.asksBeforeOpening(URL(string: "FaceTime:me@example.com")!))
        let long = URL(string: "shortcuts://run-shortcut?name=" + String(repeating: "x", count: 100))!
        XCTAssertEqual(NavigationPolicy.target(of: long).count, 82, "quoted and cut short")
    }
}

final class MediaCapturePolicyTests: XCTestCase {
    func testOnlyTheOwnersOwnAppsGetTheMicrophone() {
        let app = WebApp(name: "Coach", startURL: URL(string: "https://coach.tailabc123.ts.net/")!)
        var shared = app
        shared.membershipID = "m1"
        func decide(_ type: WKMediaCaptureType, in app: WebApp, from origin: String = "https://Coach.tailabc123.ts.net",
                    locked: Bool = false, onScreen: Bool = true) -> WKPermissionDecision {
            let url = URL(string: origin)!
            return MediaCapturePolicy.decide(type: type, scheme: url.scheme!, host: url.host!, app: app,
                                             ownersHosts: ["coach.tailabc123.ts.net"], locked: locked, onScreen: onScreen)
        }
        XCTAssertEqual(decide(.microphone, in: app), .grant)
        XCTAssertEqual(decide(.microphone, in: app, locked: true), .deny, "nothing records under the lock screen")
        XCTAssertEqual(decide(.camera, in: app, locked: true), .deny)
        XCTAssertEqual(decide(.microphone, in: app, onScreen: false), .deny, "nor a page kept in the background")
        XCTAssertEqual(decide(.camera, in: shared, onScreen: false), .deny)
        XCTAssertEqual(decide(.camera, in: app), .prompt)
        XCTAssertEqual(decide(.cameraAndMicrophone, in: app), .prompt)
        XCTAssertEqual(decide(.microphone, in: shared), .prompt, "someone else's app asks")
        XCTAssertEqual(decide(.microphone, in: app, from: "https://evil.example.com"), .prompt, "a frame from elsewhere asks")
        XCTAssertEqual(decide(.microphone, in: app, from: "http://coach.tailabc123.ts.net"), .prompt)
        let site = WebApp(name: "Site", startURL: URL(string: "https://recorder.example.com/")!)
        XCTAssertEqual(decide(.microphone, in: site, from: "https://recorder.example.com"), .prompt,
                       "a site added by address isn't one of the owner's apps")
        let funnel = WebApp(name: "Funnel", startURL: URL(string: "https://recorder.tail0000.ts.net/")!)
        XCTAssertEqual(decide(.microphone, in: funnel, from: "https://recorder.tail0000.ts.net"), .prompt,
                       "nor is someone's public Funnel address")
        let found = WebApp(name: "Found", startURL: URL(string: "https://notes.tailabc123.ts.net/")!, discovered: true)
        XCTAssertEqual(decide(.microphone, in: found, from: "https://notes.tailabc123.ts.net"), .grant, "discovered on the owner's machines")
    }
}

final class DownloadPolicyTests: XCTestCase {
    private let host = "coach.tailabc123.ts.net"

    private func allows(_ text: String, from source: String? = "Coach.tailabc123.ts.net", locked: Bool = false) -> Bool {
        DownloadPolicy.allows(url: URL(string: text)!, from: source, appHost: host, locked: locked)
    }

    func testOnlyTheAppsOwnFilesDownload() {
        XCTAssertTrue(allows("https://coach.tailabc123.ts.net/export.csv"))
        XCTAssertTrue(allows("https://coach.tailabc123.ts.net/export.csv", from: nil), "a response names no frame")
        XCTAssertTrue(allows("blob:https://coach.tailabc123.ts.net/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10"))
        XCTAssertTrue(allows("data:text/csv,a,b"))
        XCTAssertFalse(allows("data:text/csv,a,b", from: nil), "a data URL is the app's only when its page started it")
        XCTAssertFalse(allows("https://cdn.example.com/export.csv"))
        XCTAssertFalse(allows("blob:https://widget.example.com/6c1f0b2e-1d2a-4c8e-9a51-0b7a3e5d9f10"))
        XCTAssertFalse(allows("https://coach.tailabc123.ts.net/export.csv", from: "widget.example.com"),
                       "a frame of another site starts nothing")
        XCTAssertFalse(allows("https://coach.tailabc123.ts.net/export.csv", locked: true), "nothing under the lock screen")
        XCTAssertFalse(allows("javascript:void(0)"))
    }

    func testAttachmentsAndFilesWebKitCantShowDownload() {
        func action(_ status: Int?, _ disposition: String?, canShow: Bool = true) -> DownloadPolicy.ResponseAction {
            DownloadPolicy.action(status: status, disposition: disposition, canShow: canShow)
        }
        XCTAssertEqual(action(200, "attachment; filename=\"export.csv\""), .download)
        XCTAssertEqual(action(200, " Attachment"), .download)
        XCTAssertEqual(action(200, "attachment ; filename=export.csv"), .download, "space before the semicolon")
        XCTAssertEqual(action(200, "ATTACHMENT;filename=export.csv"), .download)
        XCTAssertEqual(action(200, nil, canShow: false), .download, "a zip, say")
        XCTAssertEqual(action(nil, nil, canShow: false), .download, "a blob WebKit can't show")
        XCTAssertEqual(action(200, "inline; filename=\"report.pdf\""), .show)
        XCTAssertEqual(action(200, "attachments-are-not-this"), .show)
        XCTAssertEqual(action(200, nil), .show)
        XCTAssertEqual(action(200, ""), .show)
        // No body, and no Content-Type, which WebKit takes as application/octet-stream.
        XCTAssertEqual(action(204, nil, canShow: false), .show, "WebKit stays on the page itself")
        XCTAssertEqual(action(205, nil, canShow: false), .show)
        XCTAssertEqual(action(204, "attachment; filename=export.csv"), .show)
        XCTAssertEqual(action(304, "attachment; filename=export.csv", canShow: false), .show)
        XCTAssertEqual(action(404, nil, canShow: false), .show, "an error page isn't a file")
        XCTAssertEqual(action(500, nil, canShow: false), .show)
    }

    func testFileNamesAreSafeToWrite() {
        XCTAssertEqual(DownloadPolicy.fileName("export.csv"), "export.csv")
        XCTAssertEqual(DownloadPolicy.fileName("../../Library/apps.json"), "-..-Library-apps.json")
        XCTAssertEqual(DownloadPolicy.fileName(".hidden"), "hidden")
        XCTAssertEqual(DownloadPolicy.fileName("a:b\\c\u{0}d.txt"), "a-b-c-d.txt")
        XCTAssertEqual(DownloadPolicy.fileName(" .. "), "Download")
        XCTAssertEqual(DownloadPolicy.fileName("Notes ✏️ May.md"), "Notes ✏️ May.md")
        let long = DownloadPolicy.fileName(String(repeating: "é", count: 300) + ".csv")
        XCTAssertLessThanOrEqual(long.utf8.count, DownloadPolicy.maxFileNameBytes)
        XCTAssertTrue(long.hasSuffix("é.csv"))
    }

    func testAFailedDownloadIsNamedOnlyByARealFileName() {
        func notice(_ url: String) -> String { DownloadPolicy.failedNotice(DownloadPolicy.name(in: URL(string: url))) }
        XCTAssertEqual(notice("https://coach.example.com/exports/report.pdf"), "Couldn't download report.pdf")
        XCTAssertEqual(notice("https://coach.example.com/"), "Couldn't download the file", "no path")
        XCTAssertEqual(notice("https://coach.example.com"), "Couldn't download the file")
        XCTAssertEqual(notice("blob:https://coach.example.com/6f1c2b3a-0d4e-4f5a-9b8c-7d6e5f4a3b2c"), "Couldn't download the file")
        XCTAssertEqual(notice("data:text/csv;base64,YSxi"), "Couldn't download the file")
    }

    /// The share sheet closes once, a turn after it leaves the screen or is let go, kept
    /// until then, and hands over what waited and whether it ever showed.
    @MainActor
    func testTheShareSheetClosesOnce() async throws {
        var log: [String: [String]] = [:]
        func sheet(_ name: String, appear: Bool) -> ShareSheet {
            let sheet = ShareSheet(activityItems: [], applicationActivities: nil)
            sheet.waiting = [{ log[name, default: []].append("waited") }]
            sheet.closed = { shown, waiting in
                log[name, default: []].append(shown ? "closed" : "unseen")
                waiting.forEach { $0() }
            }
            if appear { sheet.viewDidAppear(false) }
            return sheet
        }
        weak var kept: ShareSheet?
        do {
            let dismissed = sheet("dismissed", appear: true)
            XCTAssertTrue(dismissed.isOffScreen)
            (0..<2).forEach { _ in dismissed.viewDidDisappear(false) }
            sheet("unseen", appear: false).viewDidDisappear(false)
            XCTAssertEqual(log, [:], "not from inside viewDidDisappear")
            kept = dismissed
        }
        XCTAssertNotNil(kept, "kept until it has closed")
        _ = sheet("covered", appear: true)
        _ = sheet("refused", appear: false)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(log, ["dismissed": ["closed", "waited"], "unseen": ["unseen", "waited"],
                             "covered": ["closed", "waited"], "refused": ["unseen", "waited"]])
    }

    /// Only a sheet that showed takes its files off the waiting list; either way it unregisters.
    @MainActor
    func testOnlyAShownSheetTakesItsFiles() async throws {
        let id = UUID(), shared = URL(fileURLWithPath: "/a"), later = URL(fileURLWithPath: "/b")
        WebViewPool.shared.waitingDownloads[id] = [shared, later]
        defer { WebViewPool.shared.waitingDownloads[id] = nil }
        _ = WebCoordinator.makeSheet(sharing: [shared], of: id)
        let sheet = WebCoordinator.makeSheet(sharing: [shared], of: id)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(WebViewPool.shared.waitingDownloads[id], [shared, later], "refused")
        XCTAssertTrue(WebCoordinator.sheet === sheet)
        sheet.viewDidAppear(false)
        sheet.viewDidDisappear(false)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(WebCoordinator.sheet)
        XCTAssertEqual(WebViewPool.shared.waitingDownloads[id], [later])
    }
}

final class UserAgentTests: XCTestCase {
    func testEndsWithOvenlightAndItsVersion() {
        XCTAssertEqual(WebViewPool.userAgentName(after: "Mobile/15E148", version: "1.2"), "Mobile/15E148 Ovenlight/1.2")
        XCTAssertEqual(WebViewPool.userAgentName(after: nil, version: "1.2"), "Ovenlight/1.2")
    }

    /// What a page sees, as Ovenlight's views are configured.
    @MainActor
    func testPagesStillSeeMobileSafari() async throws {
        let config = WKWebViewConfiguration()
        WebViewPool.configure(config)
        let view = WKWebView(frame: .zero, configuration: config)
        view.loadHTMLString("<p>hi</p>", baseURL: nil)
        let result = try await view.evaluateJavaScript("navigator.userAgent")
        let agent = try XCTUnwrap(result as? String)
        let version = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        XCTAssertTrue(agent.hasPrefix("Mozilla/5.0 (iPhone;"), agent)
        let ending = #" Mobile/\S+ Ovenlight/"# + NSRegularExpression.escapedPattern(for: version) + "$"
        XCTAssertNotNil(agent.range(of: ending, options: .regularExpression), agent)
    }
}

final class AppAddressTests: XCTestCase {
    func testAddsHTTPSToABareHostAndKeepsPortAndPath() {
        XCTAssertEqual(AppAddress.url(from: "  alex-mac.tailabc123.ts.net:8443/app ")?.absoluteString,
                       "https://alex-mac.tailabc123.ts.net:8443/app")
        XCTAssertEqual(AppAddress.url(from: "HTTPS://example.com")?.host, "example.com")
    }

    func testRefusesInsecureAndIncompleteAddresses() {
        XCTAssertNil(AppAddress.url(from: "http://example.com"))
        XCTAssertNil(AppAddress.url(from: "https://"))
        XCTAssertNil(AppAddress.url(from: "example"))
        XCTAssertNil(AppAddress.url(from: ""))
    }

    func testAddsOnlyAddressesOnTheOwnersOwnTailnet() {
        let own = "tailabc123.ts.net"
        XCTAssertEqual(AppAddress.url(from: "coach.tailabc123.ts.net", ownerSuffix: own)?.absoluteString,
                       "https://coach.tailabc123.ts.net")
        XCTAssertEqual(AppAddress.url(from: "https://Coach.TailABC123.ts.net:8443/app", ownerSuffix: own)?.port, 8443)
        XCTAssertNil(AppAddress.url(from: "example.com", ownerSuffix: own))
        XCTAssertNil(AppAddress.url(from: "recorder.tail0000.ts.net", ownerSuffix: own), "another tailnet's Funnel name")
        XCTAssertNil(AppAddress.url(from: "tailabc123.ts.net.example.com", ownerSuffix: own))
        XCTAssertNil(AppAddress.url(from: "http://coach.tailabc123.ts.net", ownerSuffix: own))
        XCTAssertNil(AppAddress.url(from: "coach.tailabc123.ts.net", ownerSuffix: nil), "not signed in to their own computers yet")
        XCTAssertNil(AppAddress.url(from: "coach.tailabc123.ts.net", ownerSuffix: ""))
    }
}

@MainActor
final class AppRegistryTests: XCTestCase {
    private func makeRegistry(_ dir: URL) -> AppRegistry {
        AppRegistry(directory: dir, session: { _ in nil }) // no network in tests
    }

    func testStartsEmptyAndPersistsChanges() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }

        let first = makeRegistry(dir)
        XCTAssertEqual(first.apps, [])
        let app = first.add(url: URL(string: "https://notes.example.com/")!)
        first.rename(app.id, to: "Notes")

        let second = makeRegistry(dir)
        XCTAssertEqual(second.apps.map(\.name), ["Notes"])
        XCTAssertEqual(second.apps[0].dataStoreID, app.dataStoreID)
    }

    func testDiscoveredAppsPersistAndRemovedOnesStayRemoved() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let coach = DiscoveredApp(host: "interview-coach.tailabc123.ts.net",
                                  manifest: OvenlightManifest(name: "Interview Coach", icon: "/icon-512.png", version: 1))

        let registry = makeRegistry(dir)
        await registry.mergeDiscovered([coach])
        XCTAssertEqual(registry.apps.map(\.name), ["Interview Coach"])
        XCTAssertTrue(registry.apps[0].discovered)
        await registry.remove(registry.apps[0].id)
        await registry.mergeDiscovered([coach])
        XCTAssertEqual(registry.apps, [])

        let reopened = makeRegistry(dir)
        await reopened.mergeDiscovered([coach])
        XCTAssertEqual(reopened.apps, [], "a removed app must not come back after relaunch")
        reopened.add(url: URL(string: "https://interview-coach.tailabc123.ts.net/")!)
        XCTAssertEqual(reopened.apps.count, 1, "adding it by hand brings it back")
    }

    func testRemovedHostsAreSavedEvenWhenTheAppsCantBe() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let registry = makeRegistry(dir)
        let app = registry.add(url: URL(string: "https://notes.tailabc123.ts.net/")!)
        let file = dir.appendingPathComponent("apps.json")
        try FileManager.default.removeItem(at: file)
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false) // nothing can be written there

        await registry.remove(app.id)
        let dismissed = try JSONDecoder().decode([String].self, from: Data(contentsOf: dir.appendingPathComponent("dismissed-hosts.json")))
        XCTAssertEqual(dismissed, ["notes.tailabc123.ts.net"])
    }

    func testSigningOutRemovesTheAppsFoundOnYourComputers() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let defaults = UserDefaults.standard
        let keys = ["ownTailnetEnabled", MembershipNode.ownerSuffixKey, NodeManager.signOutPendingKey]
        let saved = keys.map { defaults.object(forKey: $0) }
        defer { for (key, value) in zip(keys, saved) { defaults.set(value, forKey: key) } }
        let nodes = NodeManager.shared
        let registry = makeRegistry(dir)
        let hook = nodes.onOwnerForgotten
        nodes.onOwnerForgotten = { await registry.removeDiscovered() }
        defer { nodes.onOwnerForgotten = hook }
        // Signing out deletes the owner's node state: the test host's own waits aside.
        let state = NodeManager.stateDirectory(for: Membership.ownerID)
        let aside = dir.appendingPathComponent("owner")
        try? FileManager.default.moveItem(at: state, to: aside)
        defer { try? FileManager.default.moveItem(at: aside, to: state) }

        let coach = DiscoveredApp(host: "interview-coach.tailabc123.ts.net",
                                  manifest: OvenlightManifest(name: "Interview Coach", version: 1))
        let recipes = DiscoveredApp(host: "recipe-box.tailabc123.ts.net", manifest: OvenlightManifest(name: "Recipe Box", version: 1))
        await registry.mergeDiscovered([coach, recipes])
        await registry.remove(try XCTUnwrap(registry.apps.first { $0.name == "Recipe Box" }).id)
        let handAdded = registry.add(url: URL(string: "https://notes.tailabc123.ts.net/")!)
        let shared = registry.addShared(name: "Echo Board", host: "echo-board.taildef456.ts.net", slug: "echo-board",
                                        membershipID: "m1", sharedBy: "Riley")
        let found = try XCTUnwrap(registry.apps.first(where: \.discovered))
        let cookie = try XCTUnwrap(HTTPCookie(properties: [.domain: coach.host, .path: "/", .name: "session", .value: "1"]))
        await WKWebsiteDataStore(forIdentifier: found.dataStoreID).httpCookieStore.setCookie(cookie)
        func stores() async -> [UUID] {
            await withCheckedContinuation { continuation in
                WKWebsiteDataStore.fetchAllDataStoreIdentifiers { continuation.resume(returning: $0) }
            }
        }
        let before = await stores()
        XCTAssertTrue(before.contains(found.dataStoreID))

        await nodes.disableOwner()
        XCTAssertEqual(registry.apps.map(\.id), [handAdded.id, shared.id])
        XCTAssertEqual(nodes.discovered, [], "what the node found goes with it, so nothing merges it back")
        let after = await stores()
        XCTAssertFalse(after.contains(found.dataStoreID), "its data store is deleted")

        await registry.mergeDiscovered([coach, recipes])
        XCTAssertEqual(registry.apps.filter(\.discovered).map(\.name), ["Interview Coach", "Recipe Box"],
                       "signing in again finds them all")

        // Apps found before an earlier version's Sign Out, which kept them, stay at launch.
        try FileManager.default.createDirectory(at: state, withIntermediateDirectories: true)
        await nodes.finishSignOut()
        XCTAssertEqual(registry.apps.filter(\.discovered).count, 2)

        // Ovenlight quit during a Sign Out: launch finishes it.
        UserDefaults.standard.set(true, forKey: NodeManager.signOutPendingKey)
        await nodes.finishSignOut()
        XCTAssertFalse(FileManager.default.fileExists(atPath: state.path), "its login doesn't come back")
        XCTAssertEqual(registry.apps.map(\.id), [handAdded.id, shared.id])
    }

    func testAMissingFileIsEmptyAndSaves() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let registry = makeRegistry(dir)
        XCTAssertFalse(registry.loadFailed)
        registry.add(url: URL(string: "https://notes.example.com/")!)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent("apps.json").path))
        let unknown = UUID()
        XCTAssertEqual(registry.unusedDataStores(among: [registry.apps[0].dataStoreID, unknown]), [unknown])
    }

    func testAnUnreadableFileIsNeverSavedOverOrSwept() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("apps.json")
        let corrupt = Data("{\"truncated".utf8)
        try corrupt.write(to: file)

        let registry = makeRegistry(dir)
        XCTAssertTrue(registry.loadFailed)
        XCTAssertEqual(registry.unusedDataStores(among: [UUID()]), [], "every store would look unused")
        registry.add(url: URL(string: "https://notes.example.com/")!)
        XCTAssertEqual(try Data(contentsOf: file), corrupt, "nothing is saved over the file")

        // Once the file reads (after the first unlock), the reload replaces the guesswork.
        let saved = makeRegistry(FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        saved.add(url: URL(string: "https://coach.example.com/")!)
        try JSONEncoder().encode(saved.apps).write(to: file)
        registry.reloadIfFailed()
        XCTAssertFalse(registry.loadFailed)
        XCTAssertEqual(registry.apps, saved.apps)
    }

    func testDecodesEachAppOnItsOwnAndFillsInWhatsMissing() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let json = """
        [{"id": "\(UUID())", "startURL": "https://coach.tailabc123.ts.net/", "dataStoreID": "\(UUID())",
          "name": "Coach", "themeColorHex": 7, "futureField": {"a": 1}},
         {"name": "No ID", "startURL": "https://broken.example.com/"},
         "not an app"]
        """
        try Data(json.utf8).write(to: dir.appendingPathComponent("apps.json"))
        let registry = makeRegistry(dir)
        XCTAssertFalse(registry.loadFailed)
        XCTAssertEqual(registry.apps.map(\.name), ["Coach"])
        XCTAssertNil(registry.apps[0].themeColorHex)
        XCTAssertFalse(registry.apps[0].discovered || registry.apps[0].isNew)

        // What it couldn't read may be another build's app, with a data store of its own.
        XCTAssertFalse(registry.readInFull)
        XCTAssertEqual(registry.unusedDataStores(among: [UUID()]), [], "no store is swept")
        registry.add(url: URL(string: "https://notes.example.com/")!)
        let saved = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("apps.json"))) as? [Any])
        XCTAssertEqual(saved.count, 4, "the two it read, then the two it didn't, as they were")
        XCTAssertEqual((saved[2] as? [String: String])?["name"], "No ID")
        XCTAssertEqual(saved[3] as? String, "not an app")
        let reopened = makeRegistry(dir)
        XCTAssertEqual(reopened.apps.map(\.name), ["Coach", "notes.example.com"])
        XCTAssertFalse(reopened.readInFull)
    }

    func testIconsAreDecodedSmall() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let png = UIGraphicsImageRenderer(size: CGSize(width: 2048, height: 1024), format: format).pngData { context in
            UIColor.orange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 2048, height: 1024))
        }
        XCTAssertEqual(AppRegistry.iconImage(from: png)?.cgImage.map { [$0.width, $0.height] }, [288, 144], "what a save writes")
        let wide = UIGraphicsImageRenderer(size: CGSize(width: 4097, height: 8), format: format).pngData { context in
            context.fill(CGRect(x: 0, y: 0, width: 4097, height: 8))
        }
        XCTAssertNil(AppRegistry.iconImage(from: wide), "over 4096 pixels a side, refused before decoding")
        let tall = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 4097), format: format).pngData { context in
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 4097))
        }
        XCTAssertNil(AppRegistry.iconImage(from: tall), "over 4096 pixels high, refused too")

        // One an earlier build saved as the server sent it.
        let registry = makeRegistry(dir)
        var app = WebApp(name: "Coach", startURL: URL(string: "https://coach.tailabc123.ts.net/")!)
        app.iconFile = "coach.png"
        try png.write(to: registry.iconsDirectory.appendingPathComponent("coach.png"))
        XCTAssertEqual(registry.icon(for: app)?.cgImage.map { [$0.width, $0.height] }, [288, 144])
    }
}

@MainActor
final class MembershipStoreLoadTests: XCTestCase {
    func testAnUnreadableFileIsNeverSavedOver() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("memberships.json")
        let corrupt = Data("[{".utf8)
        try corrupt.write(to: file)

        let store = MembershipStore(directory: dir)
        XCTAssertTrue(store.loadFailed)
        let invite = InviteLink(control: URL(string: "https://controlplane.tailscale.com")!, key: "tskey-1", owner: "Riley",
                                app: "echo-board", invite: "inv1", host: "echo-board.taildef456.ts.net", name: "Echo Board", to: "Sam")
        store.upsert(Membership(invite: invite))
        XCTAssertEqual(try Data(contentsOf: file), corrupt)

        try FileManager.default.removeItem(at: file)
        let fresh = MembershipStore(directory: dir)
        XCTAssertFalse(fresh.loadFailed, "no file is simply no memberships")
        fresh.upsert(Membership(invite: invite))
        XCTAssertEqual(MembershipStore(directory: dir).memberships.count, 1)
        XCTAssertTrue(fresh.readInFull)
    }

    func testAMembershipItCantReadIsKeptAndHoldsBackCleanup() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("memberships.json")
        try Data(#"[{"id":"future","controlURL":"https://c.example","networkDomain":"tail1.ts.net","joined":"yes","hostName":"ovenlight-x"}]"#.utf8)
            .write(to: file)

        let store = MembershipStore(directory: dir)
        XCTAssertFalse(store.loadFailed)
        XCTAssertEqual(store.memberships, [])
        XCTAssertFalse(store.readInFull, "its apps would look orphaned")
        let invite = InviteLink(control: URL(string: "https://controlplane.tailscale.com")!, key: "tskey-1", owner: "Riley",
                                app: "echo-board", invite: "inv1", host: "echo-board.taildef456.ts.net", name: "Echo Board", to: "Sam")
        store.upsert(Membership(invite: invite))
        let saved = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [[String: Any]])
        XCTAssertEqual(saved.map { $0["id"] as? String }.last, "future", "written back as it was")
        XCTAssertEqual(saved.first?["joined"] as? Bool, false)
        XCTAssertEqual(MembershipStore(directory: dir).memberships, store.memberships, "what it read reads back the same")
    }
}

@MainActor
final class LockTimingTests: XCTestCase {
    func testLocksByTimeInTheBackgroundNotTheClock() {
        var now = ContinuousClock.now
        var saved = false
        let lock = LockManager(hasSavedData: { saved }, now: { now }, defaults: .throwaway(for: self))
        let previous = lock.lockAfterSeconds
        defer { lock.lockAfterSeconds = previous }
        saved = true
        lock.lockAfterSeconds = 60

        lock.didEnterBackground()
        now += .seconds(59)
        lock.willBecomeActive()
        XCTAssertFalse(lock.isLocked)

        lock.didEnterBackground()
        now += .seconds(60)
        lock.willBecomeActive()
        XCTAssertTrue(lock.isLocked)
    }

    /// What a link arriving on a return from the background asks, before `willBecomeActive`.
    func testLockIsDueByTheSameRule() {
        var now = ContinuousClock.now
        var saved = false
        let lock = LockManager(hasSavedData: { saved }, now: { now }, defaults: .throwaway(for: self))
        let previous = lock.lockAfterSeconds
        defer { lock.lockAfterSeconds = previous }
        lock.lockAfterSeconds = 60

        lock.didEnterBackground()
        now += .seconds(60)
        XCTAssertFalse(lock.lockIsDue, "nothing saved, nothing to lock")
        lock.willBecomeActive()

        saved = true
        XCTAssertFalse(lock.lockIsDue, "not in the background")
        lock.didEnterBackground()
        now += .seconds(59)
        XCTAssertFalse(lock.lockIsDue)
        now += .seconds(1)
        XCTAssertTrue(lock.lockIsDue)
        XCTAssertFalse(lock.isLocked, "due, not yet locked")
        lock.willBecomeActive()
        XCTAssertTrue(lock.isLocked)
        XCTAssertFalse(lock.lockIsDue, "back in front")
    }

    /// Quit in the background (by iOS, to free memory, or by the person), Ovenlight launches
    /// unlocked only within the delay, on the same boot, and never set to Immediately.
    func testALaunchLocksUnlessQuitInTheBackgroundWithinTheDelay() {
        let defaults = UserDefaults.throwaway(for: self)
        var now = ContinuousClock.now
        var boot: String? = "boot-1"
        var saved = false
        func launch() -> LockManager { LockManager(hasSavedData: { saved }, now: { now }, boot: { boot }, defaults: defaults) }
        let first = launch()
        let previous = first.lockAfterSeconds
        defer { first.lockAfterSeconds = previous }
        first.lockAfterSeconds = 60
        saved = true
        XCTAssertTrue(launch().isLocked, "never in the background yet")

        first.didEnterBackground()
        now += .seconds(30)
        let inBackground = launch()
        XCTAssertFalse(inBackground.isLocked, "quit 30 seconds after it left the front")
        inBackground.didEnterBackground()
        now += .seconds(30)
        XCTAssertTrue(launch().isLocked, "a launch in the background doesn't start the delay over")

        now -= .seconds(30)
        boot = "boot-2"
        XCTAssertTrue(launch().isLocked, "the iPhone restarted")
        boot = nil
        XCTAssertTrue(launch().isLocked, "this boot can't be told")
        XCTAssertNotNil(UUID(uuidString: LockManager.bootSession() ?? ""), "the kernel's boot session reads")
        boot = "boot-1"
        first.lockAfterSeconds = 0
        XCTAssertTrue(launch().isLocked, "Immediately")
        first.lockAfterSeconds = 60

        let relaunched = launch()
        XCTAssertFalse(relaunched.isLocked)
        relaunched.willBecomeActive()
        XCTAssertTrue(launch().isLocked, "quit in front: no time in the background to go by")
    }

    func testAnEmptyNodeFolderOrListIsNothingToLock() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: base) }
        let nodes = base.appendingPathComponent("tailscale/owner", isDirectory: true)
        try FileManager.default.createDirectory(at: nodes, withIntermediateDirectories: true)
        XCTAssertTrue(OvenlightApp.hasSavedData(in: base))
        try FileManager.default.removeItem(at: nodes)
        XCTAssertFalse(OvenlightApp.hasSavedData(in: base), "what Stop Using My Own Computers leaves")
        for name in ["apps.json", "memberships.json"] {
            try Data("[]".utf8).write(to: base.appendingPathComponent(name))
        }
        XCTAssertFalse(OvenlightApp.hasSavedData(in: base), "what a failed join or removing the last app leaves")
        let app = WebApp(name: "Notes", startURL: URL(string: "https://notes.example.com/")!)
        try JSONEncoder().encode([app]).write(to: base.appendingPathComponent("apps.json"))
        XCTAssertTrue(OvenlightApp.hasSavedData(in: base))
    }
}

final class NodeMachineTests: XCTestCase {
    private let login = URL(string: "https://login.tailscale.com/a/abc")!

    private func run(_ events: [NodeEvent], from state: NodeState = .idle) -> NodeState {
        events.reduce(state) { NodeMachine.next($0, $1) }
    }

    func testFirstRunGoesThroughLogin() {
        XCTAssertEqual(run([.start, .backend(state: "NoState", authURL: nil)]), .starting)
        XCTAssertEqual(run([.start, .backend(state: "NeedsLogin", authURL: nil)]), .starting)
        XCTAssertEqual(run([.start, .backend(state: "NeedsLogin", authURL: login)]), .needsLogin(login))
        // After signing in the backend starts, then runs; the reachability check decides ready.
        XCTAssertEqual(run([.backend(state: "Starting", authURL: nil)], from: .needsLogin(login)), .connecting)
        XCTAssertEqual(run([.backend(state: "Running", authURL: nil), .reachable], from: .needsLogin(login)), .ready)
    }

    @MainActor
    func testDeviceNameIsTheOneTheTailnetGave() throws {
        let requested = NodeManager.hostName
        let waiting = #"{"BackendState":"NeedsMachineAuth","Self":{"HostName":"ovenlight-abc123","DNSName":"ovenlight-abc123-1.tailabc123.ts.net.","UserID":42}}"#
        XCTAssertEqual(NodeManager.deviceName(in: try XCTUnwrap(TailnetStatus.decode(Data(waiting.utf8)))), "ovenlight-abc123-1")
        XCTAssertEqual(NodeManager.deviceName(in: nil), requested, "before the tailnet names it")
        let unnamed = #"{"BackendState":"NeedsMachineAuth","Self":{"HostName":"ovenlight-abc123","DNSName":""}}"#
        XCTAssertEqual(NodeManager.deviceName(in: try XCTUnwrap(TailnetStatus.decode(Data(unnamed.utf8)))), requested)
    }

    func testLoggedInNodeConnectsWithoutLogin() {
        XCTAssertEqual(run([.start, .backend(state: "Starting", authURL: nil)]), .starting)
        XCTAssertEqual(run([.start, .backend(state: "Running", authURL: nil)]), .connecting)
        XCTAssertEqual(run([.start, .backend(state: "Running", authURL: nil), .reachable]), .ready)
    }

    func testLostFirstHandshakeRetriesThenGivesUpToTheApps() {
        let first = run([.start, .backend(state: "Running", authURL: nil), .unreachable])
        XCTAssertEqual(first, .retrying(attempt: 1))
        XCTAssertEqual(run([.backend(state: "Running", authURL: nil)], from: first), first, "polls keep the retry count")
        XCTAssertEqual(run([.unreachable, .reachable], from: first), .ready)
        let exhausted = run(Array(repeating: .unreachable, count: NodeMachine.maxRetries + 1), from: .connecting)
        XCTAssertEqual(exhausted, .ready, "the node is up; the app shows its own error from here")
    }

    func testStopRestartAndFailures() {
        XCTAssertEqual(run([.stop], from: .ready), .idle)
        XCTAssertEqual(run([.start], from: .ready), .starting)
        XCTAssertEqual(run([.error("boom")], from: .starting), .failed("boom"))
        XCTAssertEqual(run([.backend(state: "Running", authURL: nil)], from: .failed("boom")), .connecting)
        XCTAssertEqual(run([.backend(state: "NeedsMachineAuth", authURL: nil)], from: .starting), .awaitingApproval,
                       "device approval pending is its own state, for the owner's approval prompt")
        XCTAssertEqual(run([.backend(state: "Running", authURL: nil)], from: .awaitingApproval), .connecting)
        XCTAssertEqual(run([.reachable], from: .needsLogin(login)), .needsLogin(login), "a stray probe result can't skip login")
    }

    func testTheLogNeverCarriesTheSignInLink() {
        XCTAssertEqual(NodeState.needsLogin(login).logDescription, "needsLogin")
        XCTAssertEqual(NodeState.retrying(attempt: 2).logDescription, "retrying(attempt: 2)")
        XCTAssertEqual(NodeState.failed("boom").logDescription, "failed", "the message is the summary beside it")
    }

    /// On a tailnet that approves each new device, the start after the sign-in doesn't close
    /// the sign-in sheet.
    func testSignInStaysOpenUntilApproved() {
        var states: [NodeState] = [.needsLogin(login)]
        for event: NodeEvent in [.backend(state: "Starting", authURL: nil), .backend(state: "NeedsMachineAuth", authURL: nil),
                                 .backend(state: "Running", authURL: nil), .reachable] {
            states.append(NodeMachine.next(states.last!, event))
        }
        XCTAssertEqual(states, [.needsLogin(login), .connecting, .awaitingApproval, .connecting, .ready])
        XCTAssertEqual(states.map(\.endsSignIn), [false, false, false, false, true])
        XCTAssertTrue(NodeState.failed("boom").endsSignIn)
        XCTAssertFalse(NodeState.idle.endsSignIn, "a restart on coming back passes through idle")
    }

    func testStatusDecoding() throws {
        let json = """
        {"BackendState":"Running","AuthURL":"","Self":{"HostName":"ovenlight-abc123","DNSName":"ovenlight-abc123.tailabc123.ts.net.","OS":"iOS","Online":true,"UserID":42},
         "Peer":{"nodekey:1":{"HostName":"interview-coach","DNSName":"Interview-Coach.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":42},
                 "nodekey:2":{"HostName":"phone","DNSName":"phone.tailabc123.ts.net.","OS":"iOS","Online":true,"UserID":42},
                 "nodekey:3":{"HostName":"laptop","DNSName":"laptop.tailabc123.ts.net.","OS":"macOS","Online":false,"UserID":42}},
         "User":{"42":{"ID":42,"LoginName":"alex@example.com","DisplayName":"Alex"}},
         "CurrentTailnet":{"Name":"alex@example.com","MagicDNSSuffix":"tailabc123.ts.net","MagicDNSEnabled":true}}
        """
        let status = try XCTUnwrap(TailnetStatus.decode(Data(json.utf8)))
        XCTAssertEqual(status.event, .backend(state: "Running", authURL: nil))
        XCTAssertEqual(status.account?.loginName, "alex@example.com")
        XCTAssertEqual(status.tailnet?.magicDNSSuffix, "tailabc123.ts.net")
        XCTAssertEqual(Discovery.candidateHosts(in: status), ["interview-coach.tailabc123.ts.net"],
                       "online, non-phone peers only, normalized")

        let needsLogin = try XCTUnwrap(TailnetStatus.decode(Data(#"{"BackendState":"NeedsLogin","AuthURL":"https://login.tailscale.com/a/abc","Peer":null}"#.utf8)))
        XCTAssertEqual(needsLogin.event, .backend(state: "NeedsLogin", authURL: login))
        XCTAssertEqual(needsLogin.peers, [])
    }
}

final class DiscoveryTests: XCTestCase {
    func testManifestParsing() throws {
        let json = ##"{"name":"Interview Coach","slug":"interview-coach","icon":"/icon-512.png","themeColor":"#0e6b66","version":1}"##
        let manifest = try XCTUnwrap(OvenlightManifest.parse(Data(json.utf8)))
        XCTAssertEqual(manifest.name, "Interview Coach")
        XCTAssertEqual(manifest.themeColor, "#0e6b66")
        let app = DiscoveredApp(host: "interview-coach.tailabc123.ts.net", manifest: manifest)
        XCTAssertEqual(app.startURL.absoluteString, "https://interview-coach.tailabc123.ts.net/")
        XCTAssertEqual(app.iconURL?.absoluteString, "https://interview-coach.tailabc123.ts.net/icon-512.png")

        XCTAssertNil(OvenlightManifest.parse(Data(#"{"name":"","version":1}"#.utf8)), "needs a name")
        XCTAssertNil(OvenlightManifest.parse(Data(#"{"name":" \u0007\n","version":1}"#.utf8)), "and not just spaces and control characters")
        let spoof = try XCTUnwrap(OvenlightManifest.parse(Data(#"{"name":" Bank‮\u0000 Login \n","version":1}"#.utf8)))
        XCTAssertEqual(spoof.name, "Bank Login", "cleaned like an invite's names")
        let long = try XCTUnwrap(OvenlightManifest.parse(Data(#"{"name":"\#(String(repeating: "x", count: 100))","version":1}"#.utf8)))
        XCTAssertEqual(long.name.count, InviteLink.maxLabel)
        XCTAssertNil(OvenlightManifest.parse(Data(#"{"name":"X","version":0}"#.utf8)), "needs a version")
        XCTAssertNil(OvenlightManifest.parse(Data("<html>".utf8)))
        let offHost = DiscoveredApp(host: "a.tailabc123.ts.net",
                                    manifest: OvenlightManifest(name: "A", icon: "https://cdn.example.com/i.png", version: 1))
        XCTAssertNil(offHost.iconURL, "icons elsewhere can't be fetched through the node")
    }

    func testMergeDeDupesByHostAndKeepsUserNames() {
        var manual = WebApp(name: "My coach", startURL: URL(string: "https://interview-coach.tailabc123.ts.net/")!)
        manual.manifestName = nil
        let found = [
            DiscoveredApp(host: "Interview-Coach.tailabc123.ts.net.", manifest: OvenlightManifest(name: "Interview Coach", themeColor: "#0e6b66", version: 1)),
            DiscoveredApp(host: "interview-coach.tailabc123.ts.net", manifest: OvenlightManifest(name: "Duplicate", version: 1)),
            DiscoveredApp(host: "notes.tailabc123.ts.net", manifest: OvenlightManifest(name: "Notes", version: 1)),
            DiscoveredApp(host: "old.tailabc123.ts.net", manifest: OvenlightManifest(name: "Old", version: 1)),
        ]
        let merged = DiscoveryMerge.merge(found, into: [manual], dismissedHosts: ["old.tailabc123.ts.net"])
        XCTAssertEqual(merged.map(\.name), ["My coach", "Notes"], "one tile per host, user's name kept, dismissed skipped")
        XCTAssertEqual(merged[0].id, manual.id)
        XCTAssertEqual(merged[0].themeColorHex, "#0e6b66")
        XCTAssertTrue(merged[0].discovered, "a tile added by its address is a connector's app once found")
        XCTAssertTrue(merged[1].discovered)

        // A name that came from the manifest follows the manifest.
        let renamed = DiscoveryMerge.merge([DiscoveredApp(host: "notes.tailabc123.ts.net", manifest: OvenlightManifest(name: "Notes 2", version: 1))],
                                           into: merged, dismissedHosts: [])
        XCTAssertEqual(renamed[1].name, "Notes 2")
    }

    /// Self is user 42 on tailabc123.ts.net; tagged nodes belong to the tagged-devices user.
    private let trustStatus = """
    {"BackendState":"Running","Self":{"DNSName":"ovenlight-abc123.tailabc123.ts.net.","OS":"iOS","Online":true,"UserID":42},
     "Peer":{"k1":{"DNSName":"alex-mac.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":42},
             "k2":{"DNSName":"coach.tailabc123.ts.net.","OS":"linux","Online":true,"UserID":9000,"Tags":["tag:ovenlight-app-coach"]},
             "k3":{"DNSName":"coworker.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":7},
             "k4":{"DNSName":"ci.tailabc123.ts.net.","OS":"linux","Online":true,"UserID":9000,"Tags":["tag:ci"]},
             "k5":{"DNSName":"echo.tail99.ts.net.","OS":"linux","Online":true,"UserID":55,"Tags":["tag:ovenlight-app-echo"]},
             "k6":{"DNSName":"laptop.tail99.ts.net.","OS":"macOS","Online":true,"UserID":42},
             "k7":{"DNSName":"notes.tailabc123.ts.net.","OS":"macOS","Online":false,"UserID":42}},
     "CurrentTailnet":{"Name":"alex@example.com","MagicDNSSuffix":"tailabc123.ts.net"}}
    """

    func testOnlyTheOwnersOwnMachinesAreTrusted() throws {
        let status = try XCTUnwrap(TailnetStatus.decode(Data(trustStatus.utf8)))
        XCTAssertEqual(Discovery.ownersHosts(in: status), ["alex-mac.tailabc123.ts.net", "coach.tailabc123.ts.net", "notes.tailabc123.ts.net"],
                       "not another user's machine, another tag, or anything shared in from another tailnet")
        XCTAssertEqual(Discovery.candidateHosts(in: status), ["alex-mac.tailabc123.ts.net", "coach.tailabc123.ts.net"], "online ones only")
        let noSelf = try XCTUnwrap(TailnetStatus.decode(Data(trustStatus.replacingOccurrences(of: #""UserID":42}"#, with: #""UserID":0}"#).utf8)))
        XCTAssertEqual(Discovery.candidateHosts(in: noSelf), [], "no signed-in user, no owner")
        let taggedSelf = try XCTUnwrap(TailnetStatus.decode(Data(trustStatus.replacingOccurrences(
            of: #""OS":"iOS","Online":true,"UserID":42}"#, with: #""OS":"iOS","Online":true,"UserID":9000,"Tags":["tag:phone"]}"#).utf8)))
        XCTAssertEqual(Discovery.ownersHosts(in: taggedSelf), ["coach.tailabc123.ts.net"],
                       "a tagged self shares the tagged-devices user; only app tags count then")
        XCTAssertFalse(OvenlightTag.isApp("tag:ovenlight-app"))
        XCTAssertFalse(OvenlightTag.isApp("tag:ovenlight-apple"))
    }

    func testAListingCoversOnlyHostsItCanVouchFor() throws {
        let owners = Discovery.ownersHosts(in: try XCTUnwrap(TailnetStatus.decode(Data(trustStatus.utf8))))
        let json = """
        [{"slug":"coach","name":"Coach","url":"https://coach.tailabc123.ts.net/","state":"running","online":true,"shareable":true,"guests":0},
         {"slug":"cw","name":"Coworker","url":"https://coworker.tailabc123.ts.net/","state":"running","online":true,"shareable":false,"guests":0},
         {"slug":"echo","name":"Echo","url":"https://echo.tail99.ts.net/","state":"running","online":true,"shareable":false,"guests":0},
         {"slug":"web","name":"Web","url":"https://example.com/","state":"running","online":true,"shareable":false,"guests":0},
         {"slug":"off","name":"Off","state":"stopped","online":false,"shareable":false,"guests":0}]
        """
        let listed = try AdminAPI.decoder.decode([AdminApp].self, from: Data(json.utf8))

        let listing = OwnerSharing.vouched(listed, ownersHosts: owners)
        XCTAssertEqual(listing.covered, ["coach.tailabc123.ts.net"], "only the owner's own machines")
        XCTAssertEqual(listing.apps.map(\.slug), ["coach", "off"])

        var shared = WebApp(name: "Echo", startURL: URL(string: "https://echo.tail99.ts.net/")!, discovered: true)
        shared.membershipID = "m1"
        let apps = [WebApp(name: "Coworker", startURL: URL(string: "https://coworker.tailabc123.ts.net/")!, discovered: true), shared]
        let found = [DiscoveredApp(host: "coach.tailabc123.ts.net", manifest: OvenlightManifest(name: "Coach", version: 1))]
        XCTAssertEqual(OwnerSharing.candidateHosts(apps, discovered: found, ownersHosts: owners), ["coach.tailabc123.ts.net"])
    }

    /// A computer that drops the probe (a firewall, or just asleep) mustn't hold up apps on
    /// the others.
    @MainActor
    func testTheFirstAnswerCountsWithoutWaitingForTheSlowest() async {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [SlowHostProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let start = Date.now
        var answeredAfter: TimeInterval?
        let outcome = await Discovery.probe(hosts: ["coach.tailabc123.ts.net", SlowHostProtocol.host], session: session) {
            XCTAssertNil(answeredAfter, "once")
            answeredAfter = Date.now.timeIntervalSince(start)
        }
        XCTAssertLessThan(try XCTUnwrap(answeredAfter), SlowHostProtocol.delay / 2)
        XCTAssertGreaterThanOrEqual(Date.now.timeIntervalSince(start), SlowHostProtocol.delay, "discovery still hears from every host")
        XCTAssertEqual(outcome.answered, ["coach.tailabc123.ts.net": true, SlowHostProtocol.host: false])
        XCTAssertEqual(outcome.apps.map(\.manifest.name), ["Coach"])

        var called = false
        _ = await Discovery.probe(hosts: [SlowHostProtocol.host], session: session) { called = true }
        XCTAssertFalse(called, "nothing answered")
    }

    func testRoutesOnlyTheOwnersTailnetThroughTheOwnersNode() {
        let own = "tailabc123.ts.net"
        XCTAssertEqual(Routing.route(host: "interview-coach.tailabc123.ts.net", ownerSuffix: own), .tailnetNode)
        XCTAssertEqual(Routing.route(host: "Interview-Coach.TAILABC123.ts.net.", ownerSuffix: "TailABC123.ts.net."), .tailnetNode)
        XCTAssertEqual(Routing.route(host: "recorder.tail0000.ts.net", ownerSuffix: own), .refused, "another tailnet's Funnel name")
        XCTAssertEqual(Routing.route(host: "eviltailabc123.ts.net", ownerSuffix: own), .refused)
        XCTAssertEqual(Routing.route(host: "example.com", ownerSuffix: own), .refused)
        XCTAssertEqual(Routing.route(host: "tailabc123.ts.net.example.com", ownerSuffix: own), .refused)
        XCTAssertEqual(Routing.route(host: "localhost", ownerSuffix: own), .direct, "in a debug build only")
        XCTAssertEqual(Routing.route(host: "127.0.0.1", ownerSuffix: own), .refused, "loopback literals stay blocked")

        // Until the owner's tailnet is known (never signed in, or not yet reported), every
        // MagicDNS name goes through the owner's node rather than to public DNS.
        for unknown in [nil, ""] as [String?] {
            XCTAssertEqual(Routing.route(host: "interview-coach.tailabc123.ts.net", ownerSuffix: unknown), .tailnetNode)
            XCTAssertEqual(Routing.route(host: "Recorder.Tail0000.TS.NET.", ownerSuffix: unknown), .tailnetNode)
            XCTAssertEqual(Routing.route(host: "example.com", ownerSuffix: unknown), .refused)
            XCTAssertEqual(Routing.route(host: "ts.net.example.com", ownerSuffix: unknown), .refused)
            XCTAssertEqual(Routing.route(host: "localhost", ownerSuffix: unknown), .direct)
        }
    }
}

/// Serves every host a connector's manifest at once, except `host`, which fails after `delay`.
private final class SlowHostProtocol: URLProtocol {
    static let host = "slow.tailabc123.ts.net"
    static let delay: TimeInterval = 1

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        if url.host == Self.host {
            DispatchQueue.global().asyncAfter(deadline: .now() + Self.delay) { [self] in
                client?.urlProtocol(self, didFailWithError: URLError(.timedOut))
            }
            return
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!,
                            cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"name":"Coach","version":1}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// A public Funnel name added before the owner ever signed in routes to the owner's node;
/// signing in shows it isn't on the owner's tailnet. The view made for the owner's node
/// must never load, through the owner's node or at all, and its new one is refused.
@MainActor
final class StaleRouteTests: XCTestCase {
    private let funnel = WebApp(name: "Cool", startURL: URL(string: "https://cool.attacker.ts.net/")!)

    func testTheOwnersNodeCarriesOnlyItsKnownTailnet() {
        XCTAssertFalse(Routing.isOnOwnersTailnet(host: "cool.attacker.ts.net", ownerSuffix: nil),
                       "an unknown tailnet carries nothing, though the route waits on the owner's node")
        XCTAssertFalse(Routing.isOnOwnersTailnet(host: "cool.attacker.ts.net", ownerSuffix: "tailabc123.ts.net"))
        XCTAssertTrue(Routing.isOnOwnersTailnet(host: "Coach.tailabc123.ts.net.", ownerSuffix: "tailabc123.ts.net"))
        XCTAssertFalse(Routing.isOnOwnersTailnet(host: "tailabc123.ts.net.example.com", ownerSuffix: "tailabc123.ts.net"))
    }

    func testAViewIsStaleOnceTheRouteNamesAnotherNode() {
        let owner = MembershipNode(id: Membership.ownerID) { nil }
        func node(for app: WebApp, ownerSuffix: String?, guest: MembershipNode? = nil) -> MembershipNode? {
            Routing.node(for: app, owner: owner, ownerSuffix: ownerSuffix) { _ in guest }
        }

        let built = node(for: funnel, ownerSuffix: nil)
        XCTAssertTrue(built === owner, "never signed in: it waits behind the owner's sign-in")
        XCTAssertTrue(Routing.mayLoad(built: built, current: node(for: funnel, ownerSuffix: nil)))

        let signedIn = node(for: funnel, ownerSuffix: "tailabc123.ts.net")
        XCTAssertNil(signedIn, "another tailnet's name has no node")
        XCTAssertFalse(Routing.mayLoad(built: built, current: signedIn), "the owner's node is no longer its route")
        XCTAssertFalse(Routing.mayLoad(built: nil, current: owner), "nor the other way round")
        XCTAssertTrue(Routing.mayLoad(built: nil, current: nil))

        var shared = funnel
        shared.membershipID = "m1"
        let guest = MembershipNode(id: "m1") { nil }
        let sharedBuilt = node(for: shared, ownerSuffix: nil, guest: guest)
        XCTAssertTrue(sharedBuilt === guest)
        XCTAssertTrue(Routing.mayLoad(built: sharedBuilt, current: node(for: shared, ownerSuffix: "tailabc123.ts.net", guest: guest)))
        XCTAssertFalse(Routing.mayLoad(built: sharedBuilt, current: node(for: shared, ownerSuffix: nil)), "its membership is gone")
    }

    func testAStaleViewNeverLoadsAndIsMadeAgain() async {
        let defaults = UserDefaults.standard
        let key = MembershipNode.ownerSuffixKey
        let saved = defaults.string(forKey: key)
        defaults.removeObject(forKey: key)
        defer { defaults.set(saved, forKey: key) }
        let pool = WebViewPool.shared
        defer { pool.discard(funnel.id) }

        let (view, stale) = pool.webView(for: funnel)
        XCTAssertTrue(stale.node === NodeManager.shared.owner)

        let replaced = expectation(description: "replaced")
        replaced.assertForOverFulfill = false
        let watch = pool.objectWillChange.sink { replaced.fulfill() }
        defer { watch.cancel() }
        // The owner signs in, to a tailnet this host isn't on, while the app waits.
        defaults.set("tailabc123.ts.net", forKey: key)
        await fulfillment(of: [replaced], timeout: 3)

        XCTAssertNil(view.url, "nothing loaded through the owner's node")
        let (fresh, current) = pool.webView(for: funnel)
        XCTAssertFalse(fresh === view)
        XCTAssertFalse(current === stale)
        XCTAssertNil(current.node, "made again, refused")
    }

    /// Remembers `suffix` as the owner's tailnet for one test.
    private func rememberOwnerSuffix(_ suffix: String) {
        let defaults = UserDefaults.standard
        let key = MembershipNode.ownerSuffixKey
        let saved = defaults.string(forKey: key)
        defaults.set(suffix, forKey: key)
        addTeardownBlock { @MainActor in defaults.set(saved, forKey: key) }
    }

    /// A pooled view of the owner's old tailnet must not get the owner's node again when it
    /// restarts after Sign Out, signed in to another tailnet.
    func testLoggingOutDropsTheViewsTheOwnersNodeCarried() {
        rememberOwnerSuffix("taila.ts.net")
        let pool = WebViewPool.shared
        let owner = NodeManager.shared.owner
        let own = WebApp(name: "Foo", startURL: URL(string: "https://foo.taila.ts.net/")!)
        let refused = WebApp(name: "Site", startURL: URL(string: "https://site.example.invalid/")!)
        defer { pool.discard(own.id); pool.discard(refused.id) }
        let (ownView, _) = pool.webView(for: own)
        let (refusedView, _) = pool.webView(for: refused)
        XCTAssertNotNil(owner.stores[own.id])

        var changed = false
        let watch = pool.objectWillChange.sink { changed = true }
        defer { watch.cancel() }
        pool.discard(attachedTo: owner)

        XCTAssertTrue(changed, "an app on screen fetches a new view")
        XCTAssertNil(owner.stores[own.id], "the node's next start routes nothing of the old tailnet")
        XCTAssertFalse(pool.webView(for: own).0 === ownView)
        XCTAssertTrue(pool.webView(for: refused).0 === refusedView, "views on other routes stay")
    }

    func testANewOwnerTailnetRefusesTheOldOnesApps() async throws {
        rememberOwnerSuffix("taila.ts.net")
        let pool = WebViewPool.shared
        let owner = NodeManager.shared.owner
        let own = WebApp(name: "Foo", startURL: URL(string: "https://foo.taila.ts.net/")!)
        defer { pool.discard(own.id) }
        let (view, _) = pool.webView(for: own)
        XCTAssertNotNil(owner.stores[own.id])

        MembershipNode.rememberOwnerSuffix("tailb.ts.net")

        XCTAssertNil(NodeManager.shared.session(for: own), "no page or icon fetch goes to public DNS")
        XCTAssertNil(NodeManager.shared.uploadSession(for: own, timeout: 1), "nor feedback")
        XCTAssertNil(owner.stores[own.id], "dropped at once, not at the next probe or open")
        let (fresh, coordinator) = pool.webView(for: own)
        XCTAssertFalse(fresh === view)
        XCTAssertNil(coordinator.node, "the old tailnet's name has no node")
        XCTAssertEqual(fresh.configuration.websiteDataStore.proxyConfigurations.map(\.debugDescription),
                       [MembershipNode.closedProxy.debugDescription], "its store refuses every connection")
        for _ in 0..<100 where coordinator.gate != .unreachable { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertEqual(coordinator.gate, .unreachable, "Can't Reach, with no probe")
        XCTAssertNil(fresh.url, "nothing loaded")
    }
}

/// An app removed while its cover is closing (Leave from its menu, access ended) must not
/// come back as a hidden web view, with its data store made again.
@MainActor
final class AppHostViewTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    private func render(_ app: WebApp, registry: AppRegistry) {
        let host = UIHostingController(rootView: AppHostView(app: app, onClose: {})
            .environmentObject(registry)
            .environmentObject(NodeManager.shared)
            .environmentObject(LockManager()))
        // SwiftUI evaluates a body only in a window.
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return XCTFail("no scene") }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = host
        window.isHidden = false
        host.view.layoutIfNeeded()
        window.isHidden = true
    }

    func testAnAppNoLongerListedGetsNoWebView() {
        let registry = AppRegistry(directory: dir, session: { _ in nil })
        let listed = registry.add(url: URL(string: "https://listed.tail0000.ts.net/")!)
        let removed = WebApp(name: "Gone", startURL: URL(string: "https://gone.tail0000.ts.net/")!)
        defer { WebViewPool.shared.discard(listed.id); WebViewPool.shared.discard(removed.id) }
        let owner = NodeManager.shared.owner

        render(listed, registry: registry)
        XCTAssertNotNil(owner.stores[listed.id], "a listed app gets its view")
        render(removed, registry: registry)
        XCTAssertNil(owner.stores[removed.id], "no view, no store, nothing attached to a node")
    }
}

final class AppLoadGateTests: XCTestCase {
    private func run(_ events: [AppLoadGate.Event], from gate: AppLoadGate = .waiting) -> AppLoadGate {
        events.reduce(gate) { $0.next($1) }
    }

    func testLoadsOnlyAfterTheNodeIsReadyAndTheHostAnswers() {
        XCTAssertEqual(run([.reached]), .waiting, "a probe can't count before the node is ready")
        XCTAssertEqual(run([.nodeReady]), .probing)
        XCTAssertEqual(run([.nodeReady, .nodeNotReady, .reached]), .waiting, "a node restart sends it back to waiting")
        XCTAssertEqual(run([.nodeReady, .nodeNotReady, .nodeReady, .reached]), .ready)
    }

    func testALoadedPageStaysThroughNodeRestarts() {
        XCTAssertEqual(run([.nodeNotReady, .gaveUp], from: .ready), .ready)
        XCTAssertEqual(run([.loadFailed], from: .ready), .waiting, "a failed first navigation checks the path again")
        XCTAssertEqual(run([.retry], from: .ready), .waiting, "Reload checks the path before reloading")
    }

    func testGivingUpWaitsForRetry() {
        let gaveUp = run([.nodeReady, .gaveUp])
        XCTAssertEqual(gaveUp, .unreachable)
        XCTAssertEqual(run([.nodeReady, .reached], from: gaveUp), gaveUp, "only Retry leaves it")
        XCTAssertEqual(run([.retry], from: gaveUp), .waiting)
    }

    /// What every released connector, 1.0.0 to 1.0.6, sends for an app that isn't running.
    func testTheConnectorsAppDownAnswer() {
        XCTAssertTrue(AppLoadGate.appIsDown(status: 502, header: "unavailable"))
        XCTAssertTrue(AppLoadGate.appIsDown(status: 502, header: " unavailable "))
        XCTAssertFalse(AppLoadGate.appIsDown(status: 502, header: nil), "the app's own 502 is its page")
        XCTAssertFalse(AppLoadGate.appIsDown(status: 503, header: "unavailable"))
        XCTAssertFalse(AppLoadGate.appIsDown(status: 403, header: "not_invited"))
        XCTAssertFalse(AppLoadGate.appIsDown(status: nil, header: nil))
        CopyRules.assertNeutral(ConnectionCopy.notResponding("Coach"))
    }

    func testAPhoneWithNoNetworkGivesUpAtOnceAndOpensOnceItHasOne() {
        XCTAssertEqual(run([.offline]), .offline, "nothing to wait for or probe")
        XCTAssertEqual(run([.nodeReady, .offline]), .offline)
        XCTAssertEqual(run([.nodeReady, .reached, .gaveUp], from: .offline), .offline, "only a network, or Retry, leaves it")
        XCTAssertEqual(run([.online], from: .offline), .waiting)
        XCTAssertEqual(run([.retry], from: .offline), .waiting)
        XCTAssertEqual(run([.offline], from: .ready), .ready, "a loaded page stays")
        XCTAssertEqual(run([.offline], from: .unreachable), .unreachable)
    }

    func testAComputerListedOfflineOpensTheAppWhenItAnswers() {
        let gaveUp = run([.nodeReady, .gaveUp])
        XCTAssertEqual(run([.woke], from: gaveUp), .ready, "a quiet retry answered")
        XCTAssertEqual(run([.woke]), .waiting, "only after giving up")
        XCTAssertEqual(run([.nodeReady, .woke]), .probing)
    }
}

final class ConnectionStateTests: XCTestCase {
    func testTilesFlagOnlyTailnetAppsThatDidNotAnswer() {
        let coach = "interview-coach.tailabc123.ts.net"
        func tile(_ host: String = coach, route: Routing.Route = .tailnetNode, node: NodeState = .ready,
                  answers: [String: Bool]? = [coach: true]) -> AppReachability? {
            AppReachability.forTile(host: host, route: route, nodeState: node, answers: answers)
        }
        XCTAssertNil(tile())
        XCTAssertNil(tile("Interview-Coach.tailabc123.ts.net"), "hosts compare normalized")
        XCTAssertEqual(tile(answers: [coach: false]), .offline)
        XCTAssertEqual(tile(answers: ["other.tailabc123.ts.net": true]), .offline, "not probed: its machine is offline")
        XCTAssertNil(tile(answers: nil), "nothing before the first probe")
        XCTAssertNil(tile(node: .connecting, answers: [coach: false]), "the launcher shows the connection itself")
        XCTAssertNil(tile("example.com", route: .refused, answers: [:]))
    }

    func testSharedCopyNeverNamesTailscale() {
        let copy = [ConnectionCopy.connecting, ConnectionCopy.stopped, ConnectionCopy.couldNotStart,
                    ConnectionCopy.connecting(to: "Coach"), ConnectionCopy.cantReach("Coach"),
                    ConnectionCopy.cantReachDetail, ConnectionCopy.signIn(toOpen: "Coach"),
                    ConnectionCopy.cantOpen("Coach"), ConnectionCopy.offline, ConnectionCopy.offlineDetail,
                    NodeState.starting.summary, NodeMachine.next(.ready, .backend(state: "Stopped", authURL: nil)).summary,
                    ConnectionCopy.reportProblem, ConnectionCopy.leaveApps("Riley"), ConnectionCopy.leaveTitle("Riley"),
                    ConnectionCopy.leaveDetail("Riley"), LauncherWelcome.title, LauncherWelcome.message]
        for text in copy {
            XCTAssertFalse(text.localizedCaseInsensitiveContains("tailscale"), text)
            XCTAssertFalse(text.localizedCaseInsensitiveContains("tailnet"), text)
        }
    }
}

final class AcknowledgementsTests: XCTestCase {
    /// scripts/build-tailscalekit.sh writes the licenses into the framework the app embeds.
    func testTheAppShipsTheLicenses() throws {
        let notices = try XCTUnwrap(AcknowledgementsView.notices)
        XCTAssertTrue(notices.contains("Go standard library"))
        XCTAssertTrue(notices.contains("github.com/tailscale/libtailscale"))
    }

    /// A module's title underlined with `=` shows as a heading.
    func testASectionTitleIsAHeading() {
        XCTAssertEqual(AcknowledgementsView.heading("Go standard library\n==================="), "Go standard library")
        XCTAssertNil(AcknowledgementsView.heading("Copyright 2009 The Go Authors."))
        XCTAssertNil(AcknowledgementsView.heading("Source Serif\n------------"), "only = underlines a heading")
    }
}

@MainActor
final class NodeManagerTests: XCTestCase {
    func testRemovingAGuestLogsOutAndForgetsItsRoute() async throws {
        let nodes = NodeManager.shared
        let invite = InviteLink(control: URL(string: "https://controlplane.tailscale.com")!, key: "tskey-1", owner: "Riley",
                                app: "echo-board", invite: UUID().uuidString, host: "echo-board.taildef456.ts.net", name: "Echo Board", to: "Sam")
        let membership = Membership(invite: invite)
        nodes.memberships.upsert(membership)
        XCTAssertNotNil(nodes.guests[membership.id])
        let directory = NodeManager.stateDirectory(for: membership.id)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        // Twice at once, as Leave and the orphan sweep can: one removal, so one deletion of the
        // membership (each deletion publishes the list, removing something or not).
        var deletions = 0
        let watch = nodes.memberships.$memberships.dropFirst().sink { _ in deletions += 1 }
        defer { watch.cancel() }
        async let first: Void = nodes.removeGuest(membership.id)
        async let second: Void = nodes.removeGuest(membership.id)
        _ = await (first, second)
        XCTAssertEqual(deletions, 1)
        XCTAssertNil(nodes.guests[membership.id])
        XCTAssertNil(nodes.memberships[membership.id])
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }
}

@MainActor
final class LocalAddressRulesTests: XCTestCase {
    /// What WebKit does with the rules: blocked if a block rule matches and no later
    /// ignore-previous-rules rule does. url-filters are case-insensitive by default.
    private func blocks(_ url: String) -> Bool {
        func matches(_ host: String) -> Bool {
            url.range(of: LocalAddressRules.urlFilter(host: host), options: [.regularExpression, .caseInsensitive]) != nil
        }
        return LocalAddressRules.blockedHosts.contains(where: matches) && !LocalAddressRules.allowedHosts.contains(where: matches)
    }

    func testBlocksLoopbackAndLocalAddressesOnly() {
        for url in [
            "http://127.0.0.1:8080/", "https://127.255.0.9/x", "ws://127.0.0.1:9/socket", "wss://127.0.0.1/",
            "https://[::1]:8443/", "http://[::1]/", "http://[::]/", "https://user:pw@127.0.0.1/",
            "https://[::ffff:7f00:1]/", "https://[::ffff:a00:1]:443/", "https://[::ffff:c0a8:4441]/",
            "https://[::ffff:ac10:1]/", "https://[::ffff:a9fe:a9fe]/", "https://[::ffff:0:0]/",
            "https://10.0.0.1/", "https://10.20.30.40:3000/", "https://172.16.0.1/", "https://172.31.255.254/",
            "https://192.168.68.65:56596/", "https://169.254.169.254/latest", "http://0.0.0.0:80/",
            "https://[fe80::1]/", "https://[febf::1]/", "https://[fd96:bdba:f856:4b19::1]/", "https://[fc00::1]/",
        ] {
            XCTAssertTrue(blocks(url), url)
        }
        for url in [
            "https://100.64.0.1/ping", "https://100.101.102.103/", "https://[fd7a:115c:a1e0::5]/",
            "https://recipe-box.e2e.example/", "https://example.com/", "https://8.8.8.8/", "https://172.15.0.1/",
            "https://172.32.0.1/", "https://11.0.0.1/", "https://192.169.0.1/", "https://[2606:4700::1111]/",
            "https://[::ffff:5db8:d70e]/", "https://[fec0::1]/", "https://[64:ff9b::7f00:1]/",
            "https://example.com/127.0.0.1/", "https://example.com/?u=http://127.0.0.1/", "https://10.0.0.1.example.com/",
            "https://127.0.0.1.nip.io/", "https://example.com/#[::1]",
        ] {
            XCTAssertFalse(blocks(url), url)
        }
    }

    func testTheRulesCompile() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try XCTUnwrap(WKContentRuleListStore(url: dir))
        _ = try await store.compileContentRuleList(forIdentifier: "test", encodedContentRuleList: LocalAddressRules.json)
    }
}

extension UserDefaults {
    /// A suite of the test's own, so what it saves stays out of the app's defaults; gone
    /// when the test ends.
    static func throwaway(for test: XCTestCase) -> UserDefaults {
        let name = "test.\(UUID().uuidString)"
        test.addTeardownBlock { UserDefaults().removePersistentDomain(forName: name) }
        return UserDefaults(suiteName: name)!
    }
}
