import SwiftUI

/// The launcher's only controls, small and in the top corners so the icons stay the
/// point: Lock and a menu (Add App, Edit Apps, Settings) normally; Add App and Done
/// while editing, where the Home Screen puts its own. Ovenlight's connection state sits
/// between them while it matters.
struct HomeTopBar<Status: View, MenuItems: View>: View {
    @Binding var isEditing: Bool
    let lock: () -> Void
    let addApp: () -> Void
    let settings: () -> Void
    @ViewBuilder let status: Status
    /// More menu entries, above Settings.
    @ViewBuilder let menuItems: MenuItems

    var body: some View {
        HStack(spacing: 8) {
            if isEditing {
                GlassIconButton(systemName: "plus", label: "Add App", action: addApp)
            } else {
                GlassIconButton(systemName: "lock", label: "Lock Ovenlight", action: lock)
            }
            Spacer(minLength: 0)
            if !isEditing { status }
            Spacer(minLength: 0)
            if isEditing {
                Button { withAnimation { isEditing = false } } label: {
                    Text("Done")
                        .font(.body.weight(.semibold))
                        .padding(.horizontal, 16)
                        .frame(minHeight: 44)
                        .ovenlightGlass(in: Capsule(), interactive: true)
                }
                .buttonStyle(.plain)
            } else {
                Menu {
                    Button(action: addApp) { Label("Add App", systemImage: "plus") }
                    Button { withAnimation { isEditing = true } } label: {
                        Label("Edit Apps", systemImage: "apps.iphone")
                    }
                    menuItems
                    Divider()
                    Button(action: settings) { Label("Settings", systemImage: "gearshape") }
                } label: {
                    GlassIconLabel(systemName: "ellipsis")
                }
                // A menu tints its label with the accent; match the Lock button beside it.
                .tint(.primary)
                .accessibilityLabel("More")
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 52)
        .animation(.smooth(duration: 0.25), value: isEditing)
    }
}

struct GlassIconButton: View {
    let systemName: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) { GlassIconLabel(systemName: systemName) }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
    }
}

struct GlassIconLabel: View {
    let systemName: String

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(.primary)
            .frame(width: 44, height: 44)
            .ovenlightGlass(in: Circle(), interactive: true)
            .contentShape(Circle())
    }
}

/// Connecting, or a failure with a retry, in a small capsule between the corner controls.
/// Nothing when connected or off; signing in and approval have a card instead.
struct TailnetStatusPill: View {
    let state: NodeState
    let retry: () -> Void

    var body: some View {
        switch state {
        case .starting, .connecting, .retrying:
            HStack(spacing: 7) {
                ProgressView().controlSize(.small)
                Text(ConnectionCopy.connecting)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 34)
            .ovenlightGlass(in: Capsule())
            .accessibilityElement(children: .combine)
            .transition(.opacity.combined(with: .scale(scale: 0.9)))
        case .failed(let message):
            Button(action: retry) {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    ViewThatFits {
                        Text("\(message) \(Text("Try Again").foregroundStyle(.tint))")
                        Text("Try Again").foregroundStyle(.tint)
                    }
                    .lineLimit(1)
                }
                .font(.footnote.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 14)
                .frame(minHeight: 34)
                .ovenlightGlass(in: Capsule(), interactive: true)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(message)
            .accessibilityHint("Tries to connect again")
        case .idle, .ready, .needsLogin, .awaitingApproval:
            EmptyView()
        }
    }
}

/// The Home Screen's edit-mode wiggle: a small, quick rotation with a slight bob, a little
/// out of step from icon to icon.
struct Jiggle: ViewModifier {
    let seed: Int
    let active: Bool

    private struct Pose {
        var angle: Double = 0
        var lift: CGFloat = 0
    }

    func body(content: Content) -> some View {
        // Each icon gets its own tempo and starting direction, so the grid doesn't move in step.
        let beat = 0.12 + Double(abs(seed) % 5) * 0.006
        let sign: Double = seed % 2 == 0 ? 1 : -1
        content.keyframeAnimator(initialValue: Pose(), repeating: active) { view, pose in
            view
                .rotationEffect(.degrees(pose.angle))
                .offset(y: pose.lift)
        } keyframes: { _ in
            KeyframeTrack(\.angle) {
                CubicKeyframe(1.6 * sign, duration: beat / 2)
                CubicKeyframe(-1.6 * sign, duration: beat)
                CubicKeyframe(0, duration: beat / 2)
            }
            KeyframeTrack(\.lift) {
                CubicKeyframe(-0.6, duration: beat)
                CubicKeyframe(0.4, duration: beat)
            }
        }
    }
}

/// With Reduce Motion on, editing icons stay still and get a quiet outline instead.
struct EditOutline: View {
    let iconSize: CGFloat

    var body: some View {
        let size = iconSize + 8
        Design.iconShape(size: size)
            .strokeBorder(Color.primary.opacity(0.28), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
            .frame(width: size, height: size)
            .offset(y: -4)
            .accessibilityHidden(true)
    }
}

/// The minus badge on an icon's corner while editing.
struct RemoveBadge: View {
    let name: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "minus")
                .font(.system(size: 12, weight: .heavy))
                .foregroundStyle(.primary)
                .frame(width: 24, height: 24)
                .background(Circle().fill(.regularMaterial))
                .overlay(Circle().strokeBorder(Color.primary.opacity(0.1), lineWidth: 0.5))
                .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
                .padding(8)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .padding(-8)
        .accessibilityLabel("Remove \(name)")
        .transition(.scale.combined(with: .opacity))
    }
}

/// The empty Home Screen's first slot: an outlined space to add an app into.
struct EmptyHomeSlot: View {
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: HomeGridLayout.labelGap) {
                Design.iconShape(size: size)
                    .strokeBorder(Color.primary.opacity(0.25), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                    .frame(width: size, height: size)
                    .overlay {
                        Image(systemName: "plus")
                            .font(.system(size: size * 0.36, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                Text("Add App")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .buttonStyle(TilePressStyle())
        .accessibilityHint("Adds an app by its address. Apps published with the Ovenlight connector on your computers show up on their own.")
    }
}

/// Behind the icons, the launcher's wallpaper: the oven's interior with its light on.
struct HomeWallpaper: View {
    var lampLevel = 0.6

    var body: some View {
        ZStack {
            Color.ovenInterior.ignoresSafeArea()
            LampLight(level: lampLevel)
        }
    }
}
