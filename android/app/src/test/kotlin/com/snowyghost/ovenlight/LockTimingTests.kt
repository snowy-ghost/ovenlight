package com.snowyghost.ovenlight

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun putInt(key: String, value: Int) {
        values[key] = value
    }
    override fun getString(key: String) = values[key] as? String
    override fun putString(key: String, value: String) {
        values[key] = value
    }
    override fun getBoolean(key: String) = values[key] as? Boolean ?: false
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
}

class LockTimingTests {
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

    @Test
    fun testAnEmptyNodeFolderOrListIsNothingToLock() {
        val base = Files.createTempDirectory("ovenlight").toFile()
        try {
            val ovenlight = File(base, "Ovenlight").apply { mkdirs() }
            val nodes = File(base, "tailscale")
            File(nodes, "owner").mkdirs()
            assertTrue(OvenlightApp.hasSavedData(ovenlight, nodes))
            File(nodes, "owner").delete()
            assertFalse("what Stop Using My Own Computers leaves", OvenlightApp.hasSavedData(ovenlight, nodes))
            for (name in listOf("apps.json", "memberships.json")) File(ovenlight, name).writeText("[]")
            assertFalse("what a failed join or removing the last app leaves", OvenlightApp.hasSavedData(ovenlight, nodes))
            File(ovenlight, "apps.json").writeText("""[{"id":"a","name":"Notes","startURL":"https://notes.example.com/","dataStoreID":"s"}]""")
            assertTrue(OvenlightApp.hasSavedData(ovenlight, nodes))
        } finally {
            base.deleteRecursively()
        }
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
