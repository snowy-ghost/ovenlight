package com.snowyghost.ovenlight

import com.snowyghost.ovenlight.AppLoadGate.Event
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
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
        CopyRules.assertNeutral(ConnectionCopy.notResponding("Coach", owner = null))
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

    /** The node's 30 seconds count only while it's busy, never while the owner signs in or approves. */
    @Test
    fun testTheNodeDeadlineCountsOnlyBusyTime() {
        val clock = TestTimeSource()
        val deadline = NodeDeadline(clock)
        assertEquals(30.seconds, deadline.left(NodeState.Starting))
        clock += 20.seconds
        assertEquals(10.seconds, deadline.left(NodeState.Connecting))
        // The gate waits on a sign-in for as long as it takes, then sees the node start.
        assertEquals(Duration.INFINITE, deadline.left(NodeState.NeedsLogin(URI("https://login.example.com/a/1"))))
        clock += 5.minutes
        assertEquals("the sign-in's time doesn't count", 30.seconds, deadline.left(NodeState.Starting))
        clock += 25.seconds
        assertEquals(5.seconds, deadline.left(NodeState.Connecting))
        deadline.left(NodeState.AwaitingApproval)
        clock += 2.minutes
        assertEquals("nor an approval's", 30.seconds, deadline.left(NodeState.Starting))
    }
}
