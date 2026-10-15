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
 * activity result.
 */
class UnlockPrompt(private val activity: ComponentActivity) : Authenticator {
    private var credentialAnswer: CompletableDeferred<Boolean>? = null
    private val credentialScreen = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        credentialAnswer?.complete(it.resultCode == Activity.RESULT_OK)
    }

    /** True while Android 10's screen lock screen is up, which stops Ovenlight without leaving it. */
    var showingCredentialScreen = false
        private set

    override suspend fun authenticate(): Authenticator.Result {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) return Authenticator.Result.NoScreenLock
        // With the screen lock among its choices, the prompt always answers.
        if (Build.VERSION.SDK_INT >= 30) return prompt(withScreenLock = true) ?: Authenticator.Result.Canceled
        val biometrics = activity.getSystemService(BiometricManager::class.java)
        @Suppress("DEPRECATION") // canAuthenticate(Int) is API 30.
        if (biometrics.canAuthenticate() != BiometricManager.BIOMETRIC_SUCCESS) return credential(keyguard)
        return prompt(withScreenLock = false) ?: credential(keyguard)
    }

    /** The prompt's answer, or null when Android 10's button for the screen lock was tapped. */
    private suspend fun prompt(withScreenLock: Boolean): Authenticator.Result? = suspendCancellableCoroutine { done ->
        val title = activity.getString(R.string.unlock_prompt_title)
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

    private suspend fun credential(keyguard: KeyguardManager): Authenticator.Result {
        @Suppress("DEPRECATION") // The Android 10 path only; later versions use the prompt.
        val intent: Intent = keyguard.createConfirmDeviceCredentialIntent(activity.getString(R.string.unlock_prompt_title), null)
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

/** The lock's delay, kept in SharedPreferences under iOS's key names. */
class PreferencesStore(context: Context) : KeyValueStore {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override fun getInt(key: String, default: Int) = preferences.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        preferences.edit { putInt(key, value) }
    }
}
