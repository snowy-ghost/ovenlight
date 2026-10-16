package com.snowyghost.ovenlight

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An intent: link from a page, as Android's own parser reads it (Robolectric runs the framework's). */
@RunWith(AndroidJUnit4::class)
class PageIntentTests {
    private val own = "com.snowyghost.ovenlight"

    private fun intent(text: String) = PageEdges.pageIntent(text, own)

    @Test
    fun testOnlyAViewGoesAheadWithNothingThatAimsItElsewhere() {
        val link = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;" +
            "component=com.google.zxing.client.android/.CaptureActivity;launchFlags=0x10000003;" +
            "S.browser_fallback_url=https%3A%2F%2Fevil.example%2F;S.output=content%3A%2F%2Fmedia%2F1;S.label=hello;i.count=3;end"
        val intent = assertNotNull(intent(link))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("zxing://scan/", intent.dataString)
        assertEquals("com.google.zxing.client.android", intent.`package`)
        assertNull(intent.component)
        assertNull(intent.selector)
        assertEquals(0, intent.flags)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull("the fallback is never followed", intent.getStringExtra("browser_fallback_url"))
        assertNull(intent.getStringExtra("output"))
        assertEquals("hello", intent.getStringExtra("label"))
        assertEquals(3, intent.getIntExtra("count", 0))
    }

    @Test
    fun testASelectorGoes() {
        val intent = assertNotNull(intent("intent://example.com/#Intent;scheme=https;SEL;component=com.example/.Hidden;end"))
        assertNull(intent.selector)
        assertNull(intent.component)
    }

    @Test
    fun testRefusesAnythingButAView() {
        assertNull(intent("intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.example;end"))
        assertNull(intent("intent://settings#Intent;action=android.settings.SETTINGS;end"))
        assertNull(intent("intent://x#Intent;action=android.intent.action.SEND;S.android.intent.extra.STREAM=content%3A%2F%2Fx;end"))
    }

    @Test
    fun testRefusesOvenlightItself() {
        assertNull(intent("intent://join?v=1#Intent;scheme=https;package=com.snowyghost.ovenlight;end"))
        assertNull(intent("intent://join?v=1#Intent;scheme=https;package=COM.SNOWYGHOST.OVENLIGHT;end"))
        assertNull(intent("intent://join?v=1&key=k#Intent;scheme=ovenlight;end"))
        assertNull(intent("intent://open?app=Coach#Intent;scheme=OVENLIGHT;end"))
        for (path in listOf("join", "join/", "join.html", "JOIN", "Join.Html")) {
            assertNull(path, intent("intent://ovenlight.app/$path#Intent;scheme=https;end"))
        }
        assertNotNull(intent("intent://ovenlight.app/support#Intent;scheme=https;end"))
    }

    @Test
    fun testRefusesThePhonesFiles() {
        assertNull(intent("intent://com.android.contacts/contacts#Intent;scheme=content;end"))
        assertNull(intent("intent:///sdcard/notes.txt#Intent;scheme=file;end"))
        assertNull(intent("intent://com.example.app/#Intent;scheme=android-app;end"))
    }

    @Test
    fun testRefusesWhatDoesntParse() {
        assertNull(intent("intent://x#Intent;i.count=many;end"))
        assertNull(intent("intent://x#Intent;launchFlags=zz;end"))
    }
}
