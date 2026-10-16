package com.snowyghost.ovenlight

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** A phone with no browser at all, as Robolectric's starts: a plain view is all CustomTabs has. */
@RunWith(AndroidJUnit4::class)
class CustomTabsTests {
    private val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val job = Job()
    private var away = false
    private val lifecycle = AppLifecycle(CoroutineScope(job), { 0L }, setAway = { away = it }, closeNodes = {}, cameToFront = {})

    @After
    fun tearDown() = job.cancel()

    /** Opening Ovenlight's own tab begins an excursion, so the stop that follows isn't leaving; a tab that didn't open begins none. */
    @Test
    fun testATabThatOpensIsAnExcursion() {
        assertTrue(CustomTabs.open(context, URI("https://ovenlight.app/privacy"), lifecycle))
        lifecycle.activityStopped()
        assertFalse("an excursion keeps things running", away)
        lifecycle.activityStarted()

        assertFalse(CustomTabs.open(context, URI("https://ovenlight.app/join"), lifecycle))
        lifecycle.activityStopped()
        assertTrue("no tab, so stopping is leaving", away)
    }

    @Test
    fun testWithNoBrowserTheJoinPageNeverReachesOvenlight() {
        for (link in listOf("https://ovenlight.app/join#v=1", "https://OVENLIGHT.app/Join.html")) {
            assertFalse(link, CustomTabs.open(context, URI(link), lifecycle))
            assertNull(link, shadowOf(context).nextStartedActivity)
        }
    }

    @Test
    fun testWithNoBrowserOtherPagesStillGo() {
        assertTrue(CustomTabs.open(context, URI("https://ovenlight.app/support"), lifecycle))
        val started = shadowOf(context).nextStartedActivity
        assertEquals("https://ovenlight.app/support", started.dataString)
        assertNull("no browser to name", started.`package`)
    }
}
