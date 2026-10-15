package com.snowyghost.ovenlight

import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dot on apps that arrived on their own and haven't been opened. */
class NewAppDotTests {
    @Test
    fun testSharedAndDiscoveredAppsAreNewUntilOpened() = inTemporaryDirectory { dir ->
        runTest {
            val registry = offlineRegistry(dir)
            val added = registry.add(URI("https://typed.example.com/"))
            val shared = registry.addShared(
                name = "Echo Board", host = "echo-board.taildef456.ts.net", slug = "echo-board", membershipID = "m1", sharedBy = "Riley",
            )
            registry.mergeDiscovered(listOf(DiscoveredApp("notes.tailabc123.ts.net", OvenlightManifest(name = "Notes", version = 1))))
            val notes = registry.apps.first { it.name == "Notes" }
            assertEquals("an app the user added by address isn't new", listOf(false, true, true), registry.apps.map { it.isNew })

            registry.markOpened(shared.id)
            assertEquals(listOf(false, false, true), registry.apps.map { it.isNew })

            // Persisted per app: a relaunch keeps both the cleared dot and the remaining one.
            val reopened = offlineRegistry(dir)
            assertEquals(listOf(added.id, shared.id, notes.id), reopened.apps.map { it.id })
            assertEquals(listOf(false, false, true), reopened.apps.map { it.isNew })

            // Discovery finding it again, or opening it twice, never brings the dot back.
            reopened.mergeDiscovered(listOf(DiscoveredApp("notes.tailabc123.ts.net", OvenlightManifest(name = "Notes", version = 1))))
            assertTrue(reopened.apps[2].isNew)
            reopened.markOpened(notes.id)
            reopened.markOpened(notes.id)
            reopened.mergeDiscovered(listOf(DiscoveredApp("notes.tailabc123.ts.net", OvenlightManifest(name = "Notes 2", version = 1))))
            assertFalse(offlineRegistry(dir).apps.any { it.isNew })
        }
    }
}
