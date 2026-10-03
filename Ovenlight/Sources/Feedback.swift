import SwiftUI
import WebKit

/// What Ovenlight posts to the app's connector at `/__ovenlight/feedback`: a note, the page
/// it's about, and a screenshot of the web view.
struct FeedbackPayload: Encodable, Equatable {
    var note: String
    var pageUrl: String
    var screenshotPngBase64: String?

    /// The connector's limits (connector/feedback.go).
    static let maxNote = 4000
    static let maxScreenshot = 5 << 20
    static let maxPageURL = 2048

    enum Problem: Error, Equatable {
        case empty
        case noteTooLong
        case screenshotTooLarge
    }

    static func make(note: String, pageURL: URL?, png: Data?) throws(Problem) -> FeedbackPayload {
        let note = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !note.isEmpty || png != nil else { throw .empty }
        // The connector counts code points, not what the eye sees as characters.
        guard note.unicodeScalars.count <= maxNote else { throw .noteTooLong }
        if let png, png.count > maxScreenshot { throw .screenshotTooLarge }
        let page = pageURL?.absoluteString ?? ""
        return FeedbackPayload(note: note, pageUrl: page.count <= maxPageURL ? page : "",
                               screenshotPngBase64: png?.base64EncodedString())
    }

    /// A PNG of the snapshot under the connector's limit, smaller if it has to be.
    static func png(from image: UIImage, limit: Int = maxScreenshot - 256 << 10) -> Data? {
        for scale in [image.scale, 2, 1] where scale <= image.scale {
            let format = UIGraphicsImageRendererFormat()
            format.scale = scale
            let data = UIGraphicsImageRenderer(size: image.size, format: format).pngData { _ in
                image.draw(in: CGRect(origin: .zero, size: image.size))
            }
            if data.count <= limit { return data }
        }
        return nil
    }
}

enum FeedbackResult: Equatable {
    case sent
    case failed(String)

    static func interpret(status: Int, body: Data) -> FeedbackResult {
        switch status {
        case 200: return .sent
        case 429: return .failed(ConnectionCopy.feedbackTooMany)
        default:
            let message = AdminAPI.errorMessage(body).map { $0.prefix(1).uppercased() + $0.dropFirst() + "." }
            return .failed(message ?? ConnectionCopy.feedbackNetwork)
        }
    }
}

enum FeedbackSender {
    @MainActor
    static func send(_ payload: FeedbackPayload, about app: WebApp) async -> FeedbackResult {
        guard let session = NodeManager.shared.uploadSession(for: app, timeout: 30),
              let url = URL(string: "https://\(app.host)/__ovenlight/feedback") else {
            return .failed(ConnectionCopy.feedbackNetwork)
        }
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(payload)
        guard let (data, response) = try? await session.data(for: request),
              let status = (response as? HTTPURLResponse)?.statusCode else {
            return .failed(ConnectionCopy.feedbackNetwork)
        }
        if status == 403, app.isShared { await GuestManager.shared.verifyAccess(to: app) }
        return FeedbackResult.interpret(status: status, body: data)
    }
}

/// Send Feedback from the app menu: a note and, unless left out, what the screen shows.
struct FeedbackSheet: View {
    let app: WebApp
    let screenshot: UIImage?
    let pageURL: URL?

    @Environment(\.dismiss) private var dismiss
    @State private var note = ""
    @State private var includeScreenshot = true
    @State private var sending = false
    @State private var result: FeedbackResult?
    @FocusState private var focused: Bool

    private var canSend: Bool {
        !sending && (!note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (includeScreenshot && screenshot != nil))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("What's working, or what isn't?", text: $note, axis: .vertical)
                        .lineLimit(4...10)
                        .focused($focused)
                } footer: {
                    Text(app.sharedBy.map { "Goes to \($0), who runs \(app.name)." } ?? "Goes to your feedback in People & Sharing.")
                }
                .ovenlightRows()
                if let screenshot {
                    Section {
                        Toggle("Include Screenshot", isOn: $includeScreenshot.animation())
                        if includeScreenshot {
                            Image(uiImage: screenshot)
                                .resizable()
                                .scaledToFit()
                                .frame(maxHeight: 260)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .strokeBorder(Color.primary.opacity(0.1), lineWidth: 0.5))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 4)
                                .accessibilityLabel("Screenshot of \(app.name)")
                        }
                    }
                    .ovenlightRows()
                }
                if case .failed(let message) = result {
                    Section {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .labelStyle(.warning)
                    }
                    .ovenlightRows()
                }
            }
            .ovenlightGroupedBackground()
            .navigationTitle("Send Feedback")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if sending {
                        ProgressView()
                    } else {
                        Button("Send") { Task { await send() } }.disabled(!canSend)
                    }
                }
            }
            .overlay {
                if result == .sent {
                    ContentUnavailableView {
                        Label {
                            Text(ConnectionCopy.feedbackSent)
                        } icon: {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        }
                    } description: {
                        Text(ConnectionCopy.feedbackSentDetail(to: app.sharedBy))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.groupedBackground)
                    .transition(.opacity)
                }
            }
            .interactiveDismissDisabled(sending)
            .onAppear { if screenshot == nil { focused = true } }
        }
        .sensoryFeedback(trigger: result) { _, new in
            switch new {
            case .sent: .success
            case .failed: .error
            case nil: nil
            }
        }
    }

    private func send() async {
        let png = includeScreenshot ? screenshot.flatMap { FeedbackPayload.png(from: $0) } : nil
        let payload: FeedbackPayload
        do {
            payload = try FeedbackPayload.make(note: note, pageURL: pageURL, png: png)
        } catch .noteTooLong {
            result = .failed("Keep the note under \(FeedbackPayload.maxNote) characters.")
            return
        } catch {
            result = .failed(ConnectionCopy.feedbackFailed)
            return
        }
        sending = true
        result = nil
        let outcome = await FeedbackSender.send(payload, about: app)
        sending = false
        withAnimation { result = outcome }
        if outcome == .sent {
            try? await Task.sleep(for: .seconds(1.4))
            dismiss()
        }
    }
}
