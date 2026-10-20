package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Test

class PageDialogTextTests {
    @Test
    fun testAPagesTextIsCutWithoutSplittingACharacter() {
        assertEquals("short", PageEdges.cut("short"))
        assertEquals("", PageEdges.cut(null))
        val long = PageEdges.cut("x".repeat(5_000_000))
        assertEquals(4_001, long.length)
        assertEquals('\u2026', long.last())
        // A face that would straddle the cut goes whole.
        val emoji = PageEdges.cut("x".repeat(3_999) + "\uD83D\uDE00" + "y")
        assertEquals("x".repeat(3_999) + "\u2026", emoji)
    }
}
