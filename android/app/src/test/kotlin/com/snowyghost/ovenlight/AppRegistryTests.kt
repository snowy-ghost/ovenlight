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
        override fun delete(store: UUID) = true
        override suspend fun stores() = emptyList<UUID>()
    },
    iconPNG = { null },
    scope = CoroutineScope(Dispatchers.Unconfined),
)

class AppRegistryTests {
    @get:Rule
    val temp = TemporaryFolder()
    private val dir: File get() = temp.root

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
