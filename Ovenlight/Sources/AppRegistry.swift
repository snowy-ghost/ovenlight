import Combine
import ImageIO
import os
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
    /// Apps another build saved that this one can't read (see `SavedJSON.List`).
    private var unread: [Any] = []
    /// Every saved app was read, so cleaning up after the list deletes only what it means to.
    var readInFull: Bool { !loadFailed && unread.isEmpty }
    private var reloadOnUnlock: AnyCancellable?

    private let directory: URL
    private var fileURL: URL { directory.appendingPathComponent("apps.json") }
    private var dismissedURL: URL { directory.appendingPathComponent("dismissed-hosts.json") }
    var iconsDirectory: URL { directory.appendingPathComponent("icons", isDirectory: true) }
    /// The session to fetch an app's page and icon with: through the app's node (nil
    /// while it isn't up, and for a refused app; see `NodeManager.session(for:)`).
    private let session: @MainActor (WebApp) -> URLSession?

    init(directory: URL? = nil, session: (@MainActor (WebApp) -> URLSession?)? = nil) {
        let base = directory ?? SavedJSON.directory
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
            let loaded = try SavedJSON.read([WebApp].self, from: fileURL) ?? .init()
            dismissedHosts = Set(try SavedJSON.read([String].self, from: dismissedURL)?.elements ?? [])
            apps = loaded.elements
            unread = loaded.unread
            loadFailed = false
            if !unread.isEmpty { SavedJSON.log.error("apps: kept \(self.unread.count) this version can't read") }
        } catch {
            loadFailed = true
            SavedJSON.log.error("apps: couldn't read: \(String(describing: error), privacy: .public)")
        }
    }

    private func save() {
        guard !loadFailed else { return }
        do {
            try SavedJSON.encode(apps, keeping: unread)
                .write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            SavedJSON.log.error("apps: couldn't save: \(String(describing: error), privacy: .public)")
        }
        do {
            try JSONEncoder().encode(dismissedHosts)
                .write(to: dismissedURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            SavedJSON.log.error("dismissed hosts: couldn't save: \(String(describing: error), privacy: .public)")
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

    /// Adds an app by its address, or returns the owner's own app already listed for that host.
    @discardableResult
    func add(url: URL) -> WebApp {
        let host = Discovery.normalizedHost(url.host ?? "")
        if let listed = apps.first(where: { !$0.isShared && Discovery.normalizedHost($0.host) == host }) { return listed }
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

    /// A shared app's tile takes the name its connector gives it now (by host), unless the
    /// guest renamed it, as the owner's own apps do from discovery.
    func takeSharedNames(_ names: [String: String], in membershipID: String) {
        var changed = false
        for index in apps.indices where apps[index].membershipID == membershipID {
            guard let name = names[Discovery.normalizedHost(apps[index].host)], name != apps[index].manifestName else { continue }
            if apps[index].hasDefaultName { apps[index].name = name }
            apps[index].manifestName = name
            changed = true
        }
        if changed { save() }
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

    /// Removes the app and deletes everything it stored (cookies, storage, caches, and files
    /// waiting to be saved).
    /// Discovery won't add one of the owner's own apps back.
    func remove(_ id: UUID) async {
        guard let app = apps.first(where: { $0.id == id }) else { return }
        if !app.isShared { dismissedHosts.insert(Discovery.normalizedHost(app.host)) }
        await remove([app])
    }

    /// Removes the apps found on the owner's computers, and everything they stored, as the
    /// owner signs out. The tailnet is forgotten, so its dismissals go too: signing in again
    /// finds them all.
    func removeDiscovered() async {
        if !dismissedHosts.isEmpty {
            dismissedHosts = []
            save()
        }
        await remove(apps.filter { $0.discovered && !$0.isShared })
    }

    private func remove(_ gone: [WebApp]) async {
        guard !gone.isEmpty else { return }
        let ids = Set(gone.map(\.id))
        apps.removeAll { ids.contains($0.id) }
        save()
        for app in gone {
            WebViewPool.shared.discard(app.id)
            WebViewPool.shared.dropWaitingDownloads(of: app.id)
            if let file = app.iconFile {
                icons[file] = nil
                try? FileManager.default.removeItem(at: iconsDirectory.appendingPathComponent(file))
            }
            StatusBarTint.forget(app)
            await Self.removeDataStore(app.dataStoreID)
        }
    }

    /// Deletes a data store's cookies, storage and caches. Always go through
    /// here: touching the default store first starts WebKit, and removing a store before
    /// anything else has used it crashes the app when the removal completes.
    ///
    /// A web view that is still closing keeps its store in use, and removing a store in
    /// use fails. So the data is cleared at once, and the store itself is removed once
    /// it's free: retried here, and swept at the next launch.
    private static func removeDataStore(_ id: UUID) async {
        _ = WKWebsiteDataStore.default()
        await WKWebsiteDataStore(forIdentifier: id).removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
        for delay in [0.0, 1, 2, 4, 8] {
            try? await Task.sleep(for: .seconds(delay))
            if (try? await WKWebsiteDataStore.remove(forIdentifier: id)) != nil { return }
        }
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

    /// None while any app couldn't be read: its store would look unused.
    func unusedDataStores(among all: [UUID]) -> [UUID] {
        guard readInFull else { return [] }
        let used = Set(apps.map(\.dataStoreID))
        return all.filter { !used.contains($0) }
    }

    /// Signs the app out of everything by wiping its data store and starting a fresh one.
    func clearData(_ id: UUID) async {
        guard let index = apps.firstIndex(where: { $0.id == id }) else { return }
        let old = apps[index].dataStoreID
        WebViewPool.shared.discard(id)
        WebViewPool.shared.dropWaitingDownloads(of: id)
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
    /// connector's ovenlight.json comes first when the app has one, and is all it needs: the
    /// connector already looked in the page for an icon. Saves only a change.
    func refreshMetadata(for id: UUID) async {
        guard let app = apps.first(where: { $0.id == id }), let session = session(app) else { return }
        if app.hasConnector, let url = URL(string: "https://\(app.host)\(OvenlightManifest.path)"),
           let data = await Self.body(from: url, in: session), let manifest = OvenlightManifest.parse(data),
           let index = apps.firstIndex(where: { $0.id == id }) {
            var updated = apps[index]
            if updated.hasDefaultName { updated.name = manifest.name }
            updated.manifestName = manifest.name
            if let theme = manifest.themeColor { updated.themeColorHex = theme }
            if updated.slug == nil { updated.slug = manifest.slug }
            if updated != apps[index] { apps[index] = updated; save() }
            if let iconURL = DiscoveredApp(host: Discovery.normalizedHost(app.host), manifest: manifest).iconURL {
                await saveIcon(from: iconURL, for: id)
            }
            return
        }
        guard let data = await Self.body(from: app.startURL, in: session) else { return }
        // Off the main actor: a page can run to megabytes.
        let (links, title) = await Task.detached { [startURL = app.startURL] in
            let html = String(decoding: data, as: UTF8.self)
            return (ManifestParser.links(inHTML: html, baseURL: startURL), ManifestParser.title(inHTML: html))
        }.value
        var meta = ManifestParser.Metadata(name: title)
        if let manifestURL = links.manifest, let data = await Self.body(from: manifestURL, in: session) {
            let fromManifest = ManifestParser.metadata(fromManifest: data, manifestURL: manifestURL)
            meta.name = fromManifest.name ?? meta.name
            meta.themeColorHex = fromManifest.themeColorHex
            meta.iconURL = fromManifest.iconURL
        }
        guard let index = apps.firstIndex(where: { $0.id == id }) else { return }
        var updated = apps[index]
        // Keep a name the user typed; only replace the placeholder host name.
        if let name = InviteLink.label(meta.name), updated.hasDefaultName, !updated.hasConnector {
            updated.name = name
        }
        if let theme = meta.themeColorHex { updated.themeColorHex = theme }
        if updated != apps[index] { apps[index] = updated; save() }
        if let iconURL = meta.iconURL ?? links.touchIcon {
            await saveIcon(from: iconURL, for: id)
        }
    }

    private func saveIcon(from url: URL, for id: UUID) async {
        guard let app = apps.first(where: { $0.id == id }), let session = session(app),
              let data = await Self.body(from: url, in: session),
              let image = await Task.detached(operation: { Self.iconImage(from: data) }).value, let png = image.pngData(),
              let index = apps.firstIndex(where: { $0.id == id }) else { return }
        let file = "\(id.uuidString).png"
        // A write that fails leaves the icon as it was: an app with none tries again later.
        guard (try? png.write(to: iconsDirectory.appendingPathComponent(file), options: .atomic)) != nil else { return }
        // A refreshed icon keeps its file name, so `apps` may not change; say so anyway.
        objectWillChange.send()
        icons[file] = image
        apps[index].iconFile = file
        save()
    }

    /// Only a 2xx answer counts. A redirect to an address comes back as itself, and its page,
    /// like an error page, says nothing about the app.
    private static func body(from url: URL, in session: URLSession) async -> Data? {
        guard let (data, response) = try? await session.cappedData(from: url),
              let status = (response as? HTTPURLResponse)?.statusCode, (200..<300).contains(status) else { return nil }
        return data
    }

    func refreshAllMissingIcons() async {
        for app in apps where app.iconFile == nil {
            await refreshMetadata(for: app.id)
        }
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
