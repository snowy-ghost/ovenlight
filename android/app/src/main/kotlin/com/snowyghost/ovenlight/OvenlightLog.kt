package com.snowyghost.ovenlight

import android.util.Log

/**
 * Ovenlight's own log lines. They name apps, hosts, tailnets and invites, so only debug
 * builds write them: in a release build the system log, which bug reports and anything with
 * adb can read, gets nothing from Ovenlight.
 */
object OvenlightLog {
    const val TAG = "Ovenlight"

    fun i(line: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, line)
    }

    fun w(line: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, line)
    }
}
