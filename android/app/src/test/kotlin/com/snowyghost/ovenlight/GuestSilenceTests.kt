package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSilenceTests {
    @Test
    fun testRestartsAfterSilentLooksThenBacksOff() {
        val silence = GuestSilence()
        val hosts = listOf("two-nights.example.ts.net")
        fun looksUntilRestart(): Int {
            var n = 0
            do {
                n += 1
            } while (!silence.record(probed = hosts, reachable = false))
            return n
        }
        assertEquals(GuestSilence.firstRestart, looksUntilRestart())
        assertEquals(GuestSilence.firstRestart * 2, looksUntilRestart())
        repeat(10) { looksUntilRestart() }
        assertEquals(GuestSilence.maxGap, looksUntilRestart())
    }

    @Test
    fun testAnAnswerOrNothingOnlineNeverRestarts() {
        val silence = GuestSilence()
        val hosts = listOf("two-nights.example.ts.net")
        repeat(GuestSilence.firstRestart - 1) { assertFalse(silence.record(probed = hosts, reachable = false)) }
        assertFalse("an answer starts over", silence.record(probed = hosts, reachable = true))
        repeat(GuestSilence.firstRestart - 1) { assertFalse(silence.record(probed = hosts, reachable = false)) }
        repeat(10) { assertFalse("asleep computers aren't silence", silence.record(probed = emptyList(), reachable = false)) }
        assertTrue(silence.record(probed = hosts, reachable = false))
    }
}
