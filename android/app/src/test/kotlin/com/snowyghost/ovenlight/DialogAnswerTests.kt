package com.snowyghost.ovenlight

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogAnswerTests {
    @Test
    fun testAnswersOnceAndFallsBackWhenLetGoUnanswered() {
        val answers = mutableListOf<String?>()
        val answer = DialogAnswer<String?>(fallback = null) { answers += it }
        answer("Sam")
        answer("again")
        answer.dropped()
        assertEquals(listOf<String?>("Sam"), answers)

        // Swift answers from deinit; here whatever takes the dialog away calls dropped().
        val confirmed = mutableListOf<Boolean>()
        val dropped = DialogAnswer(fallback = false) { confirmed += it }
        dropped.dropped()
        dropped(true)
        assertEquals("a dialog taken away still answers, once", listOf(false), confirmed)
    }
}
