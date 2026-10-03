import SwiftUI
import VisionKit

/// What the join sheet opens with: an invite from a link, or nothing yet (the "Join with
/// Invite" entry, which takes a pasted link or a scanned code).
struct JoinRequest: Identifiable {
    let id = UUID()
    var invite: Result<InviteLink, InviteLink.ParseError>?

    init(url: URL) {
        do {
            invite = .success(try InviteLink.parse(url))
        } catch {
            invite = .failure(error)
        }
    }

    init(invite: Result<InviteLink, InviteLink.ParseError>? = nil) {
        self.invite = invite
    }
}

/// The whole join, in one sheet: find the invite, confirm it, then follow it through
/// joining and the claim. Nothing in it names the network underneath.
struct JoinFlowView: View {
    @State var request: JoinRequest
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch request.invite {
                case nil:
                    InviteEntryView { request.invite = $0 }
                case .failure(let error):
                    JoinMessage(symbol: "link.badge.plus", title: "Can't Use This Invite", message: error.message) {
                        Button("Try Another Invite") { request.invite = nil }
                            .buttonStyle(.borderedProminent)
                            .buttonBorderShape(.capsule)
                            .controlSize(.large)
                    }
                case .success(let invite):
                    JoinInviteView(invite: invite, close: { dismiss() })
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ovenlightGroupedBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
    }
}

/// Paste the link or scan its code.
private struct InviteEntryView: View {
    let found: (Result<InviteLink, InviteLink.ParseError>) -> Void

    @State private var text = ""
    @State private var error: String?
    @State private var scanning = false

    private var canScan: Bool { DataScannerViewController.isSupported && DataScannerViewController.isAvailable }

    var body: some View {
        Form {
            Section {
                PasteButton(payloadType: String.self) { strings in
                    guard let pasted = strings.first else { return }
                    Task { @MainActor in use(pasted) }
                }
                .buttonBorderShape(.capsule)
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            } header: {
                Text(ConnectionCopy.invitePrompt)
                    .textCase(nil)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 6)
            }
            Section {
                TextField("https://ovenlight.app/join#…", text: $text, axis: .vertical)
                    .lineLimit(1...4)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.callout.monospaced())
                    .submitLabel(.continue)
                    .onSubmit { use(text) }
                Button("Continue") { use(text) }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } header: {
                Text("Or Type It")
            } footer: {
                if let error {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .labelStyle(.warning)
                }
            }
            .ovenlightRows()
            if canScan {
                Section {
                    Button { scanning = true } label: {
                        Label("Scan Invite Code", systemImage: "qrcode.viewfinder")
                    }
                }
                .ovenlightRows()
            }
        }
        .navigationTitle(ConnectionCopy.joinWithInvite)
        .fullScreenCover(isPresented: $scanning) {
            InviteScannerView { url in
                scanning = false
                use(url.absoluteString)
            } cancel: {
                scanning = false
            }
        }
    }

    private func use(_ text: String) {
        do {
            found(.success(try InviteLink.parse(text: text)))
        } catch {
            withAnimation { self.error = error.message }
        }
    }
}

/// "Join Riley's Echo Board?", then its progress.
private struct JoinInviteView: View {
    let invite: InviteLink
    let close: () -> Void

    @EnvironmentObject private var registry: AppRegistry
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var node: NodeManager
    @ObservedObject private var guests = GuestManager.shared

    private var preview: WebApp {
        WebApp(name: invite.name, startURL: invite.appURL)
    }

    /// The invited app once it's on this iPhone, joined now or before. Its icon can't be
    /// fetched until joining gives this iPhone access to its computer; adding the app
    /// fetches it, so the letter turns into the real icon a moment after joining.
    private func added(_ phase: JoinPhase?) -> WebApp? {
        if case .joined(let id) = phase { return registry.apps.first { $0.id == id } }
        if case .alreadyAdded(let app) = guests.preflight(invite) { return app }
        return nil
    }

    var body: some View {
        let phase = guests.phases[invite.invite]
        // Scrolls only when the largest text sizes don't fit.
        ViewThatFits(in: .vertical) {
            layout(for: phase)
            ScrollView { layout(for: phase) }
        }
        .animation(.easeInOut(duration: 0.25), value: phase)
        .sensoryFeedback(trigger: phase) { _, new in
            switch new {
            case .joined: .success
            case .failed: .error
            default: nil
            }
        }
    }

    private func layout(for phase: JoinPhase?) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)
            let app = added(phase)
            AppIconView(app: app ?? preview, image: app.flatMap(registry.icon(for:)), size: 88)
                .shadow(color: .black.opacity(0.1), radius: 8, y: 4)
                .overlay(alignment: .bottomTrailing) { badge(for: phase) }
                .padding(.bottom, 22)
            // Anchored just above the buttons rather than floating mid-sheet.
            content(for: phase)
                .padding(.bottom, 32)
            controls(for: phase)
                .padding(.bottom, 12)
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func badge(for phase: JoinPhase?) -> some View {
        switch phase {
        case .joined:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 28))
                .foregroundStyle(.white, .green)
                .background(Circle().fill(Color.groupedBackground).padding(2))
                .offset(x: 8, y: 8)
                .transition(.scale.combined(with: .opacity))
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func content(for phase: JoinPhase?) -> some View {
        VStack(spacing: 10) {
            switch (phase, guests.preflight(invite)) {
            case (.joined, _), (nil, .alreadyAdded):
                title(ConnectionCopy.joined(invite.name))
                detail("It's with your apps now, shared by \(invite.owner).")
            case (.failed(let error), _):
                title(error.title(owner: invite.owner, app: invite.name))
                detail(error.message(owner: invite.owner, app: invite.name))
            case (.joining, _), (.claiming, _):
                title(ConnectionCopy.joining(owner: invite.owner, app: invite.name))
                detail(ConnectionCopy.joiningDetail)
                ProgressView().padding(.top, 6)
            case (nil, .ownApp):
                title(JoinError.ownApp.title(owner: invite.owner, app: invite.name))
                detail(JoinError.ownApp.message(owner: invite.owner, app: invite.name))
            case (nil, .ready):
                title(ConnectionCopy.joinTitle(owner: invite.owner, app: invite.name))
                // The address reads as code to a friend; VoiceOver still offers it.
                detail(ConnectionCopy.joinDetail(owner: invite.owner, app: invite.name, to: invite.to))
                    .accessibilityHint(ConnectionCopy.appAddress(invite.host))
                if !invite.usesTailscaleControl {
                    Label(ConnectionCopy.privateServer(invite.control.host ?? "", owner: invite.owner),
                          systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .labelStyle(.warning)
                        .padding(.top, 4)
                }
            }
        }
        .multilineTextAlignment(.center)
    }

    private func title(_ text: String) -> some View {
        Text(text).font(.display(.title2))
    }

    private func detail(_ text: String) -> some View {
        Text(text).foregroundStyle(.secondary)
    }

    @ViewBuilder
    private func controls(for phase: JoinPhase?) -> some View {
        VStack(spacing: 12) {
            switch (phase, guests.preflight(invite)) {
            case (.joined(let id), _):
                primary("Open \(invite.name)") { open(id) }
            case (nil, .alreadyAdded(let app)):
                primary("Open \(invite.name)") { open(app.id) }
                // A new invite after the owner removed the app must reach its connector.
                Button("Join Again") { Task { await guests.accept(invite) } }
            case (.failed(let error), _) where error.isRetryable:
                primary("Try Again") { Task { await guests.retry(invite.invite) } }
                Button("Stop Joining", role: .destructive) { Task { await guests.cancel(invite.invite); close() } }
            case (.failed, _), (nil, .ownApp):
                primary("Done", action: close)
            case (.joining, _), (.claiming, _):
                EmptyView()
            case (nil, .ready):
                primary("Join") { Task { await guests.accept(invite) } }
                Button("Not Now", action: close)
            }
        }
    }

    private func primary(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.headline).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .controlSize(.large)
    }

    private func open(_ id: UUID) {
        if let app = registry.apps.first(where: { $0.id == id }) { router.open(app) } else { close() }
    }
}

/// A symbol, a title and a message, for the sheet's end states.
private struct JoinMessage<Controls: View>: View {
    let symbol: String
    let title: String
    let message: String
    @ViewBuilder let controls: Controls

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: symbol)
        } description: {
            Text(message)
        } actions: {
            controls
        }
    }
}

/// Scans an invite's QR code with the camera.
struct InviteScannerView: View {
    let found: (URL) -> Void
    let cancel: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            ScannerRepresentable(found: found)
                .ignoresSafeArea()
            HStack {
                Text("Point at the invite code")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Button("Cancel", action: cancel)
                    .font(.subheadline.weight(.semibold))
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .ovenlightGlass(in: Capsule())
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
    }

    private struct ScannerRepresentable: UIViewControllerRepresentable {
        let found: (URL) -> Void

        func makeUIViewController(context: Context) -> DataScannerViewController {
            let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                                    qualityLevel: .balanced, recognizesMultipleItems: false,
                                                    isHighFrameRateTrackingEnabled: false, isHighlightingEnabled: true)
            scanner.delegate = context.coordinator
            try? scanner.startScanning()
            return scanner
        }

        func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

        static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
            controller.stopScanning()
        }

        func makeCoordinator() -> Coordinator { Coordinator(found: found) }

        final class Coordinator: NSObject, DataScannerViewControllerDelegate {
            let found: (URL) -> Void
            private var done = false

            init(found: @escaping (URL) -> Void) { self.found = found }

            func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
                for case .barcode(let code) in items {
                    guard !done, let text = code.payloadStringValue, let url = URL(string: text),
                          InviteLink.isInvite(url) else { continue }
                    done = true
                    found(url)
                }
            }
        }
    }
}

/// Under a shared app's name on its tile: who shared it.
struct SharedByCaption: View {
    let owner: String

    var body: some View {
        Text(ConnectionCopy.sharedBy(owner))
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(1)
    }
}

/// Above the apps: invites still joining.
struct PendingInvitesBanner: View {
    @EnvironmentObject private var node: NodeManager
    @ObservedObject private var guests = GuestManager.shared
    let open: (InviteLink) -> Void

    var body: some View {
        let pending = node.memberships.memberships.flatMap { $0.pendingClaims.map(\.invite) }
        if !pending.isEmpty {
            VStack(spacing: 8) {
                ForEach(pending, id: \.invite) { invite in
                    Button { open(invite) } label: { row(invite) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.top, 8)
        }
    }

    private func row(_ invite: InviteLink) -> some View {
        HStack(spacing: 12) {
            AppIconView(app: WebApp(name: invite.name, startURL: invite.appURL), image: nil, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(invite.name).font(.subheadline.weight(.semibold))
                Text(status(invite)).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if case .failed = guests.phases[invite.invite] {
                Image(systemName: "exclamationmark.circle").foregroundStyle(.orange)
            } else {
                ProgressView()
            }
        }
        .padding(12)
        .ovenlightGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous), interactive: true)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func status(_ invite: InviteLink) -> String {
        switch guests.phases[invite.invite] {
        case .failed(let error): error.title(owner: invite.owner, app: invite.name)
        default: ConnectionCopy.joining(owner: invite.owner, app: invite.name)
        }
    }
}

/// The launcher before anything is set up. Nothing here assumes the person runs their own
/// machines: a friend who got an invite starts here too, and never sees a sign-in.
struct LauncherWelcome: View {
    @EnvironmentObject private var node: NodeManager
    @Environment(\.colorScheme) private var colorScheme
    let join: () -> Void

    static let title = "Welcome to Ovenlight"
    static let message = "Open the invite a friend sent you, or connect the computers where your own apps run."

    var body: some View {
        // Scrolls only when the largest text sizes don't fit.
        ViewThatFits(in: .vertical) {
            content
            ScrollView { content.padding(.vertical, 24) }
        }
        .frame(maxHeight: .infinity)
    }

    private var content: some View {
        VStack(spacing: 10) {
            // The empty rack, in the light, waiting for apps.
            OvenRack(width: 250)
                .background {
                    Ellipse()
                        .fill(EllipticalGradient(colors: [Color.lampGlow.opacity(colorScheme == .dark ? 0.25 : 0.2), .clear]))
                        .frame(width: 270, height: 64)
                }
                .padding(.bottom, 30)
            Text(Self.title)
                .font(.display(.title2))
            Text(Self.message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            VStack(spacing: 14) {
                Button(ConnectionCopy.joinWithInvite, action: join)
                    .font(.headline)
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                Button("Use My Own Computers") { node.enableOwner() }
            }
            .padding(.top, 14)
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 32)
    }
}
