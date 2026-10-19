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
    @ObservedObject private var network = NetworkPath.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let welcome = registry.apps.isEmpty && !node.ownerEnabled && node.memberships.memberships.isEmpty
        VStack(spacing: 0) {
            HomeTopBar(isEditing: $isEditing, showsLock: !welcome, showsAddApp: node.ownerEnabled, showsEditApps: !registry.apps.isEmpty,
                       lock: { lock.lock() }, addApp: { router.present(.addApp) }, settings: { router.present(.settings) }) {
                // The owner's own connection only once they set Ovenlight up for their machines.
                TailnetStatusPill(state: node.ownerEnabled ? node.state : .idle, offline: !network.isSatisfied) {
                    Task { await node.owner.restart() }
                }
            } menuItems: {
                LauncherShareMenuItems()
            }
            // Stops growing where the system's bars do.
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            TailnetBanner(state: node.ownerEnabled ? node.state : .idle, signedInBefore: node.owner.magicDNSSuffixSeen != nil,
                          connect: { router.present(.login($0)) }, approve: { router.present(.approve) })
            PendingInvitesBanner { router.present(.join(JoinRequest(invite: .success($0)))) }
                .padding(.horizontal, 26)
            if welcome {
                LauncherWelcome { router.present(.join(JoinRequest())) }
            } else {
            HomeScreenGrid(apps: registry.apps, ownerEnabled: node.ownerEnabled, isEditing: $isEditing,
                           isOffline: { node.isOffline($0) },
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
            // Any larger and names break inside words.
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            }
        }
        .background(HomeWallpaper())
        .animation(.smooth, value: node.state)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshSharing() } }
        }
        // Which apps can be shared follows the owner's computers: ask again once the connection
        // is up (the ask at launch usually comes first) and whenever a computer comes or goes.
        .onChange(of: node.ownerIsConnected ? node.ownersOnlineHosts : nil) { _, hosts in
            if hosts != nil { Task { await refreshSharing(force: true) } }
        }
        // And as discovery adds or drops the owner's apps: at the first connection the node is
        // ready before they're found, and the ask then had nothing to ask about.
        .onChange(of: Set(registry.apps.filter { !$0.isShared && $0.discovered }.map(\.host))) {
            Task { await refreshSharing(force: true) }
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
                let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                if let app = renaming, !name.isEmpty { registry.rename(app.id, to: name) }
            }
        }
        .alert(clearing.map { "Clear \($0.name)’s Data?" } ?? "", isPresented: isPresent($clearing),
               presenting: clearing) { app in
            Button("Cancel", role: .cancel) {}
            Button("Clear App Data", role: .destructive) { Task { await registry.clearData(app.id) } }
        } message: { _ in
            Text("This deletes the app’s cookies and storage on this \(ConnectionCopy.device).")
        }
        .alert(removing.map { "Remove \($0.name)?" } ?? "", isPresented: isPresent($removing),
               presenting: removing) { app in
            Button("Cancel", role: .cancel) {}
            Button("Remove App", role: .destructive) {
                removals += 1
                Task { await registry.remove(app.id) }
            }
        } message: { app in
            Text(app.sharedBy.map(ConnectionCopy.removeSharedDetail)
                 ?? "This removes the app from Ovenlight and deletes everything it stored on this \(ConnectionCopy.device)."
                 + (app.discovered ? " It won’t come back on its own. Add it again with Add App." : ""))
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
        let plan = AppMenuPlan.plan(for: app, admin: info.map { ($0.app, $0.machine.guests) },
                                    ownersApps: registry.apps.filter { $0.isShared && $0.membershipID == app.membershipID }.count)
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
                        if let info { Text(AppMenuPlan.notShareableDetail(slug: info.app.slug)) }
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
                Label("Clear App Data", systemImage: "eraser")
            }
            Button(role: .destructive) { removing = app } label: {
                Label("Remove App", systemImage: "trash")
            }
        }
    }

    private func membership(of app: WebApp) -> Membership? {
        node.memberships.memberships.first { $0.id == app.membershipID }
    }

    /// The owner's guests and shareable apps, for the menus. `force` asks even when the last
    /// answer is recent, since a computer came or went since.
    private func refreshSharing(force: Bool = false) async {
        #if DEBUG
        if SharingFixture.isOn { return sharing.useFixture(SharingFixture.machines(for: registry.apps)) }
        #endif
        guard node.ownerIsConnected, registry.apps.contains(where: { !$0.isShared && $0.discovered }) else { return }
        if force { await sharing.refresh(apps: registry.apps) } else { await sharing.refreshIfStale(apps: registry.apps) }
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
    /// Connected before and not signed out since: the sign-in ended (it expired, or the phone
    /// was removed from the account), which the card says rather than starting over.
    let signedInBefore: Bool
    let connect: (URL) -> Void
    let approve: () -> Void

    static func signIn(before: Bool) -> (title: String, message: String, button: String) {
        before ? ("Sign In Again", "This phone’s Tailscale sign-in ended. Sign in again to reach your computers.", "Sign In")
            : ("Connect Your Computers", "Ovenlight connects privately to your own computers with your Tailscale account, without a VPN.", "Connect")
    }

    var body: some View {
        switch state {
        case .needsLogin(let url):
            let copy = Self.signIn(before: signedInBefore)
            card {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        SettingsSymbol(systemName: "network", color: .ember)
                        Text(copy.title)
                            .font(.headline)
                            .accessibilityAddTraits(.isHeader)
                    }
                    Text(copy.message)
                        .font(.subheadline)
                        .foregroundStyle(Color.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                    Button { connect(url) } label: {
                        Text(copy.button)
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.ovenlightProminent)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                }
            }
        case .awaitingApproval:
            card { OwnerApprovalPrompt(approve: approve) }
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

    let approve: () -> Void
    @EnvironmentObject private var node: NodeManager

    var body: some View {
        let name = Text(node.deviceName).bold()
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                SettingsSymbol(systemName: "checkmark.shield", color: .ember)
                Text("Approve This \(ConnectionCopy.device)")
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
            }
            Text("Your Tailscale network asks you to approve each new device once. On the Machines page, find \(name), open its ••• menu and choose Approve. Ovenlight connects as soon as you do.")
                .font(.subheadline)
                .foregroundStyle(Color.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: approve) {
                Text("Open Machines Page")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.ovenlightProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
        }
    }
}

/// Tailscale's sign-in page, or its Machines page for approving this iPhone, inside Ovenlight
/// rather than Safari, whose cookies are separate, so approving can use the sign-in. RootView
/// dismisses the sign-in page once the node connects, which with device approval means once
/// the iPhone is approved. The Machines page stays until Done.
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
    var isOffline = false
    /// Editing the Home Screen: a tap doesn't open the app.
    var isEditing = false

    var iconSize: CGFloat = 64

    var body: some View {
        VStack(spacing: HomeGridLayout.labelGap) {
            AppIconView(app: app, image: registry.icon(for: app), size: iconSize)
                .launchSource(for: app, in: namespace, iconSize: iconSize)
                .opacity(isOffline ? 0.5 : 1)
                .overlay(alignment: .topTrailing) {
                    if isOffline { OfflineBadge().offset(x: 4, y: -4) }
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
        .accessibilityValue([app.isNew ? "New" : nil, isOffline ? "Offline" : nil].compactMap { $0 }.joined(separator: ", "))
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
                    .foregroundStyle(Color.secondaryText)
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
    static let insecureWarning = "Ovenlight only opens secure addresses that start with https."
    @EnvironmentObject private var registry: AppRegistry
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
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
                            .announcing(isInsecure ? Self.insecureWarning : nil)
                    } footer: {
                        if isInsecure {
                            Label(Self.insecureWarning, systemImage: "exclamationmark.triangle.fill")
                                .labelStyle(.warning)
                        } else if refused {
                            Label(AppAddress.notYourComputer, systemImage: "exclamationmark.triangle.fill")
                                .labelStyle(.warning)
                        } else {
                            Text("Ovenlight fetches the app’s name and icon from its web manifest. Each app gets its own private storage.")
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
                            Button("Approve This \(ConnectionCopy.device)") { router.present(.approve) }
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
                        Button("Add", action: add).disabled(AppAddress.url(from: text, completingWith: ownerSuffix) == nil)
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
            refused = AppAddress.url(from: text, completingWith: ownerSuffix) != nil
            // The refusal shows under the field, away from VoiceOver's focus.
            if refused { AccessibilityNotification.Announcement(AppAddress.notYourComputer).post() }
            return
        }
        let listed = Set(registry.apps.map(\.id))
        let app = registry.add(url: url)
        // One already here opens instead.
        if listed.contains(app.id) { return router.open(app) }
        // An app discovery already found (one removed, then added back) joins it at once.
        Task { await registry.mergeDiscovered(node.discovered) }
        dismiss()
    }
}

struct SettingsView: View {
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var registry: AppRegistry
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var lock: LockManager
    @Environment(\.dismiss) private var dismiss
    @AppStorage(LockManager.lockAfterKey) private var lockAfterSeconds = LockManager.defaultLockAfter
    @Environment(\.dynamicTypeSize) private var typeSize
    /// Sign Out, or Stop Using My Own Computers with apps to remove, asks first.
    @State private var confirming = false
    @State private var mail: MailDraft?
    @State private var showingPrivacyPolicy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // The menu's value would be cut short at the largest sizes; a page of its own has the room.
                    if typeSize.isAccessibilitySize {
                        lockAfterPicker.pickerStyle(.navigationLink)
                    } else {
                        lockAfterPicker
                    }
                } footer: {
                    if lock.noPasscode {
                        Text("Ovenlight can’t lock until this \(ConnectionCopy.device) has a passcode. Set one in the Settings app.")
                    } else {
                        Text("How long Ovenlight can stay in the background before it locks.")
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
                                Button("Approve This \(ConnectionCopy.device)") { router.present(.approve) }
                            }
                            if signedIn {
                                Button("Sign Out", role: .destructive) { confirming = true }
                            }
                        }
                        // While no account is signed in, "Use My Own Computers" can still be taken back.
                        if node.ownerHasNoAccount {
                            Button("Stop Using My Own Computers") {
                                if registry.apps.contains(where: { !$0.isShared && $0.discovered }) {
                                    confirming = true
                                } else {
                                    stopUsingMyOwnComputers()
                                }
                            }
                        }
                    }
                    .ovenlightRows()
                    .confirmationDialog(signedIn ? "Sign Out of Your Computers?" : "Stop Using Your Own Computers?",
                                        isPresented: $confirming, titleVisibility: .visible) {
                        Button(signedIn ? "Sign Out" : "Stop Using My Own Computers", role: .destructive) { stopUsingMyOwnComputers() }
                    } message: {
                        Text("This removes the apps found on your computers and everything they stored on this \(ConnectionCopy.device).")
                    }
                    Section {
                        NavigationLink("Advanced") { AdvancedSettingsView() }
                    }
                    .ovenlightRows()
                }
                Section("Support") {
                    Button { mail = .contactUs() } label: {
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

    private var lockAfterPicker: some View {
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
    }

    /// Sign Out shows while an account is signed in, Stop Using My Own Computers before one.
    private var signedIn: Bool { node.status?.account != nil }

    private var computersSummary: String {
        if node.state == .ready, let account = node.status?.account {
            return "Connected as \(account.displayName.isEmpty ? account.loginName : account.displayName)"
        }
        return node.state.summary
    }

    /// Sign Out and Stop Using My Own Computers, one action (see `NodeManager.disableOwner`).
    private func stopUsingMyOwnComputers() {
        Task { await node.disableOwner() }
        dismiss()
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
                LabeledContent("This \(ConnectionCopy.device)", value: node.deviceName)
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
