package com.antivocale.app.util

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/**
 * Registers ComponentActivity in Robolectric's shadow PackageManager
 * before createComposeRule() tries to launch it: the rule cannot resolve
 * the activity otherwise (robolectric/robolectric#4736). Every Robolectric
 * Compose test uses it as its order-0 rule, ahead of createComposeRule().
 */
class RegisterComponentActivityRule : TestWatcher() {
    override fun starting(description: Description?) {
        val appContext: Application = ApplicationProvider.getApplicationContext()
        shadowOf(appContext.packageManager).addActivityIfNotPresent(
            android.content.ComponentName(
                appContext.packageName,
                ComponentActivity::class.java.name))
    }
}
