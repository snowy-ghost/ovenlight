import SwiftUI

/// Settings rows for sharing: apps others shared with this iPhone (with Leave), and the
/// way in for someone who hasn't set up their own machines. The owner shares from the
/// Home Screen: each app's menu, and People & Sharing in the More menu.
struct SharedSettingsSections: View {
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var registry: AppRegistry
    @Environment(\.dismiss) private var dismiss
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
                            Text(apps.isEmpty ? "Joining…" : ListFormatter.localizedString(byJoining: apps.map(\.name)))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Leave", role: .destructive) { leaving = membership }
                            .buttonStyle(.borderless)
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
                Text("Open apps that run on your own computers. You'll sign in once to connect them.")
            }
            .ovenlightRows()
        }
    }
}
