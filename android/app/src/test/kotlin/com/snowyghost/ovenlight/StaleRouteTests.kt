package com.snowyghost.ovenlight

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A public Funnel name added before the owner ever signed in routes to the owner's node; signing
 * in shows it isn't on the owner's tailnet. The view made for the owner's node must never load,
 * through the owner's node or at all.
 */
class StaleRouteTests {
    private val funnel = WebApp(name = "Cool", startURL = URI("https://cool.attacker.ts.net/"))

    /** Stands in for a membership's node: the routing rules only compare them. */
    private class Node(val id: String)

    /** A route with no node is refused (plan decision 9), as on iPhone. */
    @Test
    fun testAViewIsStaleOnceTheRouteNamesAnotherNode() {
        val owner = Node(Membership.ownerID)
        fun node(app: WebApp, ownerSuffix: String?, guest: Node? = null): Node? = Routing.node(app, owner, ownerSuffix) { guest }

        val built = node(funnel, null)
        assertSame("never signed in: it waits behind the owner's sign-in", owner, built)
        assertTrue(Routing.mayLoad(built, node(funnel, null)))

        val signedIn = node(funnel, "tailabc123.ts.net")
        assertNull("another tailnet's name is refused", signedIn)
        assertFalse("the owner's node is no longer its route", Routing.mayLoad(built, signedIn))
        assertFalse("nor the other way round", Routing.mayLoad(null, owner))
        assertTrue(Routing.mayLoad(null, null))

        val shared = funnel.copy(membershipID = "m1")
        val guest = Node("m1")
        val sharedBuilt = node(shared, null, guest)
        assertSame(guest, sharedBuilt)
        assertTrue(Routing.mayLoad(sharedBuilt, node(shared, "tailabc123.ts.net", guest)))
        assertFalse("its membership is gone", Routing.mayLoad(sharedBuilt, node(shared, null)))
    }
}
