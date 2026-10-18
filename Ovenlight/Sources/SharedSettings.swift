import SwiftUI

/// Settings rows for sharing: apps others shared with this iPhone (with Leave), and the
/// way in for someone who hasn't set up their own machines. The owner shares from the
/// Home Screen: each app's menu, and People & Sharing in the More menu.
struct SharedSettingsSections: View {
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var registry: AppRegistry
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var guests = GuestManager.shared
    @State private var leaving: Membership?

    var body: some View {
        let memberships = node.memberships.memberships
        if !memberships.isEmpty {
            Section {
                ForEach(memberships) { membership in
                    let apps = registry.apps.filter { $0.membershipID == membership.id }
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(membership.ownerName)
                            Text(apps.isEmpty ? joinDetail(membership) : ListFormatter.localizedString(byJoining: apps.map(\.name)))
                                .font(.caption)
                                .foregroundStyle(Color.secondaryText)
                        }
                        Spacer()
                        Button("Leave", role: .destructive) { leaving = membership }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(ConnectionCopy.leaveApps(membership.ownerName))
                    }
                }
            } header: {
                Text("Shared with You")
            } footer: {
                Text("Leaving removes their apps and everything they stored on this iPhone.")
            }
            .ovenlightRows()
            .leaveConfirmation($leaving)
        }
        if !node.ownerEnabled {
            Section {
                Button("Use My Own Computers") {
                    node.enableOwner()
                    dismiss()
                }
            } footer: {
                Text("Open apps that run on your own computers. You’ll sign in once to connect them.")
            }
            .ovenlightRows()
        }
    }

    /// A membership with no apps yet: the app its invite is for, and how joining it went, as
    /// the join screen says it.
    private func joinDetail(_ membership: Membership) -> String {
        guard let invite = membership.pendingClaims.first?.invite else { return "Joining…" }
        if case .failed(let error) = guests.phases[invite.invite] {
            return error.title(owner: membership.ownerName, app: invite.name)
        }
        return "\(invite.name), joining…"
    }
}
