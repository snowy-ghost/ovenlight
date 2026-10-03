import Foundation

/// When an app reached through Ovenlight's node may load its page. Opening the app shows its
/// placeholder at once; the first navigation waits until the node is ready and a probe
/// to that app's host through the node's current proxy answers, so an app that can't be
/// reached shows Can't Reach rather than a failed page.
enum AppLoadGate: Equatable {
    /// Ovenlight's own connection isn't ready.
    case waiting
    /// Checking that the app's host answers through the current proxy.
    case probing
    /// The page may load, and did.
    case ready
    /// The host didn't answer in time.
    case unreachable

    enum Event: Equatable {
        case nodeReady
        case nodeNotReady
        case reached
        case gaveUp
        /// Try Again, or Reload from the app menu.
        case retry
        /// The first navigation failed at the connection level; check the path again.
        case loadFailed
    }

    /// How long the app's host may take to answer once the node is ready.
    static let reachTimeout: TimeInterval = 10
    /// How long to wait for the node itself before giving up on this app.
    static let nodeTimeout: TimeInterval = 30

    func next(_ event: Event) -> AppLoadGate {
        switch (self, event) {
        case (_, .retry):
            return .waiting
        case (.ready, .loadFailed):
            return .waiting
        case (.ready, _), (.unreachable, _):
            // A loaded page stays put through node restarts; a gate that gave up waits for Retry.
            return self
        case (.waiting, .nodeReady):
            return .probing
        case (.probing, .nodeNotReady):
            return .waiting
        case (.probing, .reached):
            return .ready
        case (.waiting, .gaveUp), (.probing, .gaveUp):
            return .unreachable
        default:
            return self
        }
    }
}

/// Words for connection states that anyone may see. None of them names Tailscale or the
/// tailnet, so a guest, who never signs in, can be shown every one. Copy that asks the
/// owner to act on their Tailscale account lives only in the sign-in card, Settings and the
/// approval prompt over the owner's apps.
enum ConnectionCopy {
    static let connecting = "Connecting…"
    static let stopped = "Ovenlight lost its connection."
    static let couldNotStart = "Ovenlight couldn't connect."

    static func connecting(to app: String) -> String { "Connecting to \(app)…" }
    static func cantReach(_ app: String) -> String { "Can't Reach \(app)" }
    static let cantReachDetail = "Its computer may be asleep or offline. Try again in a moment."
    static func signIn(toOpen app: String) -> String { "Sign In to Open \(app)" }

    // Apps someone shared, and joining them. Guests see all of these.

    static func sharedBy(_ owner: String) -> String { "from \(owner)" }
    static func joinTitle(owner: String, app: String) -> String { "Join \(owner)'s \(app)?" }
    static func joinDetail(owner: String, app: String, to guest: String) -> String {
        let who = guest.isEmpty ? "\(owner) invited you." : "\(owner) invited you as \(guest)."
        return "\(who) \(app) runs on \(owner)'s computer. You don't need an account."
    }
    static func appAddress(_ host: String) -> String { "Address: \(host)" }
    static func privateServer(_ server: String, owner: String) -> String {
        "This invite uses \(server), a connection server \(owner) chose. Join only if you know this invite came from \(owner)."
    }
    static func joining(owner: String, app: String) -> String { "Joining \(owner)'s \(app)…" }
    static let joiningDetail = "This can take up to a minute."
    static func joined(_ app: String) -> String { "\(app) Is Ready" }
    static let noLongerSharedTitle = "No Longer Shared with You"
    static func noLongerShared(apps: [String], owner: String) -> String {
        let names = ListFormatter.localizedString(byJoining: apps)
        let them = apps.count == 1 ? "it and everything it" : "them and everything they"
        return "\(owner) stopped sharing \(names) with you, so Ovenlight removed \(them) stored on this iPhone."
    }
    static let joinWithInvite = "Join with Invite"
    static let invitePrompt = "Paste the invite link you were sent, or scan its code."
    static let feedbackSent = "Feedback Sent"
    static func feedbackSentDetail(to owner: String?) -> String {
        owner.map { "\($0) will see it." } ?? "It's in your feedback in People & Sharing."
    }
    static let feedbackFailed = "Couldn't Send Feedback"
    static let feedbackTooMany = "That's a lot of feedback for one hour. Try again later."
    static let feedbackNetwork = "Ovenlight couldn't reach the app's computer. Try again in a moment."
    static let reportProblem = "Report a Problem"
    static func leaveApps(_ owner: String) -> String { "Leave \(owner)'s Apps" }
    static func leaveTitle(_ owner: String) -> String { "Leave \(owner)'s apps?" }
    static func leaveDetail(_ owner: String) -> String {
        "\(owner)'s apps and everything they stored on this iPhone will be removed. You'll need a new invite from \(owner) to open them again."
    }
}
