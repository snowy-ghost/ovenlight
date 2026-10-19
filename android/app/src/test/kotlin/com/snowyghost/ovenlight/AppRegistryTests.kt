package com.snowyghost.ovenlight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** A registry with no network, no web views and no icons, as the iOS tests make one. */
internal fun offlineRegistry(dir: File) = AppRegistry(
    directory = dir,
    session = { null },
    webData = object : AppRegistry.WebData {
        override fun discard(app: UUID) {}
        override suspend fun clear(store: UUID) {}
        override fun delete(store: UUID) {}
        override suspend fun stores() = emptyList<UUID>()
    },
    iconPNG = { null },
    scope = CoroutineScope(Dispatchers.Unconfined),
)

class AppRegistryTests {
    @get:Rule
    val temp = TemporaryFolder()
    private val dir: File get() = temp.root

    /** Add app with a host already listed returns that app, which Add app then opens, rather than a second tile. */
    @Test
    fun testAddingAnAppAlreadyListedOpensIt() {
        val registry = offlineRegistry(dir)
        val notes = registry.add(URI("https://notes.tailabc123.ts.net/"))
        assertEquals(notes, registry.add(URI("https://Notes.TailABC123.ts.net/")))
        assertEquals(listOf(notes), registry.apps)
        val shared = registry.addShared("Echo", "echo.taildef456.ts.net", "echo", "m1", "Riley")
        assertTrue("a shared app at the host isn't the owner's", registry.add(URI("https://echo.taildef456.ts.net/")) != shared)
        assertEquals(3, registry.apps.size)
    }

    @Test
    fun testStartsEmptyAndPersistsChanges() {
        val first = offlineRegistry(dir)
        assertEquals(emptyList<WebApp>(), first.apps)
        val app = first.add(URI("https://notes.example.com/"))
        first.rename(app.id, "Notes")

        val second = offlineRegistry(dir)
        assertEquals(listOf("Notes"), second.apps.map { it.name })
        assertEquals(app.dataStoreID, second.apps[0].dataStoreID)
    }

    @Test
    fun testDiscoveredAppsPersistAndRemovedOnesStayRemoved() = runTest {
        val coach = DiscoveredApp(
            "interview-coach.tailabc123.ts.net",
            OvenlightManifest(name = "Interview Coach", icon = "/icon-512.png", version = 1),
        )

        val registry = offlineRegistry(dir)
        registry.mergeDiscovered(listOf(coach))
        assertEquals(listOf("Interview Coach"), registry.apps.map { it.name })
        assertTrue(registry.apps[0].discovered)
        registry.remove(registry.apps[0].id)
        registry.mergeDiscovered(listOf(coach))
        assertEquals(emptyList<WebApp>(), registry.apps)

        val reopened = offlineRegistry(dir)
        reopened.mergeDiscovered(listOf(coach))
        assertEquals("a removed app must not come back after relaunch", emptyList<WebApp>(), reopened.apps)
        reopened.add(URI("https://interview-coach.tailabc123.ts.net/"))
        assertEquals("adding it by hand brings it back", 1, reopened.apps.size)
    }

    @Test
    fun testRemovedHostsAreSavedEvenWhenTheAppsCantBe() = runTest {
        val registry = offlineRegistry(dir)
        val app = registry.add(URI("https://notes.tailabc123.ts.net/"))
        val file = File(dir, "apps.json")
        assertTrue(file.delete())
        assertTrue(file.mkdir()) // nothing can be written there

        registry.remove(app.id)
        val dismissed = ovenlightJSON.decodeFromString<List<String>>(File(dir, "dismissed-hosts.json").readText())
        assertEquals(listOf("notes.tailabc123.ts.net"), dismissed)
    }

    @Test
    fun testRemovingAnAppOrClearingItsDataDeletesItsWaitingDownloads() = runTest {
        val registry = offlineRegistry(dir)
        val apps = listOf("removed", "cleared", "kept").map { registry.add(URI("https://$it.example.com/")) }
        val files = apps.map { app ->
            File(temp.newFolder(), "notes.txt").also {
                it.writeText(app.name)
                assertTrue(DownloadShelf.add(app.id, it))
            }
        }
        val (removed, cleared, kept) = apps

        registry.remove(removed.id)
        registry.clearData(cleared.id)
        assertNull(DownloadShelf.waiting.value[removed.id])
        assertNull(DownloadShelf.waiting.value[cleared.id])
        assertEquals(listOf(false, false, true), files.map { it.parentFile!!.exists() })
        assertEquals(listOf(files[2]), DownloadShelf.waiting.value[kept.id]?.map { it.file })
        DownloadShelf.drop(kept.id)
    }

    @Test
    fun testAMissingFileIsEmptyAndSaves() {
        val registry = offlineRegistry(dir)
        assertFalse(registry.loadFailed)
        registry.add(URI("https://notes.example.com/"))
        assertTrue(File(dir, "apps.json").exists())
        val unknown = UUID.randomUUID()
        assertEquals(listOf(unknown), registry.unusedDataStores(listOf(registry.apps[0].dataStoreID, unknown)))
    }

    @Test
    fun testAnUnreadableFileIsNeverSavedOverOrSwept() {
        val file = File(dir, "apps.json")
        val corrupt = "{\"truncated".toByteArray()
        file.writeBytes(corrupt)

        val registry = offlineRegistry(dir)
        assertTrue(registry.loadFailed)
        assertEquals("every store would look unused", emptyList<UUID>(), registry.unusedDataStores(listOf(UUID.randomUUID())))
        registry.add(URI("https://notes.example.com/"))
        assertArrayEquals("nothing is saved over the file", corrupt, file.readBytes())

        // Once the file reads, the reload replaces the guesswork.
        temp.newFolder().let { other ->
            val saved = offlineRegistry(other)
            saved.add(URI("https://coach.example.com/"))
            file.writeText(ovenlightJSON.encodeToString(ListSerializer(WebApp.serializer()), saved.apps))
            registry.reloadIfFailed()
            assertFalse(registry.loadFailed)
            assertEquals(saved.apps, registry.apps)
        }
    }

    @Test
    fun testDecodesEachAppOnItsOwnAndFillsInWhatsMissing() {
        val json = """
        [{"id": "${UUID.randomUUID()}", "startURL": "https://coach.tailabc123.ts.net/", "dataStoreID": "${UUID.randomUUID()}",
          "name": "Coach", "themeColorHex": 7, "futureField": {"a": 1}},
         {"name": "No ID", "startURL": "https://broken.example.com/"},
         "not an app"]
        """
        File(dir, "apps.json").writeText(json)
        val registry = offlineRegistry(dir)
        assertFalse(registry.loadFailed)
        assertEquals(listOf("Coach"), registry.apps.map { it.name })
        assertEquals(null, registry.apps[0].themeColorHex)
        assertFalse(registry.apps[0].discovered || registry.apps[0].isNew)

        // What it couldn't read may be another build's app, with a data store of its own.
        assertFalse(registry.readInFull)
        assertEquals("no store is swept", emptyList<UUID>(), registry.unusedDataStores(listOf(UUID.randomUUID())))
        registry.add(URI("https://notes.example.com/"))
        val saved = ovenlightJSON.parseToJsonElement(File(dir, "apps.json").readText()) as JsonArray
        assertEquals("the two it read, then the two it didn't, as they were", 4, saved.size)
        assertEquals("No ID", ((saved[2] as? JsonObject)?.get("name") as? JsonPrimitive)?.content)
        assertEquals(JsonPrimitive("not an app"), saved[3])
        val reopened = offlineRegistry(dir)
        assertEquals(listOf("Coach", "notes.example.com"), reopened.apps.map { it.name })
        assertFalse(reopened.readInFull)
    }

    /** Android only: an icon that couldn't be written is fetched again, and no icon is fetched before its node can carry it. */
    @Test
    fun testAnIconIsSavedOnlyWhenWrittenAndFetchedOnlyWhenReachable() = runTest {
        var fetches = 0
        val registry = AppRegistry(
            dir, session = { { _ -> fetches += 1; FetchResponse(200, "icon".toByteArray()) } },
            webData = object : AppRegistry.WebData {
                override fun discard(app: UUID) {}
                override suspend fun clear(store: UUID) {}
                override fun delete(store: UUID) {}
                override suspend fun stores() = emptyList<UUID>()
            },
            iconPNG = { it }, scope = backgroundScope,
        )
        val coach = DiscoveredApp("coach.tailabc123.ts.net", OvenlightManifest(name = "Coach", icon = "/icon.png", version = 1))
        assertTrue(registry.iconsDirectory.delete())
        registry.iconsDirectory.writeText("in the way")
        registry.mergeDiscovered(listOf(coach))
        assertEquals(1, fetches)
        assertNull("nothing was written", registry.apps.single().iconFile)

        assertTrue(registry.iconsDirectory.delete())
        assertTrue(registry.iconsDirectory.mkdirs())
        registry.refreshAllMissingIcons { false }
        assertEquals("its node isn't connected", 1, fetches)
        registry.mergeDiscovered(listOf(coach))
        val app = registry.apps.single()
        assertEquals("${app.id.toString().uppercase()}.png", app.iconFile)
        assertArrayEquals("icon".toByteArray(), registry.iconFile(app)!!.readBytes())
    }

    /**
     * Coming to the front looks again for each missing icon. A connector's app with none stops
     * after its manifest there, and a manifest that changes nothing writes nothing.
     */
    @Test
    fun testARefreshThatChangesNothingSavesNothing() = runTest {
        val fetched = mutableListOf<String>()
        var manifest = """{"name":"Coach","version":1}"""
        val registry = AppRegistry(
            dir, session = { { url -> fetched += url.path; FetchResponse(200, manifest.toByteArray()) } },
            webData = object : AppRegistry.WebData {
                override fun discard(app: UUID) {}
                override suspend fun clear(store: UUID) {}
                override fun delete(store: UUID) {}
                override suspend fun stores() = emptyList<UUID>()
            },
            iconPNG = { it }, scope = backgroundScope,
        )
        registry.mergeDiscovered(listOf(DiscoveredApp("coach.tailabc123.ts.net", OvenlightManifest(name = "Coach", version = 1))))
        val saved = File(dir, "apps.json")
        assertTrue(saved.delete())
        registry.refreshAllMissingIcons { true }
        assertEquals("the manifest only, not the start page", listOf(OvenlightManifest.path), fetched)
        assertFalse("nothing was saved", saved.exists())

        manifest = """{"name":"Coach","themeColor":"#336699","version":1}"""
        registry.refreshAllMissingIcons { true }
        assertEquals("#336699", registry.apps.single().themeColorHex)
        assertTrue("a change is saved", saved.exists())

        // Refresh icon reads the page too: an icon added since the connector last read it isn't in its manifest yet.
        fetched.clear()
        registry.refreshMetadata(registry.apps.single().id)
        assertEquals(listOf(OvenlightManifest.path, "/"), fetched)
    }
}

/** The registry's tests that need Android's own image decoding, run under Robolectric with its native graphics. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppRegistryIconTests {
    @get:Rule
    val temp = TemporaryFolder()

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 128, 0)) }
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    private fun size(bitmap: Bitmap?) = bitmap?.let { listOf(it.width, it.height) }

    @Test
    fun testIconsAreDecodedSmall() {
        val png = png(2048, 1024)
        assertEquals("what a save writes", listOf(288, 144), size(IconDecoder.png(png)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }))
        assertNull("over 4096 pixels a side, refused before decoding", IconDecoder.png(png(4097, 8)))
        assertNull("over 4096 pixels high, refused too", IconDecoder.png(png(8, 4097)))

        // One an earlier build saved as the server sent it.
        val registry = offlineRegistry(temp.root)
        val app = WebApp(name = "Coach", startURL = URI("https://coach.tailabc123.ts.net/"), iconFile = "coach.png")
        File(registry.iconsDirectory, "coach.png").writeBytes(png)
        assertEquals(listOf(288, 144), size(IconDecoder.load(registry.iconFile(app))))
    }
}
