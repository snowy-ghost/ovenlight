import SafariServices
import SwiftUI

struct LauncherView: View {
    @EnvironmentObject private var registry: AppRegistry
    @EnvironmentObject private var lock: LockManager
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var node: NodeManager
    @State private var renaming: WebApp?
    @State private var renameText = ""
    @State private var clearing: WebApp?
    @State private var removing: WebApp?
    /// Counts removals the person confirmed, for their haptic; apps a share takes away don't.
    @State private var removals = 0
    @State private var isEditing = false
    @State private var mail: MailDraft?
    @State private var leaving: Membership?
    @ObservedObject private var sharing = OwnerSharing.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 0) {
            HomeTopBar(isEditing: $isEditing, lock: { lock.lock() }, addApp: { router.present(.addApp) },
                       settings: { router.present(.settings) }) {
                // The owner's own connection only once they set Ovenlight up for their machines.
                TailnetStatusPill(state: node.ownerEnabled ? node.state : .idle) { Task { await node.restart() } }
            } menuItems: {
                LauncherShareMenuItems()
            }
            TailnetBanner(state: node.ownerEnabled ? node.state : .idle) { router.present(.login($0)) }
            PendingInvitesBanner { router.present(.join(JoinRequest(invite: .success($0)))) }
                .padding(.horizontal, 26)
            if registry.apps.isEmpty && !node.ownerEnabled && node.memberships.memberships.isEmpty {
                LauncherWelcome { router.present(.join(JoinRequest())) }
            } else {
            HomeScreenGrid(apps: registry.apps, ownerEnabled: node.ownerEnabled, isEditing: $isEditing,
                           reachability: { node.reachability(of: $0) },
                           open: { router.open($0) },
                           move: { registry.move($0, to: $1) },
                           remove: { removing = $0 },
                           addApp: { router.present(.addApp) }) { app in
                menu(for: app)
            } preview: { app in
                AppPreviewCard(app: app, icon: registry.icon(for: app))
                    // The menu is open: bring its People count and Share up to date.
                    .task { if !app.isShared { await refreshSharing() } }
            }
            }
        }
        .background(HomeWallpaper())
        .animation(.smooth, value: node.state)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshSharing() } }
        }
        .task {
            await registry.refreshAllMissingIcons()
            await refreshSharing()
        }
        .mailComposer($mail)
        .leaveConfirmation($leaving)
        .alert("Rename App", isPresented: isPresent($renaming)) {
            TextField("Name", text: $renameText)
                .submitLabel(.done)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                if let app = renaming, !renameText.isEmpty { registry.rename(app.id, to: renameText) }
            }
        }
        .confirmationDialog(clearing.map { "Sign out of \($0.name)?" } ?? "", isPresented: isPresent($clearing),
                            titleVisibility: .visible, presenting: clearing) { app in
            Button("Sign Out and Clear Data", role: .destructive) { Task { await registry.clearData(app.id) } }
        } message: { _ in
            Text("This deletes the app's cookies, storage and offline data on this iPhone.")
        }
        .confirmationDialog(removing.map { "Remove \($0.name)?" } ?? "", isPresented: isPresent($removing),
                            titleVisibility: .visible, presenting: removing) { app in
            Button("Remove App", role: .destructive) {
                removals += 1
                Task { await registry.remove(app.id) }
            }
        } message: { _ in
            Text("This removes the app from Ovenlight and deletes everything it stored on this iPhone.")
        }
        .sensoryFeedback(.success, trigger: removals)
        .onChange(of: registry.apps.isEmpty) { _, empty in if empty { isEditing = false } }
    }

    @ViewBuilder
    private func menu(for app: WebApp) -> some View {
        #if DEBUG
        let app = SharingFixture.apply(to: app)
        #endif
        let info = sharing.info(for: app)
        let plan = AppMenuPlan.plan(for: app, admin: info.map { ($0.app, $0.machine.guests) })
        Section {
            Button { router.open(app) } label: { Label("Open", systemImage: "arrow.up.forward.app") }
            Button { renameText = app.name; renaming = app } label: { Label("Rename", systemImage: "pencil") }
            Button { Task { await registry.refreshMetadata(for: app.id) } } label: {
                Label("Refresh Icon", systemImage: "arrow.clockwise")
            }
        }
        if plan != AppMenuPlan() {
            Section {
                switch plan.share {
                case .available:
                    Button { if let info { router.present(.invite(info.machine, info.app)) } } label: { Label("Share…", systemImage: "square.and.arrow.up") }
                case .notShareable:
                    Button {} label: {
                        Label("Share…", systemImage: "square.and.arrow.up")
                        Text(AppMenuPlan.notShareableDetail)
                    }
                    .disabled(true)
                case .hidden:
                    EmptyView()
                }
                if let people = plan.people, let info {
                    Button { router.present(.people(machineID: info.machine.id, slug: info.app.slug)) } label: {
                        Label(AppMenuPlan.peopleTitle(people), systemImage: "person.2")
                    }
                }
                if plan.reportProblem {
                    Button { mail = .problemReport(for: app) } label: {
                        Label(ConnectionCopy.reportProblem, systemImage: "flag")
                    }
                }
                if let owner = plan.leaveOwner {
                    Button(role: .destructive) { leaving = membership(of: app) } label: {
                        Label(ConnectionCopy.leaveApps(owner), systemImage: "rectangle.portrait.and.arrow.right")
                    }
                }
            }
        }
        Section {
            Button { clearing = app } label: {
                Label("Sign Out and Clear Data", systemImage: "eraser")
            }
            Button(role: .destructive) { removing = app } label: {
                Label("Remove App", systemImage: "trash")
            }
        }
    }

    private func membership(of app: WebApp) -> Membership? {
        node.memberships.memberships.first { $0.id == app.membershipID }
    }

    /// The owner's guests and shareable apps, for the menus.
    private func refreshSharing() async {
        #if DEBUG
        if SharingFixture.isOn { return sharing.useFixture(SharingFixture.machines(for: registry.apps)) }
        #endif
        guard node.ownerIsConnected, registry.apps.contains(where: { !$0.isShared && $0.discovered }) else { return }
        await sharing.refreshIfStale(apps: registry.apps)
    }

    private func isPresent(_ item: Binding<WebApp?>) -> Binding<Bool> {
        Binding(get: { item.wrappedValue != nil }, set: { if !$0 { item.wrappedValue = nil } })
    }
}

/// The owner's sign-in or approval card, a widget above the apps until Ovenlight is
/// connected to the tailnet. The only place on the Home Screen that names Tailscale;
/// connecting and failures show in `TailnetStatusPill`.
struct TailnetBanner: View {
    let state: NodeState
    let connect: (URL) -> Void

    var body: some View {
        switch state {
        case .needsLogin(let url):
            card {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        SettingsSymbol(systemName: "network", color: .ember)
                        Text("Connect Your Computers")
                            .font(.headline)
                    }
                    Text("Ovenlight connects privately to your own computers with your Tailscale account, without a VPN.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button { connect(url) } label: {
                        Text("Connect")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                }
            }
        case .awaitingApproval:
            card { OwnerApprovalPrompt() }
        default:
            EmptyView()
        }
    }

    private func card(@ViewBuilder _ content: () -> some View) -> some View {
        content()
            .padding(18)
            .ovenlightGlass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .padding(.horizontal, 26)
            .padding(.top, 6)
            .padding(.bottom, 4)
            .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }
}

/// What the owner sees while their own node waits for approval (device approval is on in
/// their tailnet).
struct OwnerApprovalPrompt: View {
    /// Tailscale's device list, where a new device waits for approval.
    static let machinesPage = URL(string: "https://console.tailscale.com/admin/machines")!

    @EnvironmentObject private var node: NodeManager
    @Environment(\.openURL) private var openURL

    var body: some View {
        let name = Text(node.deviceName).bold()
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                SettingsSymbol(systemName: "checkmark.shield", color: .ember)
                Text("Approve This iPhone")
                    .font(.headline)
            }
            Text("Your Tailscale network asks you to approve each new device once. On the Machines page, this iPhone is listed as \(name). Ovenlight connects as soon as you approve it.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button { openURL(Self.machinesPage) } label: {
                Text("Open Machines Page")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
        }
    }
}

/// Tailscale's sign-in page, inside Ovenlight. RootView dismisses it once the node connects.
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

struct AppTile: View {
    @EnvironmentObject private var registry: AppRegistry
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let app: WebApp
    var namespace: Namespace.ID?
    /// Set when the app's machine isn't answering; the icon dims and gets a badge.
    var reachability: AppReachability?
    /// Editing the Home Screen: a tap doesn't open the app.
    var isEditing = false

    var iconSize: CGFloat = 64

    var body: some View {
        VStack(spacing: HomeGridLayout.labelGap) {
            AppIconView(app: app, image: registry.icon(for: app), size: iconSize)
                .launchSource(for: app, in: namespace, iconSize: iconSize)
                .opacity(reachability == .offline ? 0.5 : 1)
                .overlay(alignment: .topTrailing) {
                    if let reachability {
                        ReachabilityBadge(status: reachability).offset(x: 4, y: -4)
                    }
                }
                .shadow(color: .black.opacity(0.08), radius: 3, y: 1)
            label
                .font(.caption)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
                .multilineTextAlignment(.center)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            if let owner = app.sharedBy {
                SharedByCaption(owner: owner).padding(.top, -HomeGridLayout.labelGap + 1)
            }
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(app.sharedBy.map { "\(app.name), \(ConnectionCopy.sharedBy($0))" } ?? app.name)
        .accessibilityValue([app.isNew ? "New" : nil, reachability?.label].compactMap { $0 }.joined(separator: ", "))
        .accessibilityHint(isEditing ? "" : "Opens the app")
    }

    /// The name, after a blue dot while the app is new, as the Home Screen marks newly
    /// installed apps. The dot is inline text, so the label keeps its height.
    private var label: Text {
        guard app.isNew else { return Text(app.name) }
        return Text(Image(systemName: "circle.fill")).font(.system(size: 7)).foregroundStyle(.blue).baselineOffset(1)
            + Text("\u{2009}") + Text(app.name)
    }
}

/// The lifted card a long press shows: the app, bigger, with where it lives.
struct AppPreviewCard: View {
    let app: WebApp
    let icon: UIImage?

    var body: some View {
        VStack(spacing: 14) {
            AppIconView(app: app, image: icon, size: 88)
            VStack(spacing: 3) {
                Text(app.name)
                    .font(.headline)
                Text(app.sharedBy.map(ConnectionCopy.sharedBy) ?? app.host)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 36)
        .frame(minWidth: 240)
        .background(Color(.systemBackground))
    }
}

struct AddAppView: View {
    @EnvironmentObject private var registry: AppRegistry
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var text = ""
    /// Add was tapped with an address that isn't on the owner's own computers.
    @State private var refused = false
    @FocusState private var focused: Bool

    /// The owner's tailnet, the only place Add App opens apps from. Nil until they sign in.
    private var ownerSuffix: String? { node.ownerEnabled ? node.owner.magicDNSSuffixSeen : nil }
    private var isInsecure: Bool { text.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("http://") }

    var body: some View {
        NavigationStack {
            Form {
                if let ownerSuffix {
                    Section {
                        TextField("Address", text: $text, prompt: Text(verbatim: "my-app.\(ownerSuffix)"))
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.done)
                            .focused($focused)
                            .onSubmit(add)
                            .onChange(of: text) { refused = false }
                    } footer: {
                        if isInsecure {
                            Label("Ovenlight only opens secure addresses that start with https.", systemImage: "exclamationmark.triangle.fill")
                                .labelStyle(.warning)
                        } else if refused {
                            Label(AppAddress.notYourComputer, systemImage: "exclamationmark.triangle.fill")
                                .labelStyle(.warning)
                        } else {
                            Text("Ovenlight fetches the app's name and icon from its web manifest. Each app gets its own private storage.")
                        }
                    }
                    .ovenlightRows()
                } else {
                    Section {
                        Text(AppAddress.signInFirst)
                        if !node.ownerEnabled {
                            Button("Use My Own Computers") {
                                node.enableOwner()
                                dismiss()
                            }
                        } else if case .needsLogin(let url) = node.state {
                            Button("Sign In to Your Computers") { router.present(.login(url)) }
                        } else if node.state == .awaitingApproval {
                            Button("Approve This iPhone") { openURL(OwnerApprovalPrompt.machinesPage) }
                        }
                    }
                    .ovenlightRows()
                }
            }
            .ovenlightGroupedBackground()
            .navigationTitle("Add App")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                if ownerSuffix != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Add", action: add).disabled(AppAddress.url(from: text) == nil)
                    }
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
        .sensoryFeedback(.success, trigger: registry.apps.count) { old, new in new > old }
    }

    private func add() {
        guard let url = AppAddress.url(from: text, ownerSuffix: ownerSuffix) else {
            refused = AppAddress.url(from: text) != nil
            return
        }
        registry.add(url: url)
        dismiss()
    }
}

struct SettingsView: View {
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var lock: LockManager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @AppStorage(LockManager.lockAfterKey) private var lockAfterSeconds = LockManager.defaultLockAfter
    @State private var confirmingSignOut = false
    @State private var mail: MailDraft?
    @State private var showingPrivacyPolicy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(selection: $lockAfterSeconds) {
                        Text("Immediately").tag(0)
                        Text("After 1 Minute").tag(60)
                        Text("After 5 Minutes").tag(300)
                        Text("After 15 Minutes").tag(900)
                    } label: {
                        Label { Text("Require \(lock.biometry.name)") } icon: {
                            SettingsSymbol(systemName: lock.biometry.symbol, color: .green)
                        }
                    }
                } footer: {
                    if lock.noPasscode {
                        Text("Ovenlight can't lock until this iPhone has a passcode. Set one in the Settings app.")
                    } else {
                        Text("How long Ovenlight can stay in the background before it asks for \(lock.biometry.name) again.")
                    }
                }
                .ovenlightRows()
                SharedSettingsSections()
                if node.ownerEnabled {
                    Section {
                        if case .needsLogin(let url) = node.state {
                            Button("Sign In to Your Computers") { router.present(.login(url)) }
                        } else {
                            LabeledContent {
                                Text(computersSummary)
                            } label: {
                                Label { Text("Your Computers") } icon: {
                                    SettingsSymbol(systemName: "desktopcomputer", color: .indigo)
                                }
                            }
                            if node.state == .awaitingApproval {
                                Button("Approve This iPhone") { openURL(OwnerApprovalPrompt.machinesPage) }
                            }
                            if node.status?.account != nil {
                                Button("Sign Out", role: .destructive) { confirmingSignOut = true }
                            }
                        }
                        // While no account is signed in, "Use My Own Computers" can still be taken back.
                        if node.ownerHasNoAccount {
                            Button("Stop Using My Own Computers") {
                                Task { await node.disableOwner() }
                                dismiss()
                            }
                        }
                    }
                    .ovenlightRows()
                    .confirmationDialog("Sign out of your computers?", isPresented: $confirmingSignOut, titleVisibility: .visible) {
                        Button("Sign Out", role: .destructive) { Task { await node.signOut() } }
                    } message: {
                        Text("Your apps stay in Ovenlight; sign in again to open them.")
                    }
                    Section {
                        NavigationLink("Advanced") { AdvancedSettingsView() }
                    }
                    .ovenlightRows()
                }
                Section("Support") {
                    Button { mail = .contactUs(version: Bundle.main.versionString) } label: {
                        Label { Text("Contact Us") } icon: {
                            SettingsSymbol(systemName: "envelope.fill", color: .blue)
                        }
                    }
                    Button { showingPrivacyPolicy = true } label: {
                        Label { Text("Privacy Policy") } icon: {
                            SettingsSymbol(systemName: "hand.raised.fill", color: .gray)
                        }
                    }
                    NavigationLink { AcknowledgementsView() } label: {
                        Label { Text("Acknowledgements") } icon: {
                            SettingsSymbol(systemName: "doc.text.fill", color: .gray)
                        }
                    }
                }
                .tint(.primary)
                .ovenlightRows()
                Section {
                    LabeledContent("Version", value: Bundle.main.versionString)
                }
                .ovenlightRows()
            }
            .ovenlightGroupedBackground()
            .mailComposer($mail)
            .sheet(isPresented: $showingPrivacyPolicy) { SafariView(url: AppLinks.privacyPolicy).ignoresSafeArea() }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    private var computersSummary: String {
        if node.state == .ready, let account = node.status?.account {
            return "Connected as \(account.displayName.isEmpty ? account.loginName : account.displayName)"
        }
        return node.state.summary
    }
}

/// Connection details most people never need, for when something is wrong. The only
/// place Settings names Tailscale.
struct AdvancedSettingsView: View {
    @EnvironmentObject private var node: NodeManager

    var body: some View {
        Form {
            Section("Details") {
                LabeledContent("Status", value: node.state.summary)
                if let account = node.status?.account {
                    LabeledContent("Tailscale Account", value: account.loginName)
                }
                if let tailnet = node.status?.tailnet?.name, !tailnet.isEmpty {
                    LabeledContent("Tailnet", value: tailnet)
                }
                LabeledContent("This iPhone", value: node.deviceName)
            }
            .ovenlightRows()
        }
        .ovenlightGroupedBackground()
        .navigationTitle("Advanced")
        .navigationBarTitleDisplayMode(.inline)
    }
}

extension Bundle {
    var versionString: String {
        let version = object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version) (\(build))"
    }
}
