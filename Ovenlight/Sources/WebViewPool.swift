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
    /// its page shows them. They outlive the app's view; those of an app that's removed go
    /// at the next launch.
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
        // owner's guest node, everything else loads directly.
        let node = NodeManager.shared.node(for: app)
        // Service workers only run in app-bound mode, and app-bound mode must be off for
        // hosts not in WKAppBoundDomains, or WebKit refuses to load the page.
        config.limitsNavigationsToAppBoundDomains = AppBoundDomains.contains(host: app.host)
        Self.configure(config)

        let view = WKWebView(frame: .zero, configuration: config)
        // Route the store the web view actually holds, now and after every node restart.
        let store = view.configuration.websiteDataStore
        if let node {
            node.attach(store, for: app.id)
        } else {
            store.proxyConfigurations = app.isShared ? [MembershipNode.closedProxy] : []
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

    /// What every app view gets, whichever app it shows.
    static func configure(_ config: WKWebViewConfiguration) {
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.applicationNameForUserAgent = userAgentName(after: config.applicationNameForUserAgent)
    }

    /// The end of every app view's user agent, so a page can tell it runs in Ovenlight.
    /// WebKit's own name (`Mobile/...`) stays before it, so pages still see mobile Safari.
    nonisolated static func userAgentName(after webKitName: String?,
                                          version: String? = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) -> String {
        [webKitName, "Ovenlight/\(version ?? "?")"].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// Ends every microphone and camera stream, on screen or not, so none runs under the lock.
    func stopCapture() {
        for view in views.values {
            view.setMicrophoneCaptureState(.none)
            view.setCameraCaptureState(.none)
        }
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
    weak var webView: WKWebView?

    @Published var loadError: String?
    @Published var hasLoadedOnce = false
    @Published private(set) var gate: AppLoadGate = .waiting

    private static let log = Logger(subsystem: "com.snowyghost.ovenlight", category: "node")
    /// First navigations that failed at the connection level after the probe answered.
    private var failedLoads = 0
    private static let maxFailedLoads = 2
    private var gateTask: Task<Void, Never>?
    /// Each download under way, and the file it's written to once WebKit asks where.
    private var downloads: [ObjectIdentifier: (download: WKDownload, file: URL?)] = [:]
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
    /// reached: loading early lets a service worker paint a cached shell whose requests fail.
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
        for (download, file) in downloads.values {
            download.cancel { _ in
                if let file { DownloadPolicy.removeFolder(of: file) }
            }
        }
        downloads = [:]
    }

    /// Try Again, and Reload from the app menu. A page that already loaded stays on screen
    /// while the path is checked again.
    func reload() {
        failedLoads = 0
        loadError = nil
        gate = gate.next(.retry)
        openGate()
    }

    /// Opens the service worker's cached copy when the app can't be reached.
    func openOffline() {
        gate = gate.next(.openOffline)
        if gate == .ready { load() }
    }

    private func openGate() {
        gateTask?.cancel()
        gateTask = Task { [weak self] in await self?.runGate() }
    }

    /// Waits for the node, then probes this app's host until it answers or time runs out.
    private func runGate() async {
        // A shared app always goes through its owner's node; without one it has no way in.
        if app.isShared && node == nil { return await GuestManager.shared.collectOrphans() }
        let opened = Date.now
        var probingSince: Date?
        while !Task.isCancelled, gate == .waiting || gate == .probing {
            // Not even a probe goes through a node the app's route no longer names.
            guard routeIsCurrent() else { return }
            // Apps loaded directly (on the public internet) have no node to wait for.
            if node == nil || (node?.state == .ready && nodeMayCarry) {
                gate = gate.next(.nodeReady)
                let since = probingSince ?? .now
                probingSince = since
                let reach = if let node { await node.canReach(app) } else { await Self.canReachDirectly(app.startURL) ? Reach.reached : .unreachable }
                switch reach {
                case .reached:
                    guard !Task.isCancelled else { return }
                    gate = gate.next(.reached)
                    load()
                    return
                case .notInvited:
                    // The owner stopped sharing it; Ovenlight takes the app away.
                    GuestManager.shared.accessEnded(for: [app])
                    return
                case .unreachable:
                    break
                }
                if Date.now.timeIntervalSince(since) >= AppLoadGate.reachTimeout { return await giveUp() }
                try? await Task.sleep(for: .seconds(1))
            } else {
                gate = gate.next(.nodeNotReady)
                probingSince = nil
                // Sign-in and node failures have their own screens and wait for the owner.
                if node?.state.isBusy == true, Date.now.timeIntervalSince(opened) >= AppLoadGate.nodeTimeout { return await giveUp() }
                try? await Task.sleep(for: .milliseconds(250))
            }
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

    private func giveUp() async {
        let offline = await hasOfflineCopy()
        guard !Task.isCancelled else { return }
        Self.log.info("\(self.app.host, privacy: .public): unreachable, offline copy \(offline)")
        gate = gate.next(.gaveUp(offlineAvailable: offline))
        // A page already on screen keeps it; its service worker decides what a reload shows.
        if hasLoadedOnce { gate = .ready; load() }
    }

    private func hasOfflineCopy() async -> Bool {
        guard let store = webView?.configuration.websiteDataStore else { return false }
        return await !store.dataRecords(ofTypes: [WKWebsiteDataTypeServiceWorkerRegistrations]).isEmpty
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
        Self.log.info("\(self.app.host, privacy: .public): route changed, making a new view")
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
        switch NavigationPolicy.decide(url: url, appHost: app.host, isMainFrame: isMainFrame, tapped: Self.isTap(action)) {
        case .allow where action.shouldPerformDownload:
            // `<a download>`, of the app's own pages or of a blob or data URL.
            let source = (action.sourceFrame as WKFrameInfo?)?.securityOrigin.host
            decisionHandler(mayDownload(url, from: source) ? .download : .cancel)
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
            decisionHandler(mayDownload(url, from: nil) ? .download : .cancel)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        hasLoadedOnce = true
        loadError = nil
        failedLoads = 0
    }

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
        // Once the app has rendered, a failed load (offline, a file WebKit can't show)
        // leaves the page as it is; a service worker may serve it offline.
        if hasLoadedOnce { return }
        if usesNode && ns.domain == NSURLErrorDomain && Self.retryableThroughNode.contains(ns.code) {
            // The probe answered, but the navigation itself couldn't connect (a lost
            // handshake looks like NSURLError -1000 through the proxy): check the path again.
            if failedLoads < Self.maxFailedLoads {
                failedLoads += 1
                Self.log.info("\(self.app.host, privacy: .public): error \(ns.code), checking the path again")
                gate = gate.next(.loadFailed)
                openGate()
                return
            }
            failedLoads = 0
            loadError = ConnectionCopy.cantReachDetail
            return
        }
        let unreachable: Set<Int> = [NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost,
                                     NSURLErrorTimedOut, NSURLErrorNotConnectedToInternet,
                                     NSURLErrorNetworkConnectionLost, NSURLErrorDNSLookupFailed]
        if ns.domain == NSURLErrorDomain && unreachable.contains(ns.code) {
            loadError = ConnectionCopy.cantReachDetail
        } else {
            loadError = error.localizedDescription
        }
    }

    static let retryableThroughNode: Set<Int> = [NSURLErrorBadURL, NSURLErrorTimedOut, NSURLErrorCannotFindHost,
                                                 NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost]

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
                                                  locked: OverlayPresenter.pageWindow == nil))
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void) {
        let answer = DialogAnswer(fallback: ()) { _ in completionHandler() }
        let alert = UIAlertController(title: app.name, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in answer(()) })
        present(alert) { answer(()) }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void) {
        let answer = DialogAnswer(fallback: false, completionHandler)
        let alert = UIAlertController(title: app.name, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in answer(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in answer(true) })
        present(alert) { answer(false) }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping @MainActor (String?) -> Void) {
        let answer = DialogAnswer(fallback: nil, completionHandler)
        let alert = UIAlertController(title: app.name, message: prompt, preferredStyle: .alert)
        alert.addTextField { $0.text = defaultText }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in answer(nil) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak alert] _ in answer(alert?.textFields?.first?.text) })
        present(alert) { answer(nil) }
    }

    // MARK: Downloads

    /// Nothing downloads while this page is kept off screen either: a script's `a.click()`
    /// would start one under another app.
    private func mayDownload(_ url: URL, from sourceHost: String?) -> Bool {
        webView?.window != nil
            && DownloadPolicy.allows(url: url, from: sourceHost, appHost: app.host, locked: OverlayPresenter.pageWindow == nil)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        track(download)
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        track(download)
    }

    private func track(_ download: WKDownload) {
        download.delegate = self
        downloads[ObjectIdentifier(download)] = (download, nil)
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
        downloads[ObjectIdentifier(download)] = (download, file)
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
        guard let file = downloads.removeValue(forKey: ObjectIdentifier(download))?.file else { return }
        DownloadPolicy.removeFolder(of: file)
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
            let alert = UIAlertController(title: "Open in Another App?",
                                          message: "\(app.name) wants to open \(NavigationPolicy.target(of: url)).", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            alert.addAction(UIAlertAction(title: "Open", style: .default) { _ in UIApplication.shared.open(url) })
            present(alert, orElse: {})
        } else {
            UIApplication.shared.open(url)
        }
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

    deinit {
        guard let handler else { return }
        let fallback = fallback
        Task { @MainActor in handler(fallback) }
    }
}

/// Who gets the microphone without the page asking again (iOS still asks once): the
/// owner's own apps (discovered, or on one of `ownersHosts`), on their own origin. The
/// camera, shared apps, other sites added by address (someone's public Funnel URL among
/// them) and every other origin get WebKit's prompt. Nothing gets either while Ovenlight
/// is locked.
enum MediaCapturePolicy {
    static func decide(type: WKMediaCaptureType, scheme: String, host: String, app: WebApp,
                       ownersHosts: Set<String>, locked: Bool) -> WKPermissionDecision {
        if locked { return .deny }
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
            // blob:https://host/<uuid> names the origin of the page that made it.
            return URL(string: String(url.absoluteString.dropFirst("blob:".count)))
                .map { Discovery.normalizedHost($0.host ?? "") } == app
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
}
