package com.antivocale.app.work

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crashlytics 2026-10-11 (1.13.2, targetSdk 36): the subtitle-timeout
 * worker's FGS promotion carried foregroundServiceType 0, and WorkManager
 * 2.10 passes that straight to Service.startForeground, which Android 14+
 * prohibits for apps targeting 34+ (InvalidForegroundServiceTypeException,
 * fatal on the service's main thread). The pin: on every supported
 * version with the typed API, the ForegroundInfo must carry DATA_SYNC,
 * the exact type the manifest declares on SystemForegroundService.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleChoiceTimeoutWorkerForegroundInfoTest {

    @Test
    @Config(sdk = [34])
    fun `carries the dataSync type on Android 14`() {
        val info = SubtitleChoiceTimeoutWorker.foregroundInfo(
            androidx.test.core.app.ApplicationProvider.getApplicationContext())
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(SubtitleChoiceTimeoutWorker.NOTIFICATION_ID, info.getNotificationId())
    }

    @Test
    @Config(sdk = [28])
    fun `below the typed API the untyped form stays`() {
        val info = SubtitleChoiceTimeoutWorker.foregroundInfo(
            androidx.test.core.app.ApplicationProvider.getApplicationContext())
        assertEquals(0, info.foregroundServiceType)
    }
}
