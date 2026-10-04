import Combine
import ImageIO
import SwiftUI
import WebKit

/// The user's apps, persisted as JSON in Application Support, with icons cached beside it.
@MainActor
final class AppRegistry: ObservableObject {
    @Published private(set) var apps: [WebApp] = []
    /// Hosts of apps the user removed; discovery won't add them back.
    private var dismissedHosts: Set<String> = []
    /// A file is there but couldn't be read (a prewarmed launch before the first unlock)
    /// or decoded. Nothing is saved over it and no data store is swept, until a reload
    /// reads it.
    private(set) var loadFailed = false
    private var reloadOnUnlock: AnyCancellable?

    private let directory: URL
    private var fileURL: URL { directory.appendingPathComponent("apps.json") }
    private var dismissedURL: URL { directory.appendingPathComponent("dismissed-hosts.json") }
    var iconsDirectory: URL { directory.appendingPathComponent("icons", isDirectory: true) }
    /// The session to fetch an app's page and icon with: through the app's node (nil
    /// while it isn't up), directly for apps outside any tailnet.
    private let session: @MainActor (WebApp) -> URLSession?

    init(directory: URL? = nil, session: (@MainActor (WebApp) -> URLSession?)? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Ovenlight", isDirectory: true)
        self.directory = base
        self.session = session ?? { NodeManager.shared.session(for: $0) }
        try? FileManager.default.createDirectory(at: iconsDirectory, withIntermediateDirectories: true)
        load()
        reloadOnUnlock = SavedJSON.whenUnlocked { [weak self] in self?.reloadIfFailed() }
    }

    /// Reads the files again if they couldn't be read before.
    func reloadIfFailed() {
        if loadFailed { load() }
    }

    private func load() {
        do {
            let loaded = try SavedJSON.read([WebApp].self, from: fileURL) ?? []
            dismissedHosts = Set(try SavedJSON.read([String].self, from: dismissedURL) ?? [])
            apps = loaded
            loadFailed = false
        } catch {
            loadFailed = true
        }
    }

    private func save() {
        guard !loadFailed else { return }
        if let data = try? JSONEncoder().encode(apps) {
            try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
        if let data = try? JSONEncoder().encode(dismissedHosts) {
            try? data.write(to: dismissedURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// Icons read once from disk; views ask on every render.
    private var icons: [String: UIImage] = [:]

    func icon(for app: WebApp) -> UIImage? {
        guard let file = app.iconFile else { return nil }
        if let icon = icons[file] { return icon }
        let icon = (try? Data(contentsOf: iconsDirectory.appendingPathComponent(file))).flatMap(Self.iconImage(from:))
        icons[file] = icon
        return icon
    }

    /// Decodes an icon no larger than the largest one drawn (96 points, while an app
    /// opens) at 3x. The server chooses the image, and earlier builds saved it as it came,
    /// so one over 4096 pixels a side is refused before decoding: for an interlaced PNG, a
    /// GIF or a WebP, ImageIO makes the full-size bitmap even for a thumbnail.
    nonisolated static func iconImage(from data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int, width <= 4096,
              let height = properties[kCGImagePropertyPixelHeight] as? Int, height <= 4096 else { return nil }
        return Screenshot.image(from: data, maxPixels: 288)
    }

    @discardableResult
    func add(url: URL) -> WebApp {
        let app = WebApp(name: url.host ?? url.absoluteString, startURL: url)
        dismissedHosts.remove(Discovery.normalizedHost(app.host))
        apps.append(app)
        save()
        Task { await refreshMetadata(for: app.id) }
        return app
    }

    func rename(_ id: UUID, to name: String) {
        guard let index = apps.firstIndex(where: { $0.id == id }) else { return }
        apps[index].name = name
        save()
    }

    /// Adds an app someone shared, once its invite is claimed. An app already listed for
    /// that host in that membership is returned as it is. Every app of the membership takes
    /// the owner's name as this claim gave it, the same one its membership keeps.
    @discardableResult
    func addShared(name: String, host: String, slug: String, membershipID: String, sharedBy: String) -> WebApp {
        let host = Discovery.normalizedHost(host)
        let renamed = apps.indices.filter { apps[$0].membershipID == membershipID && apps[$0].sharedBy != sharedBy }
        for index in renamed { apps[index].sharedBy = sharedBy }
        if !renamed.isEmpty { save() }
        if let existing = apps.first(where: { $0.membershipID == membershipID && Discovery.normalizedHost($0.host) == host }) {
            return existing
        }
        var app = WebApp(name: name, startURL: URL(string: "https://\(host)/")!)
        app.manifestName = name
        app.slug = slug
        app.membershipID = membershipID
        app.sharedBy = sharedBy
        app.isNew = true
        apps.append(app)
        save()
        Task { await refreshMetadata(for: app.id) }
        return app
    }

    /// The app was opened: its tile loses the new dot, for good.
    func markOpened(_ id: UUID) {
        guard let index = apps.firstIndex(where: { $0.id == id }), apps[index].isNew else { return }
        apps[index].isNew = false
        save()
    }

    /// Moves one app so it ends up at `index` (clamped), the way the Home Screen drops it.
    func move(_ id: UUID, to index: Int) {
        let moved = HomeReorder.moving(apps, id: id, to: index)
        guard moved != apps else { return }
        apps = moved
        save()
    }

    /// Removes the app and deletes everything it stored (cookies, storage, caches).
    /// Discovery won't add one of the owner's own apps back.
    func remove(_ id: UUID) async {
        guard let app = apps.first(where: { $0.id == id }) else { return }
        apps.removeAll { $0.id == id }
        if !app.isShared { dismissedHosts.insert(Discovery.normalizedHost(app.host)) }
        save()
        WebViewPool.shared.discard(app.id)
        if let file = app.iconFile {
            icons[file] = nil
            try? FileManager.default.removeItem(at: iconsDirectory.appendingPathComponent(file))
        }
        StatusBarTint.forget(app)
        await Self.removeDataStore(app.dataStoreID)
    }

    /// Deletes a data store's cookies, storage and caches. Always go through
    /// here: touching the default store first starts WebKit, and removing a store before
    /// anything else has used it crashes the app when the removal completes (it did at
    /// launch in build 3, when a store was removed before any web view existed).
    ///
    /// A web view that is still closing keeps its store in use, and removing a store in
    /// use fails. So the data is cleared at once, and the store itself is removed once
    /// it's free: retried here, and swept at the next launch.
    private static func removeDataStore(_ id: UUID) async {
        _ = WKWebsiteDataStore.default()
        await clearData(inStore: id)
        for delay in [0.0, 1, 2, 4, 8] {
            try? await Task.sleep(for: .seconds(delay))
            if (try? await WKWebsiteDataStore.remove(forIdentifier: id)) != nil { return }
        }
    }

    private static func clearData(inStore id: UUID) async {
        let store = WKWebsiteDataStore(forIdentifier: id)
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
    }

    /// Removes data stores no app uses: ones a removal couldn't delete while a web view
    /// still held them. Runs once WebKit is up, after launch.
    func sweepUnusedDataStores() async {
        _ = WKWebsiteDataStore.default()
        let all: [UUID] = await withCheckedContinuation { continuation in
            WKWebsiteDataStore.fetchAllDataStoreIdentifiers { continuation.resume(returning: $0) }
        }
        for id in unusedDataStores(among: all) {
            try? await WKWebsiteDataStore.remove(forIdentifier: id)
        }
    }

    /// None while the apps couldn't be read: every store would look unused.
    func unusedDataStores(among all: [UUID]) -> [UUID] {
        guard !loadFailed else { return [] }
        let used = Set(apps.map(\.dataStoreID))
        return all.filter { !used.contains($0) }
    }

    /// Signs the app out of everything by wiping its data store and starting a fresh one.
    func clearData(_ id: UUID) async {
        guard let index = apps.firstIndex(where: { $0.id == id }) else { return }
        let old = apps[index].dataStoreID
        WebViewPool.shared.discard(id)
        apps[index].dataStoreID = UUID()
        save()
        await Self.removeDataStore(old)
    }

    /// Adds apps found on the tailnet and updates the ones already listed.
    func mergeDiscovered(_ found: [DiscoveredApp]) async {
        let merged = DiscoveryMerge.merge(found, into: apps, dismissedHosts: dismissedHosts)
        if merged != apps {
            apps = merged
            save()
        }
        for item in found {
            guard let app = apps.first(where: { !$0.isShared && Discovery.normalizedHost($0.host) == Discovery.normalizedHost(item.host) }),
                  app.iconFile == nil, let iconURL = item.iconURL else { continue }
            await saveIcon(from: iconURL, for: app.id)
        }
    }

    /// Fetches the app's page and manifest to fill in its name, theme color and icon. A
    /// connector's ovenlight.json comes first when the app has one.
    func refreshMetadata(for id: UUID) async {
        guard let app = apps.first(where: { $0.id == id }), let session = session(app) else { return }
        if app.hasConnector, let url = URL(string: "https://\(app.host)\(OvenlightManifest.path)"),
           let (data, _) = try? await session.data(from: url), let manifest = OvenlightManifest.parse(data),
           let index = apps.firstIndex(where: { $0.id == id }) {
            if apps[index].hasDefaultName { apps[index].name = manifest.name }
            apps[index].manifestName = manifest.name
            if let theme = manifest.themeColor { apps[index].themeColorHex = theme }
            if apps[index].slug == nil { apps[index].slug = manifest.slug }
            save()
            if let iconURL = DiscoveredApp(host: Discovery.normalizedHost(app.host), manifest: manifest).iconURL {
                await saveIcon(from: iconURL, for: id)
                return
            }
        }
        guard let (html, _) = try? await fetchString(app.startURL, session: session) else { return }
        let links = ManifestParser.links(inHTML: html, baseURL: app.startURL)
        var meta = ManifestParser.Metadata(name: ManifestParser.title(inHTML: html))
        if let manifestURL = links.manifest,
           let (data, _) = try? await session.data(from: manifestURL) {
            let fromManifest = ManifestParser.metadata(fromManifest: data, manifestURL: manifestURL)
            meta.name = fromManifest.name ?? meta.name
            meta.themeColorHex = fromManifest.themeColorHex
            meta.iconURL = fromManifest.iconURL
        }
        guard let index = apps.firstIndex(where: { $0.id == id }) else { return }
        // Keep a name the user typed; only replace the placeholder host name.
        if let name = meta.name, apps[index].hasDefaultName, !apps[index].hasConnector {
            apps[index].name = name
        }
        if let theme = meta.themeColorHex { apps[index].themeColorHex = theme }
        save()
        if let iconURL = meta.iconURL ?? links.touchIcon {
            await saveIcon(from: iconURL, for: id)
        }
    }

    private func saveIcon(from url: URL, for id: UUID) async {
        guard let app = apps.first(where: { $0.id == id }), let session = session(app),
              let (data, _) = try? await session.data(from: url),
              let image = await Task.detached(operation: { Self.iconImage(from: data) }).value, let png = image.pngData(),
              let index = apps.firstIndex(where: { $0.id == id }) else { return }
        let file = "\(id.uuidString).png"
        try? png.write(to: iconsDirectory.appendingPathComponent(file), options: .atomic)
        // A refreshed icon keeps its file name, so `apps` may not change; say so anyway.
        objectWillChange.send()
        icons[file] = image
        apps[index].iconFile = file
        save()
    }

    func refreshAllMissingIcons() async {
        for app in apps where app.iconFile == nil {
            await refreshMetadata(for: app.id)
        }
    }

    private func fetchString(_ url: URL, session: URLSession) async throws -> (String, URLResponse) {
        let (data, response) = try await session.data(from: url)
        return (String(decoding: data, as: UTF8.self), response)
    }
}

extension Color {
    init?(hex: String?) {
        guard var hex = hex?.trimmingCharacters(in: .whitespaces) else { return nil }
        if hex.hasPrefix("#") { hex.removeFirst() }
        if hex.count == 3 { hex = hex.map { "\($0)\($0)" }.joined() }
        guard hex.count == 6, let value = UInt32(hex, radix: 16) else { return nil }
        self.init(red: Double((value >> 16) & 0xFF) / 255,
                  green: Double((value >> 8) & 0xFF) / 255,
                  blue: Double(value & 0xFF) / 255)
    }
}
