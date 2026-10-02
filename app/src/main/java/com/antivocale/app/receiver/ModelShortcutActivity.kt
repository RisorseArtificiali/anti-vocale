package com.antivocale.app.receiver

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.antivocale.app.R
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.transcription.ModelActivator

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TASK-552: the app-icon dynamic model-shortcut trampoline. A NoDisplay
 * activity (never visible, excluded from recents) that hands the switch to
 * [ModelActivator] on the application scope and finishes immediately: the
 * model switch must not depend on this activity staying alive, and no UI
 * opens (AC2). The outcome lands as a self-replacing confirmation
 * notification (fixed id, below the result band).
 */
@AndroidEntryPoint
class ModelShortcutActivity : ComponentActivity() {

    @Inject lateinit var modelActivator: ModelActivator
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    // The services' own idiom: the factory is context-only and not
    // Dagger-provided; constructing it here is the established shape.
    private val resultNotificationFactory by lazy { ResultNotificationFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backendId = intent.getStringExtra(EXTRA_BACKEND_ID)
        val context: Context = applicationContext
        if (backendId != null) {
            applicationScope.launch(Dispatchers.IO) { switch(context, backendId) }
        }
        finish()
    }

    private suspend fun switch(context: Context, backendId: String) {
        val displayName = runCatching { modelActivator.activate(backendId, context) }.getOrNull()
        // Range review: with POST_NOTIFICATIONS denied the notify() drops
        // silently; skip the dead post (MemoryKillStartupCheck's precedent).
        // The switch itself still lands; the system setting is the user's.
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        val notification = resultNotificationFactory.alertNotification(
            title = context.getString(R.string.model_switched_title),
            text = if (displayName != null) {
                context.getString(R.string.model_switched_message, displayName)
            } else {
                context.getString(R.string.model_switch_failed)
            },
        )
        nm.notify(SWITCH_NOTIFICATION_ID, notification)
    }

    companion object {
        /** The backend id to activate (the shortcut intent's only payload). */
        const val EXTRA_BACKEND_ID = "model_shortcut_backend_id"

        /**
         * Fixed, self-replacing (a second switch replaces the first
         * confirmation, they never stack). Below RESULT_NOTIFICATION_ID_BASE,
         * pinned by ReservedNotificationIdContractTest.
         */
        const val SWITCH_NOTIFICATION_ID = 1009
    }
}
