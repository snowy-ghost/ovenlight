import Combine
import SwiftUI

/// Shared look: icon shapes, glass surfaces and small reusable pieces. Everything here is
/// presentation only.
enum Design {
    /// The corner radius Apple's icon grid uses, as a fraction of the icon's side.
    static let iconCornerFraction: CGFloat = 0.2237

    static func iconShape(size: CGFloat) -> RoundedRectangle {
        RoundedRectangle(cornerRadius: size * iconCornerFraction, style: .continuous)
    }
}

extension Font {
    /// Ovenlight's own voice: New York, for the wordmark, titles and monograms. Anything
    /// tappable stays SF Pro, so the app still reads as iOS.
    static func display(_ style: TextStyle, weight: Weight = .semibold) -> Font {
        .system(style, design: .serif, weight: weight)
    }
}

/// A small dot that sits on an offline app's icon corner, ringed so it reads on any icon.
struct OfflineBadge: View {
    var body: some View {
        Circle()
            .fill(Color(.systemGray))
            .frame(width: 11, height: 11)
            .padding(2.5)
            .background(Circle().fill(Color(.systemBackground)))
            .accessibilityHidden(true)
    }
}

/// An app's icon: its cached image, or a monogram on its theme color.
struct AppIconView: View {
    let app: WebApp
    let image: UIImage?
    var size: CGFloat = 64

    var body: some View {
        let shape = Design.iconShape(size: size)
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                let base = Color(hex: app.themeColorHex) ?? .ember
                ZStack {
                    LinearGradient(colors: [base.mix(with: .white, by: 0.18), base],
                                   startPoint: .top, endPoint: .bottom)
                    Text(String(app.name.prefix(1)).uppercased())
                        .font(.system(size: size * 0.5, weight: .semibold, design: .serif))
                        .foregroundStyle(.white)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .overlay(shape.strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5))
        .accessibilityHidden(true)
    }
}

/// The oven light: the Dynamic Island is the lamp, so nothing is drawn for it. A cone of
/// warm light starts behind the island and falls across the screen, with a soft halo where
/// it leaves. In landscape it pours sideways from whichever edge the island is on. The lock
/// screen, the privacy cover and the launcher are the same room lit to different levels, so
/// moving between them only brightens or dims the light.
///
/// iOS doesn't say where the island is, but the island, like the notch before it, sits at
/// the center of the top edge. The light starts there and is soft at its source, so it
/// needs no island measurements, and screenshots, which leave the island out, show only
/// warm light. A screen whose status bar runs down one side instead (the iPhone Duo's cover)
/// takes the light from that side.
struct LampLight: View {
    /// 0 is off, 1 is the lock screen asking for Face ID.
    var level: Double

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var source = LightSource.current
    /// The side a status bar runs down, if it does.
    @State private var statusBarSide: LightSource?

    var body: some View {
        // Drawn on a square as long as the screen, so turning the phone never changes the
        // canvas's shape: one that did would be drawn for the new orientation but cropped to
        // the old shape, a hard edge of light, until the turn ends.
        GeometryReader { geo in
            let side = max(geo.size.width, geo.size.height)
            LightCone(level: level, source: statusBarSide ?? source, visible: geo.size)
                .frame(width: side, height: side)
        }
        .ignoresSafeArea()
        .onGeometryChange(for: LightSource?.self) { LightSource.statusBarSide($0.safeAreaInsets) } action: { statusBarSide = $0 }
        // The square hangs off the screen's right or bottom edge; right to left, it would hang
        // off the left instead and take the light with it, and the insets' sides would swap.
        .environment(\.layoutDirection, .leftToRight)
        // Follows the phone as it turns. A half turn between the two landscapes keeps the
        // screen's size, so only the scene's orientation shows it.
        .onReceive(LightSource.changes) { source = $0 }
        .animation(reduceMotion ? nil : .smooth, value: level)
        .lampWarmUp()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The screen edge the island is on.
enum LightSource {
    case top, left, right

    /// In landscape the top of the phone, and its island, is on the side the phone was
    /// turned toward. Physical sides, so right-to-left languages don't flip the light.
    init(_ orientation: UIInterfaceOrientation?) {
        switch orientation {
        case .landscapeRight: self = .left
        case .landscapeLeft: self = .right
        default: self = .top
        }
    }

    @MainActor private static var scene: UIWindowScene? {
        UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene
    }

    @MainActor static var current: LightSource { LightSource(scene?.effectiveGeometry.interfaceOrientation) }

    /// With no top inset and a wider one at one side, the status bar runs down that side.
    static func statusBarSide(_ insets: EdgeInsets) -> LightSource? {
        guard insets.top == 0, insets.leading != insets.trailing else { return nil }
        return insets.trailing > insets.leading ? .right : .left
    }

    /// The scene's geometry is observable, and changes as soon as the interface turns.
    @MainActor static var changes: AnyPublisher<LightSource, Never> {
        guard let scene else { return Empty().eraseToAnyPublisher() }
        return scene.publisher(for: \.effectiveGeometry)
            .map { LightSource($0.interfaceOrientation) }
            .removeDuplicates()
            .eraseToAnyPublisher()
    }
}

/// The cone and its halo. Animatable, so a change of level brightens or dims it smoothly.
private struct LightCone: View, Animatable {
    var level: Double
    let source: LightSource
    /// The screen's size, at the canvas's top left corner.
    let visible: CGSize

    @Environment(\.colorScheme) private var colorScheme

    var animatableData: Double {
        get { level }
        set { level = newValue }
    }

    var body: some View {
        let dark = colorScheme == .dark
        // The light's core where it leaves the island, amber as it falls. Opacities are set
        // per appearance at the launcher's level of 0.6, and scale with the level.
        let lit = level / 0.6
        let sideways = source != .top
        Canvas { context, _ in
            // Turn the canvas so the light always runs down the drawing's own y axis.
            let across = sideways ? visible.height : visible.width
            let along = sideways ? visible.width : visible.height
            switch source {
            case .top: break
            case .left:
                context.translateBy(x: 0, y: visible.height)
                context.rotate(by: .degrees(-90))
            case .right:
                context.translateBy(x: visible.width, y: 0)
                context.rotate(by: .degrees(90))
            }
            // The island's middle, which the light leaves from.
            let origin = CGPoint(x: across / 2, y: 30)
            // The cone: about the island's width where it starts, past both edges at the far
            // end. Softer sideways, where a defined beam would cut through the labels.
            let start = sideways ? 132.0 : 96.0
            let spread = across * (sideways ? 0.30 : 0.22)
            var cone = Path()
            cone.move(to: CGPoint(x: origin.x - start / 2, y: origin.y))
            cone.addLine(to: CGPoint(x: origin.x + start / 2, y: origin.y))
            cone.addLine(to: CGPoint(x: across + spread, y: along))
            cone.addLine(to: CGPoint(x: -spread, y: along))
            cone.closeSubpath()
            var beam = context
            beam.addFilter(.blur(radius: sideways ? 58 : 26))
            beam.fill(cone, with: .linearGradient(
                Gradient(stops: [.init(color: Color.lampCore.opacity(min((dark ? 0.62 : 0.8) * lit, 1)), location: 0),
                                 .init(color: Color.lampGlow.opacity((dark ? 0.30 : 0.34) * lit), location: 0.48),
                                 .init(color: Color.lampGlow.opacity(0), location: 1)]),
                startPoint: origin, endPoint: CGPoint(x: origin.x, y: along)))
            // A soft halo where it leaves.
            let halo = 150.0
            context.fill(Path(ellipseIn: CGRect(x: origin.x - halo, y: origin.y - halo, width: 2 * halo, height: 2 * halo)),
                         with: .radialGradient(
                            Gradient(stops: [.init(color: Color.lampCore.opacity((dark ? 0.55 : 0.42) * lit), location: 0),
                                             .init(color: Color.lampGlow.opacity(0.18 * lit), location: 0.4),
                                             .init(color: Color.lampGlow.opacity(0), location: 1)]),
                            center: origin, startRadius: 0, endRadius: halo))
        }
    }
}

extension View {
    /// At least 44 points tall, all of it taking the tap: for a plain text button's label.
    func minimumHitArea() -> some View {
        frame(minHeight: 44).contentShape(Rectangle())
    }

    /// Says a status to VoiceOver as it appears or changes, since VoiceOver otherwise reads
    /// only what has focus. Nil says nothing.
    func announcing(_ status: String?) -> some View {
        onChange(of: status) { _, status in
            if let status { AccessibilityNotification.Announcement(status).post() }
        }
    }

    /// Fades the lamp's light in once per launch, like an oven light coming on over the
    /// plain launch screen. Every glow shares that one fade, so the privacy cover, lock
    /// screen and launcher can swap without the light dimming.
    func lampWarmUp() -> some View { modifier(LampWarmUp()) }
}

private struct LampWarmUp: ViewModifier {
    private static let duration: TimeInterval = 0.9
    /// When the lamp came on. A glow that shows later, say in the lock screen's own window,
    /// joins the fade where it is rather than starting over.
    @MainActor private static var litAt: TimeInterval?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var level = LampWarmUp.progress

    @MainActor private static var progress: Double {
        guard let litAt else { return 0 }
        return min(1, (ProcessInfo.processInfo.systemUptime - litAt) / duration)
    }

    func body(content: Content) -> some View {
        content
            .opacity(level)
            .onAppear {
                if Self.litAt == nil { Self.litAt = ProcessInfo.processInfo.systemUptime }
                let remaining = (1 - Self.progress) * Self.duration
                withAnimation(reduceMotion || remaining <= 0 ? nil : .linear(duration: remaining)) { level = 1 }
            }
    }
}

/// A rounded, colored symbol like the ones beside rows in Settings.
struct SettingsSymbol: View {
    let systemName: String
    let color: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 29, height: 29)
            .background(color.gradient, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .accessibilityHidden(true)
    }
}

extension View {
    /// Liquid Glass on iOS 26, a material with a hairline and soft shadow before it.
    /// `interactive` is for controls: the glass reacts to touch.
    @ViewBuilder
    func ovenlightGlass<S: Shape>(in shape: S, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            self
                .background(.regularMaterial, in: shape)
                .overlay(shape.stroke(Color.primary.opacity(0.08), lineWidth: 0.5))
                .shadow(color: .black.opacity(0.15), radius: 10, y: 4)
        }
    }
}

extension View {
    /// Grouped lists and forms on `Color.groupedBackground`, their rows' values in
    /// `Color.secondaryText`.
    func ovenlightGroupedBackground() -> some View {
        scrollContentBackground(.hidden).background(Color.groupedBackground)
            .labeledContentStyle(SecondaryValueStyle())
    }

    /// The rows to match, for each section (or loose row) of a list that uses
    /// `ovenlightGroupedBackground()`. Not on a section whose row sets its own background.
    func ovenlightRows() -> some View {
        listRowBackground(Color.groupedRow)
    }
}

/// A row's label and its value, the value in `Color.secondaryText`: the system's style
/// fades it below 4.5:1 on the warm rows. Beside the label while it fits, under it after.
private struct SecondaryValueStyle: LabeledContentStyle {
    func makeBody(configuration: Configuration) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline) {
                configuration.label
                Spacer(minLength: 12)
                configuration.content.foregroundStyle(Color.secondaryText)
            }
            VStack(alignment: .leading, spacing: 2) {
                configuration.label
                configuration.content.foregroundStyle(Color.secondaryText)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// `.borderedProminent`, its label in `onEmber`.
struct OvenlightProminentButtonStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button(configuration)
            .buttonStyle(.borderedProminent)
            .foregroundStyle(Color.onEmber)
    }
}

extension PrimitiveButtonStyle where Self == OvenlightProminentButtonStyle {
    static var ovenlightProminent: OvenlightProminentButtonStyle { OvenlightProminentButtonStyle() }
}

/// A warning: the symbol in orange, the words in secondary text, since orange text is too
/// faint to read on a light background. The secondary label color rather than `.secondary`,
/// which in a form footer, already secondary, would fade the words a level further.
struct WarningLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        Label {
            configuration.title.foregroundStyle(Color(.secondaryLabel))
        } icon: {
            configuration.icon.foregroundStyle(.orange)
        }
    }
}

extension LabelStyle where Self == WarningLabelStyle {
    static var warning: WarningLabelStyle { WarningLabelStyle() }
}

/// Dims on press, the way Home Screen icons do.
struct TilePressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.55 : 1)
            .animation(.easeOut(duration: configuration.isPressed ? 0.05 : 0.2), value: configuration.isPressed)
    }
}
