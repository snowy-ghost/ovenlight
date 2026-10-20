package com.snowyghost.ovenlight

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Asks the person to prove who they are: their fingerprint or face, or the phone's screen lock. */
interface Authenticator {
    sealed interface Result {
        data object Success : Result
        /** They closed the prompt, or Ovenlight did. Not an error worth showing. */
        data object Canceled : Result
        /** The phone has no screen lock, so there is nothing to unlock with. */
        data object NoScreenLock : Result
        data class Failed(val message: String) : Result
    }

    suspend fun authenticate(): Result
}

/** Settings kept on the phone, under iOS's UserDefaults key names. */
interface KeyValueStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
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

/**
 * Gates the whole app behind the phone's own unlock: a fingerprint or face, falling back to
 * the screen lock.
 */
class LockManager(
    private val store: KeyValueStore,
    /** Whether there is anything to protect yet. A new install stays unlocked until there is. */
    private val hasSavedData: () -> Boolean = { true },
    /**
     * Milliseconds on a clock that keeps counting while the phone sleeps and ignores changes to
     * the time of day (SystemClock.elapsedRealtime), so setting the clock back can't skip the
     * lock.
     */
    private val now: () -> Long,
    /** Android only: the phone's count of boots (Settings.Global.BOOT_COUNT), since [now] starts again at each. */
    private val bootCount: () -> Int = { 0 },
) {
    /**
     * When Ovenlight went to the background unlocked, kept on disk: Android ends a background
     * Ovenlight within minutes, and the next launch locks by the same rule as a return. Gone
     * after a reboot, which always locks.
     */
    private var backgroundedAt: Long? = store.getString(BACKGROUNDED_AT_KEY)?.split(' ')
        ?.takeIf { it.size == 2 && it[0].toIntOrNull() == bootCount() }?.get(1)?.toLongOrNull()
        set(value) {
            field = value
            if (value == null) store.remove(BACKGROUNDED_AT_KEY) else store.putString(BACKGROUNDED_AT_KEY, "${bootCount()} $value")
        }

    /** A launch locks while [lock] holds, and otherwise unless the lock is off or Ovenlight went to the background unlocked within the timeout. */
    private val locked = MutableStateFlow(
        hasSavedData() && (store.getBoolean(LOCKED_BY_HAND_KEY) || lockAfterSeconds != OFF && (backgroundedAt == null || lockIsDue)),
    ).also { if (!it.value) store.remove(LOCKED_BY_HAND_KEY) }
    val isLocked: StateFlow<Boolean> = locked

    private val authenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = authenticating

    val lastError = MutableStateFlow<String?>(null)

    /** This phone has no screen lock, so Ovenlight stays open; Settings says so. */
    private val withoutScreenLock = MutableStateFlow(false)
    val noPasscode: StateFlow<Boolean> = withoutScreenLock

    /** Seconds in the background before Ovenlight locks again. 0 locks every time, [OFF] never. */
    var lockAfterSeconds: Int
        get() = store.getInt(LOCK_AFTER_KEY, DEFAULT_LOCK_AFTER)
        set(value) = store.putInt(LOCK_AFTER_KEY, value)

    /**
     * The prompt opens by itself at launch and on each return from the background, never
     * again after the person cancels it; the prompt itself pauses Ovenlight, and coming back
     * from that is no reason to ask again.
     */
    private var promptsWhenActive = true

    fun didEnterBackground() {
        if (!locked.value) backgroundedAt = now()
        promptsWhenActive = true
    }

    /** Whether the next return to the front locks. A link opened on a return asks this too. */
    val lockIsDue: Boolean
        get() {
            val since = backgroundedAt ?: return false
            if (!hasSavedData() || lockAfterSeconds == OFF) return false
            val elapsed = now() - since
            return elapsed < 0 || elapsed >= lockAfterSeconds * 1000L
        }

    /** Whether an app a link asks for must wait for the unlock. */
    val holdsLinks: Boolean get() = locked.value || lockIsDue

    fun willBecomeActive() {
        if (lockIsDue) locked.value = true
        backgroundedAt = null
    }

    /** Holds until an unlock, whatever the delay, so it's kept on disk: Android ends a background Ovenlight within minutes. */
    fun lock() {
        locked.value = true
        store.putBoolean(LOCKED_BY_HAND_KEY, true)
    }

    /** The screen lock was just entered again while unlocked (see UnlockPrompt.confirm): the time it kept Ovenlight stopped doesn't lock it. */
    fun screenLockEntered() {
        backgroundedAt = null
    }

    suspend fun didBecomeActive(authenticator: Authenticator) {
        if (!locked.value || !promptsWhenActive) return
        promptsWhenActive = false
        unlock(authenticator)
    }

    suspend fun unlock(authenticator: Authenticator) {
        if (!locked.value || authenticating.value) return
        authenticating.value = true
        try {
            when (val result = authenticator.authenticate()) {
                Authenticator.Result.Success -> {
                    lastError.value = null
                    withoutScreenLock.value = false
                    locked.value = false
                }
                Authenticator.Result.NoScreenLock -> openWithoutScreenLock()
                Authenticator.Result.Canceled -> lastError.value = null
                is Authenticator.Result.Failed -> lastError.value = result.message
            }
            if (!locked.value) store.remove(LOCKED_BY_HAND_KEY)
        } finally {
            authenticating.value = false
        }
    }

    /**
     * Ovenlight came back: a screen lock set meanwhile (from Settings' Set screen lock, say)
     * ends Settings' note that there is none.
     */
    fun screenLockChecked(secure: Boolean) {
        if (secure) withoutScreenLock.value = false
    }

    /**
     * Without a screen lock there is nothing to authenticate with, and staying locked would
     * keep everything saved out of reach for good.
     */
    private fun openWithoutScreenLock() {
        lastError.value = null
        withoutScreenLock.value = true
        locked.value = false
    }

    companion object {
        const val LOCK_AFTER_KEY = "lockAfterSeconds"
        const val BACKGROUNDED_AT_KEY = "lockBackgroundedAt"
        const val LOCKED_BY_HAND_KEY = "lockedByHand"
        const val DEFAULT_LOCK_AFTER = 60
        /** Never locks. Read as a delay, it's the longest. */
        const val OFF = Int.MAX_VALUE
    }
}
