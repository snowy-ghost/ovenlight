package com.snowyghost.ovenlight

import com.snowyghost.ovenlight.AppLoadGate.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoadGateTests {
    private fun run(events: List<Event>, from: AppLoadGate = AppLoadGate.Waiting): AppLoadGate =
        events.fold(from) { gate, event -> gate.next(event) }

    @Test
    fun testLoadsOnlyAfterTheNodeIsReadyAndTheHostAnswers() {
        assertEquals("a probe can't count before the node is ready", AppLoadGate.Waiting, run(listOf(Event.Reached)))
        assertEquals(AppLoadGate.Probing, run(listOf(Event.NodeReady)))
        assertEquals("a node restart sends it back to waiting", AppLoadGate.Waiting, run(listOf(Event.NodeReady, Event.NodeNotReady, Event.Reached)))
        assertEquals(AppLoadGate.Ready, run(listOf(Event.NodeReady, Event.NodeNotReady, Event.NodeReady, Event.Reached)))
    }

    @Test
    fun testALoadedPageStaysThroughNodeRestarts() {
        assertEquals(AppLoadGate.Ready, run(listOf(Event.NodeNotReady, Event.GaveUp), from = AppLoadGate.Ready))
        assertEquals("a failed first navigation checks the path again", AppLoadGate.Waiting, run(listOf(Event.LoadFailed), from = AppLoadGate.Ready))
        assertEquals("Reload checks the path before reloading", AppLoadGate.Waiting, run(listOf(Event.Retry), from = AppLoadGate.Ready))
    }

    @Test
    fun testGivingUpWaitsForRetry() {
        val gaveUp = run(listOf(Event.NodeReady, Event.GaveUp))
        assertEquals(AppLoadGate.Unreachable, gaveUp)
        assertEquals("only Retry leaves it", gaveUp, run(listOf(Event.NodeReady, Event.Reached), from = gaveUp))
        assertEquals(AppLoadGate.Waiting, run(listOf(Event.Retry), from = gaveUp))
    }

    /** What every released connector, 1.0.0 to 1.0.6, sends for an app that isn't running. */
    @Test
    fun testTheConnectorsAppDownAnswer() {
        assertTrue(AppLoadGate.appIsDown(502, "unavailable"))
        assertTrue(AppLoadGate.appIsDown(502, " unavailable "))
        assertFalse("the app's own 502 is its page", AppLoadGate.appIsDown(502, null))
        assertFalse(AppLoadGate.appIsDown(503, "unavailable"))
        assertFalse(AppLoadGate.appIsDown(403, "not_invited"))
        assertFalse(AppLoadGate.appIsDown(null, null))
        CopyRules.assertNeutral(ConnectionCopy.notResponding("Coach"))
    }

    @Test
    fun testAComputerListedOfflineOpensTheAppWhenItAnswers() {
        val gaveUp = run(listOf(Event.NodeReady, Event.GaveUp))
        assertEquals("a quiet retry answered", AppLoadGate.Ready, run(listOf(Event.Woke), from = gaveUp))
        assertEquals("only after giving up", AppLoadGate.Waiting, run(listOf(Event.Woke)))
        assertEquals(AppLoadGate.Probing, run(listOf(Event.NodeReady, Event.Woke)))
    }
}
