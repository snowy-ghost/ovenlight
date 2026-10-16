package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStateTests {
    @Test
    fun testTilesFlagOnlyTailnetAppsThatDidNotAnswer() {
        val coach = "interview-coach.tailabc123.ts.net"
        fun tile(
            host: String = coach,
            node: NodeState = NodeState.Ready,
            answers: Map<String, Boolean>? = mapOf(coach to true),
        ) = AppReachability.forTile(host, node, answers)
        assertNull(tile())
        assertNull("hosts compare normalized", tile("Interview-Coach.tailabc123.ts.net"))
        assertEquals(AppReachability.Offline, tile(answers = mapOf(coach to false)))
        assertEquals("not probed: its computer is offline", AppReachability.Offline, tile(answers = mapOf("other.tailabc123.ts.net" to true)))
        assertNull("nothing before the first probe", tile(answers = null))
        assertNull("the launcher shows the connection itself", tile(node = NodeState.Connecting, answers = mapOf(coach to false)))
    }

    @Test
    fun testSharedCopyNeverNamesTailscale() {
        val copy = listOf(
            ConnectionCopy.connecting, ConnectionCopy.stopped, ConnectionCopy.couldNotStart,
            ConnectionCopy.connecting(to = "Coach"), ConnectionCopy.cantReach("Coach"),
            ConnectionCopy.cantReachDetail, ConnectionCopy.pageFailed, ConnectionCopy.signIn(toOpen = "Coach"),
            NodeState.Starting.summary, NodeMachine.next(NodeState.Ready, NodeEvent.Backend("Stopped", null)).summary,
            ConnectionCopy.reportProblem, ConnectionCopy.leaveApps("Riley"), ConnectionCopy.leaveTitle("Riley"),
            ConnectionCopy.leaveDetail("Riley"), LauncherWelcome.title, LauncherWelcome.message,
        )
        copy.forEach(CopyRules::assertNeutral)
    }
}
