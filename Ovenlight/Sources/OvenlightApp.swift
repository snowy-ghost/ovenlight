import SwiftUI
import TipKit

@main
struct OvenlightApp: App {
    @StateObject private var registry: AppRegistry
    @StateObject private var lock: LockManager
    @StateObject private var router: Router
    @StateObject private var node = NodeManager.shared

    init() {
        _registry = StateObject(wrappedValue: AppRegistry())
        let lock = LockManager { Self.hasSavedData() }
        _lock = StateObject(wrappedValue: lock)
        _router = StateObject(wrappedValue: Router(isLocked: { [weak lock] in lock?.holdsLinks ?? true }))
        #if DEBUG
        // Simulator testing: `-resetTips YES` shows first-run tips again.
        if UserDefaults.standard.bool(forKey: "resetTips") { try? Tips.resetDatastore() }
        #endif
        try? Tips.configure()
        // Guest nodes get their hooks from the first one on.
        _ = GuestManager.shared
    }

    /// Whether this iPhone has kept anything: apps, invites or a node. It asks only whether
    /// the files exist and their size, which iOS answers even before the first unlock after
    /// a restart, when a prewarmed launch can't read them.
    static func hasSavedData(in directory: URL? = nil) -> Bool {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Ovenlight", isDirectory: true)
        let files = FileManager.default
        // A failed join, leaving the last network and removing the last app each leave an
        // empty list, `[]`; a file whose size can't be read still counts.
        if ["apps.json", "memberships.json"].contains(where: {
            let file = base.appendingPathComponent($0)
            return files.fileExists(atPath: file.path) && (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) != 2
        }) {
            return true
        }
        // Stop Using My Own Computers and leaving the last network leave `tailscale/` empty;
        // a listing that fails still counts it.
        let nodes = base.appendingPathComponent("tailscale").path
        return files.fileExists(atPath: nodes) && (try? files.contentsOfDirectory(atPath: nodes))?.isEmpty != true
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(registry)
                .environmentObject(lock)
                .environmentObject(router)
                .environmentObject(node)
        }
    }
}

/// What the root presents: the open app as a full-screen cover, and one sheet at a time.
/// Deep links (`ovenlight://open?app=<name>`, `ovenlight://join?...` and the universal link
/// `https://ovenlight.app/join#...`) land here; they only become visible once
/// Ovenlight is unlocked, because the lock window covers everything. An app a link opens
/// waits for the unlock, so its page can't run under the lock screen.
///
/// A cover and a sheet can't present over each other, so anything that needs the other
/// one closes it first and waits for its `onDismiss`. Setting another sheet while one is up
/// swaps them; SwiftUI does that in turn.
@MainActor
final class Router: ObservableObject {
    @Published var openApp: WebApp?
    @Published var sheet: RootSheet?

    /// A deep-linked app that isn't listed yet (discovery may add it in a moment), or that
    /// waits for Ovenlight to unlock.
    private var pendingName: String?
    private let isLocked: @MainActor () -> Bool
    /// True from the moment a cover or sheet shows until its `onDismiss`, which comes after
    /// the dismissal animation; `openApp` and `sheet` are already nil by then.
    private var appOnScreen = false
    private var sheetOnScreen = false
    /// Work waiting for the cover and sheet to be gone.
    private var whenClear: [() -> Void] = []

    private var isClear: Bool { openApp == nil && sheet == nil && !appOnScreen && !sheetOnScreen }

    init(isLocked: @escaping @MainActor () -> Bool = { false }) {
        self.isLocked = isLocked
    }

    /// Opens an app, closing any sheet first.
    func open(_ app: WebApp) {
        guard sheet != nil || sheetOnScreen else { openApp = app; return }
        sheet = nil
        afterClear { self.openApp = app }
    }

    /// Shows a sheet, closing the open app first.
    func present(_ sheet: RootSheet) {
        guard openApp != nil || appOnScreen else { self.sheet = sheet; return }
        openApp = nil
        afterClear { self.sheet = sheet }
    }

    /// Runs `action` now if nothing is presented, otherwise once it has all closed.
    func afterClear(_ action: @escaping () -> Void) {
        if isClear { action() } else { whenClear.append(action) }
    }

    func appAppeared() { appOnScreen = true }
    func sheetAppeared() { sheetOnScreen = true }

    /// Both are also called when one item replaces another; the new one is still up then.
    func appDismissed() {
        if openApp == nil { appOnScreen = false }
        drain()
    }

    func sheetDismissed() {
        if sheet == nil { sheetOnScreen = false }
        drain()
    }

    private func drain() {
        guard isClear else { return }
        let actions = whenClear
        whenClear = []
        actions.forEach { $0() }
    }

    func handle(_ url: URL, apps: [WebApp]) {
        let scheme = url.scheme?.lowercased() ?? ""
        if InviteLink.isInvite(url) {
            present(.join(JoinRequest(url: url)))
            return
        }
        guard scheme == InviteLink.scheme, url.host?.lowercased() == "open",
              let name = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "app" })?.value else { return }
        pendingName = name
        appsChanged(apps)
    }

    /// Also called on unlock, for an app a link asked for while locked. True when it opens
    /// an app other than the one open.
    @discardableResult
    func appsChanged(_ apps: [WebApp]) -> Bool {
        guard !isLocked(), let name = pendingName,
              let app = apps.first(where: { $0.name.localizedCaseInsensitiveCompare(name) == .orderedSame }) else { return false }
        pendingName = nil
        defer { open(app) }
        return openApp?.id != app.id
    }
}

/// The sheets the root presents.
enum RootSheet: Identifiable {
    /// An invite link, or the Join with Invite entry.
    case join(JoinRequest)
    /// Tailscale's sign-in page for the owner's node.
    case login(URL)
    /// The owner's People & Sharing.
    case sharing
    case addApp
    case settings
    case invite(OwnerSharing.Machine, AdminApp)
    case people(machineID: String, slug: String)

    var id: String {
        switch self {
        case .join(let request): "join \(request.id)"
        case .login(let url): "login \(url)"
        case .sharing: "sharing"
        case .addApp: "add"
        case .settings: "settings"
        case .invite(let machine, let app): "invite \(machine.id) \(app.slug)"
        case .people(let machineID, let slug): "people \(machineID) \(slug)"
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var registry: AppRegistry
    @EnvironmentObject private var lock: LockManager
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var node: NodeManager
    @ObservedObject private var guests = GuestManager.shared
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var overlay = OverlayPresenter()
    @Namespace private var launch

    var body: some View {
        ZStack {
            LauncherView()
                .environment(\.launchNamespace, launch)
                .fullScreenCover(item: $router.openApp, onDismiss: router.appDismissed) { app in
                    AppHostView(app: app) { router.openApp = nil }
                        .launchDestination(for: app, in: launch)
                        // Pulling down or pinching would fight the page's own gestures.
                        .interactiveDismissDisabled()
                        .onAppear(perform: router.appAppeared)
                }
        }
        .background(WindowCatcher { window in overlay.install(over: window, lock: lock) })
        // Custom schemes and tapped universal links (https://ovenlight.app/join#...).
        .onOpenURL { url in router.handle(url, apps: registry.apps) }
        .onAppear {
            #if DEBUG
            // Simulator testing: `-joinInvite "ovenlight://join?..."` opens an invite.
            if let link = UserDefaults.standard.string(forKey: "joinInvite"), let url = URL(string: link) {
                router.handle(url, apps: registry.apps)
            }
            // Simulator testing: `-openApp "Interview Coach"` as a launch argument.
            if let name = UserDefaults.standard.string(forKey: "openApp"),
               let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
               let url = URL(string: "ovenlight://open?app=\(encoded)") {
                router.handle(url, apps: registry.apps)
            }
            #endif
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .background:
                lock.didEnterBackground()
            case .active:
                lock.willBecomeActive()
                // A prewarmed launch before the first unlock couldn't read the saved lists.
                registry.reloadIfFailed()
                node.memberships.reloadIfFailed()
                Task {
                    await node.didBecomeActive()
                    await guests.resumePending()
                    // The Home Screen has no pull to refresh; Ovenlight looks for new apps
                    // each time it comes to the front instead.
                    await node.rediscover()
                    await registry.refreshAllMissingIcons()
                }
            default: break
            }
            overlay.update(isLocked: lock.isLocked, isActive: phase == .active)
            if phase == .active { Task { await lock.didBecomeActive() } }
        }
        .onChange(of: lock.isLocked) { _, locked in
            overlay.update(isLocked: locked, isActive: scenePhase == .active)
            if locked { WebViewPool.shared.stopCapture() }
            // Only the page on top shows its files. One a pending link opens shows them as it
            // appears; the page it replaces is going, and its files wait for it.
            if !locked, !router.appsChanged(registry.apps) { WebViewPool.shared.presentWaitingDownloads() }
        }
        .onChange(of: node.state) { _, state in
            if case .needsLogin = state { return }
            if case .login = router.sheet { router.sheet = nil }
        }
        // Opening an app, from anywhere, clears its new dot.
        .onChange(of: router.openApp?.id) { _, id in
            if let id { registry.markOpened(id) }
        }
        .onChange(of: registry.apps) { _, apps in
            router.appsChanged(apps)
            Task { await guests.collectOrphans() }
        }
        .onReceive(node.owner.$discovered) { found in
            Task { await registry.mergeDiscovered(found) }
        }
        .task {
            guests.registry = registry
            guests.router = router
            // Leftovers from apps removed while their web view was still closing.
            try? await Task.sleep(for: .seconds(5))
            await registry.sweepUnusedDataStores()
        }
        .sheet(item: $router.sheet, onDismiss: router.sheetDismissed) { sheet in
            content(for: sheet)
                .onAppear(perform: router.sheetAppeared)
        }
        .alert(guests.notice?.title ?? "", isPresented: Binding(
            get: { guests.notice != nil }, set: { if !$0 { guests.notice = nil } }), presenting: guests.notice) { _ in
            Button("OK", role: .cancel) {}
        } message: { notice in
            Text(notice.message)
        }
    }

    @ViewBuilder
    private func content(for sheet: RootSheet) -> some View {
        switch sheet {
        case .join(let request):
            JoinFlowView(request: request)
        case .login(let url):
            SafariView(url: url).ignoresSafeArea()
        case .sharing:
            NavigationStack {
                SharingList()
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { router.sheet = nil } } }
            }
        case .addApp:
            AddAppView()
        case .settings:
            SettingsView()
        case .invite(let machine, let app):
            InviteComposer(machine: machine, app: app)
        case .people(let machineID, let slug):
            AppPeopleSheet(machineID: machineID, slug: slug)
        }
    }
}

/// Hosts the lock screen and the app-switcher privacy cover in their own window above
/// everything else, including sheets, alerts and Safari views the apps present.
@MainActor
final class OverlayPresenter: ObservableObject {
    @Published var isLocked = true
    @Published var isActive = true
    private var window: UIWindow?
    private var host: UIView?
    /// Frost between the lock screen and the apps, shown only as an unlock clears it.
    private let frost = UIVisualEffectView()
    /// Unlocked since the cover last went away; the next reveal clears frost, not a fade.
    private var unlocked = false
    /// Ovenlight's own window, which gets the keyboard back after an unlock.
    private weak var mainWindow: UIWindow?
    private static weak var installed: OverlayPresenter?

    /// Where a page's dialogs and sheets present: Ovenlight's own window, since the lock
    /// window is key while locked. Nil while locked, so nothing a page opens then lands on
    /// the lock screen, or waits under it.
    static var pageWindow: UIWindow? {
        guard let installed, !installed.isLocked else { return nil }
        return installed.mainWindow
    }

    func install(over mainWindow: UIWindow, lock: LockManager) {
        guard window == nil, let scene = mainWindow.windowScene else { return }
        Self.installed = self
        self.mainWindow = mainWindow
        // Runs during a view update (didMoveToWindow): publish only a real change.
        if isLocked != lock.isLocked { isLocked = lock.isLocked }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        let host = UIHostingController(rootView: OverlayRoot(presenter: self).environmentObject(lock))
        host.view.backgroundColor = .clear
        // VoiceOver stays in the lock screen, not the apps under it.
        host.view.accessibilityViewIsModal = true
        window.rootViewController = host
        frost.frame = window.bounds
        frost.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        window.insertSubview(frost, belowSubview: host.view)
        self.window = window
        self.host = host.view
        apply()
    }

    func update(isLocked: Bool, isActive: Bool) {
        if self.isLocked && !isLocked { unlocked = true }
        self.isLocked = isLocked
        self.isActive = isActive
        apply()
    }

    /// The cover appears at once, so nothing private reaches the app switcher snapshot.
    /// Once Ovenlight is active again it fades away, or after an unlock gives way to frost
    /// over the apps that clears (a fade with Reduce Motion).
    private func apply() {
        guard let window, let host else { return }
        if isLocked || !isActive {
            [window.layer, host.layer, frost.layer].forEach { $0.removeAllAnimations() }
            frost.effect = nil
            host.alpha = 1
            window.alpha = 1
            window.isHidden = false
            // Typing must not reach a page under the lock, and iOS would bring its keyboard
            // back above this window on return.
            if isLocked, !window.isKeyWindow {
                mainWindow?.endEditing(true)
                window.makeKey()
            }
        } else if !window.isHidden {
            mainWindow?.makeKey()
            let done: (Bool) -> Void = { [weak self] _ in
                guard let self, !self.isLocked, self.isActive else { return }
                window.isHidden = true
                window.alpha = 1
                host.alpha = 1
            }
            let clearsFrost = unlocked && !UIAccessibility.isReduceMotionEnabled
            unlocked = false
            guard clearsFrost else {
                UIView.animate(withDuration: 0.3, delay: 0, options: [.beginFromCurrentState, .curveEaseOut],
                               animations: { window.alpha = 0 }, completion: done)
                return
            }
            frost.effect = UIBlurEffect(style: .systemThickMaterial)
            UIView.animate(withDuration: 0.2, delay: 0, options: .curveEaseOut) { host.alpha = 0 }
            UIView.animate(withDuration: 0.45, delay: 0.1, options: .curveEaseOut,
                           animations: { self.frost.effect = nil }, completion: done)
        }
    }
}

struct OverlayRoot: View {
    @ObservedObject var presenter: OverlayPresenter

    var body: some View {
        if presenter.isLocked {
            LockScreen()
        } else if !presenter.isActive {
            PrivacyCover()
        }
    }
}

/// Reports the window this view lands in.
struct WindowCatcher: UIViewRepresentable {
    let onWindow: (UIWindow) -> Void

    func makeUIView(context: Context) -> CatcherView { CatcherView(onWindow: onWindow) }
    func updateUIView(_ uiView: CatcherView, context: Context) {}

    final class CatcherView: UIView {
        let onWindow: (UIWindow) -> Void
        init(onWindow: @escaping (UIWindow) -> Void) {
            self.onWindow = onWindow
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }
        required init?(coder: NSCoder) { fatalError() }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { onWindow(window) }
        }
    }
}

/// Ovenlight's palette, from the asset catalog: an oven light, from its bright core out to
/// ember.
extension Color {
    /// The brand color, the app's accent: controls and surfaces that stay warm in both appearances.
    static let ember = Color.accentColor
    /// The screen behind the lock screen, the privacy cover and the launcher.
    static let ovenInterior = Color("OvenInterior")
    /// The light's warm core, where it leaves the island.
    static let lampCore = Color("LampCore")
    /// The lamp's glow: washes, halos and highlights, never text.
    static let lampGlow = Color("LampGlow")
    /// Behind grouped lists and forms: warm paper in place of the system's cool grey.
    static let groupedBackground = Color("GroupedBackground")
    /// Rows on `groupedBackground`: white in light, a warm step up from it in dark.
    static let groupedRow = Color("GroupedRow")
}
