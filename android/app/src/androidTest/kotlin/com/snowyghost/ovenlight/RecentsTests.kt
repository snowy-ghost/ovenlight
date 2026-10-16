package com.snowyghost.ovenlight

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
     * A link starts Ovenlight in a task of its own (no task affinity), which Back then leaves.
     * Its card doesn't stay in Recents, where tapping it beside the task the icon opens would
     * start a second Ovenlight sharing the first one's pages and lifecycle.
     */
    @Test
    fun testALinksTaskLeftWithBackLeavesNoSecondCard() {
        tasks.appTasks.forEach { it.finishAndRemoveTask() }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ovenlight://open?app=Nothing")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Ovenlight from the link") { resumed() is MainActivity }
        val fromLink = resumed()!!
        onMain { (fromLink as MainActivity).onBackPressedDispatcher.onBackPressed() }
        waitFor("Back to leave it") { fromLink.isDestroyed }

        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("Ovenlight from the icon") { resumed().let { it is MainActivity && it !== fromLink } }
        val fromIcon = resumed()!!
        // Every other card, opened as Recents opens one.
        tasks.appTasks.filter { it.taskInfo?.taskId != fromIcon.taskId }.forEach { it.moveToFront() }
        Thread.sleep(2_000)
        assertEquals("activities", listOf(fromIcon), live())
        assertEquals("cards", 1, tasks.appTasks.size)
    }
}
