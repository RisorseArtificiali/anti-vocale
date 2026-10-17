package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakeExternalRecordsProvider
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ScheduledFolderStore
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.ui.appearance.LauncherIconVariant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TASK-848: the ONE SettingsViewModel construction harness (extracted at
 * the third copy of the block; the extract-at-second-consumer rule the
 * FakePreferencesManager KDoc documents). Real [FakePreferencesManager]
 * and [ActiveModelRepository], every other dependency a relaxed mockk or
 * a cheap real object, exactly as the three former copies built it.
 *
 * Per-test seams (constructor parameters):
 * - [fakePrefs] and [logDao] are exposed as fields for assertions and
 *   stubbing (the backup tests stub the DAO; the seed tests assert prefs).
 * - [externalRecords] defaults to an empty flow; ActiveModelTest passes
 *   its class-level mutable flow (TASK-611: a REAL StateFlow, not a
 *   relaxed mock; a mocked flow never emits, so the availability combine
 *   would be dead and every test would pass against the seed alone).
 * - [application] is exposed for resolver stubbing (the backup arms read
 *   contentResolver off it).
 * - [repositoryContext] reaches ActiveModelRepository; pass a
 *   getString-stubbed context when a test needs distinguishable catalog
 *   display names.
 *
 * PRECONDITION: construct only AFTER Dispatchers.setMain(testDispatcher)
 * ran in the suite's setup (the ViewModel's init launches on Main; an
 * uninitialized Main fails every test with a dispatcher error that reads
 * like a harness bug). Pair every construction with [cleanup] in @After
 * (the speaker-ids temp dir is the helper's one filesystem footprint).
 */
internal class SettingsViewModelTestHelper(
    val fakePrefs: FakePreferencesManager = FakePreferencesManager(),
    val logDao: LogDao = mockk(relaxed = true),
    externalRecords: MutableStateFlow<List<ExternalModelRecord>> =
        MutableStateFlow(emptyList()),
    val application: Application = mockk(relaxed = true),
    repositoryContext: Context = mockk(relaxed = true),
) {

    private val speakerIdsDir = java.nio.file.Files
        .createTempDirectory("speaker-ids").toFile()

    /** Deletes the one filesystem footprint; call from the suite's @After. */
    fun cleanup() {
        speakerIdsDir.deleteRecursively()
    }

    val viewModel: SettingsViewModel = SettingsViewModel(
        externalRecordsProvider = object : ExternalModelRecordsProvider {
            override val records = externalRecords
        },
        application = application,
        preferencesManager = fakePrefs,
        logDao = logDao,
        huggingFaceTokenManager = mockk(relaxed = true),
        huggingFaceAuthManager = mockk(relaxed = true),
        huggingFaceApiClient = mockk(relaxed = true),
        perAppPreferencesManager = mockk(relaxed = true),
        // TASK-741: the folder-watch store; these tests never watch.
        scheduledFolderStore = ScheduledFolderStore(FakePreferencesManager()),
        transcriptionCalibrator = mockk(relaxed = true),
        backendManager = mockk(relaxed = true),
        llmManager = mockk(relaxed = true),
        shareTargetManager = mockk(relaxed = true),
        shareShortcutManager = mockk(relaxed = true),
        backendRegistry = staticRegistry(),
        // TASK-490: these tests never pick an icon; a relaxed mock of the
        // store keeps the construction honest without Robolectric.
        shortcutIconStore = mockk<com.antivocale.app.data.ShortcutIconStore>(relaxed = true),
        // Enum returns are stubbed explicitly: a relaxed mock's enum answer
        // is version-dependent, and the ViewModel reads current() at init.
        launcherIconManager = mockk(relaxed = true) {
            every { current() } returns LauncherIconVariant.DEFAULT
        },
        // TASK-681: the LAN-offload probe backend; relaxed, the tests never
        // trigger a connection test.
        remoteOmnivoiceBackend = mockk(relaxed = true),
        // TASK-679: real recorder over relaxed mocks; the tests never open
        // the memory panel.
        oomBreadcrumbRecorder = com.antivocale.app.transcription.OomBreadcrumbRecorder(
            fakePrefs,
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            backendRegistry = staticRegistry(),
        ),
        activeModelRepository = ActiveModelRepository(
            fakePrefs,
            repositoryContext,
            staticRegistry(), FakeExternalRecordsProvider(),
        ),
        // TASK-670 simplify F2: the enrollment pipeline seam; the tests
        // never enroll.
        speakerEnroller = mockk(),
        // TASK-271: the permission-poke seam; never poked here.
        voiceNoteDetectionCoordinator = mockk(relaxed = true),
        // TASK-826: the grant-acceptance owner; the helper's suites drive
        // saveVoiceNoteTreeUri directly, acceptGrant is never awaited here.
        voiceNoteTreeGrant = mockk(relaxed = true),
        speakerIdentityStore = com.antivocale.app.transcription.diarization.SpeakerIdentityStore(
            speakerIdsDir),
    )
}
