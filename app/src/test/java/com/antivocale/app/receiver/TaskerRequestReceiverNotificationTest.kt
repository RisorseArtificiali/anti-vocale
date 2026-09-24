package com.antivocale.app.receiver

import android.app.Application
import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import com.antivocale.app.service.InferenceService
import io.mockk.every
import io.mockk.spyk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests that TaskerRequestReceiver creates the correct notification channel
 * in the fallback notification path. These tests target the CURRENT production
 * code and establish a baseline for the notification channel refactor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TaskerRequestReceiverNotificationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `fallback notification path creates tasker_fallback_channel with IMPORTANCE_HIGH and badge`() {
        // Use spyk to intercept startForegroundService and force the fallback path
        val contextSpy = spyk(context)
        every { contextSpy.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("test: blocked from background")

        val receiver = TaskerRequestReceiver()
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "text")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "test_task")
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, "test prompt")
        }

        receiver.onReceive(contextSpy, intent)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("tasker_fallback_channel")

        assertNotNull("tasker_fallback_channel should be created", channel)
        assertEquals(
            "tasker_fallback_channel should have IMPORTANCE_HIGH",
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance
        )
        assertEquals(
            "tasker_fallback_channel should show badge",
            true,
            channel.canShowBadge()
        )
    }

    @Test
    fun `channel creation is idempotent - posting fallback twice does not crash`() {
        val contextSpy = spyk(context)
        every { contextSpy.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("test: blocked from background")

        val receiver = TaskerRequestReceiver()
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "text")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "test_task")
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, "test prompt")
        }

        // Call twice — should not crash
        receiver.onReceive(contextSpy, intent)
        receiver.onReceive(contextSpy, intent)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("tasker_fallback_channel")
        assertNotNull("tasker_fallback_channel should still exist after double creation", channel)
    }

    @Test
    fun `two queued taskIds post two distinct fallback notifications`() {
        val contextSpy = spyk(context)
        every { contextSpy.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("test: blocked from background")

        val receiver = TaskerRequestReceiver()

        listOf("task_a", "task_b").forEach { taskId ->
            receiver.onReceive(
                contextSpy,
                Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
                    putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "text")
                    putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
                }
            )
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val ids = notificationManager.activeNotifications.map { it.id }
        assertEquals(
            "TASK-380: distinct taskIds must not share one fallback notification id",
            2,
            ids.distinct().size
        )
    }

    @Test
    fun `valid backend_id is forwarded to the service as backend override`() {
        val receiver = TaskerRequestReceiver()
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "t394")
            putExtra(TaskerRequestReceiver.EXTRA_BACKEND_ID, "llm")
        }
        receiver.onReceive(context, intent)
        val started = shadowOf(context.applicationContext as Application).nextStartedService
        assertNotNull(started)
        assertEquals(
            "llm", started.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
    }

    @Test
    fun `file_path outside the allowlist is rejected with no service start`() {
        // TASK-274: only shared_audio and cacheDir are path-transcribable;
        // a databases/ or traversal path must never reach the enqueue.
        val receiver = TaskerRequestReceiver()
        val outside = java.io.File(context.filesDir, "databases/../../databases/anti_vocale_database")
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "t274path")
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, outside.absolutePath)
        }
        receiver.onReceive(context, intent)
        val shadow = shadowOf(context.applicationContext as Application)
        assertNull(shadow.nextStartedService)
        val reply = shadow.broadcastIntents.lastOrNull()
        assertNotNull(reply)
        assertEquals(TaskerRequestReceiver.STATUS_ERROR,
            reply!!.getStringExtra(TaskerRequestReceiver.EXTRA_STATUS))
    }

    @Test
    fun `file_path inside shared_audio is forwarded to the service`() {
        val receiver = TaskerRequestReceiver()
        val inside = java.io.File(context.filesDir, "shared_audio/note.ogg")
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "t274ok")
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, inside.absolutePath)
        }
        receiver.onReceive(context, intent)
        val started = shadowOf(context.applicationContext as Application).nextStartedService
        assertNotNull(started)
        assertEquals(inside.absolutePath,
            started.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
    }

    @Test
    fun `unknown backend_id fails loudly with no service start`() {
        val receiver = TaskerRequestReceiver()
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "t394bad")
            putExtra(TaskerRequestReceiver.EXTRA_BACKEND_ID, "no-such-backend")
        }
        receiver.onReceive(context, intent)
        val shadow = shadowOf(context.applicationContext as Application)
        assertNull(shadow.nextStartedService)
        val reply = shadow.broadcastIntents.lastOrNull()
        assertNotNull(reply)
        assertEquals(
            TaskerRequestReceiver.STATUS_ERROR,
            reply?.getStringExtra(TaskerRequestReceiver.EXTRA_STATUS))
        assertTrue(
            reply?.getStringExtra(TaskerRequestReceiver.EXTRA_ERROR_MESSAGE)?.contains("no-such-backend") == true)
    }
}
