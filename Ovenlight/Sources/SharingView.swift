import CoreImage.CIFilterBuiltins
import ImageIO
import LocalAuthentication
import SwiftUI

/// Face ID (or the passcode) before anything that changes who can open an app. An iPhone
/// with no passcode has nothing to ask for, and Ovenlight already opens without one there
/// (see `LockManager`).
enum OwnerAuth {
    static func confirm(_ reason: String) async -> Bool {
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

/// The owner's controls for their connectors: apps and who they're shared with, and
/// feedback. Admin credentials never leave the machines; Ovenlight asks each connector
/// over the owner's own node.
struct SharingList: View {
    @EnvironmentObject private var registry: AppRegistry
    @ObservedObject private var sharing = OwnerSharing.shared

    private var feedbackCount: Int { sharing.machines.reduce(0) { $0 + $1.feedback.count } }

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
                    ContentUnavailableView("No Computers Answered", systemImage: "desktopcomputer.trianglebadge.exclamationmark",
                                           description: Text(sharing.problem ?? "Check that the Ovenlight connector is running on your computers."))
                        .ovenlightRows()
                }
            }
            ForEach(sharing.machines) { machine in
                Section(sharing.machines.count > 1 ? machine.adminHost : "Apps") {
                    ForEach(machine.apps) { app in
                        NavigationLink {
                            SharingAppView(machineID: machine.id, slug: app.slug)
                        } label: {
                            SharingAppRow(app: app, local: localApp(app))
                        }
                    }
                }
                .ovenlightRows()
            }
            if !sharing.machines.isEmpty {
                Section {
                    NavigationLink {
                        FeedbackInbox()
                    } label: {
                        LabeledContent {
                            if feedbackCount > 0 { Text("\(feedbackCount)") }
                        } label: {
                            Label("Feedback", systemImage: "exclamationmark.bubble")
                        }
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
    }

    private func localApp(_ app: AdminApp) -> WebApp? {
        registry.apps.first { !$0.isShared && Discovery.normalizedHost($0.host) == app.host }
    }
}

private struct SharingAppRow: View {
    let app: AdminApp
    let local: WebApp?
    @EnvironmentObject private var registry: AppRegistry

    var body: some View {
        HStack(spacing: 12) {
            let tile = local ?? WebApp(name: app.name, startURL: URL(string: app.url ?? "https://\(app.slug).invalid/")!)
            AppIconView(app: tile, image: local.flatMap(registry.icon(for:)), size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(app.name)
                Text(summary).font(.caption).foregroundStyle(.secondary)
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
            if let app, let machine {
                let people = GuestPerson.all(machine.guests.guests.filter { $0.app == slug })
                let invites = machine.guests.invites.filter { $0.app == slug && $0.state == AdminInvite.sent }
                let feedback = machine.feedback.filter { $0.app == slug }.count
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.triangle.fill").labelStyle(.warning) }
                        .ovenlightRows()
                }
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
                        Text("Each invite is a link that works once, on one iPhone, for 24 hours. Guests reach only this app.")
                    } else {
                        Text("To share it, run this on its computer: ovenlight publish --slug \(app.slug) --shareable")
                    }
                }
                .ovenlightRows()
                if app.shareable {
                    Section("People") {
                        if people.isEmpty {
                            Text("No one yet").foregroundStyle(.secondary)
                        }
                        ForEach(people) { person in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(person.name)
                                Text("\(person.devices) · joined \(person.guests[0].claimedAt.formatted(.relative(presentation: .named)))")
                                    .font(.caption).foregroundStyle(.secondary)
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
                                    Text("\(invite.review == true ? "Review invite, unused" : "Unused"), expires \(invite.expires.formatted(.relative(presentation: .named)))")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
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
            } else {
                ContentUnavailableView("App Not Found", systemImage: "questionmark.app")
                    .ovenlightRows()
            }
        }
        .ovenlightGroupedBackground()
        .navigationTitle(app?.name ?? "App")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await sharing.refresh(apps: registry.apps) }
        .task { await sharing.refreshIfStale(apps: registry.apps) }
        .sheet(item: $composing) { InviteComposer(machine: $0.machine, app: $0.app, target: $0.target) }
        .alert(removing.map { "Remove \($0.name)?" } ?? "", isPresented: Binding(
            get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing) { person in
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) { Task { await remove(person) } }
        } message: { person in
            Text("\(person.name) can't open \(app?.name ?? "the app") anymore, on any device. Each of their devices is also removed from your Tailscale network unless it has another of your apps.")
        }
    }

    private func remove(_ person: GuestPerson) async {
        guard let machine, await OwnerAuth.confirm("Remove \(person.name)"), let client = sharing.client(for: machine) else { return }
        defer { client.session.finishTasksAndInvalidate() }
        do {
            let result = try await client.removeGuest(person, app: slug)
            error = result.errors?.first
        } catch let failure as AdminError {
            error = failure.message
        } catch {}
        await sharing.refresh(apps: registry.apps)
    }

    private func cancel(_ invite: AdminInvite) async {
        guard let machine, let client = sharing.client(for: machine) else { return }
        defer { client.session.finishTasksAndInvalidate() }
        _ = try? await client.cancelInvite(invite.id)
        await sharing.refresh(apps: registry.apps)
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
    @State private var result: InviteResultItem?
    @FocusState private var focused: Bool

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var people: [GuestPerson] { GuestPerson.everyone(in: machine.guests) }
    private var choices: [GuestPerson] { GuestPerson.invitable(in: machine.guests) }

    /// Someone already shared with who has the typed name.
    private var namesake: GuestPerson? {
        people.first { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
    }

    var body: some View {
        if let result {
            InviteResultView(item: result)
        } else {
            NavigationStack {
                Form {
                    if case .person(let person) = target {
                        Section {
                            LabeledContent(person.name, value: person.devices)
                        } footer: {
                            footer("The invite adds an iPhone for \(person.name): \(app.name) sees one person on all of them. It works once, on one iPhone, for 24 hours.")
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
                                Text("Someone You've Shared With")
                            } footer: {
                                Text("Choose someone here and your apps see them as one person, on every device.")
                            }
                            .ovenlightRows()
                        }
                    }
                }
                .ovenlightGroupedBackground()
                .navigationTitle("Share \(app.name)")
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
        }
    }

    private var newPerson: some View {
        Section {
            TextField("Their name, like Sam", text: $name)
                .textContentType(.name)
                .submitLabel(.done)
                .focused($focused)
                .onSubmit { Task { await create() } }
        } header: {
            Text("Someone New")
        } footer: {
            if let namesake, error == nil {
                Label(choices.contains { $0.id == namesake.id }
                      ? "\(namesake.name) is already in the list below. Choose them there, or use a name that tells them apart, like \(namesake.name) L."
                      : "\(namesake.name) already has an invite. Use a name that tells them apart, like \(namesake.name) L.", systemImage: "person.2.fill")
                    .labelStyle(.warning)
            } else {
                footer("\(trimmed.isEmpty ? "They see" : "\(trimmed) sees") this name on the invite, and \(app.name) knows them by it. You send the invite yourself next, by message or QR code. It works once, on one iPhone, for 24 hours.")
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
        let who = person?.name ?? trimmed
        guard !who.isEmpty, person != nil || namesake == nil, !working else { return }
        guard await OwnerAuth.confirm("Share \(app.name) with \(who)") else { return }
        guard let client = OwnerSharing.shared.client(for: machine) else {
            error = AdminError.unreachable.message
            return
        }
        defer { client.session.finishTasksAndInvalidate() }
        working = true
        defer { working = false }
        do {
            let shared = if let person {
                try await client.createInvite(person: person.id, app: app.slug)
            } else {
                try await client.createInvite(to: trimmed, app: app.slug)
            }
            withAnimation { result = InviteResultItem(result: shared, appName: app.name) }
            Task { await OwnerSharing.shared.refresh(apps: registry.apps) }
        } catch let failure as AdminError {
            error = failure.message
        } catch {
            self.error = AdminError.unreachable.message
        }
    }
}

struct InviteResultItem: Identifiable {
    let id = UUID()
    var result: AdminShareResult
    var appName: String
}

/// A fresh invite: its code for in person, and the link to send.
struct InviteResultView: View {
    let item: InviteResultItem
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    private var invite: AdminInvite { item.result.invite }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 6) {
                        Text("Invite for \(invite.to)")
                            .font(.display(.title2))
                        Text("Works once. Expires \(invite.expires.formatted(.relative(presentation: .named))), at \(invite.expires.formatted(date: .omitted, time: .shortened)).")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .multilineTextAlignment(.center)
                    if let link = item.result.link, let code = QRCode.image(for: link) {
                        Image(uiImage: code)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .padding(16)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
                            .frame(maxWidth: 260)
                            .accessibilityLabel("Invite code")
                        Text("In person: they scan this with their iPhone camera.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    VStack(spacing: 10) {
                        ShareLink(item: item.result.message, subject: Text("\(item.appName) in Ovenlight")) {
                            Label("Send Invite", systemImage: "square.and.arrow.up")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                        Button {
                            // A live credential: this iPhone only, and gone once the invite expires.
                            UIPasteboard.general.setItems([["public.utf8-plain-text": item.result.link ?? ""]],
                                                          options: [.localOnly: true, .expirationDate: invite.expires])
                            copied = true
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
                    Text("\(invite.to) can join without your approval and reaches only \(item.appName). Until it's used, the link works like a key, so send it privately.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
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

    @ObservedObject private var sharing = OwnerSharing.shared
    @StateObject private var shots = ScreenshotCache()

    private var items: [(OwnerSharing.Machine, AdminFeedback)] {
        sharing.machines.flatMap { m in m.feedback.map { (m, $0) } }
            .filter { only == nil || ($0.0.id == only?.machineID && $0.1.app == only?.slug) }
            .sorted { $0.1.at > $1.1.at }
    }

    var body: some View {
        List {
            if items.isEmpty {
                ContentUnavailableView("No Feedback Yet", systemImage: "exclamationmark.bubble",
                                       description: Text("People send it with Send Feedback in an open app's menu."))
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
                                .foregroundStyle(item.note.isEmpty ? .secondary : .primary)
                            Text(meta(item, on: machine))
                                .font(.caption)
                                .foregroundStyle(.secondary)
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
                .foregroundStyle(.secondary)
                if item.hasScreenshot {
                    if let image = shots.image(item.id, .screen) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
                    } else {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 200)
                    }
                }
            }
            .padding(20)
        }
        .navigationTitle(machine.apps.first { $0.slug == item.app }?.name ?? item.app)
        .navigationBarTitleDisplayMode(.inline)
        .task { await shots.load(item.id, .screen, from: machine) }
    }
}

/// Screenshots fetched from the connectors, kept while the inbox is open: small for the
/// list, screen-sized for the one open, never at full size.
@MainActor
final class ScreenshotCache: ObservableObject {
    /// The most pixels on the long side: a row's thumbnail, and the largest screen, rounded up.
    enum Size: Int {
        case thumbnail = 240
        case screen = 3000
    }

    private struct Key: Hashable {
        var id: String
        var size: Size
    }

    @Published private var images: [Key: UIImage] = [:]
    private var loading: Set<Key> = []

    func image(_ id: String, _ size: Size) -> UIImage? { images[Key(id: id, size: size)] }

    func load(_ id: String, _ size: Size, from machine: OwnerSharing.Machine) async {
        let key = Key(id: id, size: size)
        guard images[key] == nil, !loading.contains(key), let client = OwnerSharing.shared.client(for: machine) else { return }
        loading.insert(key)
        defer {
            loading.remove(key)
            client.session.finishTasksAndInvalidate()
        }
        guard let data = try? await client.screenshot(id) else { return }
        if let image = await Task.detached(operation: { Screenshot.image(from: data, maxPixels: size.rawValue) }).value {
            images[key] = image
        }
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
