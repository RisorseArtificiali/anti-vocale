package com.antivocale.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.di.AppContainer
import com.antivocale.app.ui.MainScreen
import com.antivocale.app.ui.theme.AntiVocaleTheme
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.util.DeviceCompatibility

class MainActivity : AppCompatActivity() {

    companion object {
        /** Intent extra: when true, the app opens on the Model tab. */
        const val EXTRA_NAVIGATE_TO_MODEL_TAB = "navigate_to_model_tab"
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!checkDeviceCompatibility()) return

        val startOnModelTab = intent.getBooleanExtra(EXTRA_NAVIGATE_TO_MODEL_TAB, false)
        if (startOnModelTab) intent.removeExtra(EXTRA_NAVIGATE_TO_MODEL_TAB)

        requestNotificationPermissionIfNeeded()
        setContent {
            // Collect theme preference and convert to ThemeType
            val themeName by AppContainer.preferencesManager.themePreference.collectAsState(initial = PreferencesManager.DEFAULT_THEME)
            val theme = try {
                ThemeType.valueOf(themeName)
            } catch (e: IllegalArgumentException) {
                ThemeType.DEFAULT
            }

            AntiVocaleTheme(theme = theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(startOnModelTab = startOnModelTab)
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Checks device hardware compatibility before allowing the app to proceed.
     * Shows a non-dismissible dialog if the device is unsupported.
     *
     * @return true if the device is compatible, false otherwise (activity should not proceed)
     */
    private fun checkDeviceCompatibility(): Boolean {
        val result = DeviceCompatibility.check(this)
        if (result is DeviceCompatibility.CheckResult.Compatible) return true

        val reason = (result as DeviceCompatibility.CheckResult.Incompatible).reason
        val message = when (reason) {
            is DeviceCompatibility.CheckResult.Reason.UnsupportedArchitecture ->
                getString(R.string.device_incompatible_arch)
            is DeviceCompatibility.CheckResult.Reason.InsufficientRam ->
                getString(R.string.device_incompatible_ram)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.device_incompatible_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()

        return false
    }
}
