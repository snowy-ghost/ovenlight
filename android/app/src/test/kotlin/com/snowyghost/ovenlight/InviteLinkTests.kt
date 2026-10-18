package com.snowyghost.ovenlight

import java.net.URI
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link in the connector's encoding (url.Values.Encode: spaces as "+", sorted keys), with the
 * escapes its golden file (connector/testdata/wire/invite-created.json) doesn't have.
 */
private const val connectorLink = "ovenlight://join?app=echo-board&control=https%3A%2F%2Fcontrolplane.tailscale.com&host=echo-board.taildef456.ts.net" +
    "&invite=3f9a1c2b7e&key=tskey-auth-kX1abc-DEF%2Bghi&name=Echo+Board&owner=Riley&to=Sam+Lee&v=1"

/** The same invite as the App Link the connector sends: the fields in the fragment. */
private val universalLink = connectorLink.replace("ovenlight://join?", "https://ovenlight.app/join#")

class InviteLinkTests {
    private fun parse(text: String, local: Boolean = false): InviteLink = InviteLink.parse(URI(text), allowLocalHTTP = local)

    private fun error(text: String, local: Boolean = false): InviteLink.ParseError? = try {
        InviteLink.parse(URI(text), allowLocalHTTP = local)
        null
    } catch (e: InviteLink.ParseError) {
        e
    }

    /** The connector's own link is read in `testDecodesEveryGoldenFileFromTheConnector`. */
    @Test
    fun testReadsTheConnectorsEscapes() {
        val link = parse(connectorLink)
        assertEquals("%2B is a literal plus", "tskey-auth-kX1abc-DEF+ghi", link.key)
        assertEquals("+ is a space", "Sam Lee", link.to)
        assertEquals("taildef456.ts.net", link.networkDomain)
        assertEquals("https://echo-board.taildef456.ts.net/", link.appURL.toString())
    }

    @Test
    fun testReadsTheUniversalLink() {
        val link = parse(connectorLink)
        assertEquals(link, parse(universalLink))
        for (variant in listOf("https://Ovenlight.APP/join#", "https://ovenlight.app/join.html#", "https://ovenlight.app/join/#")) {
            val text = universalLink.replace("https://ovenlight.app/join#", variant)
            assertEquals(variant, link, parse(text))
        }
        assertTrue(InviteLink.isInvite(URI(universalLink)) && InviteLink.isInvite(URI(connectorLink)))
    }

    /** Android only: the join page hands an invite to an unverified App Link this way. */
    @Test
    fun testReadsTheAppSchemeWithTheFieldsInTheFragment() {
        val link = parse(connectorLink)
        val fragment = connectorLink.replace("ovenlight://join?", "ovenlight://join#")
        assertEquals(link, parse(fragment))
        assertTrue(InviteLink.isInvite(URI(fragment)))
        assertEquals("the query wins when both are there", link, parse(connectorLink + "#v=2"))
        assertEquals(InviteLink.ParseError.UnsupportedVersion, error("ovenlight://join"))
        assertEquals("found in a message too", "3f9a1c2b7e", InviteLink.parse(text = "Open this: $fragment", allowLocalHTTP = false).invite)
        assertEquals("an empty address is none, as iOS's URL(string:) says", null, uri(""))
    }

    @Test
    fun testRefusesUniversalLinksFromElsewhere() {
        val fields = universalLink.split("#", limit = 2)[1]
        for (text in listOf(
            "https://evil.example/join#", "https://ovenlight.app.evil.example/join#",
            "https://www.ovenlight.app/join#", "http://ovenlight.app/join#", "https://ovenlight.app:8443/join#",
            "https://ovenlight.app/ovenlight/join#", "https://ovenlight.app/joined#",
            "https://ovenlight.app/privacy#",
        )) {
            assertEquals(text, InviteLink.ParseError.NotAnInvite, error(text + fields))
        }
        assertEquals("the page itself, without an invite", InviteLink.ParseError.NotAnInvite, error("https://ovenlight.app/join"))
        assertEquals("fields the server would see", InviteLink.ParseError.NotAnInvite, error("https://ovenlight.app/join?$fields"))
        assertFalse(InviteLink.isInvite(URI("https://ovenlight.app/privacy")))
        // The old address is no longer an App Link, in a message or on its own.
        for (old in listOf("https://snowyghost.com/ovenlight/join#", "https://www.snowyghost.com/ovenlight/join#")) {
            assertEquals(old, InviteLink.ParseError.NotAnInvite, error(old + fields))
            assertFailsWith<InviteLink.ParseError> { InviteLink.parse(text = "Open this link: ${old + fields}", allowLocalHTTP = false) }
        }
    }

    @Test
    fun testFindsTheLinkInsideAMessage() {
        val message = "Riley shared Echo Board with you in Ovenlight. Install Ovenlight on your iPhone, then open this link there: $connectorLink\n"
        assertEquals("3f9a1c2b7e", InviteLink.parse(text = message, allowLocalHTTP = false).invite)
        val universal = "Riley shared Echo Board with you in Ovenlight. Open this link on your iPhone: $universalLink\n"
        assertEquals("3f9a1c2b7e", InviteLink.parse(text = universal, allowLocalHTTP = false).invite)
        assertFailsWith<InviteLink.ParseError> { InviteLink.parse(text = "no link here", allowLocalHTTP = false) }
    }

    @Test
    fun testChecksVersionControlAndRequiredFields() {
        assertEquals(InviteLink.ParseError.NotAnInvite, error("ovenlight://open?app=Coach"))
        assertEquals(InviteLink.ParseError.NotAnInvite, error("https://example.com/join?v=1"))
        assertEquals(InviteLink.ParseError.UnsupportedVersion, error(connectorLink.replace("&v=1", "&v=2")))
        assertEquals(InviteLink.ParseError.UnsupportedVersion, error(connectorLink.replace("&v=1", "")))
        assertEquals(InviteLink.ParseError.InsecureControl, error(connectorLink.replace("https%3A%2F%2F", "http%3A%2F%2F")))
        for (field in listOf("key", "app", "invite", "host", "control")) {
            val without = connectorLink.replace(Regex("""(^|[?&])$field=[^&]*"""), "$1")
            assertNotNull("missing $field", error(without))
        }
        assertEquals(
            InviteLink.ParseError.MissingField("key"),
            error(connectorLink.replace("key=tskey-auth-kX1abc-DEF%2Bghi", "key=")),
        )
    }

    @Test
    fun testRefusesHostsThatArentNames() {
        for (host in listOf("10.0.0.1", "echo", "echo.ts.net%2Fpath", "echo.ts.net%3A8443", "-echo.ts.net", "ec%20ho.ts.net")) {
            assertEquals(host, InviteLink.ParseError.InvalidField("host"), error(connectorLink.replace("echo-board.taildef456.ts.net", host)))
        }
        assertEquals(InviteLink.ParseError.InvalidField("app"), error(connectorLink.replace("app=echo-board", "app=..%2Fetc")))
    }

    @Test
    fun testLocalHTTPControlOnlyForDevelopmentOnThisMachine() {
        val local = connectorLink.replace("https%3A%2F%2Fcontrolplane.tailscale.com", "http%3A%2F%2F127.0.0.1%3A18092")
        assertEquals(InviteLink.ParseError.InsecureControl, error(local))
        assertEquals(18092, parse(local, local = true).control.port)
        val remote = connectorLink.replace("https%3A%2F%2Fcontrolplane.tailscale.com", "http%3A%2F%2Fevil.example")
        assertEquals(InviteLink.ParseError.InsecureControl, error(remote, local = true))
    }

    @Test
    fun testLabelsAreCleanedAndBounded() {
        val long = "x".repeat(100)
        val link = parse(
            connectorLink.replace("owner=Riley", "owner=%0ARi%07ley+")
                .replace("to=Sam+Lee", "to=$long")
                .replace("name=Echo+Board", "name="),
        )
        assertEquals("Riley", link.owner)
        assertEquals(InviteLink.maxLabel, link.to.graphemeCount())
        assertEquals("the slug stands in for a missing name", "echo-board", link.name)
    }

    /** Counted in grapheme clusters with java.text.BreakIterator, as Swift counts characters. */
    @Test
    fun testLabelsKeepTheJoinersEmojiAndScriptsNeed() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 Family"
        assertEquals(family, InviteLink.label(family))
        assertEquals("Persian keeps its zero-width non-joiner", "می\u200Cخواهم", InviteLink.label("می\u200Cخواهم"))
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F Club"
        assertEquals(england, InviteLink.label(england))
        assertEquals(
            "bidi controls and invisible characters go",
            "BanknigoL Pay",
            InviteLink.label("Bank\u202EnigoL\u202C \u2066Pay\u2069\u200F\u200B\uFEFF"),
        )
        assertEquals("counted as people see them", InviteLink.maxLabel, InviteLink.label(family.repeat(30))?.graphemeCount())
    }

    @Test
    fun testControlServerAndNetwork() {
        val link = parse(connectorLink)
        assertTrue(link.usesTailscaleControl)
        val other = parse(connectorLink.replace("controlplane.tailscale.com", "hs.example.com"))
        assertFalse(other.usesTailscaleControl)

        assertTrue(link.isNetwork("Taildef456.ts.net."))
        assertFalse("the key led to another network", link.isNetwork("tail0000.ts.net"))
        assertFalse(link.isNetwork(""))
        assertFalse(link.isNetwork(null))
    }

    @Test
    fun testAJoinLeavesOnlyANetworkItJustJoinedAndProvablyWrong() {
        val link = parse(connectorLink)
        assertEquals(Claim.Landing.InNetwork, Claim.landing(link, suffix = "taildef456.ts.net", registeredNow = true))
        assertEquals(Claim.Landing.Elsewhere(leave = true), Claim.landing(link, suffix = "tail0000.ts.net", registeredNow = true))
        assertEquals(
            "a node that joined before keeps its login and the owner's other apps",
            Claim.Landing.Elsewhere(leave = false),
            Claim.landing(link, suffix = "tail0000.ts.net", registeredNow = false),
        )
        for (registered in listOf(true, false)) {
            assertEquals("a node that couldn't say retries", Claim.Landing.Unknown, Claim.landing(link, suffix = null, registeredNow = registered))
            assertEquals(Claim.Landing.Unknown, Claim.landing(link, suffix = "", registeredNow = registered))
        }
        assertTrue(JoinError.Network.isRetryable)
    }

    @Test
    fun testParseErrorsHaveNeutralMessages() {
        val errors = listOf(
            InviteLink.ParseError.NotAnInvite, InviteLink.ParseError.UnsupportedVersion, InviteLink.ParseError.InsecureControl,
            InviteLink.ParseError.MissingField("key"), InviteLink.ParseError.InvalidField("host"),
        )
        for (message in errors.map { it.message }) CopyRules.assertNeutral(message)
    }

    /** Android only: a character outside the BMP, written raw, decodes whole, as Swift's removingPercentEncoding does. */
    @Test
    fun testARawEmojiSurvivesDecoding() {
        assertEquals("\uD83C\uDF73 Pantry", removingPercentEncoding("\uD83C\uDF73%20Pantry"))
        assertEquals("Riley \uD83C\uDF73", parse(connectorLink.replace("owner=Riley", "owner=Riley+\uD83C\uDF73")).owner)
    }
}
