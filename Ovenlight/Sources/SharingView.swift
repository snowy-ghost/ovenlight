import CoreImage.CIFilterBuiltins
import ImageIO
import LocalAuthentication
import SwiftUI

/// Face ID (or the passcode) before anything that changes who can open an app. An iPhone
/// with no passcode has nothing to ask for, and Ovenlight already opens without one there
/// (see `LockManager`).
@MainActor
enum OwnerAuth {
    /// The prompt is up. It makes Ovenlight inactive, which is no reason for the privacy
    /// cover over the sheet that asked.
    private(set) static var isAsking = false

    static func confirm(_ reason: String) async -> Bool {
        isAsking = true
        defer {
            isAsking = false
            OverlayPresenter.ownerAuthEnded()
        }
        do {
            return try await LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        } catch {
            return LockManager.isPasscodeNotSet(error)
        }
    }
}

/// The launcher menu's entries for sharing: Join with Invite for everyone, and People &
/// Sharing (every app at once) for an owner whose connectors Ovenlight found.
struct LauncherShareMenuItems: View {
    @EnvironmentObject private var node: NodeManager
    @EnvironmentObject private var registry: AppRegistry
    @EnvironmentObject private var router: Router

    var body: some View {
        Button { router.present(.join(JoinRequest())) } label: {
            Label(ConnectionCopy.joinWithInvite, systemImage: "person.badge.plus")
        }
        if node.ownerIsConnected, registry.apps.contains(where: { !$0.isShared && $0.discovered }) {
            Button { router.present(.sharing) } label: { Label("People & Sharing", systemImage: "person.2") }
        }
    }
}

/// None of the owner's computers answered, and why, where a list would be.
struct SharingProblemView: View {
    let problem: String?

    var body: some View {
        ContentUnavailableView("No Computers Answered", systemImage: "desktopcomputer.trianglebadge.exclamationmark",
                               description: Text(problem ?? "Your computers may be asleep or offline. Wake one, or run ovenlight doctor on it."))
            .ovenlightRows()
    }
}

/// A failure in a sharing list's row or footer.
struct ProblemLabel: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Label(text, systemImage: "exclamationmark.triangle.fill").labelStyle(.warning)
    }
}


/// The owner's controls for their connectors: apps and who they're shared with, and
/// feedback. Admin credentials never leave the machines; Ovenlight asks each connector
/// over the owner's own node.
struct SharingList: View {
    @EnvironmentObject private var registry: AppRegistry
    @ObservedObject private var sharing = OwnerSharing.shared
    @State private var showingPricing = false
    @AppStorage(FeedbackInbox.seenKey) private var feedbackSeenUntil: Double = 0

    private var feedbackCount: Int { FeedbackInbox.unseen(sharing.machines.flatMap(\.feedback), seenUntil: feedbackSeenUntil) }
    private var peopleCount: Int { sharing.machines.reduce(0) { $0 + GuestPerson.all($1.guests.guests).count } }

    var body: some View {
        List {
            if !sharing.isAvailable {
                ContentUnavailableView("Not Connected", systemImage: "network.slash",
                                       description: Text("Ovenlight needs to be connected to your computers to manage sharing."))
                    .ovenlightRows()
            } else if sharing.machines.isEmpty {
                if sharing.isLoading {
                    HStack { Spacer(); ProgressView(); Spacer() }.listRowBackground(Color.clear)
                } else {
                    SharingProblemView(problem: sharing.problem)
                }
            }
            ForEach(sharing.machines) { machine in
                Section {
                    ForEach(machine.apps) { app in
                        NavigationLink {
                            SharingAppView(machineID: machine.id, slug: app.slug)
                        } label: {
                            SharingAppRow(app: app)
                        }
                    }
                } header: {
                    Text(sharing.machines.count > 1 ? machine.adminHost : "Apps")
                } footer: {
                    if let problem = machine.problem { ProblemLabel(problem) }
                }
                .ovenlightRows()
            }
            if !sharing.machines.isEmpty {
                Section {
                    if peopleCount > 0 {
                        NavigationLink {
                            SharingPeopleList()
                        } label: {
                            LabeledContent {
                                Text("\(peopleCount)")
                            } label: {
                                Label("People", systemImage: "person.2")
                            }
                        }
                    }
                    NavigationLink {
                        FeedbackInbox()
                    } label: {
                        LabeledContent {
                            if feedbackCount > 0 { Text("\(feedbackCount)") }
                        } label: {
                            Label("Feedback", systemImage: "exclamationmark.bubble")
                        }
                    }
                } footer: {
                    if let tagged = Self.taggedDevices(sharing.machines) {
                        // The link opens Tailscale's pricing in a sheet, as Settings opens the privacy policy.
                        Text(LocalizedStringKey("\(tagged) [Learn More](\(AppLinks.tailscalePricing.absoluteString))"))
                            .environment(\.openURL, OpenURLAction { _ in
                                showingPricing = true
                                return .handled
                            })
                    }
                }
                .ovenlightRows()
            }
        }
        .ovenlightGroupedBackground()
        .navigationTitle("People & Sharing")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await sharing.refresh(apps: registry.apps) }
        .task { await sharing.refresh(apps: registry.apps) }
        .animation(.default, value: sharing.machines)
        .sheet(isPresented: $showingPricing) { SafariView(url: AppLinks.tailscalePricing).ignoresSafeArea() }
    }

    /// How many of the tailnet's tagged devices, which Tailscale's plans count, the owner's
    /// shareable apps and guests' devices take. Nil before a connector has counted them.
    static func taggedDevices(_ machines: [OwnerSharing.Machine]) -> String? {
        let counts = machines.compactMap(\.guests.taggedDevices)
        guard let all = counts.map(\.tailnet).max() else { return nil }
        let used = min(counts.reduce(0) { $0 + $1.apps + $1.guests }, all)
        guard used > 0 else { return nil }
        let share = used < all ? "\(used) of the \(all) tagged devices" : used == 1 ? "the only tagged device" : "all \(all) tagged devices"
        return "Your shareable apps and your guests’ devices use \(share) in your Tailscale network. Tailscale’s plans include a set number and charge for more."
    }
}

private struct SharingAppRow: View {
    let app: AdminApp

    var body: some View {
        HStack(spacing: 12) {
            AdminAppIcon(app: app)
            VStack(alignment: .leading, spacing: 2) {
                Text(app.name)
                Text(summary).font(.caption).foregroundStyle(Color.secondaryText)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var summary: String {
        let state = app.online ? "Online" : "Offline"
        guard app.shareable else { return "\(state) · Only you" }
        return "\(state) · " + (app.guests == 0 ? "Not shared yet" : app.guests == 1 ? "1 person" : "\(app.guests) people")
    }
}

/// A connector's app with its icon from the Home Screen, or a plain tile when it isn't there.
struct AdminAppIcon: View {
    let app: AdminApp
    @EnvironmentObject private var registry: AppRegistry

    var body: some View {
        let local = registry.apps.first { !$0.isShared && Discovery.normalizedHost($0.host) == app.host }
        let tile = local ?? WebApp(name: app.name, startURL: URL(string: app.url ?? "https://\(app.slug).invalid/")!)
        AppIconView(app: tile, image: local.flatMap(registry.icon(for:)), size: 36)
    }
}

/// One app: share it, and see and remove the people it's shared with.
struct SharingAppView: View {
    let machineID: String
    let slug: String

    @EnvironmentObject private var registry: AppRegistry
    @ObservedObject private var sharing = OwnerSharing.shared
    /// The composer gets the machine and app as they were when it opened, so no refresh can
    /// empty it while it shows an invite's link, which is shown only once.
    @State private var composing: Composing?
    @State private var removing: GuestPerson?
    @State private var error: String?
    @AppStorage(FeedbackInbox.seenKey) private var feedbackSeenUntil: Double = 0

    private var machine: OwnerSharing.Machine? { sharing.machines.first { $0.id == machineID } }
    private var app: AdminApp? { machine?.apps.first { $0.slug == slug } }

    struct Composing: Identifiable {
        let target: InviteComposer.Target
        let machine: OwnerSharing.Machine
        let app: AdminApp
        var id: String { target.id }
    }

    private func compose(_ target: InviteComposer.Target) {
        if let machine, let app { composing = Composing(target: target, machine: machine, app: app) }
    }

    var body: some View {
        List {
            // Above everything, so a failed Cancel or Remove says so even when the app or its
            // computer is gone from the list.
            if let error = error ?? machine?.problem {
                Section { ProblemLabel(error) }
                    .ovenlightRows()
            }
            if let app, let machine {
                let people = GuestPerson.all(machine.guests.guests.filter { $0.app == slug })
                let invites = machine.guests.invites.filter { $0.app == slug && $0.state == AdminInvite.sent }
                let feedback = FeedbackInbox.unseen(machine.feedback.filter { $0.app == slug }, seenUntil: feedbackSeenUntil)
                Section {
                    if app.shareable {
                        Button { compose(.anyone) } label: {
                            Label("Share \(app.name)…", systemImage: "square.and.arrow.up")
                        }
                    } else {
                        Text("\(app.name) is only for you.")
                    }
                } footer: {
                    if app.shareable {
                        Text("Each invite is a link that works once, on one phone, for 7 days. Guests reach only this app.")
                    } else {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("To share it, run this on its computer:")
                            Text(verbatim: "ovenlight publish --slug \(app.slug) --shareable")
                                .font(.footnote.monospaced())
                                .textSelection(.enabled)
                        }
                    }
                }
                .ovenlightRows()
                if app.shareable {
                    Section("People") {
                        if people.isEmpty {
                            Text("No one yet").foregroundStyle(Color.secondaryText)
                        }
                        ForEach(people) { person in
                            NavigationLink {
                                SharingPersonView(machineID: machine.id, personID: person.id)
                            } label: {
                                GuestPersonRow(name: person.name, detail: person.summary(since: machine.guests.openedSince))
                            }
                            .swipeActions {
                                Button("Remove", role: .destructive) { removing = person }
                                Button("Add Device") { compose(.person(person)) }.tint(.accentColor)
                            }
                            .contextMenu {
                                Button("Add a Device for \(person.name)", systemImage: "plus.rectangle.on.rectangle") { compose(.person(person)) }
                                Button("Remove \(person.name)", systemImage: "person.fill.xmark", role: .destructive) { removing = person }
                            }
                        }
                    }
                    .ovenlightRows()
                    if !invites.isEmpty {
                        Section {
                            ForEach(invites) { invite in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("For \(invite.to)")
                                    Text("\(invite.review == true ? "Review invite" : "Unused"), expires \(invite.expiresIn)")
                                        .font(.caption).foregroundStyle(Color.secondaryText)
                                }
                                .accessibilityElement(children: .combine)
                                .swipeActions {
                                    Button("Cancel Invite", role: .destructive) { Task { await cancel(invite) } }
                                }
                            }
                        } header: {
                            Text("Open Invites")
                        } footer: {
                            Text("Swipe to cancel an invite no one has used.")
                        }
                        .ovenlightRows()
                    }
                }
                Section {
                    NavigationLink {
                        FeedbackInbox(only: (machine.id, slug))
                    } label: {
                        LabeledContent {
                            if feedback > 0 { Text("\(feedback)") }
                        } label: {
                            Label("Feedback", systemImage: "exclamationmark.bubble")
                        }
                    }
                }
                .ovenlightRows()
            } else if let problem = sharing.problem {
                SharingProblemView(problem: problem)
            } else {
                ContentUnavailableView("App Not Found", systemImage: "questionmark.app")
                    .ovenlightRows()
            }
        }
        .ovenlightGroupedBackground()
        .navigationTitle(app?.name ?? "App")
        .announcing(error)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await sharing.refresh(apps: registry.apps) }
        .task { await sharing.refreshIfStale(apps: registry.apps) }
        .sheet(item: $composing) { InviteComposer(machine: $0.machine, app: $0.app, target: $0.target) }
        .alert(removing.map { "Remove \($0.name)?" } ?? "", isPresented: Binding(
            get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing) { person in
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) { Task { await remove(person) } }
        } message: { person in
            Text("\(person.name) can’t open \(app?.name ?? "the app") anymore, on any device. Each of their devices is also removed from your Tailscale network unless it has another of your apps.")
        }
    }

    private func remove(_ person: GuestPerson) async {
        guard let machine, await OwnerAuth.confirm("Remove \(person.name)") else { return }
        guard let client = sharing.client(for: machine) else {
            error = AdminError.unreachable.message
            return
        }
        defer { client.session.finishTasksAndInvalidate() }
        do {
            let result = try await client.removeGuest(person, app: slug)
            error = result.errors?.first
        } catch {
            show(error)
        }
        await sharing.refresh(apps: registry.apps)
    }

    private func cancel(_ invite: AdminInvite) async {
        guard let machine else { return }
        guard let client = sharing.client(for: machine) else {
            error = AdminError.unreachable.message
            return
        }
        defer { client.session.finishTasksAndInvalidate() }
        do {
            _ = try await client.cancelInvite(invite.id)
            error = nil
        } catch {
            show(error)
        }
        await sharing.refresh(apps: registry.apps)
    }

    /// As People shows it: the connector's words, or that the computer didn't answer.
    private func show(_ failure: Error) {
        error = (failure as? AdminError)?.message ?? AdminError.unreachable.message
    }
}

/// Who to invite, then the invite itself.
struct InviteComposer: View {
    /// Someone new or already shared with (`.anyone`), or another device for one person.
    enum Target: Identifiable {
        case anyone
        case person(GuestPerson)
        var id: String { if case .person(let p) = self { "person/\(p.id)" } else { "anyone" } }
    }

    let machine: OwnerSharing.Machine
    let app: AdminApp
    var target: Target = .anyone

    @EnvironmentObject private var registry: AppRegistry
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var working = false
    @State private var error: String?
    @State private var result: AdminShareResult?
    @FocusState private var focused: Bool

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var people: [GuestPerson] { GuestPerson.everyone(in: machine.guests) }
    private var choices: [GuestPerson] { GuestPerson.invitable(in: machine.guests) }

    /// Someone already shared with who has the typed name.
    private var namesake: GuestPerson? {
        people.first { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
    }

    /// Why Create waits, under the name, while an error isn't showing there instead.
    private var namesakeWarning: String? {
        guard let namesake, error == nil else { return nil }
        return choices.contains { $0.id == namesake.id }
            ? "\(namesake.name) is already in the list below. Choose them there, or use a name that tells them apart, like \(namesake.name) L."
            : "\(namesake.name) already has an invite. Use a name that tells them apart, like \(namesake.name) L."
    }

    var body: some View {
        if let result {
            InviteResultView(result: result, appName: app.name)
        } else {
            NavigationStack {
                Form {
                    if case .person(let person) = target {
                        Section {
                            LabeledContent(person.name, value: person.devices)
                        } footer: {
                            footer("The invite adds a phone for \(person.name): \(app.name) sees one person on all of them. It works once, on one phone, for 7 days.")
                        }
                        .ovenlightRows()
                    } else {
                        newPerson
                            .ovenlightRows()
                        if !choices.isEmpty {
                            Section {
                                ForEach(choices) { person in
                                    Button { Task { await create(person) } } label: {
                                        LabeledContent(person.name, value: person.guests.contains { $0.app == app.slug } ? "Add a Device"
                                                       : person.guests.isEmpty ? "Invited" : person.devices)
                                    }
                                    .tint(.primary)
                                    .disabled(working)
                                }
                            } header: {
                                Text("Someone You’ve Shared With")
                            } footer: {
                                Text("Choose someone here and your apps see them as one person, on every device.")
                            }
                            .ovenlightRows()
                        }
                    }
                }
                .ovenlightGroupedBackground()
                .navigationTitle("Share \(app.name)")
                .announcing(error)
                .announcing(namesakeWarning)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        if working {
                            ProgressView()
                        } else if case .person(let person) = target {
                            Button("Create") { Task { await create(person) } }
                        } else {
                            Button("Create") { Task { await create() } }
                                .disabled(trimmed.isEmpty || trimmed.count > 64 || namesake != nil)
                        }
                    }
                }
                .onAppear { if case .anyone = target { focused = true } }
            }
            .presentationDetents(choices.isEmpty ? [.medium] : [.medium, .large])
            // From the first tap, and while a Create that failed says why.
            .holdingLinks(working || error != nil)
        }
    }

    private var newPerson: some View {
        Section {
            TextField("Their name, like Sam", text: $name)
                // Once a name is typed, VoiceOver would read only the name.
                .accessibilityLabel("Name")
                .textContentType(.name)
                .submitLabel(.done)
                .focused($focused)
                .onSubmit { Task { await create() } }
        } header: {
            Text("Someone New")
        } footer: {
            if let namesakeWarning {
                Label(namesakeWarning, systemImage: "person.2.fill")
                    .labelStyle(.warning)
            } else {
                footer("\(trimmed.isEmpty ? "They see" : "\(trimmed) sees") this name on the invite, and \(app.name) knows them by it. You send the invite yourself next, by message or QR code. It works once, on one phone, for 7 days.")
            }
        }
    }

    @ViewBuilder private func footer(_ text: String) -> some View {
        if let error {
            Label(error, systemImage: "exclamationmark.triangle.fill").labelStyle(.warning)
        } else {
            Text(text)
        }
    }

    /// Invites someone new by name, or, given a person, someone already shared with.
    private func create(_ person: GuestPerson? = nil) async {
        error = nil
        let who = person?.name ?? trimmed
        guard !who.isEmpty, person != nil || namesake == nil, !working else { return }
        // Not even Face ID while the phone's own connection is down: nothing could reach the
        // computer. One that is up, retrying the computers that didn't answer, may reach it.
        guard NodeManager.shared.owner.state == .ready || NodeManager.shared.owner.state.isRetrying else {
            error = ConnectionCopy.notConnected
            return
        }
        // From the first tap, so a second one during Face ID can't make a second invite.
        working = true
        defer { working = false }
        guard await OwnerAuth.confirm("Share \(app.name) with \(who)") else { return }
        guard let client = OwnerSharing.shared.client(for: machine) else {
            error = AdminError.unreachable.message
            return
        }
        defer { client.session.finishTasksAndInvalidate() }
        do {
            let shared = if let person {
                try await client.createInvite(person: person.id, app: app.slug)
            } else {
                try await client.createInvite(to: trimmed, app: app.slug)
            }
            withAnimation { result = shared }
            Task { await OwnerSharing.shared.refresh(apps: registry.apps) }
        } catch let failure as AdminError {
            error = failure.message
        } catch {
            self.error = AdminError.unreachable.message
        }
    }
}

/// A fresh invite: its code for in person, and the link to send. This is the link's only
/// showing, so until it's sent or copied, closing asks first and a swipe doesn't close.
struct InviteResultView: View {
    let result: AdminShareResult
    let appName: String
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var sent = false
    @State private var confirmingClose = false

    private var invite: AdminInvite { result.invite }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 6) {
                        Text("Invite for \(invite.to)")
                            .font(.display(.title2))
                            .accessibilityAddTraits(.isHeader)
                        Text("Works once. Expires \(invite.expiresIn), at \(invite.expires.formatted(date: .omitted, time: .shortened)).")
                            .font(.subheadline)
                            .foregroundStyle(Color.secondaryText)
                    }
                    .multilineTextAlignment(.center)
                    if let link = result.link, let code = QRCode.image(for: link) {
                        Image(uiImage: code)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .padding(16)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
                            .frame(maxWidth: 260)
                            .accessibilityLabel("Invite code")
                        Text("In person: they scan this with their phone’s camera.")
                            .font(.footnote)
                            .foregroundStyle(Color.secondaryText)
                            .multilineTextAlignment(.center)
                    }
                    VStack(spacing: 10) {
                        ShareLink(item: result.message, subject: Text("\(appName) in Ovenlight")) {
                            Label("Send Invite", systemImage: "square.and.arrow.up")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                        }
                        .simultaneousGesture(TapGesture().onEnded { sent = true })
                        .buttonStyle(.ovenlightProminent)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                        Button {
                            // A live credential: this iPhone only, and gone once the invite expires.
                            UIPasteboard.general.setItems([["public.utf8-plain-text": result.link ?? ""]],
                                                          options: [.localOnly: true, .expirationDate: invite.expires])
                            copied = true
                            sent = true
                        } label: {
                            Label(copied ? "Copied" : "Copy Link", systemImage: copied ? "checkmark" : "doc.on.doc")
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                        .sensoryFeedback(.success, trigger: copied)
                    }
                    Text("\(invite.to) can join without your approval and reaches only \(appName). Until it’s used, the link works like a key, so send it privately.")
                        .font(.footnote)
                        .foregroundStyle(Color.secondaryText)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
            }
            .ovenlightGroupedBackground()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { if sent { dismiss() } else { confirmingClose = true } }
                }
            }
            .alert("Close Without Sending?", isPresented: $confirmingClose) {
                Button("Keep", role: .cancel) {}
                Button("Close", role: .destructive) { dismiss() }
            } message: {
                Text("This link won’t show again. The invite stays open until it expires, and you can cancel it in People & Sharing.")
            }
        }
        .interactiveDismissDisabled(!sent)
        .holdingLinks(!sent)
    }
}

enum QRCode {
    static func image(for text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// Notes and screenshots people sent from Ovenlight, newest first, across machines.
struct FeedbackInbox: View {
    /// One app's feedback only: its machine and slug.
    var only: (machineID: String, slug: String)?

    @EnvironmentObject private var registry: AppRegistry
    @ObservedObject private var sharing = OwnerSharing.shared
    @StateObject private var shots = ScreenshotCache()
    @AppStorage(FeedbackInbox.seenKey) private var seenUntil: Double = 0

    /// The newest feedback an inbox has shown, as seconds since 1970: the counts show only
    /// what came after it. The connector has no read marks to keep.
    static let seenKey = "feedbackSeenUntil"

    /// How many of `items` came after what an inbox last showed.
    nonisolated static func unseen(_ items: [AdminFeedback], seenUntil: Double) -> Int {
        items.filter { $0.at.timeIntervalSince1970 > seenUntil }.count
    }

    /// The mark once an inbox has shown `shown`: its newest item, unless feedback it leaves
    /// out (other apps', in one app's inbox) from before that newest came after the mark,
    /// which no one has seen yet. Newer feedback stays counted either way.
    nonisolated static func seenUntil(_ seenUntil: Double, showing shown: [AdminFeedback], leavingOut others: [AdminFeedback]) -> Double {
        guard let newest = shown.map(\.at.timeIntervalSince1970).max(), newest > seenUntil,
              unseen(others.filter { $0.at.timeIntervalSince1970 <= newest }, seenUntil: seenUntil) == 0 else { return seenUntil }
        return newest
    }

    private var all: [(OwnerSharing.Machine, AdminFeedback)] { sharing.machines.flatMap { m in m.feedback.map { (m, $0) } } }

    private func isShown(_ entry: (OwnerSharing.Machine, AdminFeedback)) -> Bool {
        only == nil || (entry.0.id == only?.machineID && entry.1.app == only?.slug)
    }

    private var items: [(OwnerSharing.Machine, AdminFeedback)] {
        all.filter(isShown).sorted { $0.1.at > $1.1.at }
    }

    var body: some View {
        List {
            if items.isEmpty {
                ContentUnavailableView("No Feedback Yet", systemImage: "exclamationmark.bubble",
                                       description: Text("People send it with Send Feedback in an open app’s menu."))
                    .ovenlightRows()
            }
            ForEach(items, id: \.1.id) { machine, item in
                NavigationLink {
                    FeedbackDetail(machine: machine, item: item, shots: shots)
                } label: {
                    HStack(alignment: .top, spacing: 12) {
                        thumbnail(machine, item)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.note.isEmpty ? "Screenshot only" : item.note)
                                .lineLimit(3)
                                .foregroundStyle(item.note.isEmpty ? Color.secondaryText : .primary)
                            Text(meta(item, on: machine))
                                .font(.caption)
                                .foregroundStyle(Color.secondaryText)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .ovenlightRows()
        }
        .ovenlightGroupedBackground()
        .navigationTitle("Feedback")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await sharing.refresh(apps: registry.apps) }
        .onChange(of: items.first?.1.at, initial: true) {
            let mark = Self.seenUntil(seenUntil, showing: items.map(\.1), leavingOut: all.filter { !isShown($0) }.map(\.1))
            if mark != seenUntil { seenUntil = mark }
        }
    }

    @ViewBuilder
    private func thumbnail(_ machine: OwnerSharing.Machine, _ item: AdminFeedback) -> some View {
        if item.hasScreenshot {
            Group {
                if let image = shots.image(item.id, .thumbnail) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Rectangle().fill(Color(.secondarySystemFill))
                }
            }
            .frame(width: 44, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
            .task { await shots.load(item.id, .thumbnail, from: machine) }
        }
    }

    private func meta(_ item: AdminFeedback, on machine: OwnerSharing.Machine) -> String {
        let app = machine.apps.first { $0.slug == item.app }?.name ?? item.app
        let who = item.isFromGuest ? item.from : "You"
        return "\(who) · \(app) · \(item.at.formatted(.relative(presentation: .named)))"
    }
}

private struct FeedbackDetail: View {
    let machine: OwnerSharing.Machine
    let item: AdminFeedback
    @ObservedObject var shots: ScreenshotCache

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !item.note.isEmpty {
                    Text(item.note).font(.body).textSelection(.enabled)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("From \(item.from)\(item.isFromGuest ? " (guest)" : "")")
                    if let device = item.device, !device.isEmpty { Text(device) }
                    Text(item.at.formatted(date: .abbreviated, time: .shortened))
                    if let page = item.pageUrl, !page.isEmpty {
                        Text(page).lineLimit(2).textSelection(.enabled)
                    }
                }
                .font(.footnote)
                .foregroundStyle(Color.secondaryText)
                if item.hasScreenshot {
                    if shots.failed(item.id, .screen) {
                        Text("Couldn’t load the screenshot.")
                            .font(.footnote)
                            .foregroundStyle(Color.secondaryText)
                    } else if let image = shots.image(item.id, .screen) ?? shots.image(item.id, .thumbnail) {
                        // The list's thumbnail, the same shape, until the sharp one arrives.
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
                            .accessibilityLabel("Screenshot")
                    } else {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 200)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .ovenlightGroupedBackground()
        .navigationTitle(machine.apps.first { $0.slug == item.app }?.name ?? item.app)
        .navigationBarTitleDisplayMode(.inline)
        .task { await shots.load(item.id, .screen, from: machine) }
        // Screen-sized images are big: only the open one is kept.
        .onDisappear { shots.drop(item.id, .screen) }
    }
}

/// Screenshots fetched from the connectors, kept while the inbox is open: small for the
/// list, the most recent `maxThumbnails` of them, and screen-sized for the one open only,
/// never at full size.
@MainActor
final class ScreenshotCache: ObservableObject {
    /// The most pixels on the long side: a row's thumbnail, and the largest screen, rounded up.
    enum Size: Int {
        case thumbnail = 240
        case screen = 3000
    }

    /// A row whose thumbnail went fetches it again as it scrolls back into view.
    static let maxThumbnails = 60

    private struct Key: Hashable {
        var id: String
        var size: Size
    }

    @Published private var images: [Key: UIImage] = [:]
    /// The last try to fetch or decode each of these failed.
    @Published private var failures: Set<Key> = []
    /// Oldest first.
    private var thumbnails: [Key] = []
    private var loading: Set<Key> = []

    func image(_ id: String, _ size: Size) -> UIImage? { images[Key(id: id, size: size)] }

    func failed(_ id: String, _ size: Size) -> Bool { failures.contains(Key(id: id, size: size)) }

    func drop(_ id: String, _ size: Size) {
        images[Key(id: id, size: size)] = nil
    }

    func load(_ id: String, _ size: Size, from machine: OwnerSharing.Machine) async {
        let key = Key(id: id, size: size)
        guard images[key] == nil, !loading.contains(key) else { return }
        guard let client = OwnerSharing.shared.client(for: machine) else {
            failures.insert(key)
            return
        }
        loading.insert(key)
        failures.remove(key)
        defer {
            loading.remove(key)
            client.session.finishTasksAndInvalidate()
        }
        let data = try? await client.screenshot(id)
        guard let image = await Task.detached(operation: { data.flatMap { Screenshot.image(from: $0, maxPixels: size.rawValue) } }).value else {
            failures.insert(key)
            return
        }
        store(image, for: key)
    }

    private func store(_ image: UIImage, for key: Key) {
        switch key.size {
        case .screen:
            images = images.filter { $0.key.size != .screen }
        case .thumbnail:
            thumbnails.removeAll { $0 == key }
            thumbnails.append(key)
            if thumbnails.count > Self.maxThumbnails { images[thumbnails.removeFirst()] = nil }
        }
        images[key] = image
    }
}

enum Screenshot {
    /// Decodes an image no larger than `maxPixels` on its long side without making the
    /// full-size bitmap first: a guest chooses what the connector stores.
    static func image(from data: Data, maxPixels: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map { UIImage(cgImage: $0) }
    }
}
