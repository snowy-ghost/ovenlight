package com.snowyghost.ovenlight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTests {
    @Test
    fun testTilesFlagOnlyTailnetAppsThatDidNotAnswer() {
        val coach = "interview-coach.tailabc123.ts.net"
        fun tile(
            host: String = coach,
            node: NodeState = NodeState.Ready,
            answers: Map<String, Boolean>? = mapOf(coach to true),
        ) = NodeManager.isOffline(host, node, answers)
        assertFalse(tile())
        assertFalse("hosts compare normalized", tile("Interview-Coach.tailabc123.ts.net"))
        assertTrue(tile(answers = mapOf(coach to false)))
        assertTrue("not probed: its computer is offline", tile(answers = mapOf("other.tailabc123.ts.net" to true)))
        assertTrue("what a node records when nothing was left to probe", tile(answers = emptyMap()))
        assertFalse("nothing before the first probe", tile(answers = null))
        assertFalse("the launcher shows the connection itself", tile(node = NodeState.Connecting, answers = mapOf(coach to false)))
    }
}
