package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

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

        /** Forgets what was kept about a removed app outside its data store, such as its status bar tint. */
        fun forget(app: WebApp) {}

        /** Deletes a data store's cookies, storage and caches, even while a web view still uses it. */
        suspend fun clear(store: UUID)

        /** Deletes the data store itself; false while a web view still uses it. */
        fun delete(store: UUID): Boolean

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

    private val icons = MutableStateFlow(0)

    /** Goes up with each icon saved, so a refreshed icon redraws under its unchanged file name. */
    val iconRevision: StateFlow<Int> = icons

    /** Hosts of apps the user removed; discovery won't add them back. */
    private var dismissedHosts: Set<String> = emptySet()

    /**
     * A file is there but couldn't be read or decoded. Nothing is saved over it and no data store
     * is swept, until a reload reads it.
     */
    var loadFailed = false
        private set

    /** Apps another build saved that this one can't read (see [SavedJSON.List]). */
    private var unread: List<JsonElement> = emptyList()

    /** Every saved app was read, so cleaning up after the list deletes only what it means to. */
    val readInFull: Boolean get() = !loadFailed && unread.isEmpty()

    private val file = File(directory, "apps.json")
    private val dismissedFile = File(directory, "dismissed-hosts.json")
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
        try {
            val loaded = SavedJSON.read(WebApp.serializer(), file) ?: SavedJSON.List()
            dismissedHosts = (SavedJSON.read(String.serializer(), dismissedFile)?.elements ?: emptyList()).toSet()
            apps = loaded.elements
            unread = loaded.unread
            loadFailed = false
            if (unread.isNotEmpty()) SavedJSON.log.severe("apps: kept ${unread.size} this version can't read")
        } catch (e: Exception) {
            loadFailed = true
            SavedJSON.log.severe("apps: couldn't read: $e")
        }
    }

    private fun save() {
        if (loadFailed) return
        try {
            SavedJSON.write(SavedJSON.encode(WebApp.serializer(), apps, unread), file)
        } catch (e: Exception) {
            SavedJSON.log.severe("apps: couldn't save: $e")
        }
        try {
            SavedJSON.write(ovenlightJSON.encodeToString(ListSerializer(String.serializer()), dismissedHosts.sorted()), dismissedFile)
        } catch (e: Exception) {
            SavedJSON.log.severe("dismissed hosts: couldn't save: $e")
        }
    }

    /** The app's cached icon on disk, if one was fetched. Decoding it for the launcher is Android's. */
    fun iconFile(app: WebApp): File? = app.iconFile?.let { File(iconsDirectory, it) }

    fun add(url: URI): WebApp {
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
     * Removes the app and deletes everything it stored (cookies, storage, caches). Discovery
     * won't add one of the owner's own apps back.
     */
    suspend fun remove(id: UUID) {
        val app = apps.firstOrNull { it.id == id } ?: return
        apps = apps.filter { it.id != id }
        if (!app.isShared) dismissedHosts = dismissedHosts + Discovery.normalizedHost(app.host)
        save()
        webData.discard(app.id)
        iconFile(app)?.delete()
        webData.forget(app)
        removeDataStore(app.dataStoreID)
    }

    /**
     * Deletes a data store's cookies, storage and caches. A web view that is still closing keeps
     * its store in use, and deleting a store in use fails. So the data is cleared at once, and
     * the store itself is deleted once it's free: retried here, and swept at the next launch.
     */
    private suspend fun removeDataStore(id: UUID) {
        webData.clear(id)
        for (wait in listOf(0, 1, 2, 4, 8)) {
            delay(wait.seconds)
            if (webData.delete(id)) return
        }
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
    suspend fun clearData(id: UUID) {
        val old = apps.firstOrNull { it.id == id }?.dataStoreID ?: return
        webData.discard(id)
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
     * connector's ovenlight.json comes first when the app has one.
     */
    suspend fun refreshMetadata(id: UUID) {
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
                save()
                val iconURL = DiscoveredApp(Discovery.normalizedHost(app.host), manifest).iconURL
                if (iconURL != null) {
                    saveIcon(iconURL, id)
                    return
                }
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
        save()
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
            SavedJSON.log.severe("icons: couldn't save: $e")
        }
        update(id) { it.copy(iconFile = name) }
        save()
        icons.value += 1
    }

    suspend fun refreshAllMissingIcons() {
        for (app in apps) {
            if (app.iconFile == null) refreshMetadata(app.id)
        }
    }

    private fun update(id: UUID, change: (WebApp) -> WebApp) {
        apps = apps.map { if (it.id == id) change(it) else it }
    }
}
