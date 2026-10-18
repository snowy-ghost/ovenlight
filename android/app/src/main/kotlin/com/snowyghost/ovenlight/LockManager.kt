package com.snowyghost.ovenlight

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

    /** A launch locks unless Ovenlight went to the background unlocked within the timeout. */
    private val locked = MutableStateFlow(hasSavedData() && (backgroundedAt == null || lockIsDue))
    val isLocked: StateFlow<Boolean> = locked

    private val authenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = authenticating

    val lastError = MutableStateFlow<String?>(null)

    /** This phone has no screen lock, so Ovenlight stays open; Settings says so. */
    private val withoutScreenLock = MutableStateFlow(false)
    val noPasscode: StateFlow<Boolean> = withoutScreenLock

    /** Seconds in the background before Ovenlight locks again. 0 locks every time. */
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
            if (!hasSavedData()) return false
            val elapsed = now() - since
            return elapsed < 0 || elapsed >= lockAfterSeconds * 1000L
        }

    /** Whether an app a link asks for must wait for the unlock. */
    val holdsLinks: Boolean get() = locked.value || lockIsDue

    fun willBecomeActive() {
        if (lockIsDue) locked.value = true
        backgroundedAt = null
    }

    fun lock() {
        locked.value = true
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
        const val DEFAULT_LOCK_AFTER = 60
    }
}
