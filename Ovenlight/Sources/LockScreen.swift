import SwiftUI

/// Shown whenever Ovenlight is locked. Face ID starts on its own when Ovenlight becomes
/// active; the button is for trying again.
struct LockScreen: View {
    @EnvironmentObject private var lock: LockManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var failures = 0

    var body: some View {
        LockLayout(level: lampLevel) {
            Group {
                if let error = lock.lastError {
                    Label(error, systemImage: "exclamationmark.circle.fill")
                        .labelStyle(ErrorLabelStyle())
                } else {
                    Text("Locked")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .transition(.opacity)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 40)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(lock.lastError ?? "Ovenlight is locked")
            .animation(.easeInOut(duration: 0.2), value: lock.lastError)
            .keyframeAnimator(initialValue: CGFloat.zero, trigger: failures) { content, x in
                content.offset(x: x)
            } keyframes: { _ in
                let amount: CGFloat = reduceMotion ? 0 : 1
                KeyframeTrack {
                    CubicKeyframe(-9 * amount, duration: 0.07)
                    CubicKeyframe(8 * amount, duration: 0.09)
                    CubicKeyframe(-5 * amount, duration: 0.08)
                    CubicKeyframe(3 * amount, duration: 0.07)
                    CubicKeyframe(0, duration: 0.07)
                }
            }
        } controls: {
            unlockButton
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .padding(.horizontal, 40)
        }
        .sensoryFeedback(.error, trigger: failures)
        // A light click as Face ID starts looking.
        .sensoryFeedback(.impact(weight: .light, intensity: 0.6), trigger: lock.isAuthenticating) { _, looking in looking }
        .onChange(of: lock.lastError) { _, error in
            if error != nil { failures += 1 }
        }
    }

    /// Locked, the light is low; Face ID switches it on, the way you turn on an oven light
    /// to look inside, and it drops back when Face ID stops looking.
    private var lampLevel: Double {
        lock.isAuthenticating ? 1 : restingLampLevel
    }

    @ViewBuilder private var unlockButton: some View {
        if #available(iOS 26.0, *) {
            unlockButton(symbolColor: .ember)
                .foregroundStyle(.primary)
                .buttonStyle(.glass)
        } else {
            unlockButton(symbolColor: .white)
                .buttonStyle(.borderedProminent)
        }
    }

    private func unlockButton(symbolColor: Color) -> some View {
        Button {
            Task { await lock.unlock() }
        } label: {
            Label {
                Text("Unlock with \(lock.biometry.name)")
            } icon: {
                Image(systemName: lock.biometry.symbol)
                    .foregroundStyle(symbolColor)
            }
            .font(.headline)
            .frame(maxWidth: .infinity)
        }
    }
}

/// The light's level while the lock screen waits, and behind the privacy cover.
private let restingLampLevel = 0.35

/// Covers Ovenlight in the app switcher. It matches the waiting lock screen's layout and
/// light, so a cover that turns into the lock screen doesn't move or brighten.
struct PrivacyCover: View {
    var body: some View {
        LockLayout(level: restingLampLevel) { EmptyView() } controls: { EmptyView() }
    }
}

/// The wordmark on its rack near the top, clear of the Face ID prompt in the middle of the
/// screen, with any message under it; controls rest above the home indicator. The light is
/// the launcher's.
private struct LockLayout<Message: View, Controls: View>: View {
    let level: Double
    @ViewBuilder let message: Message
    @ViewBuilder let controls: Controls

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                // Scrolls only when the largest text sizes don't fit above the controls.
                ScrollView {
                    VStack(spacing: 14) {
                        // The message speaks for the whole block.
                        Text("Ovenlight")
                            .font(.display(.largeTitle))
                            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                            .accessibilityHidden(true)
                        OvenRack(width: 120)
                        message
                    }
                    .frame(maxWidth: .infinity)
                    // Higher at the largest sizes, so the message stays above Face ID.
                    .padding(.top, geo.size.height * (typeSize.isAccessibilitySize ? 0.06 : 0.16))
                }
                .scrollBounceBehavior(.basedOnSize)
                controls
                    .padding(.vertical, 20)
            }
        }
        .background { HomeWallpaper(lampLevel: level) }
    }
}

private struct ErrorLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            configuration.icon.foregroundStyle(.red)
            configuration.title.foregroundStyle(.secondary)
        }
        .font(.subheadline)
    }
}
