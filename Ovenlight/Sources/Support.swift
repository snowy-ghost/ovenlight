import MessageUI
import SwiftUI

/// Where people reach the team behind Ovenlight.
enum AppLinks {
    static let supportEmail = "team@snowyghost.com"
    static let privacyPolicy = URL(string: "https://ovenlight.app/privacy")!
    static let tailscalePricing = URL(string: "https://tailscale.com/pricing")!
}

/// A message to the team, opened in Mail for the person to finish and send.
struct MailDraft: Identifiable, Equatable {
    let id = UUID()
    var to = AppLinks.supportEmail
    var subject: String
    var body: String

    /// For when Mail has no account set up: whatever mail app the person uses.
    var mailtoURL: URL? {
        // URLComponents leaves & and + alone in query values, which would cut an app name short.
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        func encoded(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "" }
        var parts = URLComponents()
        parts.scheme = "mailto"
        parts.path = to
        parts.percentEncodedQuery = "subject=\(encoded(subject))&body=\(encoded(body))"
        return parts.url
    }

    static func contactUs(version: String) -> MailDraft {
        MailDraft(subject: "Ovenlight", body: "\n\nOvenlight \(version)")
    }

    /// Report a Problem for one app: what it is and where it comes from, how its connection
    /// is doing (none for an app loaded directly) and where opening it got, with room at the
    /// top for what went wrong. Nothing else is attached.
    static func problemReport(appName: String, host: String, sharedBy: String?, connection: String?,
                              opening: String? = nil, version: String, iOS: String) -> MailDraft {
        var lines = ["What happened:", "", "", "", "App: \(appName)", "Address: \(host)"]
        if let sharedBy { lines.append("Shared by: \(sharedBy)") }
        if let connection { lines.append("Connection: \(connection)") }
        if let opening { lines.append("Opening: \(opening)") }
        lines += ["Ovenlight: \(version)", "iOS: \(iOS)"]
        return MailDraft(subject: "Ovenlight report: \(appName)", body: lines.joined(separator: "\n"))
    }

    @MainActor
    static func problemReport(for app: WebApp) -> MailDraft {
        problemReport(appName: app.name, host: app.host, sharedBy: app.sharedBy,
                      connection: NodeManager.shared.node(for: app).map { connection($0.state, listedOffline: $0.isListedOffline(app.host)) },
                      opening: WebViewPool.shared.gate(of: app.id).map { String(describing: $0) },
                      version: Bundle.main.versionString, iOS: UIDevice.current.systemVersion)
    }

    /// The state as shown, then in detail as the log has it (no sign-in link), and whether
    /// the network lists the app's computer as offline.
    static func connection(_ state: NodeState, listedOffline: Bool) -> String {
        "\(state.summary) (\(state.logDescription)\(listedOffline ? ", computer listed offline" : ""))"
    }
}

extension View {
    /// Opens the draft in a mail compose sheet, or through a mailto: link when this iPhone
    /// can't send mail from Mail.
    func mailComposer(_ draft: Binding<MailDraft?>) -> some View {
        modifier(MailComposer(draft: draft))
    }
}

private struct MailComposer: ViewModifier {
    @Binding var draft: MailDraft?
    @Environment(\.openURL) private var openURL
    @State private var composing: MailDraft?

    func body(content: Content) -> some View {
        content
            .onChange(of: draft) { _, new in
                guard let new else { return }
                draft = nil
                if MFMailComposeViewController.canSendMail() {
                    composing = new
                } else if let url = new.mailtoURL {
                    openURL(url)
                }
            }
            .sheet(item: $composing) { MailComposeView(draft: $0).ignoresSafeArea() }
    }
}

private struct MailComposeView: UIViewControllerRepresentable {
    let draft: MailDraft
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let controller = MFMailComposeViewController()
        controller.mailComposeDelegate = context.coordinator
        controller.setToRecipients([draft.to])
        controller.setSubject(draft.subject)
        controller.setMessageBody(draft.body, isHTML: false)
        return controller
    }

    func updateUIViewController(_ controller: MFMailComposeViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(dismiss: { dismiss() }) }

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let dismiss: () -> Void

        init(dismiss: @escaping () -> Void) { self.dismiss = dismiss }

        func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            dismiss()
        }
    }
}

extension View {
    /// Asks before leaving someone's apps, then leaves: their apps and data go, and the
    /// iPhone leaves their network. `before` runs first, for closing an open app.
    func leaveConfirmation(_ leaving: Binding<Membership?>, before: @escaping () -> Void = {}) -> some View {
        alert(leaving.wrappedValue.map { ConnectionCopy.leaveTitle($0.ownerName) } ?? "",
              isPresented: Binding(get: { leaving.wrappedValue != nil }, set: { if !$0 { leaving.wrappedValue = nil } }),
              presenting: leaving.wrappedValue) { membership in
            Button("Cancel", role: .cancel) {}
            Button("Leave", role: .destructive) {
                before()
                Task { await GuestManager.shared.leave(membership.id) }
            }
        } message: { membership in
            Text(ConnectionCopy.leaveDetail(membership.ownerName))
        }
    }
}

/// The licenses of the open-source software in Ovenlight: TailscaleKit and the Go code it
/// links. scripts/build-tailscalekit.sh puts them in the framework.
struct AcknowledgementsView: View {
    static let notices: String? = Bundle.main.privateFrameworksURL
        .flatMap { try? String(contentsOf: $0.appending(path: "TailscaleKit.framework/THIRD_PARTY_NOTICES.txt"), encoding: .utf8) }

    // One Text per paragraph, laid out lazily: the whole text is thousands of lines.
    private let paragraphs: [(isHeading: Bool, text: String)] = (notices ?? "The licenses are missing from this build.")
        .components(separatedBy: "\n\n")
        .map { $0.trimmingCharacters(in: .newlines) }
        .filter { !$0.isEmpty }
        .map { paragraph in
            AcknowledgementsView.heading(paragraph).map { (true, $0) } ?? (false, AcknowledgementsView.reflow(paragraph))
        }

    /// A title underlined with `=`, the heading of one module's licenses.
    static func heading(_ paragraph: String) -> String? {
        let lines = paragraph.components(separatedBy: "\n")
        guard lines.count == 2, !lines[1].isEmpty, lines[1].allSatisfy({ $0 == "=" }) else { return nil }
        return lines[0]
    }

    /// A paragraph wrapped for 80 columns, rejoined so it wraps to the screen: each line runs
    /// on from the one before, except a rule of dashes and a list item's first line.
    static func reflow(_ paragraph: String) -> String {
        paragraph.components(separatedBy: "\n").reduce(into: "") { text, line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            let breaks = text.isEmpty || trimmed.allSatisfy { $0 == "-" }
                || trimmed.hasPrefix("* ") || trimmed.hasPrefix("- ") || trimmed.range(of: #"^\(?(\d+|[a-z])[.)] "#, options: .regularExpression) != nil
            text += (text.isEmpty ? "" : breaks ? "\n" : " ") + trimmed
        }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(paragraphs.indices, id: \.self) { i in
                    if paragraphs[i].isHeading {
                        Text(verbatim: paragraphs[i].text)
                            .font(.subheadline.weight(.semibold))
                            .accessibilityAddTraits(.isHeader)
                            .padding(.top, 12)
                    } else {
                        Text(verbatim: paragraphs[i].text)
                    }
                }
            }
            .font(.caption.monospaced())
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .ovenlightGroupedBackground()
        .navigationTitle("Acknowledgements")
        .navigationBarTitleDisplayMode(.inline)
    }
}
