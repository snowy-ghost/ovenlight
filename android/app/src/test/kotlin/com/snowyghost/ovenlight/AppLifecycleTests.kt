package com.snowyghost.ovenlight

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Android only: iOS suspends Ovenlight itself.
@OptIn(ExperimentalCoroutinesApi::class)
class AppLifecycleTests {
    private class Recorder {
        var paused = false
        var closes = 0
        var fronts = 0
    }

    /** [clock] stands in for elapsedRealtime, which keeps counting while a frozen process's timers don't. */
    private fun TestScope.lifecycle(recorder: Recorder, clock: () -> Long = { testScheduler.currentTime }) = AppLifecycle(
        backgroundScope, clock, pausePolling = { recorder.paused = it }, closeNodes = { recorder.closes += 1 }, cameToFront = { recorder.fronts += 1 },
    )

    @Test
    fun testLeavingPausesAndClosesTheNodesAfterAMinute() = runTest {
        val r = Recorder()
        val life = lifecycle(r)
        life.activityStarted()
        runCurrent()
        assertEquals(1, r.fronts)
        life.activityStopped()
        assertTrue(r.paused)
        advanceTimeBy(59_000)
        assertEquals(0, r.closes)
        advanceTimeBy(2_000)
        assertEquals(1, r.closes)

        life.activityStarted()
        runCurrent()
        assertFalse(r.paused)
        assertEquals("closed once, then started again", 1, r.closes)
        assertEquals(2, r.fronts)
    }

    @Test
    fun testComingBackWithinTheMinuteKeepsTheNodes() = runTest {
        val r = Recorder()
        val life = lifecycle(r)
        life.activityStopped()
        advanceTimeBy(30_000)
        life.activityStarted()
        advanceTimeBy(120_000)
        assertEquals(0, r.closes)
        assertFalse(r.paused)
    }

    @Test
    fun testAnExcursionKeepsEverythingRunningForFiveMinutes() = runTest {
        val r = Recorder()
        val life = lifecycle(r)
        life.beginExcursion()
        life.activityStopped()
        advanceTimeBy(4 * 60_000)
        assertFalse("polling goes on", r.paused)
        assertEquals(0, r.closes)
        life.activityStarted()
        advanceTimeBy(10 * 60_000)
        assertEquals("the excursion ended when Ovenlight came back", 0, r.closes)
    }

    @Test
    fun testAnExcursionPastFiveMinutesLeaves() = runTest {
        val r = Recorder()
        val life = lifecycle(r)
        life.beginExcursion()
        life.activityStopped()
        advanceTimeBy(5 * 60_000 + 1_000)
        assertTrue(r.paused)
        assertEquals(0, r.closes)
        advanceTimeBy(60_000)
        assertEquals(1, r.closes)
    }

    @Test
    fun testACloseThatCameDueWhileFrozenRunsOnReturn() = runTest {
        val r = Recorder()
        var now = 0L
        val life = lifecycle(r) { now }
        life.activityStopped()
        // Frozen: two minutes pass on the clock while no timer fires.
        now = 120_000
        life.activityStarted()
        runCurrent()
        assertEquals("closed before the nodes start again", 1, r.closes)
        assertEquals(1, r.fronts)
    }

    @Test
    fun testATimerThatFiresEarlyAfterAThawDoesNotClose() = runTest {
        val r = Recorder()
        var now = 0L
        val life = lifecycle(r) { now }
        life.activityStopped()
        // The timer fires, but the clock says only 10 seconds have passed.
        now = 10_000
        advanceTimeBy(61_000)
        assertEquals(0, r.closes)
        now = 70_000
        advanceTimeBy(61_000)
        assertEquals("it waits out the rest", 1, r.closes)
    }
}
