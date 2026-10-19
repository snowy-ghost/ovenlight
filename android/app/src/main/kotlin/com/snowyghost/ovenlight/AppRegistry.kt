package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer

/**
 * The user's apps, persisted as JSON in [directory] (the app's `files/Ovenlight`), with icons
 * cached beside it. Main thread only.
 *
 * [session] fetches an app's page and icon: through the app's node, or null while it isn't up.
 * [webData] reaches what only Android holds, the apps' web views and their data. [iconPNG]
 * turns a fetched icon into the small PNG the launcher draws, off the main thread, or null when
 * it can't. Metadata refreshes run in [scope], on the main thread.
 */
class AppRegistry(
    private val directory: File,
    private val session: (WebApp) -> Fetch?,
    private val webData: WebData,
    private val iconPNG: suspend (ByteArray) -> ByteArray?,
    private val scope: CoroutineScope,
) {
    /** The web views and data stores behind the apps. */
    interface WebData {
        /** Closes the app's web view, if it has one. */
        fun discard(app: UUID)

        /**
         * Forgets what was kept about a removed app outside its data store, such as its status bar
         * tint, and disables its home screen shortcut.
         */
        fun forget(app: WebApp) {}

        /** Deletes a data store's cookies, storage and caches, even while a web view still uses it. */
        suspend fun clear(store: UUID)

        /** Deletes the data store itself, now or, while a web view still uses it, at the next launch. */
        fun delete(store: UUID)

        /** Every data store there is. */
        suspend fun stores(): List<UUID>
    }

    private val state = MutableStateFlow<List<WebApp>>(emptyList())

    /** The apps, for screens to observe. */
    val appsFlow: StateFlow<List<WebApp>> = state
    var apps: List<WebApp>
        get() = state.value
        private set(value) {
            state.value = value
        }

    private val icons = MutableStateFlow<Map<UUID, Int>>(emptyMap())

    /** Each app's count of icons saved, so a refreshed icon redraws under its unchanged file name, and only that one. */
    val iconRevisions: StateFlow<Map<UUID, Int>> = icons

    /** Hosts of apps the user removed; discovery won't add them back. */
    private var dismissedHosts: Set<String> = emptySet()

    private val savedApps = SavedList(File(directory, "apps.json"), WebApp.serializer(), "apps")
    private val savedDismissed = SavedList(File(directory, "dismissed-hosts.json"), String.serializer(), "dismissed hosts")

    /**
     * A file is there but couldn't be read or decoded. Nothing is saved over either file and no
     * data store is swept, until a reload reads both.
     */
    val loadFailed: Boolean get() = savedApps.loadFailed || savedDismissed.loadFailed

    /** Every saved app was read, so cleaning up after the list deletes only what it means to. */
    val readInFull: Boolean get() = savedApps.readInFull && !savedDismissed.loadFailed

    val iconsDirectory = File(directory, "icons")

    init {
        iconsDirectory.mkdirs()
        load()
    }

    /** Reads the files again if they couldn't be read before. */
    fun reloadIfFailed() {
        if (loadFailed) load()
    }

    private fun load() {
        val loaded = savedApps.load() ?: return
        val dismissed = savedDismissed.load() ?: return
        dismissedHosts = dismissed.toSet()
        apps = loaded
    }

    private fun save() {
        if (loadFailed) return
        savedApps.save(apps)
        savedDismissed.save(dismissedHosts.sorted())
    }

    /** The app's cached icon on disk, if one was fetched. Decoding it for the launcher is Android's. */
    fun iconFile(app: WebApp): File? = app.iconFile?.let { File(iconsDirectory, it) }

    /** Adds an app by its address. One of the owner's already listed at that host is returned as it is, and nothing is added. */
    fun add(url: URI): WebApp {
        apps.firstOrNull { !it.isShared && Discovery.normalizedHost(it.host) == Discovery.normalizedHost(url.host ?: "") }?.let { return it }
        val app = WebApp(name = url.host ?: url.toString(), startURL = url)
        dismissedHosts = dismissedHosts - Discovery.normalizedHost(app.host)
        apps = apps + app
        save()
        scope.launch { refreshMetadata(app.id) }
        return app
    }

    fun rename(id: UUID, name: String) {
        if (apps.none { it.id == id }) return
        apps = apps.map { if (it.id == id) it.copy(name = name) else it }
        save()
    }

    /**
     * Adds an app someone shared, once its invite is claimed. An app already listed for that host
     * in that membership is returned as it is. Every app of the membership takes the owner's name
     * as this claim gave it, the same one its membership keeps.
     */
    fun addShared(name: String, host: String, slug: String, membershipID: String, sharedBy: String): WebApp {
        val normalized = Discovery.normalizedHost(host)
        if (apps.any { it.membershipID == membershipID && it.sharedBy != sharedBy }) {
            apps = apps.map { if (it.membershipID == membershipID) it.copy(sharedBy = sharedBy) else it }
            save()
        }
        apps.firstOrNull { it.membershipID == membershipID && Discovery.normalizedHost(it.host) == normalized }?.let { return it }
        val app = WebApp(
            name = name, startURL = URI("https://$normalized/"), manifestName = name, slug = slug,
            membershipID = membershipID, sharedBy = sharedBy, isNew = true,
        )
        apps = apps + app
        save()
        scope.launch { refreshMetadata(app.id) }
        return app
    }

    /**
     * The connectors of a membership's apps name them anew, by host (whoami's `appName`): each
     * tile takes its new name unless the guest renamed it, as the owner's discovered apps do.
     */
    fun renameShared(membershipID: String, names: Map<String, String>) {
        val renamed = apps.map { app ->
            val name = names[Discovery.normalizedHost(app.host)]?.takeIf { app.membershipID == membershipID && it != app.manifestName } ?: return@map app
            app.copy(name = if (app.hasDefaultName) name else app.name, manifestName = name)
        }
        if (renamed == apps) return
        apps = renamed
        save()
    }

    /** The app was opened: its tile loses the new dot, for good. */
    fun markOpened(id: UUID) {
        if (apps.firstOrNull { it.id == id }?.isNew != true) return
        apps = apps.map { if (it.id == id) it.copy(isNew = false) else it }
        save()
    }

    /** Moves one app so it ends up at [index] (clamped), the way the home screen drops it. */
    fun move(id: UUID, index: Int) {
        val moved = HomeReorder.moving(apps, id, index) { it.id }
        if (moved == apps) return
        apps = moved
        save()
    }

    /**
     * Removes the app and deletes everything it stored (cookies, storage, caches, and files
     * waiting to be saved). Discovery won't add one of the owner's own apps back.
     */
    suspend fun remove(id: UUID) = withContext(NonCancellable) {
        val app = apps.firstOrNull { it.id == id } ?: return@withContext
        if (!app.isShared) dismissedHosts = dismissedHosts + Discovery.normalizedHost(app.host)
        remove(listOf(app))
    }

    /**
     * Removes the apps found on the owner's computers, and everything they stored, as the owner
     * signs out. The tailnet is forgotten, so its dismissals go too: signing in again finds them all.
     */
    suspend fun removeDiscovered() = withContext(NonCancellable) {
        if (dismissedHosts.isNotEmpty()) {
            dismissedHosts = emptySet()
            save()
        }
        remove(apps.filter { it.discovered && !it.isShared })
    }

    private suspend fun remove(gone: List<WebApp>) {
        if (gone.isEmpty()) return
        val ids = gone.map { it.id }.toSet()
        apps = apps.filter { it.id !in ids }
        save()
        for (app in gone) {
            webData.discard(app.id)
            DownloadShelf.drop(app.id)
            iconFile(app)?.delete()
            webData.forget(app)
            removeDataStore(app.dataStoreID)
        }
    }

    /** Deletes a data store's cookies, storage and caches at once, and the store itself as soon as it can. */
    private suspend fun removeDataStore(id: UUID) {
        webData.clear(id)
        webData.delete(id)
    }

    /** Deletes data stores no app uses: ones a removal couldn't delete while a web view still held them. Runs after launch. */
    suspend fun sweepUnusedDataStores() {
        for (id in unusedDataStores(webData.stores())) webData.delete(id)
    }

    /** None while any app couldn't be read: its store would look unused. */
    fun unusedDataStores(among: List<UUID>): List<UUID> {
        if (!readInFull) return emptyList()
        val used = apps.map { it.dataStoreID }.toSet()
        return among.filter { it !in used }
    }

    /** Signs the app out of everything by wiping its data store and starting a fresh one. */
    suspend fun clearData(id: UUID) = withContext(NonCancellable) {
        val old = apps.firstOrNull { it.id == id }?.dataStoreID ?: return@withContext
        webData.discard(id)
        DownloadShelf.drop(id)
        apps = apps.map { if (it.id == id) it.copy(dataStoreID = UUID.randomUUID()) else it }
        save()
        removeDataStore(old)
    }

    /** Adds apps found on the tailnet and updates the ones already listed. */
    suspend fun mergeDiscovered(found: List<DiscoveredApp>) {
        val merged = DiscoveryMerge.merge(found, apps, dismissedHosts)
        if (merged != apps) {
            apps = merged
            save()
        }
        for (item in found) {
            val host = Discovery.normalizedHost(item.host)
            val app = apps.firstOrNull { !it.isShared && Discovery.normalizedHost(it.host) == host } ?: continue
            val iconURL = item.iconURL
            if (app.iconFile != null || iconURL == null) continue
            saveIcon(iconURL, app.id)
        }
    }

    /**
     * Fetches the app's page and manifest to fill in its name, theme color and icon. A
     * connector's ovenlight.json comes first when the app has one, and with [manifestOnly] it's
     * all: the connector looked in the page and its manifest for the icon as it last read them.
     */
    suspend fun refreshMetadata(id: UUID, manifestOnly: Boolean = false) {
        val app = apps.firstOrNull { it.id == id } ?: return
        val fetch = session(app) ?: return
        if (app.hasConnector) {
            val manifest = uri("https://${app.host}${OvenlightManifest.path}")
                ?.let { fetchOrNull(fetch, it) }
                ?.let { OvenlightManifest.parse(it.body) }
            if (manifest != null && apps.any { it.id == id }) {
                update(id) {
                    it.copy(
                        name = if (it.hasDefaultName) manifest.name else it.name,
                        manifestName = manifest.name,
                        themeColorHex = manifest.themeColor ?: it.themeColorHex,
                        slug = it.slug ?: manifest.slug,
                    )
                }
                val iconURL = DiscoveredApp(Discovery.normalizedHost(app.host), manifest).iconURL
                if (iconURL != null) return saveIcon(iconURL, id)
                if (manifestOnly) return
            }
        }
        // As iOS decodes a page: bytes that aren't UTF-8 become replacement characters.
        val html = fetchOrNull(fetch, app.startURL)?.body?.decodeToString() ?: return
        val links = ManifestParser.links(html, app.startURL)
        var meta = ManifestParser.Metadata(name = ManifestParser.title(html))
        val manifestURL = links.manifest
        val manifestData = manifestURL?.let { fetchOrNull(fetch, it) }?.body
        if (manifestURL != null && manifestData != null) {
            val fromManifest = ManifestParser.metadata(manifestData, manifestURL)
            meta = ManifestParser.Metadata(fromManifest.name ?: meta.name, fromManifest.themeColorHex, fromManifest.iconURL)
        }
        if (apps.none { it.id == id }) return
        update(id) {
            // Keep a name the user typed; only replace the placeholder host name.
            val name = InviteLink.label(meta.name)
            it.copy(
                name = if (name != null && it.hasDefaultName && !it.hasConnector) name else it.name,
                themeColorHex = meta.themeColorHex ?: it.themeColorHex,
            )
        }
        val iconURL = meta.iconURL ?: links.touchIcon
        if (iconURL != null) saveIcon(iconURL, id)
    }

    private suspend fun saveIcon(url: URI, id: UUID) {
        val app = apps.firstOrNull { it.id == id } ?: return
        val fetch = session(app) ?: return
        val data = fetchOrNull(fetch, url)?.body ?: return
        val png = iconPNG(data) ?: return
        if (apps.none { it.id == id }) return
        val name = "${id.toString().uppercase()}.png"
        try {
            SavedJSON.write(png, File(iconsDirectory, name))
        } catch (e: Exception) {
            SavedJSON.problem("icons: couldn't save: $e")
            // Without its file the app has no icon, and the next look fetches it again.
            return
        }
        update(id) { it.copy(iconFile = name) }
        icons.value += id to ((icons.value[id] ?: 0) + 1)
    }

    /** Each app's missing icon, once [reachable] says its node can carry the fetch. Runs on every return to the front. */
    suspend fun refreshAllMissingIcons(reachable: suspend (WebApp) -> Boolean) = coroutineScope {
        for (app in apps.filter { it.iconFile == null }) {
            launch { if (reachable(app) && apps.firstOrNull { it.id == app.id }?.iconFile == null) refreshMetadata(app.id, manifestOnly = true) }
        }
    }

    /** Saves only a change: refreshes repeat on every return to the front. */
    private fun update(id: UUID, change: (WebApp) -> WebApp) {
        val updated = apps.map { if (it.id == id) change(it) else it }
        if (updated == apps) return
        apps = updated
        save()
    }
}
