package com.snowyghost.ovenlight

import com.snowyghost.ovenlight.AppLoadGate.Event
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    /** With no network the gate says so at once, and starts over when one comes. */
    @Test
    fun testAPhoneWithNoNetworkGivesUpAtOnceAndOpensOnceItHasOne() {
        assertEquals(AppLoadGate.Offline, run(listOf(Event.PhoneOffline)))
        assertEquals(AppLoadGate.Offline, run(listOf(Event.NodeReady, Event.PhoneOffline)))
        val offline = run(listOf(Event.PhoneOffline))
        assertEquals("nothing else leaves it", offline, run(listOf(Event.NodeReady, Event.Reached, Event.GaveUp, Event.Woke), from = offline))
        assertEquals("a network starts over", AppLoadGate.Waiting, run(listOf(Event.PhoneOnline), from = offline))
        assertEquals(AppLoadGate.Waiting, run(listOf(Event.Retry), from = offline))
        assertEquals("a loaded page stays", AppLoadGate.Ready, run(listOf(Event.PhoneOffline), from = AppLoadGate.Ready))
        assertEquals(AppLoadGate.Unreachable, run(listOf(Event.PhoneOffline, Event.PhoneOnline), from = AppLoadGate.Unreachable))
        assertEquals(AppLoadGate.Waiting, run(listOf(Event.PhoneOnline)))
        CopyRules.assertNeutral(ConnectionCopy.offline)
        CopyRules.assertNeutral(ConnectionCopy.offlineDetail)
    }

    /**
     * Android only: offline, with the node not ready, the gate waits for a change rather than
     * looping on what it already read, and wakes on the network coming back.
     */
    @Test
    fun testTheGateOfflineAndNotReadyWaitsInsteadOfLooping() = runTest {
        val state = MutableStateFlow<NodeState>(NodeState.Starting)
        val online = MutableStateFlow(false)
        val seen = state.value to online.value
        var woke = false
        launch {
            AppLoadGate.awaitChange(combine(state, online, ::Pair), seen, Duration.INFINITE)
            woke = true
        }
        advanceTimeBy(60_000)
        assertFalse("nothing changed, so it still waits", woke)
        online.value = true
        runCurrent()
        assertTrue(woke)

        var timedOut = false
        launch {
            AppLoadGate.awaitChange(online, true, 30.seconds)
            timedOut = true
        }
        advanceTimeBy(29_000)
        assertFalse(timedOut)
        advanceTimeBy(2_000)
        assertTrue("a busy node's time ran out", timedOut)
    }
}
