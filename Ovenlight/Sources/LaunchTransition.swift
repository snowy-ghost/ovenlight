import SwiftUI

/// The namespace that links a launcher tile to the app it opens, so the app zooms out of
/// its icon and back into it on close.
extension EnvironmentValues {
    @Entry var launchNamespace: Namespace.ID?
}

extension View {
    /// Marks this view as the icon an app zooms out of.
    @ViewBuilder
    func launchSource(for app: WebApp, in namespace: Namespace.ID?, iconSize: CGFloat) -> some View {
        if let namespace {
            matchedTransitionSource(id: app.id, in: namespace) { source in
                source.clipShape(Design.iconShape(size: iconSize))
            }
        } else {
            self
        }
    }

    /// Zooms this presented view out of the app's launcher icon.
    @ViewBuilder
    func launchDestination(for app: WebApp, in namespace: Namespace.ID?) -> some View {
        if let namespace {
            navigationTransition(.zoom(sourceID: app.id, in: namespace))
        } else {
            self
        }
    }
}

/// The color each app paints behind the status bar, remembered per appearance from its
/// last load, so the next launch starts with the right header color. Before the first load
/// the manifest's theme color stands in, in light mode only, since pages often darken it.
enum StatusBarTint {
    static func color(for app: WebApp, in scheme: ColorScheme) -> Color? {
        if let hex = UserDefaults.standard.string(forKey: key(app, scheme)) { return Color(hex: hex) }
        return scheme == .light ? Color(hex: app.themeColorHex) : nil
    }

    static func remember(_ color: UIColor?, for app: WebApp, in scheme: ColorScheme) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        guard let color, color.getRed(&r, green: &g, blue: &b, alpha: &a), a > 0.5 else { return }
        let hex = String(format: "#%02x%02x%02x", Int(r * 255), Int(g * 255), Int(b * 255))
        UserDefaults.standard.set(hex, forKey: key(app, scheme))
    }

    static func forget(_ app: WebApp) {
        for scheme in [ColorScheme.light, .dark] { UserDefaults.standard.removeObject(forKey: key(app, scheme)) }
    }

    private static func key(_ app: WebApp, _ scheme: ColorScheme) -> String {
        "statusBarTint.\(app.id.uuidString).\(scheme == .dark ? "dark" : "light")"
    }
}

/// What an app shows until its first page finishes loading: its header color behind the
/// status bar and its icon in the middle, the way a Home Screen web app opens.
struct LaunchPlaceholder: View {
    let app: WebApp
    let icon: UIImage?
    /// Said under the spinner, for example while Ovenlight connects.
    var caption: String?

    @Environment(\.colorScheme) private var colorScheme
    @State private var showsSpinner = false

    var body: some View {
        VStack(spacing: 0) {
            (StatusBarTint.color(for: app, in: colorScheme) ?? Color(.systemBackground))
                .ignoresSafeArea(edges: .top)
                .frame(height: 0)
            Spacer()
            AppIconView(app: app, image: icon, size: 96)
            VStack(spacing: 10) {
                ProgressView()
                if let caption {
                    Text(caption)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.top, 28)
            .opacity(showsSpinner ? 1 : 0)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
        // Fast loads never show a spinner; slow ones get one after a beat.
        .task {
            try? await Task.sleep(for: .milliseconds(700))
            withAnimation(.easeIn(duration: 0.25)) { showsSpinner = true }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(caption.map { _ in ConnectionCopy.connecting(to: app.name) } ?? "Opening \(app.name)")
    }
}
