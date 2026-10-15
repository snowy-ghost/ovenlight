package com.snowyghost.ovenlight

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeReorderTests {
    private data class Item(val id: String)

    private val items = listOf("a", "b", "c", "d", "e").map(::Item)

    private fun order(id: String, index: Int): String = HomeReorder.moving(items, id, index) { it.id }.joinToString("") { it.id }

    @Test
    fun testMovesForwardAndBackToTheDroppedIndex() {
        assertEquals("bcade", order("a", 2))
        assertEquals("aebcd", order("e", 1))
        assertEquals("abcde", order("c", 2))
    }

    @Test
    fun testClampsToTheEndsAndIgnoresUnknownIDs() {
        assertEquals("acdeb", order("b", 99))
        assertEquals("dabce", order("d", -3))
        assertEquals("abcde", order("z", 0))
    }

    @Test
    fun testRegistryMoveSavesTheOrder() = inTemporaryDirectory { dir ->
        val registry = offlineRegistry(dir)
        val apps = listOf("one", "two", "three", "four").map { registry.add(URI("https://$it.example.com/")) }

        registry.move(apps[0].id, 2)
        assertEquals(listOf(apps[1], apps[2], apps[0], apps[3]).map { it.id }, registry.apps.map { it.id })
        registry.move(apps[3].id, 0)

        val reopened = offlineRegistry(dir)
        assertEquals(listOf(apps[3], apps[1], apps[2], apps[0]).map { it.id }, reopened.apps.map { it.id })
    }
}
