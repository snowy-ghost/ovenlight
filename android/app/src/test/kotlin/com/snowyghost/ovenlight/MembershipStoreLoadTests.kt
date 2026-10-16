package com.snowyghost.ovenlight

import java.io.File
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MembershipStoreLoadTests {
    private val invite = InviteLink(
        control = URI("https://controlplane.tailscale.com"), key = "tskey-1", owner = "Riley",
        app = "echo-board", invite = "inv1", host = "echo-board.taildef456.ts.net", name = "Echo Board", to = "Sam",
    )

    @get:Rule
    val temp = TemporaryFolder()
    private val dir: File get() = temp.root

    @Test
    fun testAnUnreadableFileIsNeverSavedOver() {
        val file = File(dir, "memberships.json")
        val corrupt = "[{".toByteArray()
        file.writeBytes(corrupt)

        val store = MembershipStore(dir)
        assertTrue(store.loadFailed)
        store.upsert(Membership(invite))
        assertArrayEquals(corrupt, file.readBytes())

        file.delete()
        val fresh = MembershipStore(dir)
        assertFalse("no file is simply no memberships", fresh.loadFailed)
        fresh.upsert(Membership(invite))
        assertEquals(1, MembershipStore(dir).memberships.size)
        assertTrue(fresh.readInFull)
    }

    /** Android only: bytes that aren't UTF-8 are a file it can't read, as iOS's decoder finds, not text to save back altered. */
    @Test
    fun testAFileThatIsntUTF8IsNeverSavedOver() {
        val file = File(dir, "memberships.json")
        val damaged = byteArrayOf('['.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ']'.code.toByte())
        file.writeBytes(damaged)
        val store = MembershipStore(dir)
        assertTrue(store.loadFailed)
        store.upsert(Membership(invite))
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test
    fun testAMembershipItCantReadIsKeptAndHoldsBackCleanup() {
        val file = File(dir, "memberships.json")
        file.writeText("""[{"id":"future","controlURL":"https://c.example","networkDomain":"tail1.ts.net","joined":"yes","hostName":"ovenlight-x"}]""")

        val store = MembershipStore(dir)
        assertFalse(store.loadFailed)
        assertEquals(emptyList<Membership>(), store.memberships)
        assertFalse("its apps would look orphaned", store.readInFull)
        store.upsert(Membership(invite))
        val saved = ovenlightJSON.parseToJsonElement(file.readText()) as JsonArray
        assertEquals("written back as it was", "future", ((saved.last() as JsonObject)["id"] as JsonPrimitive).content)
        assertEquals(false, ((saved.first() as JsonObject)["joined"] as JsonPrimitive).booleanOrNull)
        assertEquals("what it read reads back the same", store.memberships, MembershipStore(dir).memberships)
    }
}
