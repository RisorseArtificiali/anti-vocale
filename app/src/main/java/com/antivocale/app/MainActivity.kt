package com.antivocale.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ShareShortcutManager
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.service.InferenceService
import com.antivocale.app.ui.MainScreen
import com.antivocale.app.ui.TestNavigation
import com.antivocale.app.ui.theme.AntiVocaleTheme
import com.antivocale.app.ui.theme.ThemeMode
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.ui.viewmodel.LogsViewModel
import com.antivocale.app.util.DeviceCompatibility
import com.antivocale.app.util.NativeCrashDetector
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.viewModels
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var shareShortcutManager: ShareShortcutManager
    private val logsViewModel: LogsViewModel by viewModels()

    companion object {
        /** Intent extra: when true, the app opens on the Model tab. */
        const val EXTRA_NAVIGATE_TO_MODEL_TAB = "navigate_to_model_tab"

        /** Intent extra: taskId of a log entry to highlight (scroll-to + expand). */
        const val EXTRA_HIGHLIGHT_TASK_ID = "highlight_task_id"

        private const val PIP_ASPECT_RATIO_NUMERATOR = 9
        private const val PIP_ASPECT_RATIO_DENOMINATOR = 16
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    /** Observable PiP mode state for Compose. */
    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: kotlinx.coroutines.flow.StateFlow<Boolean> = _isInPipMode.asStateFlow()

    /** When true, the app opens on the Model tab. Set by native-crash dialog or intent extra. */
    private val _navigateToModelTab = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!checkDeviceCompatibility()) return

        val startOnModelTab = intent.getBooleanExtra(EXTRA_NAVIGATE_TO_MODEL_TAB, false)
        if (startOnModelTab) intent.removeExtra(EXTRA_NAVIGATE_TO_MODEL_TAB)
        captureTestNavigation(intent)

        // If the previous process died from a native crash (e.g. sherpa-onnx
        // exit(255) from a corrupt model) or a low-memory kill, explain what happened.
        when (val crash = NativeCrashDetector.checkForRecentCrash(this)) {
            is NativeCrashDetector.CrashCheckResult.NativeCrash -> {
                // If the user had NNAPI selected, the crash was likely the NNAPI driver:
                // auto-fallback to CPU so the app is usable on the next launch (issue #26).
                if (kotlinx.coroutines.runBlocking { preferencesManager.inferenceProvider.first() } == InferenceProvider.NNAPI) {
                    Log.w("MainActivity", "Native crash with NNAPI active, resetting to CPU")
                    kotlinx.coroutines.runBlocking { preferencesManager.saveInferenceProvider(InferenceProvider.CPU) }
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.native_crash_title)
                    .setMessage(R.string.native_crash_model_warning)
                    .setPositiveButton(R.string.native_crash_go_to_model) { _, _ ->
                        _navigateToModelTab.value = true
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            is NativeCrashDetector.CrashCheckResult.LowMemory -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.oom_crash_title)
                    .setMessage(R.string.oom_crash_warning)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            NativeCrashDetector.CrashCheckResult.None -> { /* no-op */ }
        }

        // Handle notification highlight (cold start)
        val highlightTaskId = intent.getStringExtra(EXTRA_HIGHLIGHT_TASK_ID)
        if (highlightTaskId != null) {
            logsViewModel.highlightLogEntry(highlightTaskId)
            intent.removeExtra(EXTRA_HIGHLIGHT_TASK_ID)
        }

        requestNotificationPermissionIfNeeded()
        setContent {
            // Collect theme preference and convert to ThemeType
            val themeName by preferencesManager.themePreference.collectAsState(initial = PreferencesManager.DEFAULT_THEME)
            val theme = try {
                ThemeType.valueOf(themeName)
            } catch (e: IllegalArgumentException) {
                ThemeType.DEFAULT
            }

            // Collect theme mode (System / Dark / Light) and convert to ThemeMode
            val themeModeName by preferencesManager.themeMode.collectAsState(initial = PreferencesManager.DEFAULT_THEME_MODE)
            val themeMode = try {
                ThemeMode.valueOf(themeModeName)
            } catch (e: IllegalArgumentException) {
                ThemeMode.SYSTEM
            }

            // Observe PiP mode state
            val isInPip by _isInPipMode.collectAsState()

            // Observe late navigation signals (e.g. the native-crash dialog button)
            val navigateToModel by _navigateToModelTab.collectAsState()

            // Observe transcription state and update PiP auto-enter params
            LaunchedEffect(Unit) {
                InferenceService.isTranscribing.collect { isTranscribing ->
                    updatePipParams(isTranscribing)
                }
            }

            AntiVocaleTheme(brand = theme, mode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        startOnModelTab = startOnModelTab,
                        navigateToModel = navigateToModel,
                        isInPipMode = isInPip
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-derive the dynamic long-press share shortcuts on every foreground:
        // converges any state change made outside the hooked sync sites (model
        // path swaps that keep the alias enabled). refresh() shifts itself to
        // Dispatchers.Default for the icon rasterization.
        lifecycleScope.launch { shareShortcutManager.refresh() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_HIGHLIGHT_TASK_ID)?.let {
            logsViewModel.highlightLogEntry(it)
        }
        captureTestNavigation(intent)
    }

    /**
     * TASK-486: the debug-only TEST_SPI navigation bridge. The receiver
     * (debug source set) starts this activity with [TestNavigation.EXTRA_TEST_NAV];
     * release builds never see the extra, and this read is compiled out of
     * them entirely (BuildConfig.DEBUG is a constant false under R8).
     */
    private fun captureTestNavigation(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra(TestNavigation.EXTRA_TEST_NAV)?.let {
            intent.removeExtra(TestNavigation.EXTRA_TEST_NAV)
            TestNavigation.pending.value = it
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // On API 26-30 (before setAutoEnterEnabled), manually enter PiP during transcription
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            InferenceService.isTranscribing.value
        ) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        _isInPipMode.value = isInPictureInPictureMode
    }

    /**
     * Public method to enter PiP mode, callable from Compose via LocalContext.
     */
    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(PIP_ASPECT_RATIO_NUMERATOR, PIP_ASPECT_RATIO_DENOMINATOR))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    /**
     * Updates PiP parameters — enables/disables auto-enter on API 31+.
     */
    private fun updatePipParams(isTranscribing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(PIP_ASPECT_RATIO_NUMERATOR, PIP_ASPECT_RATIO_DENOMINATOR))
                    .setAutoEnterEnabled(isTranscribing)
                    .build()
            )
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
