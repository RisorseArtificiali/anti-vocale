package com.antivocale.app.service

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * Verifies the [TranscriptionWakeLock] contract against Robolectric's shadowed
 * PowerManager: partial-wake-lock acquisition during a batch, idempotent release
 * from any teardown path, and the non-reference-counted "N acquires, ONE release"
 * behavior the drain loop relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TranscriptionWakeLockTest {

    private fun newLock(): TranscriptionWakeLock =
        TranscriptionWakeLock(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `acquire holds the lock and registers it with PowerManager`() {
        val lock = newLock()
        assertFalse("fresh lock should not be held", lock.isHeld)

        lock.acquire()

        assertTrue("lock should be held after acquire", lock.isHeld)
        val latest = ShadowPowerManager.getLatestWakeLock()
        assertTrue("PowerManager should see a held wake lock", latest != null && latest.isHeld)
    }

    @Test
    fun `release drops the lock`() {
        val lock = newLock()
        lock.acquire()

        lock.release()

        assertFalse("lock should be released", lock.isHeld)
    }

    @Test
    fun `double release is a safe no-op`() {
        val lock = newLock()
        lock.acquire()
        lock.release()

        lock.release()

        assertFalse(lock.isHeld)
    }

    @Test
    fun `release without acquire is a safe no-op`() {
        val lock = newLock()

        lock.release()

        assertFalse(lock.isHeld)
    }

    /**
     * Non-reference-counted contract: a queued second task calls acquire again
     * (deadline refresh) but the drain loop's single finally-release must still
     * drop the lock completely.
     */
    @Test
    fun `repeated acquires need exactly one release`() {
        val lock = newLock()
        lock.acquire()
        lock.acquire()

        lock.release()

        assertFalse("one release must clear repeated acquires", lock.isHeld)
    }

    @Test
    fun `re-acquire after release works for the next batch`() {
        val lock = newLock()
        lock.acquire()
        lock.release()

        lock.acquire()

        assertTrue(lock.isHeld)
    }
}
