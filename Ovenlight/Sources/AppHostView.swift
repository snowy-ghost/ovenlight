import SwiftUI
import WebKit

/// Full-screen host for one app: its web view edge to edge, with the tuck-away chrome from
/// `AppChrome` as the only thing Ovenlight draws over it.
struct AppHostView: View {
    let app: WebApp
    let onClose: () -> Void

    @ObservedObject private var pool = WebViewPool.shared
    // Any change to the nodes can change the app's route, which the pool checks.
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var registry: AppRegistry

    var body: some View {
        // An app removed while its cover closes (Leave, access ended) gets no new web view:
        // the pool would make its data store again, and load it.
        if registry.apps.contains(where: { $0.id == app.id }) {
            // The same live web view on every call, unless the app's route changed since it
            // was made: then a new one through the right node, and a new page around it.
            let (webView, coordinator) = pool.webView(for: app)
            AppPage(app: app, onClose: onClose, webView: webView, coordinator: coordinator)
                .id(ObjectIdentifier(coordinator))
        } else {
            Color(.systemBackground)
                .ignoresSafeArea()
        }
    }
}

private struct AppPage: View {
    let app: WebApp
    let onClose: () -> Void

    @EnvironmentObject private var lock: LockManager
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var registry: AppRegistry
    @Environment(\.colorScheme) private var colorScheme
    private let webView: WKWebView
    @ObservedObject private var coordinator: WebCoordinator
    @ObservedObject private var sharing = OwnerSharing.shared
    // The app is itself a full-screen cover, so the root's sheets can't present over it;
    // sign-in and Share present from here.
    @State private var sheet: RootSheet?
    @State private var feedback: FeedbackCapture?
    @State private var mail: MailDraft?
    @State private var leaving: Membership?

    init(app: WebApp, onClose: @escaping () -> Void, webView: WKWebView, coordinator: WebCoordinator) {
        self.app = app
        self.onClose = onClose
        self.webView = webView
        self.coordinator = coordinator
    }

    var body: some View {
        let icon = registry.icon(for: app)
        ZStack {
            Color(.systemBackground)
                .ignoresSafeArea()
            // Files that finished while the person was back in the launcher, once the page
            // is in a window to present over (it isn't yet as the view appears).
            WebViewContainer(webView: webView, onWindow: { coordinator.presentWaitingDownloads() })
                .ignoresSafeArea()
                .opacity(coordinator.hasLoadedOnce ? 1 : 0)
            if !coordinator.hasLoadedOnce && coordinator.loadError == nil {
                if let appNode = coordinator.node, let action = NodeActionOverlay.action(for: appNode.state, app: app, ownerEnabled: node.ownerEnabled) {
                    NodeActionOverlay(appName: app.name, action: action) {
                        if case .needsLogin(let url) = node.state { sheet = .login(url) } else if !node.ownerEnabled { node.enableOwner() }
                    } retry: {
                        Task { await appNode.restart() }
                    }
                } else if coordinator.gate == .unreachable {
                    LoadErrorView(title: ConnectionCopy.cantReach(app.name), message: ConnectionCopy.cantReachDetail,
                                  retry: { coordinator.reload() }, close: onClose)
                        .transition(.opacity)
                } else {
                    LaunchPlaceholder(app: app, icon: icon, caption: ConnectionCopy.connecting)
                        .transition(.opacity)
                }
            }
            if let error = coordinator.loadError {
                LoadErrorView(title: "Can't Open \(app.name)", message: error,
                              retry: { coordinator.reload() }, close: onClose)
                    .transition(.opacity)
            }
            AppChrome(
                app: app,
                icon: icon,
                webView: webView,
                isLoaded: coordinator.hasLoadedOnce,
                actions: AppChromeActions(
                    home: onClose,
                    reload: { coordinator.reload() },
                    openInSafari: coordinator.usesNode ? nil : { coordinator.openExternally(webView.url ?? app.startURL) },
                    share: shareAction,
                    sendFeedback: app.hasConnector && coordinator.hasLoadedOnce ? { Task { await captureFeedback() } } : nil,
                    reportProblem: { mail = .problemReport(for: app) },
                    leave: leaveAction,
                    lock: { lock.lock() }
                )
            )
        }
        .animation(.easeOut(duration: 0.25), value: coordinator.hasLoadedOnce)
        .animation(.easeOut(duration: 0.25), value: coordinator.loadError)
        .animation(.easeOut(duration: 0.25), value: coordinator.gate)
        .onChange(of: coordinator.hasLoadedOnce, initial: true) { _, loaded in
            if loaded { StatusBarTint.remember(webView.themeColor ?? webView.underPageBackgroundColor, for: app, in: colorScheme) }
        }
        .statusBarHidden(false)
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .login(let url): SafariView(url: url).ignoresSafeArea()
            case .invite(let machine, let app): InviteComposer(machine: machine, app: app)
            default: EmptyView()
            }
        }
        .onChange(of: node.state) { _, state in
            if case .needsLogin = state { return }
            if case .login = sheet { sheet = nil }
        }
        .sheet(item: $feedback) { capture in
            FeedbackSheet(app: app, screenshot: capture.image, pageURL: capture.pageURL)
        }
        .mailComposer($mail)
        .leaveConfirmation($leaving, before: onClose)
        .task {
            // Whether Share applies comes from the owner's connector.
            if !app.isShared, app.discovered { await sharing.refreshIfStale(apps: registry.apps) }
        }
        // VoiceOver's two-finger scrub goes back to the launcher, like Back does elsewhere.
        .accessibilityAction(.escape, onClose)
    }

    /// Share, for the owner's own apps the connector says are shareable.
    private var shareAction: (() -> Void)? {
        guard let info = sharing.info(for: app), info.app.shareable else { return nil }
        return { sheet = .invite(info.machine, info.app) }
    }

    /// Leave, for apps someone shared: all of that person's apps go, this one included.
    private var leaveAction: (owner: String, action: () -> Void)? {
        guard let id = app.membershipID, let membership = node.memberships.memberships.first(where: { $0.id == id }) else { return nil }
        return (membership.ownerName, { leaving = membership })
    }

    /// Snapshots the page as it is now, then asks for a note.
    private func captureFeedback() async {
        let image = try? await webView.takeSnapshot(configuration: WKSnapshotConfiguration())
        feedback = FeedbackCapture(image: image, pageURL: webView.url)
    }
}

struct WebViewContainer: UIViewRepresentable {
    let webView: WKWebView
    /// Runs a turn after the page lands in a window, outside the view update.
    let onWindow: () -> Void

    func makeUIView(context: Context) -> UIView {
        let container = Container()
        container.onWindow = onWindow
        container.backgroundColor = .clear
        webView.removeFromSuperview()
        webView.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            webView.topAnchor.constraint(equalTo: container.topAnchor),
            webView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    final class Container: UIView {
        var onWindow: () -> Void = {}
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { DispatchQueue.main.async(execute: onWindow) }
        }
    }
}

struct FeedbackCapture: Identifiable {
    let id = UUID()
    var image: UIImage?
    var pageURL: URL?
}

/// Shown over an app when its connection needs someone: the owner's sign-in or device
/// approval, or a failure with a retry. Plain waiting is the app's launch placeholder. A
/// guest never sees sign-in, approval or Tailscale.
struct NodeActionOverlay: View {
    enum Action: Equatable {
        case signIn
        case ownerApproval
        case failed(String)
    }

    let appName: String
    let action: Action
    let connect: () -> Void
    let retry: () -> Void

    static func action(for state: NodeState, app: WebApp, ownerEnabled: Bool) -> Action? {
        if app.isShared {
            switch state {
            case .failed(let message): return .failed(message)
            default: return nil // a lost login means the share ended; Ovenlight removes the app
            }
        }
        switch state {
        case .needsLogin: return .signIn
        case .idle where !ownerEnabled: return .signIn
        case .awaitingApproval: return .ownerApproval
        case .failed(let message): return .failed(message)
        default: return nil
        }
    }

    var body: some View {
        VStack(spacing: 14) {
            switch action {
            case .signIn:
                symbol("person.badge.key")
                Text(ConnectionCopy.signIn(toOpen: appName))
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Button("Sign In", action: connect).buttonStyle(.borderedProminent)
            case .ownerApproval:
                OwnerApprovalPrompt()
            case .failed(let message):
                symbol("wifi.exclamationmark")
                Text(message)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                Button("Try Again", action: retry).buttonStyle(.borderedProminent)
            }
        }
        .padding(28)
        .ovenlightGlass(in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(24)
    }

    private func symbol(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 36))
            .foregroundStyle(.secondary)
    }
}

struct LoadErrorView: View {
    let title: String
    let message: String
    let retry: () -> Void
    let close: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: "wifi.exclamationmark")
        } description: {
            Text(message)
        } actions: {
            Button("Try Again", action: retry)
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
            Button("Back to Apps", action: close)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}
