import Foundation

/// An invite to one app on someone else's machine, as the owner's connector writes it: a
/// universal link, `https://ovenlight.app/join#v=1&control=&key=&owner=&app=&invite=&host=&name=&to=`,
/// or the same fields as `ovenlight://join?...`. The fields ride in the universal link's
/// fragment, which never reaches the website. The key is a single-use credential until
/// Ovenlight has joined with it.
struct InviteLink: Codable, Equatable, Hashable {
    /// The owner's control server, where Ovenlight's guest node registers.
    var control: URL
    /// Single-use auth key for the guest node; also proves the claim.
    var key: String
    /// How the owner calls themself ("Riley").
    var owner: String
    /// The app's slug on the owner's connector.
    var app: String
    /// The invite's ID on the connector.
    var invite: String
    /// The app node's name, where Ovenlight claims the invite and opens the app.
    var host: String
    /// The app's name.
    var name: String
    /// What the owner called the person they invited ("Sam").
    var to: String

    static let version = "1"
    static let maxLabel = 64

    enum ParseError: Error, Equatable {
        case notAnInvite
        case unsupportedVersion
        case insecureControl
        case missingField(String)
        case invalidField(String)
    }

    var appURL: URL { URL(string: "https://\(host)/")! }

    /// The owner's network, as the part of the app's name after its first label. Two
    /// invites with the same control server and domain come from the same owner.
    var networkDomain: String { InviteLink.networkDomain(of: host) }

    /// The link scheme Ovenlight opens.
    static let scheme = "ovenlight"

    /// Tailscale's control server, by either of its names.
    static let tailscaleControlHosts: Set<String> = ["controlplane.tailscale.com", "login.tailscale.com"]

    /// Registers with Tailscale's own control server rather than one the owner runs.
    var usesTailscaleControl: Bool { Self.controlKey(control) == Self.tailscaleKey }

    private static let tailscaleKey = "tailscale"

    private static func controlKey(_ url: URL) -> String {
        let scheme = url.scheme?.lowercased() ?? ""
        let host = Discovery.normalizedHost(url.host ?? "")
        let port = url.port ?? (scheme == "http" ? 80 : 443)
        if scheme == "https", tailscaleControlHosts.contains(host), port == 443 { return tailscaleKey }
        return "\(scheme)://\(host):\(port)"
    }

    /// Whether a node's MagicDNS suffix is the network this invite names. A guest node
    /// must be in it before it claims or counts as joined.
    func isNetwork(_ magicDNSSuffix: String?) -> Bool {
        guard let suffix = magicDNSSuffix.map(Discovery.normalizedHost), !suffix.isEmpty else { return false }
        return suffix == networkDomain
    }

    static func networkDomain(of host: String) -> String {
        let host = Discovery.normalizedHost(host)
        guard let dot = host.firstIndex(of: ".") else { return host }
        return String(host[host.index(after: dot)...])
    }

    /// The page universal links open. With Ovenlight installed iOS hands them to it
    /// (Associated Domains); otherwise the page offers Ovenlight.
    static let joinPage = URL(string: "https://ovenlight.app/join")!

    /// The fields in the connector's order, form-encoded: the query of `ovenlight://join`
    /// and the fragment of the universal link.
    private var encodedFields: String {
        [("v", Self.version), ("control", control.absoluteString), ("key", key), ("owner", owner),
         ("app", app), ("invite", invite), ("host", host), ("name", name), ("to", to)]
            .map { "\($0.0)=\(Self.formEncode($0.1))" }.joined(separator: "&")
    }

    /// The link to send: the universal link, which opens Ovenlight when it's installed.
    var universalURL: URL { URL(string: Self.joinPage.absoluteString + "#" + encodedFields)! }

    /// The same invite as `ovenlight://join?...`, for when the universal link opens the page.
    var url: URL {
        var c = URLComponents()
        c.scheme = Self.scheme
        c.host = "join"
        c.percentEncodedQuery = encodedFields
        return c.url!
    }

    /// Whether a link is an invite, in any form Ovenlight accepts, before checking its fields.
    static func isInvite(_ url: URL) -> Bool { encodedFields(of: url) != nil }

    /// The form-encoded fields of an invite link: the query of `ovenlight://join`, or
    /// the fragment of the universal link.
    private static func encodedFields(of url: URL) -> String? {
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == Self.scheme {
            guard url.host?.lowercased() == "join" else { return nil }
            return URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery ?? ""
        }
        guard scheme == "https", url.host?.lowercased() == joinPage.host, url.port == nil,
              universalPaths.contains(url.path) else { return nil }
        guard let fields = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedFragment,
              !fields.isEmpty else { return nil }
        return fields
    }

    /// Paths of the universal link: the site serves the page with and without `.html`,
    /// and a trailing slash still reaches it.
    private static let universalPaths: Set<String> = ["/join", "/join/", "/join.html"]

    /// Reads and checks a link. `allowLocalHTTP` accepts an http control server on this
    /// machine, for development against a local Headscale; release builds never do.
    static func parse(_ url: URL, allowLocalHTTP: Bool = isDebugBuild) throws(ParseError) -> InviteLink {
        guard let fields = encodedFields(of: url) else { throw .notAnInvite }
        let items = fields.split(separator: "&").map { pair in
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            return (name: String(parts[0]), value: parts.count > 1 ? String(parts[1]) : "")
        }
        // The connector form-encodes: "+" is a space, a literal plus arrives as %2B.
        func value(_ name: String) -> String? {
            items.first { $0.name == name }?.value
                .replacingOccurrences(of: "+", with: "%20").removingPercentEncoding
        }
        func required(_ name: String) throws(ParseError) -> String {
            guard let text = value(name)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
                throw .missingField(name)
            }
            return text
        }
        guard value("v") == version else { throw .unsupportedVersion }

        let controlText = try required("control")
        guard let control = URL(string: controlText), let controlHost = control.host, !controlHost.isEmpty,
              control.user == nil, control.password == nil else { throw .invalidField("control") }
        switch control.scheme?.lowercased() {
        case "https": break
        case "http" where allowLocalHTTP && ["127.0.0.1", "localhost", "::1"].contains(controlHost.lowercased()): break
        default: throw .insecureControl
        }

        let key = try required("key")
        guard key.count <= 256, key.allSatisfy({ $0.isASCII && !$0.isWhitespace }) else { throw .invalidField("key") }
        let app = try required("app")
        guard app.count <= 64, app.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else {
            throw .invalidField("app")
        }
        let invite = try required("invite")
        guard invite.count <= 64, invite.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }) else {
            throw .invalidField("invite")
        }
        let host = Discovery.normalizedHost(try required("host"))
        guard isDNSName(host) else { throw .invalidField("host") }

        return InviteLink(control: control, key: key, owner: label(value("owner")) ?? "Someone", app: app, invite: invite,
                          host: host, name: label(value("name")) ?? app, to: label(value("to")) ?? "")
    }

    /// Finds an invite in pasted text, for example the whole message the owner sent.
    static func parse(text: String, allowLocalHTTP: Bool = isDebugBuild) throws(ParseError) -> InviteLink {
        let pattern = #"(ovenlight://join\?|https://ovenlight\.app/join(\.html|/)?#)[^\s<>"']+"#
        guard let range = text.range(of: pattern, options: [.regularExpression, .caseInsensitive]),
              let url = URL(string: String(text[range])) else { throw .notAnInvite }
        return try parse(url, allowLocalHTTP: allowLocalHTTP)
    }

    /// A display label: trimmed, without control characters, at most 64 characters.
    static func label(_ text: String?) -> String? {
        guard let text else { return nil }
        let cleaned = String(text.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? nil : String(cleaned.prefix(maxLabel))
    }

    /// A multi-label DNS name: letters, digits and hyphens, no IP address, no port.
    static func isDNSName(_ host: String) -> Bool {
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 2, host.count <= 253, !host.allSatisfy({ $0.isNumber || $0 == "." }) else { return false }
        return labels.allSatisfy { label in
            !label.isEmpty && label.count <= 63 && !label.hasPrefix("-") && !label.hasSuffix("-")
                && label.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
        }
    }

    private static func formEncode(_ text: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return (text.addingPercentEncoding(withAllowedCharacters: allowed) ?? text).replacingOccurrences(of: "%20", with: "+")
    }

    static var isDebugBuild: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
}

extension InviteLink.ParseError {
    /// What Ovenlight says about a link it can't use. Neutral: anyone can see it.
    var message: String {
        switch self {
        case .notAnInvite: "This isn't an Ovenlight invite. Ask for the link again and copy the whole link."
        case .unsupportedVersion: "This invite needs a newer Ovenlight. Update Ovenlight, then open the invite again."
        case .insecureControl: "This invite isn't secure, so Ovenlight won't use it. Ask for a new one."
        case .missingField, .invalidField: "This link is incomplete. Ask for it again and copy the whole link."
        }
    }
}
