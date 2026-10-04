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
                    locked: Bool = false) -> WKPermissionDecision {
            let url = URL(string: origin)!
            return MediaCapturePolicy.decide(type: type, scheme: url.scheme!, host: url.host!, app: app,
                                             ownersHosts: ["coach.tailabc123.ts.net"], locked: locked)
        }
        XCTAssertEqual(decide(.microphone, in: app), .grant)
        XCTAssertEqual(decide(.microphone, in: app, locked: true), .deny, "nothing records under the lock screen")
        XCTAssertEqual(decide(.camera, in: app, locked: true), .deny)
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
    }
}

@MainActor
final class LockTimingTests: XCTestCase {
    func testLocksByTimeInTheBackgroundNotTheClock() {
        var now = ContinuousClock.now
        var saved = false
        let lock = LockManager(hasSavedData: { saved }, now: { now })
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
        let lock = LockManager(hasSavedData: { saved }, now: { now })
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

    func testAnEmptyNodeFolderIsNothingToLock() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: base) }
        let nodes = base.appendingPathComponent("tailscale/owner", isDirectory: true)
        try FileManager.default.createDirectory(at: nodes, withIntermediateDirectories: true)
        XCTAssertTrue(OvenlightApp.hasSavedData(in: base))
        try FileManager.default.removeItem(at: nodes)
        XCTAssertFalse(OvenlightApp.hasSavedData(in: base), "what Stop Using My Own Computers leaves")
        try Data("[]".utf8).write(to: base.appendingPathComponent("apps.json"))
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

    func testRoutesOnlyTheOwnersTailnetThroughTheOwnersNode() {
        let own = "tailabc123.ts.net"
        XCTAssertEqual(Routing.route(host: "interview-coach.tailabc123.ts.net", ownerSuffix: own), .tailnetNode)
        XCTAssertEqual(Routing.route(host: "Interview-Coach.TAILABC123.ts.net.", ownerSuffix: "TailABC123.ts.net."), .tailnetNode)
        XCTAssertEqual(Routing.route(host: "recorder.tail0000.ts.net", ownerSuffix: own), .direct, "another tailnet's Funnel name")
        XCTAssertEqual(Routing.route(host: "eviltailabc123.ts.net", ownerSuffix: own), .direct)
        XCTAssertEqual(Routing.route(host: "example.com", ownerSuffix: own), .direct)
        XCTAssertEqual(Routing.route(host: "tailabc123.ts.net.example.com", ownerSuffix: own), .direct)

        // Until the owner's tailnet is known (never signed in, or not yet reported), every
        // MagicDNS name goes through the owner's node rather than to public DNS.
        for unknown in [nil, ""] as [String?] {
            XCTAssertEqual(Routing.route(host: "interview-coach.tailabc123.ts.net", ownerSuffix: unknown), .tailnetNode)
            XCTAssertEqual(Routing.route(host: "Recorder.Tail0000.TS.NET.", ownerSuffix: unknown), .tailnetNode)
            XCTAssertEqual(Routing.route(host: "example.com", ownerSuffix: unknown), .direct)
            XCTAssertEqual(Routing.route(host: "ts.net.example.com", ownerSuffix: unknown), .direct)
        }
    }
}

/// A public Funnel name added before the owner ever signed in routes to the owner's node;
/// signing in shows it isn't on the owner's tailnet. The view made for the owner's node
/// must never load, through the owner's node or at all.
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
        XCTAssertNil(signedIn, "another tailnet's name loads directly")
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
        XCTAssertNil(current.node, "made again to load directly")
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
        let direct = WebApp(name: "Site", startURL: URL(string: "https://site.example.invalid/")!)
        defer { pool.discard(own.id); pool.discard(direct.id) }
        let (ownView, _) = pool.webView(for: own)
        let (directView, _) = pool.webView(for: direct)
        XCTAssertNotNil(owner.stores[own.id])

        var changed = false
        let watch = pool.objectWillChange.sink { changed = true }
        defer { watch.cancel() }
        pool.discard(attachedTo: owner)

        XCTAssertTrue(changed, "an app on screen fetches a new view")
        XCTAssertNil(owner.stores[own.id], "the node's next start routes nothing of the old tailnet")
        XCTAssertFalse(pool.webView(for: own).0 === ownView)
        XCTAssertTrue(pool.webView(for: direct).0 === directView, "views on other routes stay")
    }

    func testANewOwnerTailnetDropsViewsOfTheOldOne() {
        rememberOwnerSuffix("taila.ts.net")
        let pool = WebViewPool.shared
        let owner = NodeManager.shared.owner
        let own = WebApp(name: "Foo", startURL: URL(string: "https://foo.taila.ts.net/")!)
        defer { pool.discard(own.id) }
        let (view, _) = pool.webView(for: own)
        XCTAssertNotNil(owner.stores[own.id])

        MembershipNode.rememberOwnerSuffix("tailb.ts.net")

        XCTAssertNil(owner.stores[own.id], "dropped at once, not at the next probe or open")
        let (fresh, coordinator) = pool.webView(for: own)
        XCTAssertFalse(fresh === view)
        XCTAssertNil(coordinator.node, "another tailnet's name loads directly")
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
        XCTAssertNil(tile("example.com", route: .direct, answers: [:]))
    }

    func testSharedCopyNeverNamesTailscale() {
        let copy = [ConnectionCopy.connecting, ConnectionCopy.stopped, ConnectionCopy.couldNotStart,
                    ConnectionCopy.connecting(to: "Coach"), ConnectionCopy.cantReach("Coach"),
                    ConnectionCopy.cantReachDetail, ConnectionCopy.signIn(toOpen: "Coach"),
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
}
