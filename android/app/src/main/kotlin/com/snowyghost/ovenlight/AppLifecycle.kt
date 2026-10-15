package com.snowyghost.ovenlight

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coming and going, Android only: iOS suspends Ovenlight itself. An excursion is a trip out
 * that hands something back (Ovenlight's own Custom Tab, a picker, a lock prompt): for its first
 * five minutes polling goes on and no node closes, so a sign-in or a file pick finds everything
 * as it left it. Leaving (anything else, or an excursion past five minutes) pauses polling, and
 * after a minute away closes every node, whose state stays on disk.
 *
 * Android may freeze a cached process, so a timer can't be trusted to fire on time: the close
 * works from timestamps, and a return finishes a close that came due while frozen before
 * starting the nodes again.
 */
class AppLifecycle(
    private val scope: CoroutineScope,
    /** Milliseconds on a clock that counts while the phone sleeps. */
    private val now: () -> Long,
    private val pausePolling: (Boolean) -> Unit,
    private val closeNodes: suspend () -> Unit,
    /** Back in front: start closed nodes, look for apps and icons. */
    private val cameToFront: suspend () -> Unit,
) {
    private var excursionStartedAt: Long? = null
    private var leftAt: Long? = null
    private var nodesClosed = false
    private var away: Job? = null

    /** Ovenlight is about to send the person somewhere that hands a result back. */
    fun beginExcursion() {
        excursionStartedAt = now()
    }

    /** The activity is in front again, which ends any excursion. */
    fun activityStarted() {
        away?.cancel()
        away = null
        val closeDue = !nodesClosed && closeIsDue()
        excursionStartedAt = null
        leftAt = null
        pausePolling(false)
        scope.launch {
            if (closeDue) closeNodes()
            nodesClosed = false
            cameToFront()
        }
    }

    /** The activity stopped, not for a configuration change. */
    fun activityStopped() {
        away?.cancel()
        val excursion = excursionStartedAt
        if (excursion == null) {
            leave()
            return
        }
        away = scope.launch {
            delay((excursion + EXCURSION_MILLIS - now()).coerceAtLeast(0))
            leave()
        }
    }

    private fun leave() {
        leftAt = now()
        pausePolling(true)
        away = scope.launch {
            // A frozen process wakes here late or not at all, so the clock decides, not the timer.
            while (!closeIsDue()) delay((leftAt ?: now()) + CLOSE_AFTER_MILLIS - now())
            nodesClosed = true
            closeNodes()
        }
    }

    private fun closeIsDue(): Boolean {
        val left = leftAt ?: excursionStartedAt?.let { it + EXCURSION_MILLIS } ?: return false
        return now() - left >= CLOSE_AFTER_MILLIS
    }

    companion object {
        const val EXCURSION_MILLIS = 5 * 60_000L
        const val CLOSE_AFTER_MILLIS = 60_000L
    }
}
