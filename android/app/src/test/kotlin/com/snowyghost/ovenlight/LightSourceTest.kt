package com.snowyghost.ovenlight

import com.snowyghost.ovenlight.LightSource.Cutout
import com.snowyghost.ovenlight.LightSource.Edge
import org.junit.Assert.assertEquals
import org.junit.Test

// Android only: iOS knows its island is always at the top center.
class LightSourceTest {
    private val width = 1080f
    private val height = 2424f

    @Test
    fun testTheLightLeavesACutoutCenteredOnTheTopEdge() {
        val hole = Cutout(500f, 40f, 580f, 120f)
        assertEquals(LightSource(Edge.TOP, 540f, 80f), LightSource.of(width, height, top = hole, left = null, right = null, statusBarHeight = 144f))
    }

    @Test
    fun testOnItsSideThePhonePoursSidewaysFromItsCutout() {
        val left = Cutout(0f, 500f, 140f, 580f)
        assertEquals(LightSource(Edge.LEFT, 70f, 540f), LightSource.of(2424f, 1080f, top = null, left = left, right = null, statusBarHeight = 0f))
        val right = Cutout(2284f, 500f, 2424f, 580f)
        assertEquals(LightSource(Edge.RIGHT, 2354f, 540f), LightSource.of(2424f, 1080f, top = null, left = null, right = right, statusBarHeight = 0f))
    }

    @Test
    fun testACornerHoleOrNoCutoutFallsBackToTheTopCenter() {
        val corner = Cutout(60f, 40f, 140f, 120f)
        assertEquals(LightSource(Edge.TOP, 540f, 72f), LightSource.of(width, height, top = corner, left = null, right = null, statusBarHeight = 144f))
        assertEquals(LightSource(Edge.TOP, 540f, 72f), LightSource.of(width, height, top = null, left = null, right = null, statusBarHeight = 144f))
    }
}
