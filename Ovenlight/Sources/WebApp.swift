import Foundation

/// One web app the user can open. Each app gets its own website data store, so cookies,
/// localStorage and caches never leak between apps.
struct WebApp: Codable, Identifiable, Hashable {
    var id: UUID
    var name: String
    var startURL: URL
    var themeColorHex: String?
    /// File name of the cached icon inside the icons directory, if one was fetched.
    var iconFile: String?
    var dataStoreID: UUID
    /// Found on the tailnet through a connector's ovenlight.json, rather than added by URL.
    var discovered: Bool
    /// The name the app's manifest gave it last, so a later rename in the manifest can
    /// replace it without overwriting a name the user chose.
    var manifestName: String?
    /// The app's slug on its connector, from ovenlight.json or the invite.
    var slug: String?
    /// Set for an app someone else shared: the guest membership whose node reaches it.
    /// Nil for the owner's own apps and apps added by address.
    var membershipID: String?
    /// Who shared it ("Riley"), for "from Riley" on its tile.
    var sharedBy: String?
    /// Arrived on its own (shared with this iPhone, or discovered) and not opened yet: its
    /// tile shows the blue dot the Home Screen gives newly installed apps.
    var isNew: Bool

    init(name: String, startURL: URL, themeColorHex: String? = nil, discovered: Bool = false) {
        self.id = UUID()
        self.name = name
        self.startURL = startURL
        self.themeColorHex = themeColorHex
        self.iconFile = nil
        self.dataStoreID = UUID()
        self.discovered = discovered
        self.isNew = false
    }

    /// Shared with this iPhone by someone else, as a guest.
    var isShared: Bool { membershipID != nil }

    /// Served by an Ovenlight connector, which answers feedback and whoami.
    var hasConnector: Bool { isShared || discovered }

    var host: String { startURL.host ?? "" }

    /// True unless the user renamed the app.
    var hasDefaultName: Bool { name.isEmpty || name == host || name == manifestName }
}

extension WebApp {
    /// Only what opens the app and finds its data is required; the rest falls back, so a
    /// file another version wrote still loads.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        startURL = try c.decode(URL.self, forKey: .startURL)
        dataStoreID = try c.decode(UUID.self, forKey: .dataStoreID)
        // A shared app without its membership would count as the owner's own.
        membershipID = try c.decodeIfPresent(String.self, forKey: .membershipID)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        themeColorHex = try? c.decodeIfPresent(String.self, forKey: .themeColorHex)
        iconFile = try? c.decodeIfPresent(String.self, forKey: .iconFile)
        discovered = (try? c.decodeIfPresent(Bool.self, forKey: .discovered)) ?? false
        manifestName = try? c.decodeIfPresent(String.self, forKey: .manifestName)
        slug = try? c.decodeIfPresent(String.self, forKey: .slug)
        sharedBy = try? c.decodeIfPresent(String.self, forKey: .sharedBy)
        isNew = (try? c.decodeIfPresent(Bool.self, forKey: .isNew)) ?? false
    }
}

/// Folds apps found on the tailnet into the user's list: one tile per host, however the
/// app got there, and never one the user removed.
enum DiscoveryMerge {
    static func merge(_ found: [DiscoveredApp], into apps: [WebApp], dismissedHosts: Set<String>) -> [WebApp] {
        var apps = apps
        var seen = Set<String>()
        for item in found {
            let host = Discovery.normalizedHost(item.host)
            guard !host.isEmpty, !dismissedHosts.contains(host), seen.insert(host).inserted else { continue }
            // Only the owner's own apps; a shared app belongs to its owner's network.
            if let index = apps.firstIndex(where: { !$0.isShared && Discovery.normalizedHost($0.host) == host }) {
                if apps[index].hasDefaultName { apps[index].name = item.manifest.name }
                apps[index].manifestName = item.manifest.name
                if let theme = item.manifest.themeColor { apps[index].themeColorHex = theme }
                if let slug = item.manifest.slug { apps[index].slug = slug }
            } else {
                var app = WebApp(name: item.manifest.name, startURL: item.startURL,
                                 themeColorHex: item.manifest.themeColor, discovered: true)
                app.manifestName = item.manifest.name
                app.slug = item.manifest.slug
                app.isNew = true
                apps.append(app)
            }
        }
        return apps
    }
}

/// Parses an app's home page and web manifest into display metadata.
enum ManifestParser {
    struct Metadata: Equatable {
        var name: String?
        var themeColorHex: String?
        var iconURL: URL?
    }

    /// Finds `<link rel="manifest">` and `<link rel="apple-touch-icon">` in an HTML page.
    static func links(inHTML html: String, baseURL: URL) -> (manifest: URL?, touchIcon: URL?) {
        var manifest: URL?
        var touchIcon: URL?
        for tag in matches(of: "<link\\b[^>]*>", in: html) {
            guard let rel = attribute("rel", in: tag)?.lowercased(),
                  let href = attribute("href", in: tag),
                  let url = URL(string: href, relativeTo: baseURL)?.absoluteURL else { continue }
            let rels = rel.split(separator: " ")
            if manifest == nil, rels.contains("manifest") { manifest = url }
            if touchIcon == nil, rels.contains("apple-touch-icon") { touchIcon = url }
        }
        return (manifest, touchIcon)
    }

    static func title(inHTML html: String) -> String? {
        guard let range = html.range(of: "<title[^>]*>([^<]*)</title>",
                                     options: [.regularExpression, .caseInsensitive]) else { return nil }
        let inner = html[range].replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        let trimmed = inner.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Reads name, theme color and the best raster icon from a web manifest.
    static func metadata(fromManifest data: Data, manifestURL: URL) -> Metadata {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return Metadata()
        }
        let name = (json["name"] as? String) ?? (json["short_name"] as? String)
        let theme = json["theme_color"] as? String
        var best: (size: Int, url: URL)?
        for icon in (json["icons"] as? [[String: Any]]) ?? [] {
            guard let src = icon["src"] as? String,
                  let url = URL(string: src, relativeTo: manifestURL)?.absoluteURL else { continue }
            // UIImage can't decode SVG; skip it and anything declared as non-raster.
            if let type = icon["type"] as? String, type.contains("svg") { continue }
            if url.pathExtension.lowercased() == "svg" { continue }
            let size = largestSize(icon["sizes"] as? String)
            // Prefer the largest icon up to 512px; anything bigger only wastes download.
            let score = size > 512 ? 512 - (size - 512) / 1000 : size
            if best == nil || score > best!.size { best = (score, url) }
        }
        return Metadata(name: name, themeColorHex: theme, iconURL: best?.url)
    }

    private static func largestSize(_ sizes: String?) -> Int {
        guard let sizes else { return 0 }
        return sizes.split(separator: " ").compactMap { token -> Int? in
            let parts = token.lowercased().split(separator: "x")
            guard parts.count == 2 else { return nil }
            return Int(parts[0])
        }.max() ?? 0
    }

    private static func matches(of pattern: String, in text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
    }

    private static func attribute(_ name: String, in tag: String) -> String? {
        let pattern = "\\b\(name)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return nil }
        let ns = tag as NSString
        guard let match = regex.firstMatch(in: tag, range: NSRange(location: 0, length: ns.length)) else { return nil }
        for group in 1...3 where match.range(at: group).location != NSNotFound {
            return ns.substring(with: match.range(at: group))
        }
        return nil
    }
}

/// Decides what happens when an app's page tries to navigate somewhere.
enum NavigationPolicy {
    enum Decision: Equatable {
        case allow
        /// Leave the app: open in an in-app Safari sheet (web) or hand to the system (mailto, tel...).
        case openExternally(URL)
        case refuse
    }

    /// Schemes iOS itself confirms before opening (calls). Every other app Ovenlight asks
    /// about first: a page's script can fake a link tap, and `shortcuts://` runs shortcuts.
    static let systemConfirms: Set<String> = ["tel", "facetime", "facetime-audio"]

    static func asksBeforeOpening(_ url: URL) -> Bool {
        !systemConfirms.contains(url.scheme?.lowercased() ?? "")
    }

    /// The link as the confirmation names it, cut short.
    static func target(of url: URL) -> String {
        let text = url.absoluteString
        return "\u{201C}" + (text.count > 80 ? String(text.prefix(79)) + "\u{2026}" : text) + "\u{201D}"
    }

    /// `tapped`: a link activated in the main frame. Other apps (mailto, tel...)
    /// open only then, never from a script or a frame.
    static func decide(url: URL, appHost: String, isMainFrame: Bool, tapped: Bool) -> Decision {
        let scheme = url.scheme?.lowercased() ?? ""
        switch scheme {
        case "http", "https":
            if !isMainFrame { return .allow }
            return Discovery.normalizedHost(url.host ?? "") == Discovery.normalizedHost(appHost) ? .allow : .openExternally(url)
        case "about", "data", "blob", "javascript":
            return .allow
        default:
            // Never Ovenlight's own scheme: a page could hand it an invite of its choosing.
            return tapped && isMainFrame && scheme != InviteLink.scheme ? .openExternally(url) : .refuse
        }
    }

    /// A popup loaded in the app's own view goes out as Ovenlight's load, without the
    /// cross-site marking the connector checks. Only the app's own pages keep the method
    /// and body; a popup from a frame of another site becomes a plain GET, which is all
    /// that site could send the app anyway.
    static func popupRequest(_ request: URLRequest, fromScheme scheme: String?, host: String?, appHost: String) -> URLRequest {
        let own = scheme == "https" && Discovery.normalizedHost(host ?? "") == Discovery.normalizedHost(appHost)
        guard !own, let url = request.url else { return request }
        return URLRequest(url: url)
    }
}

/// Turns what someone types into Add App into an app address. A bare host gets https;
/// anything that isn't https is refused. Add App opens only the owner's own computers:
/// apps anywhere else come by an invite, or by discovery once their owner publishes them.
enum AppAddress {
    static func url(from text: String) -> URL? {
        var text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.contains("://") { text = "https://" + text }
        guard let url = URL(string: text), url.scheme?.lowercased() == "https",
              let host = url.host, host.contains(".") else { return nil }
        return url
    }

    /// The address, only if it's on the owner's own tailnet. Nil `ownerSuffix` (owner mode
    /// off, or not signed in yet) takes nothing.
    static func url(from text: String, ownerSuffix: String?) -> URL? {
        guard let url = url(from: text),
              Routing.isOnOwnersTailnet(host: url.host ?? "", ownerSuffix: ownerSuffix) else { return nil }
        return url
    }

    // Guests can open Add App too, so none of this names the network underneath.
    static let signInFirst = "Add App opens apps on your own computers. Connect to them first, then add an app by its address."
    static let notYourComputer = "Ovenlight opens apps on your own computers. Publish this app with the Ovenlight connector, or ask its owner for an invite."
}
