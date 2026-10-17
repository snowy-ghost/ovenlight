package com.snowyghost.ovenlight

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The system's own unlock prompt. From Android 11 it takes a fingerprint or face, or the
 * screen lock. Android 10's prompt mishandles the screen lock, so there it offers only an
 * enrolled fingerprint or face, with a button for the screen lock's own screen, and opens that
 * screen at once when none is enrolled.
 *
 * Register it in the activity's onCreate: the screen lock's screen answers through an
 * activity result. [confirmedAfterRestart] takes a yes that comes with nothing waiting for it,
 * and [confirmAnswered] the screen's answer to [confirm], as it arrives, before Ovenlight shows.
 */
class UnlockPrompt(
    private val activity: ComponentActivity,
    confirmedAfterRestart: () -> Unit,
    private val confirmAnswered: (Boolean) -> Unit,
) : Authenticator {
    private var credentialAnswer: CompletableDeferred<Boolean>? = null
    private val credentialScreen = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val confirmed = it.resultCode == Activity.RESULT_OK
        if (confirming) confirmAnswered(confirmed)
        val waiting = credentialAnswer
        // Nothing waits when Android ended Ovenlight's process while the screen lock's screen was
        // up, and this activity is the one it made again. The yes is still the screen lock's own:
        // Android hands a result only to the activity that asked, under this launcher's key, which
        // the activity's saved state restores; this launcher opens only the system's screen lock
        // screen; and that screen says yes only once the screen lock was entered.
        if (waiting != null) waiting.complete(confirmed) else if (confirmed) confirmedAfterRestart()
    }

    /** True while Android 10's screen lock screen is up, which stops Ovenlight without leaving it. */
    var showingCredentialScreen = false
        private set

    /** True from the start of [confirm] until it answers. */
    var confirming = false
        private set

    override suspend fun authenticate(): Authenticator.Result = authenticate(activity.getString(R.string.unlock_prompt_title))

    /**
     * The screen lock again, while unlocked, before anything that changes who can open an app
     * (iOS's OwnerAuth), with [reason] as the prompt's title. A phone with no screen lock has
     * nothing to ask for, and Ovenlight already opens without one there (see [LockManager]).
     */
    suspend fun confirm(reason: String): Boolean {
        confirming = true
        try {
            val result = authenticate(reason)
            return result == Authenticator.Result.Success || result == Authenticator.Result.NoScreenLock
        } finally {
            confirming = false
        }
    }

    private suspend fun authenticate(title: String): Authenticator.Result {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) return Authenticator.Result.NoScreenLock
        // With the screen lock among its choices, the prompt always answers.
        if (Build.VERSION.SDK_INT >= 30) return prompt(title, withScreenLock = true) ?: Authenticator.Result.Canceled
        val biometrics = activity.getSystemService(BiometricManager::class.java)
        @Suppress("DEPRECATION") // canAuthenticate(Int) is API 30.
        if (biometrics.canAuthenticate() != BiometricManager.BIOMETRIC_SUCCESS) return credential(keyguard, title)
        // Any error but a cancel (a fingerprint locked out after too many tries, a sensor that
        // isn't available) goes on to the screen lock, as iOS falls back to the passcode.
        return when (val answer = prompt(title, withScreenLock = false)) {
            null, is Authenticator.Result.Failed -> credential(keyguard, title)
            else -> answer
        }
    }

    /** The prompt's answer, or null when Android 10's button for the screen lock was tapped. */
    private suspend fun prompt(title: String, withScreenLock: Boolean): Authenticator.Result? = suspendCancellableCoroutine { done ->
        val builder = BiometricPrompt.Builder(activity).setTitle(title)
        if (withScreenLock && Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        } else {
            builder.setNegativeButton(activity.getString(R.string.unlock_use_screen_lock), ContextCompat.getMainExecutor(activity)) { _, _ ->
                if (done.isActive) done.resume(null)
            }
        }
        val cancel = CancellationSignal()
        done.invokeOnCancellation { cancel.cancel() }
        builder.build().authenticate(cancel, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (done.isActive) done.resume(Authenticator.Result.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!done.isActive) return
                done.resume(
                    when (errorCode) {
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> Authenticator.Result.Canceled
                        BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> Authenticator.Result.NoScreenLock
                        else -> Authenticator.Result.Failed(errString.toString())
                    },
                )
            }
            // A finger or face that doesn't match leaves the prompt up to try again.
        })
    }

    private suspend fun credential(keyguard: KeyguardManager, title: String): Authenticator.Result {
        @Suppress("DEPRECATION") // The Android 10 path only; later versions use the prompt.
        val intent: Intent = keyguard.createConfirmDeviceCredentialIntent(title, null)
            ?: return Authenticator.Result.NoScreenLock
        val answer = CompletableDeferred<Boolean>().also { credentialAnswer = it }
        showingCredentialScreen = true
        try {
            credentialScreen.launch(intent)
            return if (answer.await()) Authenticator.Result.Success else Authenticator.Result.Canceled
        } finally {
            showingCredentialScreen = false
            credentialAnswer = null
        }
    }
}

/** Ovenlight's settings, kept in SharedPreferences under iOS's UserDefaults key names. */
class PreferencesStore(context: Context) : KeyValueStore {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override fun getInt(key: String, default: Int) = preferences.getInt(key, default)
    override fun putInt(key: String, value: Int) = preferences.edit { putInt(key, value) }
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String) = preferences.edit { putString(key, value) }
    override fun getBoolean(key: String) = preferences.getBoolean(key, false)
    override fun putBoolean(key: String, value: Boolean) = preferences.edit { putBoolean(key, value) }
    override fun remove(key: String) = preferences.edit { remove(key) }
}
