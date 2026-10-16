package com.snowyghost.ovenlight

import android.view.MotionEvent
import java.net.URI
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the chrome follows the page (AppChrome.swift's WebViewObserver), and which header color an app remembers. */
class ChromeRulesTests {
    private val events = mutableListOf<String>()

    /** At 2 pixels a dp, with an 8 px touch slop. */
    private val gestures = ChromeGestures(density = 2f, touchSlop = 8f, onPull = { events += "pull" }, onScrollDown = { events += "down" }, onTap = { events += "tap" })

    private fun down(y: Float, x: Float = 200f, at: Long = 0) = gestures.touched(MotionEvent.ACTION_DOWN, x, y, at)
    private fun move(y: Float, x: Float = 200f, at: Long = 0) = gestures.touched(MotionEvent.ACTION_MOVE, x, y, at)
    private fun up(y: Float, x: Float = 200f, at: Long = 0) = gestures.touched(MotionEvent.ACTION_UP, x, y, at)

    @Test
    fun testScrollingDownHidesItAndScrollingUpLeavesItBe() {
        down(800f)
        move(700f)
        // 12 dp is 24 px: 20 isn't enough, 30 is.
        gestures.scrolled(100, 120, 0)
        assertEquals(emptyList<String>(), events)
        gestures.scrolled(120, 130, 0)
        assertEquals(listOf("down"), events)
        // Any way up, short of the top, brings nothing back, and starts the count again.
        gestures.scrolled(130, 5, 0)
        gestures.scrolled(5, 25, 0)
        assertEquals(listOf("down"), events)
        up(900f)
        assertEquals("a drag is no tap", listOf("down"), events)
    }

    @Test
    fun testOnlyThePersonsOwnScrollingCounts() {
        // The page scrolling itself, with no finger down and no fling.
        gestures.scrolled(0, 400, 10_000)
        gestures.scrolled(400, 0, 10_000)
        assertEquals(emptyList<String>(), events)
        // A drag's fling carries on after the finger lifts, for a moment.
        down(800f, at = 0)
        move(780f, at = 10)
        up(760f, at = 20)
        gestures.scrolled(100, 130, 500)
        assertEquals(listOf("down"), events)
        gestures.scrolled(130, 160, 5_000)
        assertEquals("long after the fling", listOf("down"), events)
        // And a tap's doesn't: it never moved.
        events.clear()
        down(800f, at = 6_000)
        up(800f, at = 6_050)
        gestures.scrolled(160, 200, 6_100)
        assertEquals(listOf("tap"), events)
    }

    @Test
    fun testPullingPastTheTopBringsItBackOnce() {
        down(100f)
        move(110f)
        // WebView overscrolls the top: 40 dp, 80 px, from here.
        gestures.overscrolledTop()
        move(180f)
        assertEquals(emptyList<String>(), events)
        gestures.overscrolledTop()
        move(191f)
        move(400f)
        assertEquals(listOf("pull"), events)
        up(400f)
        assertEquals("a pull is no tap", listOf("pull"), events)
    }

    @Test
    fun testADragThePageKeepsIsNoPull() {
        // A map or an inner scroller takes the drag, so WebView never overscrolls.
        down(100f)
        move(400f)
        up(400f)
        assertEquals(emptyList<String>(), events)
        // And an overscroll with no finger down, or one the page scrolled away from, starts nothing.
        gestures.overscrolledTop()
        down(100f)
        move(110f)
        gestures.overscrolledTop()
        gestures.scrolled(0, 10, 0)
        move(400f)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun testATapOnThePageHidesIt() {
        down(500f)
        move(505f, x = 205f)
        up(505f, x = 205f)
        assertEquals(listOf("tap"), events)
        // A sideways swipe, a carousel's, is no tap.
        down(500f)
        move(500f, x = 400f)
        up(500f, x = 400f)
        assertEquals(listOf("tap"), events)
        gestures.touched(MotionEvent.ACTION_DOWN, 200f, 500f, 0)
        gestures.touched(MotionEvent.ACTION_CANCEL, 200f, 500f, 0)
        assertEquals("a cancelled touch is no tap", listOf("tap"), events)
    }

    private val app = WebApp(name = "Recipes", startURL = URI("https://recipes.tail1.ts.net/"), themeColorHex = "#336699", id = UUID.fromString("0f0e0d0c-0b0a-0908-0706-050403020100"))

    @Test
    fun testBeforeAnyLoadTheManifestColorStandsInInLightModeOnly() {
        val tint = StatusBarTint(MemoryStore())
        assertEquals(themeColor("#336699"), tint.color(app, dark = false))
        assertNull(tint.color(app, dark = true))
        assertNull(tint.color(app.copy(themeColorHex = null), dark = false))
    }

    @Test
    fun testRemembersEachAppearanceOnItsOwnWhenMostlyOpaque() {
        val store = MemoryStore()
        val tint = StatusBarTint(store)
        tint.remember("rgb(251, 244, 234)", app, dark = false)
        tint.remember("#1a1c1e", app, dark = true)
        assertEquals(themeColor("#fbf4ea"), tint.color(app, dark = false))
        assertEquals(themeColor("#1a1c1e"), tint.color(app, dark = true))
        assertEquals("under iOS's key", "#fbf4ea", store.getString("statusBarTint.0F0E0D0C-0B0A-0908-0706-050403020100.light"))
        // Half transparent or less, or not a color, leaves what was saved.
        tint.remember("rgba(0, 0, 0, 0.5)", app, dark = false)
        tint.remember("oklch(0.5 0.1 200)", app, dark = false)
        tint.remember(null, app, dark = false)
        assertEquals(themeColor("#fbf4ea"), tint.color(app, dark = false))
        tint.remember("rgba(10, 20, 30, 0.8)", app, dark = false)
        assertEquals(themeColor("#0a141e"), tint.color(app, dark = false))

        tint.forget(app)
        assertEquals("forgotten, the manifest's color again", themeColor("#336699"), tint.color(app, dark = false))
        assertNull(tint.color(app, dark = true))
    }

    @Test
    fun testReadsTheColorsChromiumWrites() {
        assertEquals("#fbf4ea", StatusBarTint.opaqueHex("rgb(251, 244, 234)"))
        assertEquals("#fbf4ea", StatusBarTint.opaqueHex("#FBF4EA"))
        assertEquals("#0a141e", StatusBarTint.opaqueHex("rgba(10, 20, 30, 0.51)"))
        assertNull(StatusBarTint.opaqueHex("rgba(0, 0, 0, 0)"))
        assertNull(StatusBarTint.opaqueHex("transparent"))
        assertNull(StatusBarTint.opaqueHex("#fff"))
        assertEquals("rgb(1, 2, 3)", StatusBarTint.decoded("\"rgb(1, 2, 3)\""))
        assertNull(StatusBarTint.decoded("null"))
        assertNull(StatusBarTint.decoded("42"))
        assertNull(StatusBarTint.decoded(null))
    }
}
