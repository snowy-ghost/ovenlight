package com.snowyghost.ovenlight

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LockTimingTests {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun testLocksByTimeInTheBackgroundNotTheClock() {
        var now = 1_000_000L
        var saved = false
        val lock = LockManager(MemoryStore(), hasSavedData = { saved }, now = { now })
        saved = true
        lock.lockAfterSeconds = 60

        lock.didEnterBackground()
        now += 59_000
        lock.willBecomeActive()
        assertFalse(lock.isLocked.value)

        lock.didEnterBackground()
        now += 60_000
        lock.willBecomeActive()
        assertTrue(lock.isLocked.value)
    }

    /** What a link arriving on a return from the background asks, before willBecomeActive. */
    @Test
    fun testLockIsDueByTheSameRule() {
        var now = 1_000_000L
        var saved = false
        val lock = LockManager(MemoryStore(), hasSavedData = { saved }, now = { now })
        lock.lockAfterSeconds = 60

        lock.didEnterBackground()
        now += 60_000
        assertFalse("nothing saved, nothing to lock", lock.lockIsDue)
        lock.willBecomeActive()

        saved = true
        assertFalse("not in the background", lock.lockIsDue)
        lock.didEnterBackground()
        now += 59_000
        assertFalse(lock.lockIsDue)
        now += 1_000
        assertTrue(lock.lockIsDue)
        assertFalse("due, not yet locked", lock.isLocked.value)
        lock.willBecomeActive()
        assertTrue(lock.isLocked.value)
        assertFalse("back in front", lock.lockIsDue)
    }

    /** Android only: Android ends a background Ovenlight within minutes, and the next launch locks by the same rule. */
    @Test
    fun testALaunchAfterTheProcessEndedLocksByTheTimeAway() {
        val store = MemoryStore()
        var now = 1_000_000L
        var boot = 7
        var saved = false
        fun launch() = LockManager(store, hasSavedData = { saved }, now = { now }, bootCount = { boot })
        val first = launch()
        saved = true
        assertTrue("nothing kept about the last process", launch().isLocked.value)

        store.putInt(LockManager.LOCK_AFTER_KEY, 900)
        first.didEnterBackground()
        now += 14 * 60_000
        assertFalse("within the 15 minutes", launch().isLocked.value)
        now += 2 * 60_000
        assertTrue("past them", launch().isLocked.value)

        now = 1_000_000L
        boot = 8
        assertTrue("a reboot always locks, whatever the clock says", launch().isLocked.value)

        boot = 7
        store.putInt(LockManager.LOCK_AFTER_KEY, 0)
        assertTrue("Immediately always locks", launch().isLocked.value)

        store.putInt(LockManager.LOCK_AFTER_KEY, 900)
        val back = launch()
        assertFalse(back.isLocked.value)
        back.willBecomeActive()
        assertTrue("a launch while in front, after a crash, say, locks", launch().isLocked.value)
    }

    @Test
    fun testAnEmptyNodeFolderOrListIsNothingToLock() {
        val ovenlight = File(temp.root, "Ovenlight").apply { mkdirs() }
        val nodes = File(temp.root, "tailscale")
        File(nodes, "owner").mkdirs()
        assertTrue(OvenlightApp.hasSavedData(ovenlight, nodes))
        File(nodes, "owner").delete()
        assertFalse("what Stop Using My Own Computers leaves", OvenlightApp.hasSavedData(ovenlight, nodes))
        for (name in listOf("apps.json", "memberships.json")) File(ovenlight, name).writeText("[]")
        assertFalse("what a failed join or removing the last app leaves", OvenlightApp.hasSavedData(ovenlight, nodes))
        File(ovenlight, "apps.json").writeText("""[{"id":"a","name":"Notes","startURL":"https://notes.example.com/","dataStoreID":"s"}]""")
        assertTrue(OvenlightApp.hasSavedData(ovenlight, nodes))
    }
}

class LockManagerTests {
    @Test
    fun testLocksOnlyOnceSomethingIsSaved() {
        var saved = false
        val lock = LockManager(MemoryStore(), hasSavedData = { saved }, now = { 0L })
        lock.lockAfterSeconds = 0
        assertFalse("a new install has nothing to protect", lock.isLocked.value)
        lock.didEnterBackground()
        lock.willBecomeActive()
        assertFalse(lock.isLocked.value)
        saved = true
        lock.didEnterBackground()
        lock.willBecomeActive()
        assertTrue(lock.isLocked.value)
    }

    /** Android only: a screen lock set while away ends Settings' note that there is none. */
    @Test
    fun testAScreenLockSetMeanwhileEndsTheNote() = kotlinx.coroutines.test.runTest {
        val lock = LockManager(MemoryStore(), hasSavedData = { true }, now = { 0L })
        lock.unlock(object : Authenticator {
            override suspend fun authenticate() = Authenticator.Result.NoScreenLock
        })
        assertTrue(lock.noPasscode.value)
        assertFalse("nothing to unlock with, so it stays open", lock.isLocked.value)
        lock.screenLockChecked(secure = false)
        assertTrue(lock.noPasscode.value)
        lock.screenLockChecked(secure = true)
        assertFalse(lock.noPasscode.value)
    }
}
