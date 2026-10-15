package com.snowyghost.ovenlight

import android.app.Application
import java.io.File

class OvenlightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The core's proxy listens before any WebView exists.
        TailnetCore.start(this)
        WebViewSetup.start(this)
    }

    companion object {
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
