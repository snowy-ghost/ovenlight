package com.snowyghost.ovenlight

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The invite in the connector's encoding, as InviteLinkTests has it. */
private const val connectorLink = "ovenlight://join?app=echo-board&control=https%3A%2F%2Fcontrolplane.tailscale.com&host=echo-board.taildef456.ts.net" +
    "&invite=3f9a1c2b7e&key=tskey-auth-kX1abc-DEF%2Bghi&name=Echo+Board&owner=Riley&to=Sam+Lee&v=1"

/** The same invite as the App Link the connector sends: the fields in the fragment. */
private val universalLink = connectorLink.replace("ovenlight://join?", "https://ovenlight.app/join#")

/** Under Robolectric for the phone's ICU, which orders and matches names as iOS does. */
@RunWith(AndroidJUnit4::class)
class RouterTests {
    private fun join(router: Router): JoinRequest? = (router.sheet.value as? RootSheet.Join)?.request

    @Test
    fun testInviteOpensTheJoinSheet() {
        val router = Router()
        router.handle(URI(universalLink), apps = emptyList())
        assertEquals("3f9a1c2b7e", join(router)?.invite?.getOrThrow()?.invite)
        // Another page on the site isn't an invite and opens nothing.
        router.sheet.value = null
        router.sheetDismissed()
        router.handle(URI("https://ovenlight.app/privacy"), apps = emptyList())
        assertNull(router.sheet.value)
    }

    /** iOS's twin waits for the closing cover; on Android the open app closes, then Join shows. */
    @Test
    fun testInviteOverAnOpenAppWaitsForItToClose() {
        val router = Router()
        router.open(WebApp(name = "Coach", startURL = URI("https://coach.example.com/")))
        router.appAppeared()
        router.handle(URI(connectorLink), apps = emptyList())
        assertNull(router.openApp.value)
        assertNull("a sheet can't show while the app is closing", router.sheet.value)
        router.appDismissed()
        assertEquals("3f9a1c2b7e", join(router)?.invite?.getOrThrow()?.invite)
    }

    @Test
    fun testOpeningAnAppFromASheetWaitsForItToClose() {
        val router = Router()
        val app = WebApp(name = "Coach", startURL = URI("https://coach.example.com/"))
        router.present(RootSheet.Settings())
        router.sheetAppeared()
        router.open(app)
        assertNull(router.sheet.value)
        assertNull(router.openApp.value)
        router.sheetDismissed()
        assertEquals(app.id, router.openApp.value?.id)
    }

    @Test
    fun testLinkedNamesMatchAsIOSMatchesThem() {
        assertTrue("composed either way", sameName("Caf\u00E9", "Cafe\u0301"))
        assertTrue(sameName("Stra\u00DFe", "STRASSE"))
        assertTrue(sameName("Coach", "coach"))
        assertFalse("a dotted capital I is its own letter", sameName("\u0130stanbul", "istanbul"))
        assertFalse("and so is a dotless i", sameName("\u0131stanbul", "Istanbul"))
        assertTrue(sameName("\u00DF", "\u1E9E"))
        assertTrue("full-width letters", sameName("\uFF43\uFF4F\uFF41\uFF43\uFF48", "coach"))
    }

    @Test
    fun testALinkedAppWaitsForTheUnlock() {
        var locked = true
        val router = Router(isLocked = { locked })
        val app = WebApp(name = "Coach", startURL = URI("https://coach.example.com/"))
        router.handle(URI("ovenlight://open?app=coach"), apps = listOf(app))
        assertNull("nothing opens under the lock screen", router.openApp.value)
        locked = false
        router.appsChanged(listOf(app))
        assertEquals(app.id, router.openApp.value?.id)
    }

    /** Android only: a home screen shortcut opens its app by ID, which a rename doesn't change. */
    @Test
    fun testAShortcutOpensItsAppByID() {
        val router = Router()
        val app = WebApp(name = "Coach", startURL = URI("https://coach.example.com/"))
        val namedLikeIt = WebApp(name = app.id.toString(), startURL = URI("https://other.example.com/"))
        router.handle(URI("ovenlight://open?id=${UUID.randomUUID()}"), apps = listOf(app, namedLikeIt))
        assertNull("a removed app's shortcut opens nothing", router.openApp.value)
        router.handle(URI("ovenlight://open?id=${app.id}"), apps = listOf(namedLikeIt, app.copy(name = "Renamed")))
        assertEquals(app.id, router.openApp.value?.id)
    }

    /** On a return from the background, a link arrives before the lock does. */
    @Test
    fun testALinkedAppWaitsWhileTheLockIsDue() {
        var now = 1_000_000L
        var saved = false
        val lock = LockManager(MemoryStore(), hasSavedData = { saved }, now = { now })
        lock.lockAfterSeconds = 60
        saved = true
        // Stands in for the fingerprint or screen lock, which a test can't pass; the rest is the app's own check.
        var unlocked = false
        val router = Router(isLocked = { !unlocked && lock.holdsLinks })
        val app = WebApp(name = "Coach", startURL = URI("https://coach.example.com/"))
        lock.didEnterBackground()
        now += 60_000
        router.handle(URI("ovenlight://open?app=coach"), apps = listOf(app))
        assertNull("nothing loads before the lock screen covers it", router.openApp.value)
        lock.willBecomeActive()
        assertTrue(lock.isLocked.value)
        router.appsChanged(listOf(app))
        assertNull(router.openApp.value)
        unlocked = true
        router.appsChanged(listOf(app))
        assertEquals(app.id, router.openApp.value?.id)
    }
}
