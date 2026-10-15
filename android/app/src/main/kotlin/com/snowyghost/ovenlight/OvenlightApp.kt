package com.snowyghost.ovenlight

import android.app.Application
import android.os.SystemClock
import java.io.File

class OvenlightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The core's proxy listens before any WebView exists.
        TailnetCore.start(this)
        WebViewSetup.start(this)
        // The lock lives with the process, never in saved instance state, so a configuration
        // change or a restore after process death starts locked.
        lock = LockManager(
            PreferencesStore(this),
            hasSavedData = { hasSavedData(File(filesDir, "Ovenlight"), File(noBackupFilesDir, "tailscale")) },
            now = SystemClock::elapsedRealtime,
        )
    }

    companion object {
        lateinit var lock: LockManager
            private set

        /**
         * Whether this phone has kept anything: apps, invites or a node. Apps and invites live
         * in [ovenlight] (filesDir/Ovenlight), each node's state in [nodes]
         * (noBackupFilesDir/tailscale).
         */
        fun hasSavedData(ovenlight: File, nodes: File): Boolean {
            // A failed join, leaving the last network and removing the last app each leave an
            // empty list, `[]`; a file whose size can't be read (0) still counts.
            if (listOf("apps.json", "memberships.json").any { File(ovenlight, it).let { file -> file.exists() && file.length() != 2L } }) {
                return true
            }
            // Stop Using My Own Computers and leaving the last network leave the folder empty;
            // a listing that fails still counts it.
            return nodes.exists() && nodes.list()?.isEmpty() != true
        }
    }
}
