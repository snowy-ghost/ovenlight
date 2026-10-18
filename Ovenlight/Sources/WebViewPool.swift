import Combine
import os
import SafariServices
import UIKit
import WebKit

/// Keeps a live web view per recently used app, so switching apps doesn't reload them.
/// Changes when a view on screen is replaced, so its `AppHostView` fetches the new one.
@MainActor
final class WebViewPool: ObservableObject {
    static let shared = WebViewPool()

    private var views: [UUID: WKWebView] = [:]
    private var coordinators: [UUID: WebCoordinator] = [:]
    private var recent: [UUID] = []
    private let maxLive = 4
    /// Files that finished while their app was off screen or locked, oldest first, until
    /// its page shows them. They outlive the app's view, not the app or its data.
    var waitingDownloads: [UUID: [URL]] = [:]

    private init() {
        // Last session's downloads: files shared, cancelled or left waiting, and any a quit
        // left behind.
        try? FileManager.default.removeItem(at: DownloadPolicy.directory)
        NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.trim(keeping: 1) }
        }
    }

    /// Drops the app's waiting files and deletes them, when the app goes or its data does.
    func dropWaitingDownloads(of id: UUID) {
        for file in waitingDownloads.removeValue(forKey: id) ?? [] { DownloadPolicy.removeFolder(of: file) }
    }

    func webView(for app: WebApp) -> (WKWebView, WebCoordinator) {
        if let view = views[app.id], let coordinator = coordinators[app.id] {
            // The app's route changed since its view was made (the owner's tailnet became
            // known, or another one): the view starts over through the right node, on
            // screen or not.
            if Routing.mayLoad(built: coordinator.node, current: NodeManager.shared.node(for: app)) {
                touch(app.id)
                return (view, coordinator)
            }
            discard(app.id)
        }
        touch(app.id)

        let config = WKWebViewConfiguration()
        config.websiteDataStore = WKWebsiteDataStore(forIdentifier: app.dataStoreID)
        // The owner's tailnet apps go through the owner's node, shared apps through their
        // owner's guest node, and nothing else loads (see `Routing.route`).
        let node = NodeManager.shared.node(for: app)
        Self.configure(config)

        let view = WKWebView(frame: .zero, configuration: config)
        // Route the store the web view actually holds, now and after every node restart.
        let store = view.configuration.websiteDataStore
        if let node {
            node.attach(store, for: app.id)
        } else {
            let direct = !app.isShared && NodeManager.shared.route(for: app.startURL) == .direct
            store.proxyConfigurations = direct ? [] : [MembershipNode.closedProxy]
        }
        view.allowsBackForwardNavigationGestures = true
        // Short pages still rubber-band, so pulling down at the top can bring the chrome back.
        view.scrollView.alwaysBounceVertical = true
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.isOpaque = false
        view.backgroundColor = .systemBackground
        #if DEBUG
        view.isInspectable = true
        #endif

        let coordinator = WebCoordinator(app: app, node: node)
        view.navigationDelegate = coordinator
        view.uiDelegate = coordinator
        coordinator.webView = view
        coordinator.start()

        views[app.id] = view
        coordinators[app.id] = coordinator
        trim(keeping: maxLive)
        return (view, coordinator)
    }

    /// On unlock and as a share sheet closes: only the page on top can show its files (see
    /// `WebCoordinator.presentWaitingDownloads`); other apps' files wait for their page.
    func presentWaitingDownloads() {
        coordinators.values.forEach { $0.presentWaitingDownloads() }
    }

    /// Where opening the app is, while it has a view, for Report a Problem.
    func gate(of id: UUID) -> AppLoadGate? {
        coordinators[id]?.gate
    }

    /// What every app view gets, whichever app it shows.
    static func configure(_ config: WKWebViewConfiguration) {
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.applicationNameForUserAgent = userAgentName(after: config.applicationNameForUserAgent)
        // In every frame: WebKit runs it in a frame's first about:blank document too, as the
        // frame is made, so no page can reach a frame that still has WebRTC.
        config.userContentController.addUserScript(WKUserScript(source: noWebRTC, injectionTime: .atDocumentStart,
                                                                forMainFrameOnly: false))
    }

    /// Takes WebRTC away from pages. Its traffic goes around the node's proxy, to any address
    /// a page names through a TURN relay, and nothing in Ovenlight uses it. Each interface
    /// becomes a fixed undefined that page script can't delete or set back.
    static let noWebRTC = """
        for (const target of [window, Window.prototype]) {
            for (const name of Object.getOwnPropertyNames(target)) {
                if (!/^(webkit)?RTC/.test(name)) continue;
                delete target[name];
                if (target === window) Object.defineProperty(window, name, { value: undefined });
            }
        }
        """

    /// The end of every app view's user agent, so a page can tell it runs in Ovenlight.
    /// WebKit's own name (`Mobile/...`) stays before it, so pages still see mobile Safari.
    nonisolated static func userAgentName(after webKitName: String?,
                                          version: String? = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) -> String {
        [webKitName, "Ovenlight/\(version ?? "?")"].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// Ends every microphone and camera stream, on screen or not, so none runs under the lock.
    func stopCapture() {
        views.values.forEach(Self.stopCapture)
    }

    /// Ends one page's microphone and camera streams, as its app closes to the launcher.
    static func stopCapture(in view: WKWebView) {
        view.setMicrophoneCaptureState(.none)
        view.setCameraCaptureState(.none)
    }

    /// Drops a coordinator whose route is stale, unless the pool already replaced it, and
    /// tells the app's screen to fetch a new view.
    func replace(_ coordinator: WebCoordinator) {
        guard coordinators[coordinator.app.id] === coordinator else { return }
        discard(coordinator.app.id)
        objectWillChange.send()
    }

    /// Drops every view routed through `node`, which is logging out: its next start must
    /// not route them again, into whatever network it signs in to. An app on screen fetches
    /// a new view through its route.
    func discard(attachedTo node: MembershipNode) {
        discard { $0.node === node }
    }

    /// Drops every view whose app's route changed since it was made (see `Routing.mayLoad`),
    /// on screen or not, as the owner's tailnet changes.
    func discardStaleRoutes() {
        discard { !Routing.mayLoad(built: $0.node, current: NodeManager.shared.node(for: $0.app)) }
    }

    private func discard(where matches: (WebCoordinator) -> Bool) {
        let ids = coordinators.filter { matches($0.value) }.map(\.key)
        guard !ids.isEmpty else { return }
        ids.forEach(discard)
        objectWillChange.send()
    }

    func discard(_ id: UUID) {
        // No node puts its proxy back on the store at its next start, and nothing the
        // view still runs goes anywhere until a new view routes the store again.
        NodeManager.shared.detach(id)
        views[id]?.configuration.websiteDataStore.proxyConfigurations = [MembershipNode.closedProxy]
        views[id]?.stopLoading()
        coordinators[id]?.stop()
        views[id]?.removeFromSuperview()
        views[id] = nil
        coordinators[id] = nil
        recent.removeAll { $0 == id }
    }

    private func touch(_ id: UUID) {
        recent.removeAll { $0 == id }
        recent.insert(id, at: 0)
    }

    /// Pages off screen go, oldest first, except those with downloads under way, which go
    /// at a later trim.
    private func trim(keeping count: Int) {
        for id in recent.dropFirst(count) where views[id]?.window == nil && coordinators[id]?.isDownloading != true {
            discard(id)
        }
    }
}

/// Navigation and UI delegate for one app's web view.
@MainActor
final class WebCoordinator: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
    let app: WebApp
    /// The node the app loads through, if any; the first navigation then waits for the gate.
    let node: MembershipNode?
    var usesNode: Bool { node != nil }
    weak var webView: WKWebView? {
        didSet {
            colorWatchers = [
                webView?.observe(\.themeColor, options: .initial) { [weak self] view, _ in
                    MainActor.assumeIsolated { self?.themeColor = view.themeColor }
                },
                webView?.observe(\.underPageBackgroundColor, options: .initial) { [weak self] view, _ in
                    MainActor.assumeIsolated { self?.underPageColor = view.underPageBackgroundColor }
                },
            ].compactMap { $0 }
        }
    }

    @Published var loadError: String?
    @Published var hasLoadedOnce = false
    /// The page lays itself out to the bottom and side edges (`viewport-fit=cover`), read as
    /// each page finishes loading and before the first one shows, so it never moves. One that
    /// doesn't stays inside the side safe areas.
    @Published private(set) var fitsCover = true
    @Published private(set) var gate: AppLoadGate = .waiting
    /// The page's theme color and the color WebKit paints around it (its background), kept
    /// current through a light or dark switch and the page's own changes.
    @Published private(set) var themeColor: UIColor?
    @Published private(set) var underPageColor: UIColor?
    private var colorWatchers: [NSKeyValueObservation] = []
    /// What the page shows at its top, as WebKit reads it.
    var headerColor: UIColor? { themeColor ?? underPageColor }

    /// Publishes only a real change: the gate's waits go round every second, and an equal
    /// assignment would redraw the page's cover each time.
    private func advance(_ event: AppLoadGate.Event) {
        let next = gate.next(event)
        if next != gate { gate = next }
    }

    private static let log = Logger(subsystem: "com.snowyghost.ovenlight", category: "node")
    /// First navigations that failed at the connection level after the probe answered.
    private var failedLoads = 0
    private static let maxFailedLoads = 2
    /// First pages the connector answered for because the app itself was down.
    private var appDownLoads = 0
    /// One of them was just cancelled, which WebKit reports as a failed load.
    private var cancelledAppDown = false
    /// The page's latest navigation is a link the person tapped (see `isTap`), not one the
    /// page started itself.
    private var tapped = false
    private var gateTask: Task<Void, Never>?
    /// Each download under way, the file it's written to once WebKit asks where, and whether
    /// the person started it (see `isTap`), who then hears if it fails.
    private var downloads: [ObjectIdentifier: (download: WKDownload, file: URL?, tapped: Bool)] = [:]
    /// A brief message over the page, such as a download that failed; it goes by itself.
    @Published private(set) var notice: String?
    private var noticeTask: Task<Void, Never>?
    /// The share sheet up, whichever page presented it, from presenting until it closes.
    private(set) static weak var sheet: ShareSheet?
    /// A sheet that never showed has had its one retry; the next trigger allows another,
    /// so one UIKit keeps refusing can't come back every turn.
    private static var retriedUnseen = false
    /// Looks again every half second while the page's files wait under something Ovenlight
    /// didn't present, until they show, none wait, or the page leaves the screen.
    private var watcher: Task<Void, Never>?
    private var stopped = false

    init(app: WebApp, node: MembershipNode?) {
        self.app = app
        self.node = node
    }

    /// Every app waits for its host to answer before the first navigation, however it's
    /// reached (see AppLoadGate).
    func start() {
        openGate()
    }

    /// The view was discarded: stop waiting on the node for it, and end its downloads under
    /// way, whose delegate this is no longer, with their files.
    func stop() {
        stopped = true
        gateTask?.cancel()
        gateTask = nil
        watcher?.cancel()
        for (download, file, _) in downloads.values {
            download.cancel { _ in
                if let file { DownloadPolicy.removeFolder(of: file) }
            }
        }
        downloads = [:]
    }

    /// The page landed in a window: opened again, its dialogs may show again (see
    /// `PageDialogs`), and files that finished meanwhile show.
    func appeared() {
        dialogs.stopped = false
        presentWaitingDownloads()
    }

    /// Try Again, and Reload from the app menu. A page that already loaded stays on screen
    /// while the path is checked again.
    func reload() {
        dialogs.stopped = false
        failedLoads = 0
        appDownLoads = 0
        loadError = nil
        advance(.retry)
        openGate()
    }

    private func openGate() {
        gateTask?.cancel()
        gateTask = Task { [weak self] in await self?.runGate() }
    }

    /// Waits for the node, then probes this app's host until it answers or time runs out.
    private func runGate() async {
        if let webView, !(await LocalAddressRules.install(in: webView)) { return giveUp() }
        // A shared app always goes through its owner's node; without one it has no way in.
        if app.isShared && node == nil { return await GuestManager.shared.collectOrphans() }
        // An app of your own on no tailnet Ovenlight knows, such as one from a tailnet the
        // owner was signed in to before: Can't Reach at once, and nothing goes out, not
        // even a probe.
        if node == nil && NodeManager.shared.route(for: app.startURL) == .refused {
            Self.log.notice("\(self.app.host, privacy: .public): on no known tailnet, refused")
            return giveUp()
        }
        let opened = Date.now
        var probingSince: Date?
        while !Task.isCancelled, gate == .waiting || gate == .probing {
            // Not even a probe goes through a node the app's route no longer names.
            guard routeIsCurrent() else { return }
            // With no network at all there is nothing to wait for or probe: You're Offline shows
            // at once, and the app opens once the phone has a network again.
            if !hasLoadedOnce, !NetworkPath.shared.isSatisfied {
                advance(.offline)
                await NetworkPath.shared.satisfied()
                guard !Task.isCancelled, gate == .offline else { return }
                advance(.online)
                probingSince = nil
                continue
            }
            // An app at localhost in a debug build has no node to wait for.
            if node == nil || (node?.state == .ready && nodeMayCarry) {
                advance(.nodeReady)
                // Its computer is listed offline, asleep most likely: Can't Reach shows at
                // once, and the app opens as soon as the computer answers.
                if !hasLoadedOnce, let node, node.isListedOffline(app.host) {
                    giveUp()
                    return await retryQuietly(through: node)
                }
                let since = probingSince ?? .now
                probingSince = since
                let reach = if let node { await node.canReach(app) } else { await Self.canReachDirectly(app.startURL) ? Reach.reached : .unreachable }
                switch reach {
                case .reached:
                    guard !Task.isCancelled else { return }
                    advance(.reached)
                    load()
                    return
                case .notInvited:
                    // The owner stopped sharing it; Ovenlight takes the app away.
                    GuestManager.shared.accessEnded(for: [app])
                    return
                case .unreachable:
                    break
                }
                if Date.now.timeIntervalSince(since) >= AppLoadGate.reachTimeout { return giveUp() }
                try? await Task.sleep(for: .seconds(1))
            } else {
                advance(.nodeNotReady)
                probingSince = nil
                // Sign-in and node failures have their own screens and wait for the owner.
                guard let node else { return }
                let deadline = node.state.isBusy ? opened.addingTimeInterval(AppLoadGate.nodeTimeout) : nil
                if let deadline, Date.now >= deadline { return giveUp() }
                await Self.change(of: node, before: deadline)
            }
        }
    }

    /// Waits for the node to change (its state, or its status, which says what the owner's
    /// node carries), or the phone's network to drop, for a second at most, and never past
    /// `deadline`. The second catches a change made before the waits listened.
    private static func change(of node: MembershipNode, before deadline: Date?) async {
        let wait = min(deadline.map { max($0.timeIntervalSinceNow, 0) } ?? 1, 1)
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await _ in node.objectWillChange.values { return }
            }
            group.addTask { @MainActor in
                // Only a drop: the publisher starts with the path as it is now.
                for await satisfied in NetworkPath.shared.$isSatisfied.dropFirst().values where !satisfied { return }
            }
            group.addTask { try? await Task.sleep(for: .seconds(wait)) }
            await group.next()
            group.cancelAll()
        }
    }

    /// Probes the app behind its Can't Reach screen, from at once (a computer can be listed
    /// offline for a moment while it answers) until it answers, Try Again starts over, or
    /// the app closes to the launcher, after which opening it again tries again.
    private func retryQuietly(through node: MembershipNode) async {
        while !Task.isCancelled, gate == .unreachable, routeIsCurrent() {
            if node.state == .ready, nodeMayCarry {
                switch await node.canReach(app) {
                case .reached:
                    guard !Task.isCancelled, gate == .unreachable else { return }
                    advance(.woke)
                    return load()
                case .notInvited:
                    return GuestManager.shared.accessEnded(for: [app])
                case .unreachable:
                    break
                }
            }
            try? await Task.sleep(for: .seconds(AppLoadGate.quietRetryInterval))
            guard webView?.window != nil else { return }
        }
    }

    private static let directProbe: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    /// Any HTTP answer from the host counts; only connection-level failures don't.
    private static func canReachDirectly(_ startURL: URL) async -> Bool {
        guard let url = URL(string: OvenlightManifest.path, relativeTo: startURL)?.absoluteURL else { return false }
        return (try? await directProbe.data(from: url)) != nil
    }

    private func giveUp() {
        guard !Task.isCancelled else { return }
        Self.log.notice("\(self.app.host, privacy: .public): unreachable")
        advance(.gaveUp)
        // A page already on screen keeps it. Once a page has loaded, only the person's Reload
        // checks the path again, so an alert tells them it couldn't reach the computer.
        if hasLoadedOnce {
            gate = .ready
            alertCantReach()
        }
    }

    private func load() {
        guard routeIsCurrent(), nodeMayCarry else { return }
        loadError = nil
        guard let webView else { return }
        if webView.url != nil {
            webView.reload()
        } else {
            webView.load(URLRequest(url: app.startURL))
        }
    }

    /// Checked before every probe and load: the node the app's route names now is the one
    /// this view's data store was attached to (see `Routing.mayLoad`). If not, the pool
    /// drops this view and the app's screen makes a new one through the right node.
    private func routeIsCurrent() -> Bool {
        if Routing.mayLoad(built: node, current: NodeManager.shared.node(for: app)) { return true }
        Self.log.notice("\(self.app.host, privacy: .public): route changed, making a new view")
        WebViewPool.shared.replace(self)
        return false
    }

    /// The owner's node carries only names on the owner's known tailnet (see
    /// `Routing.isOnOwnersTailnet`); guest nodes carry their own apps.
    private var nodeMayCarry: Bool {
        let owner = NodeManager.shared.owner
        return node !== owner || Routing.isOnOwnersTailnet(host: app.startURL.host ?? "", ownerSuffix: owner.magicDNSSuffixSeen)
    }

    // MARK: Navigation

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { return decisionHandler(.allow) }
        let isMainFrame = action.targetFrame?.isMainFrame ?? true
        if isMainFrame { tapped = Self.isTap(action) }
        switch NavigationPolicy.decide(url: url, appHost: app.host, isMainFrame: isMainFrame, tapped: Self.isTap(action)) {
        case .allow where action.shouldPerformDownload:
            // `<a download>`, of the app's own pages or of a blob or data URL.
            let source = (action.sourceFrame as WKFrameInfo?)?.securityOrigin.host
            decisionHandler(mayDownload(url, from: source, tapped: Self.isTap(action)) ? .download : .cancel)
        case .allow:
            decisionHandler(.allow)
        case .openExternally(let external):
            decisionHandler(.cancel)
            openExternally(external)
        case .refuse:
            Self.log.info("\(self.app.host, privacy: .public): refused a \(url.scheme ?? "schemeless", privacy: .public) link")
            decisionHandler(.cancel)
        }
    }

    /// A link activated in the app's main frame. WebKit has no public "user gesture" flag,
    /// and a script's `a.click()` counts too, so another app still opens only after
    /// `openExternally` asks (or iOS does).
    private static func isTap(_ action: WKNavigationAction) -> Bool {
        // sourceFrame is declared nonnull but can be nil for navigations the app started.
        action.navigationType == .linkActivated && (action.sourceFrame as WKFrameInfo?)?.isMainFrame == true
    }

    /// A shared app answering 403 may have been unshared: ask its connector once. An
    /// attachment, or a file WebKit can't show, downloads instead. A frame's response loads
    /// as sent: only the page itself downloads, so a frame (the app's own, or another
    /// site's) raises no sheet.
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void) {
        guard response.isForMainFrame else { return decisionHandler(.allow) }
        let http = response.response as? HTTPURLResponse
        if !hasLoadedOnce, AppLoadGate.appIsDown(status: http?.statusCode, header: http?.value(forHTTPHeaderField: ConnectorError.header)) {
            cancelledAppDown = true
            decisionHandler(.cancel)
            return appIsDown()
        }
        if app.isShared, http?.statusCode == 403 {
            let app = app
            Task { await GuestManager.shared.verifyAccess(to: app) }
        }
        switch DownloadPolicy.action(status: http?.statusCode, disposition: http?.value(forHTTPHeaderField: "Content-Disposition"),
                                     canShow: response.canShowMIMEType) {
        case .show:
            decisionHandler(.allow)
        case .download:
            guard let url = response.response.url else { return decisionHandler(.cancel) }
            decisionHandler(mayDownload(url, from: nil, tapped: tapped) ? .download : .cancel)
        }
    }

    /// The first page's app is down on its computer: the placeholder stays while it's tried
    /// again a few times, then the app's error screen says so.
    private func appIsDown() {
        guard appDownLoads < AppLoadGate.appDownRetries.count else {
            appDownLoads = 0
            loadError = ConnectionCopy.notResponding(app.name)
            return
        }
        let delay = AppLoadGate.appDownRetries[appDownLoads]
        appDownLoads += 1
        Self.log.notice("\(self.app.host, privacy: .public): the app is down, trying again in \(delay) s")
        gateTask?.cancel()
        gateTask = Task { [weak self] in
            guard (try? await Task.sleep(for: .seconds(delay))) != nil, let self, routeIsCurrent(), nodeMayCarry else { return }
            webView?.load(URLRequest(url: app.startURL))
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loadError = nil
        failedLoads = 0
        appDownLoads = 0
        webView.evaluateJavaScript(Self.coverScript) { [weak self] result, _ in
            self?.fitsCover = result as? Bool == true
            self?.hasLoadedOnce = true
        }
    }

    /// Whether the page's viewport, its last viewport meta, asks for `viewport-fit=cover`.
    static let coverScript = #"/viewport-fit\s*=\s*cover/i.test([...document.querySelectorAll('meta[name="viewport" i]')].pop()?.content ?? "")"#

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handle(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handle(error)
    }

    /// The page is gone (iOS reclaims the process under memory pressure), so the placeholder
    /// and load errors come back. On screen the path is checked and the page loads again;
    /// off screen the pool drops the view, and the next open starts over, unless a download
    /// is under way: it runs outside the page, and dropping the view would cancel it.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        hasLoadedOnce = false
        guard gate == .ready else { return }
        if webView.window != nil || isDownloading { reload() } else { WebViewPool.shared.discard(app.id) }
    }

    private func handle(_ error: Error) {
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { return }
        // WebKit reports the app-down response it was told to cancel (see `appIsDown`).
        if cancelledAppDown {
            cancelledAppDown = false
            if ns.domain == "WebKitErrorDomain" && ns.code == Self.frameLoadInterrupted { return }
        }
        // Once the app has rendered, a failed load (offline, a file WebKit can't show)
        // leaves the page as it is. A link the person tapped says so when it can't reach the
        // computer; what the page loads by itself, a reload on a timer say, never does.
        if hasLoadedOnce {
            if tapped, ns.domain == NSURLErrorDomain,
               Self.unreachable.contains(ns.code) || usesNode && Self.retryableThroughNode.contains(ns.code) {
                alertCantReach()
            }
            return
        }
        if usesNode && ns.domain == NSURLErrorDomain && Self.retryableThroughNode.contains(ns.code) {
            // The probe answered, but the navigation itself couldn't connect (a lost
            // handshake looks like NSURLError -1000 through the proxy): check the path again.
            if failedLoads < Self.maxFailedLoads {
                failedLoads += 1
                Self.log.info("\(self.app.host, privacy: .public): error \(ns.code), checking the path again")
                advance(.loadFailed)
                openGate()
                return
            }
            failedLoads = 0
            loadError = ConnectionCopy.cantReachDetail
            return
        }
        if ns.domain == NSURLErrorDomain && Self.unreachable.contains(ns.code) {
            loadError = ConnectionCopy.cantReachDetail
        } else {
            // WebKit's own words ("The URL was blocked by a content blocker") mean nothing here.
            #if DEBUG
            Self.log.debug("\(self.app.host, privacy: .public): load failed: \(ns.domain, privacy: .public) \(ns.code): \(ns.localizedDescription, privacy: .public)")
            #endif
            loadError = ConnectionCopy.pageFailed
        }
    }

    static let unreachable: Set<Int> = [NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost,
                                        NSURLErrorTimedOut, NSURLErrorNotConnectedToInternet,
                                        NSURLErrorNetworkConnectionLost, NSURLErrorDNSLookupFailed]
    static let retryableThroughNode: Set<Int> = [NSURLErrorBadURL, NSURLErrorTimedOut, NSURLErrorCannotFindHost,
                                                 NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost]
    /// WebKit's "Frame load interrupted", for a navigation whose response policy cancelled it.
    static let frameLoadInterrupted = 102

    // MARK: UI

    /// `window.open` and `target=_blank`: same-app links stay here, others leave the app
    /// under the same rules as any navigation.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard let url = action.request.url else { return nil }
        switch NavigationPolicy.decide(url: url, appHost: app.host, isMainFrame: true, tapped: Self.isTap(action)) {
        case .allow where Discovery.normalizedHost(url.host ?? "") == Discovery.normalizedHost(app.host):
            // sourceFrame is declared nonnull but can be nil for navigations the app started.
            let origin = (action.sourceFrame as WKFrameInfo?)?.securityOrigin
            webView.load(NavigationPolicy.popupRequest(action.request, fromScheme: origin?.protocol,
                                                       host: origin?.host, appHost: app.host))
        case .allow, .refuse:
            break
        case .openExternally(let external):
            openExternally(external)
        }
        return nil
    }

    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                 decisionHandler: @escaping @MainActor (WKPermissionDecision) -> Void) {
        decisionHandler(MediaCapturePolicy.decide(type: type, scheme: origin.protocol, host: origin.host, app: app,
                                                  ownersHosts: NodeManager.shared.ownersHosts,
                                                  locked: OverlayPresenter.pageWindow == nil, onScreen: webView.window != nil))
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void) {
        let answer = pageAnswer(fallback: ()) { _ in completionHandler() }
        let alert = UIAlertController(title: app.name, message: PageDialogs.cut(message), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in answer(()) })
        presentPageDialog(alert, answer: answer)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void) {
        let answer = pageAnswer(fallback: false, completionHandler)
        let alert = UIAlertController(title: app.name, message: PageDialogs.cut(message), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in answer(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in answer(true) })
        presentPageDialog(alert, answer: answer)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping @MainActor (String?) -> Void) {
        let answer = pageAnswer(fallback: nil, completionHandler)
        let alert = UIAlertController(title: app.name, message: PageDialogs.cut(prompt), preferredStyle: .alert)
        alert.addTextField { field in
            field.text = defaultText.map(PageDialogs.cut)
            // Also what VoiceOver calls the field; the page's question is the alert's message.
            field.placeholder = "Answer"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in answer(nil) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak alert] _ in answer(alert?.textFields?.first?.text) })
        presentPageDialog(alert, answer: answer)
    }

    /// The page's dialogs in a row, and whether the person stopped them.
    private var dialogs = PageDialogs()

    /// An answer that also marks when the dialog closed, for the run of dialogs.
    private func pageAnswer<Value: Sendable>(fallback: Value, _ handler: @escaping @MainActor (Value) -> Void) -> DialogAnswer<Value> {
        DialogAnswer(fallback: fallback) { [weak self] value in
            self?.dialogs.closed()
            handler(value)
        }
    }

    /// Past a few in a row, a page's dialog also offers to stop the rest (see `PageDialogs`).
    private func presentPageDialog<Value>(_ alert: UIAlertController, answer: DialogAnswer<Value>) {
        let offersStop = dialogs.next()
        if dialogs.stopped { return answer.fallBack() }
        if offersStop {
            alert.addAction(UIAlertAction(title: PageDialogs.stopTitle, style: .default) { [weak self] _ in
                self?.dialogs.stopped = true
                answer.fallBack()
            })
        }
        present(alert) { answer.fallBack() }
    }

    // MARK: Downloads

    /// Nothing downloads while this page is kept off screen either: a script's `a.click()`
    /// would start one under another app. One the person started and the policy refused
    /// says so.
    private func mayDownload(_ url: URL, from sourceHost: String?, tapped: Bool) -> Bool {
        guard webView?.window != nil else { return false }
        let locked = OverlayPresenter.pageWindow == nil
        if DownloadPolicy.allows(url: url, from: sourceHost, appHost: app.host, locked: locked) { return true }
        if tapped, !locked { downloadFailed(DownloadPolicy.name(in: url)) }
        return false
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        track(download, tapped: Self.isTap(navigationAction))
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        track(download, tapped: tapped)
    }

    private func track(_ download: WKDownload, tapped: Bool) {
        download.delegate = self
        downloads[ObjectIdentifier(download)] = (download, nil, tapped)
    }

    /// Each download gets a folder of its own, so the file keeps the name the page gave it.
    /// One that asks after `stop()` cancelled it gets nowhere to write.
    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String,
                  completionHandler: @escaping @MainActor (URL?) -> Void) {
        guard !stopped else { return completionHandler(nil) }
        let folder = DownloadPolicy.directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil else {
            return completionHandler(nil)
        }
        let file = folder.appendingPathComponent(DownloadPolicy.fileName(suggestedFilename))
        downloads[ObjectIdentifier(download)]?.file = file
        completionHandler(file)
    }

    /// The share sheet saves the file to Files or sends it on. An empty one isn't worth a
    /// sheet (an `<a download>` the server answered with nothing).
    func downloadDidFinish(_ download: WKDownload) {
        guard let file = downloads.removeValue(forKey: ObjectIdentifier(download))?.file else { return }
        if (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) == 0 { return DownloadPolicy.removeFolder(of: file) }
        WebViewPool.shared.waitingDownloads[app.id, default: []].append(file)
        presentWaitingDownloads()
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        Self.log.info("\(self.app.host, privacy: .public): download failed: \(error.localizedDescription, privacy: .public)")
        // None after `stop()`, which cancelled them all.
        guard let failed = downloads.removeValue(forKey: ObjectIdentifier(download)) else { return }
        if let file = failed.file { DownloadPolicy.removeFolder(of: file) }
        if failed.tapped { downloadFailed(failed.file?.lastPathComponent ?? DownloadPolicy.name(in: download.originalRequest?.url)) }
    }

    /// Says a download the person started didn't make it, while the page is on screen.
    private func downloadFailed(_ name: String?) {
        guard webView?.window != nil, OverlayPresenter.pageWindow != nil else { return }
        show(notice: DownloadPolicy.failedNotice(name))
    }

    private func show(notice text: String) {
        notice = text
        UIAccessibility.post(notification: .announcement, argument: text)
        noticeTask?.cancel()
        noticeTask = Task { [weak self] in
            guard (try? await Task.sleep(for: .seconds(3))) != nil else { return }
            self?.notice = nil
        }
    }

    /// Shows this app's finished files in one share sheet once its page is on screen,
    /// unlocked, and on top: over no dialog, Safari view or other sheet. Until then they
    /// wait in the pool. Called as each finishes, as the page appears, on unlock, and as the
    /// share sheet closes; under anything else, the page looks again (`watcher`).
    func presentWaitingDownloads() {
        Self.retriedUnseen = false
        showWaitingDownloads()
    }

    private func showWaitingDownloads() {
        watcher?.cancel()
        // Under a sheet, up or on its way, the files wait: it retries as it closes (`makeSheet`).
        guard Self.openSheet() == nil, let files = WebViewPool.shared.waitingDownloads[app.id], !files.isEmpty,
              let top = topController, let webView, webView.window != nil else { return }
        if let transition = top.transitionCoordinator,
           transition.animate(alongsideTransition: nil, completion: { [weak self] _ in
               DispatchQueue.main.async { self?.showWaitingDownloads() }
           }) { return }
        if webView.isDescendant(of: top.view) { return present(Self.makeSheet(sharing: files, of: app.id), orElse: {}) }
        watcher = Task { [weak self] in
            guard (try? await Task.sleep(for: .milliseconds(500))) != nil else { return }
            self?.showWaitingDownloads()
        }
    }

    /// Registers a share sheet for `files`. Once it has shown they leave the waiting list,
    /// shared or cancelled, and stay on disk until the next launch (AirDrop may still be
    /// sending); one that never showed leaves them waiting. Then what waited shows, and the
    /// page on top shows any files still waiting, after an unseen sheet only once per
    /// trigger. Another sheet may be up, which stays.
    static func makeSheet(sharing files: [URL], of id: UUID) -> ShareSheet {
        let sheet = ShareSheet(activityItems: files, applicationActivities: nil)
        sheet.closed = { [weak sheet] shown, waiting in
            if Self.sheet === sheet { Self.sheet = nil }
            if shown { WebViewPool.shared.waitingDownloads[id]?.removeAll(where: files.contains) }
            waiting.forEach { $0() }
            guard shown || !retriedUnseen else { return }
            WebViewPool.shared.presentWaitingDownloads()
            retriedUnseen = !shown
        }
        Self.sheet = sheet
        return sheet
    }

    /// The share sheet registered, if any. One seen off the screen closes first, and stays
    /// registered until its `closed` has run.
    private static func openSheet() -> ShareSheet? {
        if let sheet, sheet.isOffScreen { sheet.close() }
        return sheet
    }

    /// Downloads under way, which a trim keeps this page for.
    var isDownloading: Bool { !downloads.isEmpty }

    // MARK: Helpers

    /// Does nothing while Ovenlight is locked, or while this page is kept off screen: a
    /// page's script can fake a link tap, and Safari would open over another app.
    func openExternally(_ url: URL) {
        guard OverlayPresenter.pageWindow != nil, webView?.window != nil else { return }
        if ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            present(SFSafariViewController(url: url), orElse: {})
        } else if NavigationPolicy.asksBeforeOpening(url) {
            let alert = UIAlertController(title: "Open in another app?",
                                          message: "\(app.name) wants to open \(NavigationPolicy.target(of: url)).", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            alert.addAction(UIAlertAction(title: "Open", style: .default) { _ in UIApplication.shared.open(url) })
            present(alert, orElse: {})
        } else {
            UIApplication.shared.open(url)
        }
    }

    /// Tells the person that their Reload, or a link they tapped, couldn't reach the app's
    /// computer; the page already on screen stays. Never over another alert, so they can't
    /// stack.
    private func alertCantReach() {
        guard !(topController is UIAlertController) else { return }
        let alert = UIAlertController(title: ConnectionCopy.cantReach(app.name), message: ConnectionCopy.cantReachDetail,
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, orElse: {})
    }

    /// Presents over Ovenlight's own window, never the lock screen's. While locked, or
    /// while this page is kept off screen, where its dialog would show over another app
    /// under this app's name, a page's alert, confirm or prompt is answered at once
    /// (`fallback`), as if dismissed. One UIKit refuses is answered when it's let go. While
    /// a share sheet is up, anything else waits for it to close, and anything waits for a
    /// presentation under way to finish; a page that goes meanwhile lets it go.
    private func present(_ controller: UIViewController, orElse fallback: @escaping () -> Void) {
        guard let top = topController, webView?.window != nil else { return fallback() }
        if let sheet = Self.openSheet(), controller !== sheet {
            return sheet.waiting.append { [weak self] in self?.present(controller, orElse: fallback) }
        }
        if let transition = top.transitionCoordinator,
           transition.animate(alongsideTransition: nil, completion: { [weak self] _ in
               DispatchQueue.main.async { self?.present(controller, orElse: fallback) }
           }) { return }
        top.present(controller, animated: true)
        (controller as? ShareSheet)?.wasPresented()
    }

    /// The topmost controller on Ovenlight's own window; nil while locked.
    private var topController: UIViewController? {
        var top = OverlayPresenter.pageWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

/// The share sheet. It has closed once it's off the screen, dismissed or taken away with
/// what it was presented over (the app closing), or let go (covered by an activity that
/// took it away with itself, or refused): then `closed` runs, once, on the next main turn,
/// after UIKit has finished with it, told whether the sheet ever showed.
final class ShareSheet: UIActivityViewController {
    /// Presentations held back while it's up, handed to `closed`.
    var waiting: [@MainActor () -> Void] = []
    var closed: (@MainActor (_ shown: Bool, _ waiting: [@MainActor () -> Void]) -> Void)?
    private(set) var appeared = false

    /// Shown, and since taken off the screen without hearing of it.
    var isOffScreen: Bool { presentingViewController == nil && appeared }

    /// UIKit can drop it without a word (something else went up first), so one it never took
    /// closes after a moment. One it took has a presenting controller by then.
    func wasPresented() {
        Task { @MainActor [weak self] in
            guard (try? await Task.sleep(for: .milliseconds(1500))) != nil, let self,
                  presentingViewController == nil, !appeared else { return }
            close()
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Given up on before it showed: it goes, so no sheet stays up untracked.
        guard closed != nil else { return dismiss(animated: false) }
        appeared = true
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // Not while something it presented covers it.
        if presentedViewController == nil || isBeingDismissed { close() }
    }

    /// Kept until `closed` has run, so it stays the sheet up, and holds what waits, until then.
    func close() {
        guard let closed else { return }
        let shown = appeared
        self.closed = nil
        Task { @MainActor [self] in closed(shown, waiting) }
    }

    deinit {
        guard let closed else { return }
        let shown = appeared, waiting = waiting
        Task { @MainActor in closed(shown, waiting) }
    }
}

/// A page's alert, confirm or prompt waits for its answer, and WebKit crashes the app if the
/// completion handler is let go without being called. Anything can take the dialog away (a
/// sheet the Router presents, access ending, a deep link), so the answer goes through here:
/// the first one counts, and a dialog that goes away unanswered gets `fallback`.
final class DialogAnswer<Value: Sendable>: @unchecked Sendable {
    private var handler: (@MainActor (Value) -> Void)?
    private let fallback: Value

    init(fallback: Value, _ handler: @escaping @MainActor (Value) -> Void) {
        self.fallback = fallback
        self.handler = handler
    }

    @MainActor
    func callAsFunction(_ value: Value) {
        guard let handler else { return }
        self.handler = nil
        handler(value)
    }

    /// Answers as if the dialog was dismissed.
    @MainActor
    func fallBack() {
        self(fallback)
    }

    deinit {
        guard let handler else { return }
        let fallback = fallback
        Task { @MainActor in handler(fallback) }
    }
}

/// A page's dialogs in a row, each within a second of the last one closing. Past three in a
/// row, however long each was read, each offers to stop the page's dialogs, as Chrome does,
/// so a page that asks in a loop can't trap the person. Once stopped, each is answered at
/// once with its fallback, until the person reloads the app or opens it again.
struct PageDialogs {
    static let loopCount = 3
    static let gap: Duration = .seconds(1)
    /// A page's text past this many UTF-16 units is cut: a page can pass megabytes.
    static let maxText = 4000
    static let stopTitle = "Don’t Let This App Show More Dialogs"

    var stopped = false
    private var run = 0
    private var lastClosed: ContinuousClock.Instant?
    private let now: () -> ContinuousClock.Instant

    init(now: @escaping () -> ContinuousClock.Instant = { .now }) {
        self.now = now
    }

    /// Counts a new dialog; true when it offers the way out.
    mutating func next() -> Bool {
        let at = now()
        run = lastClosed.map { $0.duration(to: at) < Self.gap } == true ? run + 1 : 1
        return run > Self.loopCount
    }

    /// The dialog closed: answered, dismissed or let go.
    mutating func closed() {
        lastClosed = now()
    }

    /// A page's text as its dialog shows it, cut at `maxText` without splitting a character.
    static func cut(_ text: String) -> String {
        guard text.utf16.count > maxText else { return text }
        var end = text.startIndex
        var units = 0
        for character in text {
            units += character.utf16.count
            if units > maxText { break }
            end = text.index(after: end)
        }
        return text[..<end] + "\u{2026}"
    }
}

/// Who gets the microphone without the page asking again (iOS still asks once): the
/// owner's own apps (discovered, or on one of `ownersHosts`), on their own origin. The
/// camera, shared apps, other sites added by address (someone's public Funnel URL among
/// them) and every other origin get WebKit's prompt. Nothing gets either while Ovenlight
/// is locked, or while the page is kept off screen.
enum MediaCapturePolicy {
    static func decide(type: WKMediaCaptureType, scheme: String, host: String, app: WebApp,
                       ownersHosts: Set<String>, locked: Bool, onScreen: Bool) -> WKPermissionDecision {
        if locked || !onScreen { return .deny }
        guard type == .microphone, !app.isShared,
              app.discovered || ownersHosts.contains(Discovery.normalizedHost(app.host)),
              scheme.lowercased() == "https",
              Discovery.normalizedHost(host) == Discovery.normalizedHost(app.host) else { return .prompt }
        return .grant
    }
}

/// Which downloads go ahead: only the app's own files (its host, or a blob or data URL its
/// pages made), never started by a frame of another site, and none while Ovenlight is
/// locked. A download leaves Ovenlight only through the share sheet.
enum DownloadPolicy {
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("Downloads", isDirectory: true)

    static func removeFolder(of file: URL) {
        try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
    }

    /// `sourceHost`: the host of the frame that started it, when WebKit says (a navigation
    /// response doesn't).
    static func allows(url: URL, from sourceHost: String?, appHost: String, locked: Bool) -> Bool {
        let app = Discovery.normalizedHost(appHost)
        if locked { return false }
        if let sourceHost, Discovery.normalizedHost(sourceHost) != app { return false }
        switch url.scheme?.lowercased() {
        case "http", "https":
            return Discovery.normalizedHost(url.host ?? "") == app
        case "blob":
            return NavigationPolicy.blobHost(url) == app
        case "data":
            // Has no origin of its own: only one the app's own page started.
            return sourceHost != nil
        default:
            return false
        }
    }

    enum ResponseAction: Equatable {
        case show, download
    }

    /// A response sent as an attachment, or a successful one WebKit can't show, is a file to
    /// keep (an empty one gets no sheet). `status` is nil for a blob or data URL. 204, 205
    /// and 304 have no body, and WebKit leaves the page as it is itself. An error WebKit
    /// can't show (one with no Content-Type is `application/octet-stream`) isn't a file.
    static func action(status: Int?, disposition: String?, canShow: Bool) -> ResponseAction {
        if let status, [204, 205, 304].contains(status) { return .show }
        let isSuccess = status.map { (200..<300).contains($0) } ?? true
        // The type is the part before any `;`, in any case, with spaces allowed around it.
        let type = disposition?.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first
            .map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        let isFile = type.caseInsensitiveCompare("attachment") == .orderedSame || !canShow && isSuccess
        return isFile ? .download : .show
    }

    /// The page's name for the file, made safe to write: no folders, no hidden or empty
    /// names, and short enough for the file system, keeping the extension.
    static func fileName(_ suggested: String) -> String {
        let unsafe = CharacterSet(charactersIn: "/\\:").union(.controlCharacters)
        var name = suggested.components(separatedBy: unsafe).joined(separator: "-")
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        let ext = (name as NSString).pathExtension
        let keepsExtension = !ext.isEmpty && ext.utf8.count <= 16
        while name.utf8.count > maxFileNameBytes {
            let stem = keepsExtension ? (name as NSString).deletingPathExtension : name
            name = String(stem.dropLast()) + (keepsExtension ? "." + ext : "")
        }
        return name.isEmpty ? "Download" : name
    }

    /// Well under the 255 bytes APFS allows.
    static let maxFileNameBytes = 200

    /// A file's name from its address, for the notice when it fails: the last part of an
    /// http or https path. A blob or data URL, or one with no path, has none.
    static func name(in url: URL?) -> String? {
        guard let url, ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              !url.lastPathComponent.isEmpty, url.lastPathComponent != "/" else { return nil }
        return fileName(url.lastPathComponent)
    }

    static func failedNotice(_ name: String?) -> String {
        name.map { "Couldn’t download \($0)" } ?? "Couldn’t download the file"
    }
}

/// WebKit sends a load to the phone's loopback address or its own network address straight
/// out, past the node's proxy, which would refuse it. These rules block every load to a
/// loopback, private or link-local IP literal, in an app's frames and from its scripts alike,
/// so a page can't reach the phone itself or map what listens there. Tailnet addresses
/// (100.64.0.0/10 and Tailscale's fd7a:115c:a1e0::/48) are left to the proxy, which allows
/// only the node's peers, and names go through it as well.
@MainActor
enum LocalAddressRules {
    /// Hosts as WebKit writes them in a URL: IPv4 in dotted decimal, IPv6 bracketed in
    /// compressed hex, an IPv4-mapped address as `::ffff:7f00:1`. A url-filter has no
    /// alternation, so each range needs its own pattern.
    static let blockedHosts = [
        "127\\.[0-9]+\\.[0-9]+\\.[0-9]+[:/]", "10\\.[0-9]+\\.[0-9]+\\.[0-9]+[:/]",
        "172\\.1[6-9]\\.[0-9]+\\.[0-9]+[:/]", "172\\.2[0-9]\\.[0-9]+\\.[0-9]+[:/]", "172\\.3[01]\\.[0-9]+\\.[0-9]+[:/]",
        "192\\.168\\.[0-9]+\\.[0-9]+[:/]", "169\\.254\\.[0-9]+\\.[0-9]+[:/]", "0\\.[0-9]+\\.[0-9]+\\.[0-9]+[:/]",
        "\\[::1?\\]",
        "\\[::ffff:7f[0-9a-f][0-9a-f]:[0-9a-f]+\\]", "\\[::ffff:a[0-9a-f][0-9a-f]:[0-9a-f]+\\]",
        "\\[::ffff:ac1[0-9a-f]:[0-9a-f]+\\]", "\\[::ffff:c0a8:[0-9a-f]+\\]", "\\[::ffff:a9fe:[0-9a-f]+\\]",
        "\\[::ffff:[0-9a-f]?[0-9a-f]?:[0-9a-f]+\\]",
        "\\[fe[89ab][0-9a-f]:", "\\[f[cd][0-9a-f][0-9a-f]:",
    ]
    /// Checked after the blocks, so Tailscale's range inside fc00::/7 stays open.
    static let allowedHosts = ["\\[fd7a:115c:a1e0:"]

    /// The host comes right after the scheme and any user info, so the same text in a path
    /// or query doesn't match.
    static func urlFilter(host: String) -> String { "^[a-z]+://([^/@]*@)?" + host }

    static var json: String {
        let rules = blockedHosts.map { ["trigger": ["url-filter": urlFilter(host: $0)], "action": ["type": "block"]] }
            + allowedHosts.map { ["trigger": ["url-filter": urlFilter(host: $0)], "action": ["type": "ignore-previous-rules"]] }
        return String(decoding: try! JSONSerialization.data(withJSONObject: rules), as: UTF8.self)
    }

    private static let compiled = Task { () -> WKContentRuleList? in
        do {
            return try await WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "local-addresses",
                                                                                     encodedContentRuleList: json)
        } catch {
            Logger(subsystem: "com.snowyghost.ovenlight", category: "node")
                .error("local address rules didn't compile: \(String(describing: error), privacy: .public)")
            return nil
        }
    }
    /// Adds the rules to a view before its first load. False if they couldn't be compiled:
    /// the view then loads nothing. A controller keeps one list per identifier, so adding
    /// it again, as each reload does, changes nothing.
    static func install(in view: WKWebView) async -> Bool {
        guard let list = await compiled.value else { return false }
        view.configuration.userContentController.add(list)
        return true
    }
}
