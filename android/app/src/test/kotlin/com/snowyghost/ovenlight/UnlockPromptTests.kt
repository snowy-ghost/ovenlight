package com.snowyghost.ovenlight

import android.app.Activity
import android.app.KeyguardManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android 10's screen lock screen, which the prompt opens when no fingerprint or face is enrolled. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class UnlockPromptTests {
    /**
     * A screen lock asked for again, canceled with the lock due: the lock comes, and its own
     * prompt opens a second screen before the canceled ask has finished, as MainActivity's
     * onResume does. That second screen's answer must still unlock.
     */
    @Test
    fun testAnUnlockAfterACanceledConfirmStillAnswers() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val scope = MainScope()
        var unlock: Deferred<Authenticator.Result>? = null
        lateinit var prompt: UnlockPrompt
        prompt = UnlockPrompt(activity, confirmedAfterRestart = {}, confirmAnswered = { confirmed ->
            // The lock is due: it comes, and the resumed activity asks to unlock at once.
            if (!confirmed) unlock = scope.async(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) { prompt.authenticate() }
        })
        controller.start().resume()

        val confirm = scope.async { prompt.confirm("Share Recipes with Sam") }
        shadowOf(Looper.getMainLooper()).idle()
        val first = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(first.intent, Activity.RESULT_CANCELED, null)
        // The canceled ask finishes after the unlock's screen opened.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(confirm.getCompleted())

        val second = shadowOf(activity).nextStartedActivityForResult
        assertEquals("the unlock's screen is the one up", true, prompt.showingCredentialScreen)
        shadowOf(activity).receiveResult(second.intent, Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Authenticator.Result.Success, unlock!!.getCompleted())
        assertFalse(prompt.showingCredentialScreen)
    }
}
