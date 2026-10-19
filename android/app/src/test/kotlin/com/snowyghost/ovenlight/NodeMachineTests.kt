package com.snowyghost.ovenlight

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeMachineTests {
    private val login = URI("https://login.tailscale.com/a/abc")

    private fun run(events: List<NodeEvent>, from: NodeState = NodeState.Idle): NodeState =
        events.fold(from) { state, event -> NodeMachine.next(state, event) }

    private fun backend(state: String, authURL: URI? = null) = NodeEvent.Backend(state, authURL)

    @Test
    fun testFirstRunGoesThroughLogin() {
        assertEquals(NodeState.Starting, run(listOf(NodeEvent.Start, backend("NoState"))))
        assertEquals(NodeState.Starting, run(listOf(NodeEvent.Start, backend("NeedsLogin"))))
        assertEquals(NodeState.NeedsLogin(login), run(listOf(NodeEvent.Start, backend("NeedsLogin", login))))
        // After signing in the backend starts, then runs; the reachability check decides ready.
        assertEquals(NodeState.Connecting, run(listOf(backend("Starting")), from = NodeState.NeedsLogin(login)))
        assertEquals(NodeState.Ready, run(listOf(backend("Running"), NodeEvent.Reachable), from = NodeState.NeedsLogin(login)))
    }

    /** iOS asks for the name it saved; Android's manager passes its own the same way. */
    @Test
    fun testDeviceNameIsTheOneTheTailnetGave() {
        val requested = Membership.randomHostName()
        val waiting = """{"BackendState":"NeedsMachineAuth","Self":{"HostName":"ovenlight-abc123","DNSName":"ovenlight-abc123-1.tailabc123.ts.net.","UserID":42}}"""
        assertEquals("ovenlight-abc123-1", NodeManager.deviceName(TailnetStatus.decode(waiting)!!, requested))
        assertEquals("before the tailnet names it", requested, NodeManager.deviceName(null, requested))
        val unnamed = """{"BackendState":"NeedsMachineAuth","Self":{"HostName":"ovenlight-abc123","DNSName":""}}"""
        assertEquals(requested, NodeManager.deviceName(TailnetStatus.decode(unnamed)!!, requested))
    }

    @Test
    fun testLoggedInNodeConnectsWithoutLogin() {
        assertEquals(NodeState.Starting, run(listOf(NodeEvent.Start, backend("Starting"))))
        assertEquals(NodeState.Connecting, run(listOf(NodeEvent.Start, backend("Running"))))
        assertEquals(NodeState.Ready, run(listOf(NodeEvent.Start, backend("Running"), NodeEvent.Reachable)))
    }

    @Test
    fun testLostFirstHandshakeRetriesThenGivesUpToTheApps() {
        val first = run(listOf(NodeEvent.Start, backend("Running"), NodeEvent.Unreachable))
        assertEquals(NodeState.Retrying(1), first)
        assertEquals("polls keep the retry count", first, run(listOf(backend("Running")), from = first))
        assertEquals(NodeState.Ready, run(listOf(NodeEvent.Unreachable, NodeEvent.Reachable), from = first))
        val exhausted = run(List(NodeMachine.maxRetries + 1) { NodeEvent.Unreachable }, from = NodeState.Connecting)
        assertEquals("the node is up; the app shows its own error from here", NodeState.Ready, exhausted)
    }

    @Test
    fun testStopRestartAndFailures() {
        assertEquals(NodeState.Idle, run(listOf(NodeEvent.Stop), from = NodeState.Ready))
        assertEquals(NodeState.Starting, run(listOf(NodeEvent.Start), from = NodeState.Ready))
        assertEquals(NodeState.Failed("boom"), run(listOf(NodeEvent.Error("boom")), from = NodeState.Starting))
        assertEquals(NodeState.Connecting, run(listOf(backend("Running")), from = NodeState.Failed("boom")))
        assertEquals(
            "device approval pending is its own state, for the owner's approval prompt",
            NodeState.AwaitingApproval,
            run(listOf(backend("NeedsMachineAuth")), from = NodeState.Starting),
        )
        assertEquals(NodeState.Connecting, run(listOf(backend("Running")), from = NodeState.AwaitingApproval))
        assertEquals("a stray probe result can't skip login", NodeState.NeedsLogin(login), run(listOf(NodeEvent.Reachable), from = NodeState.NeedsLogin(login)))
    }

    /** On a tailnet that approves each new device, the start after the sign-in doesn't close the sign-in page. */
    @Test
    fun testSignInStaysOpenUntilApproved() {
        val events = listOf(backend("Starting"), backend("NeedsMachineAuth"), backend("Running"), NodeEvent.Reachable)
        val states = events.runningFold<NodeEvent, NodeState>(NodeState.NeedsLogin(login), NodeMachine::next)
        assertEquals(
            listOf(NodeState.NeedsLogin(login), NodeState.Connecting, NodeState.AwaitingApproval, NodeState.Connecting, NodeState.Ready),
            states,
        )
        assertEquals(listOf(false, false, false, false, true), states.map { it.endsSignIn })
        assertTrue(NodeState.Failed("boom").endsSignIn)
        assertFalse("Ovenlight closing its nodes while away", NodeState.Idle.endsSignIn)
    }

    @Test
    fun testTheLogNeverCarriesTheSignInLink() {
        assertEquals("needsLogin", NodeState.NeedsLogin(login).logDescription)
        assertEquals("retrying(attempt: 2)", NodeState.Retrying(2).logDescription)
        assertEquals("the message is the summary's, not the log's", "failed", NodeState.Failed("boom").logDescription)
    }

    @Test
    fun testStatusDecoding() {
        val json = """
        {"BackendState":"Running","AuthURL":"","Self":{"HostName":"ovenlight-abc123","DNSName":"ovenlight-abc123.tailabc123.ts.net.","OS":"iOS","Online":true,"UserID":42},
         "Peer":{"nodekey:1":{"HostName":"interview-coach","DNSName":"Interview-Coach.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":42},
                 "nodekey:2":{"HostName":"phone","DNSName":"phone.tailabc123.ts.net.","OS":"iOS","Online":true,"UserID":42},
                 "nodekey:3":{"HostName":"laptop","DNSName":"laptop.tailabc123.ts.net.","OS":"macOS","Online":false,"UserID":42}},
         "User":{"42":{"ID":42,"LoginName":"alex@example.com","DisplayName":"Alex"}},
         "CurrentTailnet":{"Name":"alex@example.com","MagicDNSSuffix":"tailabc123.ts.net","MagicDNSEnabled":true}}
        """
        val status = TailnetStatus.decode(json)!!
        assertEquals(NodeEvent.Backend("Running", null), status.event)
        assertEquals("alex@example.com", status.account?.loginName)
        assertEquals("tailabc123.ts.net", status.tailnet?.magicDNSSuffix)
        assertEquals("online, non-phone peers only, normalized", listOf("interview-coach.tailabc123.ts.net"), Discovery.candidateHosts(status))

        val needsLogin = TailnetStatus.decode("""{"BackendState":"NeedsLogin","AuthURL":"https://login.tailscale.com/a/abc","Peer":null}""")!!
        assertEquals(NodeEvent.Backend("NeedsLogin", login), needsLogin.event)
        assertEquals(emptyList<TailnetStatus.Peer>(), needsLogin.peers)
    }

    /** Polls repeat while nothing changes, and only a change redraws: the same status is equal, whatever order its peers come in. */
    @Test
    fun testTheSameStatusTwiceIsEqual() {
        val peers = listOf(
            """"nodekey:1":{"HostName":"interview-coach","DNSName":"interview-coach.tailabc123.ts.net.","OS":"macOS","Online":true,"UserID":42}""",
            """"nodekey:2":{"HostName":"laptop","DNSName":"laptop.tailabc123.ts.net.","OS":"macOS","Online":false,"UserID":42}""",
            """"nodekey:3":{"HostName":"phone","DNSName":"phone.tailabc123.ts.net.","OS":"iOS","Online":true,"UserID":42}""",
        )
        fun status(peers: List<String>) = TailnetStatus.decode("""{"BackendState":"Running","Peer":{${peers.joinToString(",")}}}""")!!
        assertEquals(status(peers), status(peers))
        assertEquals(status(peers), status(peers.reversed()))
        assertNotEquals("a peer coming online is a change", status(peers), status(peers.map { it.replace("false", "true") }))
    }
}
