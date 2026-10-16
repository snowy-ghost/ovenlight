package com.snowyghost.ovenlight

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Ovenlight's tasks in Recents: one activity, one card. */
@RunWith(AndroidJUnit4::class)
class RecentsTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tasks = context.getSystemService(ActivityManager::class.java)

    private fun live(): List<Activity> = onMain {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }
    }

    private fun resumed(): Activity? = onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }

    @After
    fun tearDown() {
        tasks.appTasks.forEach { it.finishAndRemoveTask() }
    }

    /**
     * A link starts Ovenlight in a task of its own (no task affinity), which Back sends behind
     * rather than finishes. The icon brings that same Ovenlight back: one activity, one card, and
     * never a card with no Ovenlight in it, which would start a second one sharing the first
     * one's pages and lifecycle.
     */
    @Test
    fun testBackSendsALinksTaskBehindAndTheIconBringsItBack() {
        tasks.appTasks.forEach { it.finishAndRemoveTask() }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ovenlight://open?app=Nothing")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Ovenlight from the link") { resumed() is MainActivity }
        val fromLink = resumed()!!
        onMain { (fromLink as MainActivity).onBackPressedDispatcher.onBackPressed() }
        waitFor("Back to send it behind") { resumed() !== fromLink }
        assertEquals("finished", false, fromLink.isFinishing || fromLink.isDestroyed)

        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Ovenlight from the icon") { resumed() === fromLink }
        Thread.sleep(2_000)
        assertEquals("activities", listOf(fromLink), live())
        assertEquals("cards", 1, tasks.appTasks.size)
    }

    /**
     * Recents restores a card into its own task, past singleTask (after a reboot, a link's card
     * beside the icon's task). Its Ovenlight gives way to the running one: still one activity,
     * in one card. Here the card is one Ovenlight adds itself, started into its own task as
     * Recents starts one (Android 10 sends that start to the running Ovenlight instead).
     */
    @Test
    fun testACardRestoredBesideOvenlightGivesWayToIt() {
        tasks.appTasks.forEach { it.finishAndRemoveTask() }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Ovenlight from the icon") { resumed() is MainActivity }
        val running = resumed()!!
        val size = tasks.appTaskThumbnailSize
        val card = onMain {
            tasks.addAppTask(running, Intent(context, MainActivity::class.java), null, Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888))
        }
        tasks.appTasks.single { it.taskInfo?.taskId == card }.startActivity(context, Intent(context, MainActivity::class.java), null)
        waitFor("the running one in front") { resumed() === running }
        Thread.sleep(2_000)
        assertEquals("activities", listOf(running), live())
        assertEquals("cards with Ovenlight in them", 1, tasks.appTasks.count { it.taskInfo?.isRunning == true })
    }
}
