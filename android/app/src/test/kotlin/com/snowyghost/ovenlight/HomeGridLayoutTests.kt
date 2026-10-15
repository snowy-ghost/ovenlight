package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridLayoutTests {
    /** The width of an iPhone 17 Pro Max. */
    private val proMax = HomeGridLayout(width = 440.0, labelHeight = 15.0, largeText = false)

    @Test
    fun testFourColumnsInsideTheMargins() {
        assertEquals(4, proMax.columns)
        assertEquals(64.0, proMax.iconSize, 0.0)
        assertEquals(60.0, HomeGridLayout(width = 375.0, labelHeight = 15.0, largeText = false).iconSize, 0.0)
        assertTrue(proMax.tileCenter(0).x - proMax.tileSize.width / 2 > 0)
        assertTrue(proMax.tileCenter(3).x + proMax.tileSize.width / 2 < 440)
        // The first row sits a little below the top controls, and rows follow one pitch.
        assertEquals(HomeGridLayout.topMargin, proMax.tileCenter(0).y - proMax.tileHeight / 2, 0.001)
        assertEquals(2 * proMax.rowPitch, proMax.tileCenter(9).y - proMax.tileCenter(1).y, 0.001)
    }

    @Test
    fun testLargeTextGetsThreeColumnsAndBiggerIcons() {
        val large = HomeGridLayout(width = 440.0, labelHeight = 2 * 30.0, largeText = true)
        assertEquals(3, large.columns)
        assertEquals(72.0, large.iconSize, 0.0)
        assertTrue(large.tileSize.width >= 72)
        assertTrue(large.tileCenter(2).x + large.tileSize.width / 2 <= 440)
        assertEquals("the fourth app starts the next row", large.tileCenter(0).x, large.tileCenter(3).x, 0.001)
    }

    @Test
    fun testLandscapeFitsMoreColumns() {
        // An iPhone 17 Pro on its side, inside the safe area.
        assertEquals(7, HomeGridLayout(width = 750.0, labelHeight = 15.0, largeText = false).columns)
        assertEquals(4, HomeGridLayout(width = 750.0, labelHeight = 2 * 30.0, largeText = true).columns)
    }

    @Test
    fun testEverySlotIsFoundFromItsOwnCenterAndNearIt() {
        for (index in 0 until 60) {
            val center = proMax.tileCenter(index)
            assertEquals(index, proMax.slot(center))
            val nudged = HomeGridLayout.Point(center.x + proMax.columnWidth * 0.4, center.y - proMax.rowPitch * 0.4)
            assertEquals(index, proMax.slot(nudged))
        }
    }

    @Test
    fun testPointsOutsideTheGridClampToTheNearestSlot() {
        assertEquals(0, proMax.slot(HomeGridLayout.Point(-30.0, -50.0)))
        assertEquals(3, proMax.slot(HomeGridLayout.Point(470.0, 2.0)))
        assertEquals(23, proMax.slot(HomeGridLayout.Point(470.0, proMax.tileCenter(20).y)))
    }
}
