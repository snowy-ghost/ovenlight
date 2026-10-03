import SwiftUI
import TipKit
import WebKit

/// What the chrome can do for the open app.
struct AppChromeActions {
    var home: () -> Void
    var reload: () -> Void
    /// Nil for apps reached through Ovenlight's own tailnet node: Safari can't reach those, and
    /// opening one there would also skip Ovenlight's lock.
    var openInSafari: (() -> Void)?
    /// The owner's own shareable apps only: invite someone to this app.
    var share: (() -> Void)?
    /// Apps served by an Ovenlight connector: a note and a screenshot to whoever runs it.
    var sendFeedback: (() -> Void)?
    /// Every app: an email to the team behind Ovenlight.
    var reportProblem: () -> Void
    /// Apps someone shared: leave all of that person's apps.
    var leave: (owner: String, action: () -> Void)?
    var lock: () -> Void
}

/// The open app's only native chrome: a small glass capsule at the top with the way home
/// and the app's menu. It shows while the app opens, then tucks up under the status bar,
/// leaving a small handle in the top safe area. Tapping the handle, scrolling up, or pulling
/// down at the top of the page brings it back; scrolling down or touching the page hides it
/// again. With VoiceOver on it stays put.
struct AppChrome: View {
    let app: WebApp
    let icon: UIImage?
    let webView: WKWebView
    let isLoaded: Bool
    let actions: AppChromeActions

    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isShown = true
    /// Hide on its own after the app loads, until the user takes charge of the chrome.
    @State private var autoHides = true
    @State private var tipShowing = false
    @State private var revealCount = 0
    private let tip = ChromeTip()

    var body: some View {
        VStack {
            if isShown || voiceOver {
                pill
                    .padding(.top, 4)
                    .transition(reduceMotion
                                ? .opacity
                                : .move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.6, anchor: .top)))
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .overlay(alignment: .top) {
            if !isShown && !voiceOver {
                ChromeHandle { reveal() }
                    .transition(.opacity)
            }
        }
        .background(
            WebViewObserver(webView: webView,
                            onScroll: { up in up ? reveal(haptic: false) : hide() },
                            onTouch: { hide() })
        )
        .sensoryFeedback(.impact(weight: .light), trigger: revealCount)
        .task(id: AutoHideKey(loaded: isLoaded, armed: autoHides, tip: tipShowing)) {
            guard isLoaded, autoHides, !tipShowing else { return }
            try? await Task.sleep(for: .seconds(2.5))
            guard !Task.isCancelled else { return }
            autoHides = false
            hide()
        }
        .task {
            for await status in tip.statusUpdates {
                if case .available = status { tipShowing = true } else { tipShowing = false }
            }
        }
    }

    private var pill: some View {
        HStack(spacing: 0) {
            Button(action: actions.home) {
                Image(systemName: "square.grid.2x2")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 46, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("All Apps")

            Divider().frame(height: 18)

            Menu {
                Button(action: actions.home) { Label("All Apps", systemImage: "square.grid.2x2") }
                Section {
                    Button(action: actions.reload) { Label("Reload", systemImage: "arrow.clockwise") }
                    if let openInSafari = actions.openInSafari {
                        Button(action: openInSafari) { Label("Open in Safari", systemImage: "safari") }
                    }
                }
                Section {
                    if let share = actions.share {
                        Button(action: share) { Label("Share…", systemImage: "square.and.arrow.up") }
                    }
                    if let sendFeedback = actions.sendFeedback {
                        // A guest's feedback goes to the owner; Report a Problem goes to Ovenlight.
                        Button(action: sendFeedback) {
                            Label(app.sharedBy.map { "Send Feedback to \($0)" } ?? "Send Feedback", systemImage: "exclamationmark.bubble")
                        }
                    }
                    Button(action: actions.reportProblem) { Label(ConnectionCopy.reportProblem, systemImage: "flag") }
                    if let leave = actions.leave {
                        Button(role: .destructive, action: leave.action) {
                            Label(ConnectionCopy.leaveApps(leave.owner), systemImage: "rectangle.portrait.and.arrow.right")
                        }
                    }
                }
                Section {
                    Button(action: actions.lock) { Label("Lock Ovenlight", systemImage: "lock") }
                }
            } label: {
                HStack(spacing: 7) {
                    AppIconView(app: app, image: icon, size: 22)
                    Text(app.name)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.secondary)
                }
                .padding(.leading, 10)
                .padding(.trailing, 14)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .accessibilityLabel("\(app.name) options")
        }
        .foregroundStyle(.primary)
        .ovenlightGlass(in: Capsule(), interactive: true)
        .simultaneousGesture(TapGesture().onEnded { autoHides = false })
        .popoverTip(tip, arrowEdge: .top)
        .frame(maxWidth: 320)
    }

    private func reveal(haptic: Bool = true) {
        if !isShown {
            withAnimation(.spring(duration: 0.35, bounce: 0.2)) { isShown = true }
            if haptic { revealCount += 1 }
        }
        if haptic { tip.invalidate(reason: .actionPerformed) }
        autoHides = false
    }

    private func hide() {
        guard isShown, !tipShowing else { return }
        withAnimation(.spring(duration: 0.3, bounce: 0)) { isShown = false }
        autoHides = false
    }

    private struct AutoHideKey: Equatable {
        let loaded: Bool
        let armed: Bool
        let tip: Bool
    }
}

/// Teaches, once, where the chrome goes when it hides.
struct ChromeTip: Tip {
    var title: Text { Text("Your Apps Are Up Here") }
    var message: Text? { Text("This bar tucks away while you use an app. Tap the handle at the top of the screen, or scroll up, to bring it back.") }
    var image: Image? { Image(systemName: "hand.tap") }
}

/// What stays of the chrome while it's tucked away: a small handle just above the page's
/// content, in the top safe area that pages leave clear. It works on any page, however
/// the page scrolls. A phone on its side has no top safe area, so there the handle sits on
/// the page's top edge.
private struct ChromeHandle: View {
    let reveal: () -> Void

    @Environment(\.verticalSizeClass) private var verticalSizeClass

    var body: some View {
        Button(action: reveal) {
            Capsule()
                .fill(Color.primary.opacity(0.3))
                .frame(width: 36, height: 5)
                .frame(width: 120, height: 20)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // In portrait, sit in the bottom of the top safe area, under the status bar or
        // Dynamic Island.
        .offset(y: verticalSizeClass == .compact ? 0 : -20)
        .accessibilityLabel("Show App Menu")
    }
}

/// Watches the web view without taking it over: scroll direction through key-value
/// observation, and touches through a recognizer that never claims them.
private struct WebViewObserver: UIViewRepresentable {
    let webView: WKWebView
    let onScroll: (_ up: Bool) -> Void
    let onTouch: () -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        context.coordinator.attach(to: webView)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onScroll = onScroll
        context.coordinator.onTouch = onTouch
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.detach()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onScroll: onScroll, onTouch: onTouch)
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onScroll: (Bool) -> Void
        var onTouch: () -> Void
        private weak var webView: WKWebView?
        private var observation: NSKeyValueObservation?
        private var recognizer: UITapGestureRecognizer?
        private var travel: CGFloat = 0

        /// Points of steady scrolling before the chrome follows.
        private let downThreshold: CGFloat = 12
        private let upThreshold: CGFloat = 60
        /// Points of pulling down past the top of the page that bring the chrome back.
        private let pullThreshold: CGFloat = 40

        init(onScroll: @escaping (Bool) -> Void, onTouch: @escaping () -> Void) {
            self.onScroll = onScroll
            self.onTouch = onTouch
        }

        func attach(to webView: WKWebView) {
            self.webView = webView
            let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
            tap.cancelsTouchesInView = false
            tap.delaysTouchesEnded = false
            tap.delegate = self
            webView.addGestureRecognizer(tap)
            recognizer = tap
            observation = webView.scrollView.observe(\.contentOffset, options: [.old, .new]) { [weak self] scrollView, change in
                guard let old = change.oldValue?.y, let new = change.newValue?.y else { return }
                MainActor.assumeIsolated { self?.scrolled(scrollView, from: old, to: new) }
            }
        }

        func detach() {
            observation?.invalidate()
            observation = nil
            if let recognizer { webView?.removeGestureRecognizer(recognizer) }
            recognizer = nil
        }

        private func scrolled(_ scrollView: UIScrollView, from old: CGFloat, to new: CGFloat) {
            // Only the user's own scrolling counts, and not the rubber band past either end.
            guard scrollView.isTracking || scrollView.isDecelerating else { travel = 0; return }
            if scrollView.isTracking, new < -pullThreshold, old >= -pullThreshold {
                onScroll(true)
                travel = 0
                return
            }
            let maxY = scrollView.contentSize.height - scrollView.bounds.height
            guard new > 0, new < maxY else { return }
            let delta = new - old
            if (delta > 0) != (travel > 0) { travel = 0 }
            travel += delta
            if travel > downThreshold {
                onScroll(false)
                travel = 0
            } else if travel < -upThreshold {
                onScroll(true)
                travel = 0
            }
        }

        @objc private func tapped() { onTouch() }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}
