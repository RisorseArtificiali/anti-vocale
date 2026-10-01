package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakeExternalRecordsProvider
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.ui.appearance.LauncherIconVariant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-685 (GH #112): the tour-completion moment seeds the interface
 * language as the Models-filter favorite, exactly once per install. The
 * guards under test: the seed fires only on the untouched preference (null),
 * never over a favorite or an explicit clear (a replayed tour from Settings
 * must not touch either), and it never writes the decode-language preference
 * (the TASK-457 no-pin principle; the untouched decode path keeps
 * model-side detection).
 *
 * Construction follows SettingsViewModelActiveModelTest: StandardTestDispatcher
 * as Main, real [FakePreferencesManager] and [ActiveModelRepository], every
 * other dependency a relaxed mockk. The interface language is always passed
 * explicitly so the default's Android locale read never runs here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelOnboardingSeedTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        viewModel = SettingsViewModel(
            externalRecordsProvider = object : ExternalModelRecordsProvider {
                override val records = MutableStateFlow(emptyList<com.antivocale.app.data.ExternalModelRecord>())
            },
            application = mockk<Application>(relaxed = true),
            preferencesManager = fakePrefs,
            logDao = mockk(relaxed = true),
            huggingFaceTokenManager = mockk(relaxed = true),
            huggingFaceAuthManager = mockk(relaxed = true),
            huggingFaceApiClient = mockk(relaxed = true),
            perAppPreferencesManager = mockk(relaxed = true),
            transcriptionCalibrator = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            shareShortcutManager = mockk(relaxed = true),
            backendRegistry = staticRegistry(),
            // TASK-490: these tests never pick an icon; a relaxed mock of
            // the store keeps the construction honest without Robolectric.
            shortcutIconStore = mockk<com.antivocale.app.data.ShortcutIconStore>(relaxed = true),
            launcherIconManager = mockk(relaxed = true) {
                every { current() } returns LauncherIconVariant.DEFAULT
            },
            remoteOmnivoiceBackend = mockk(relaxed = true),
            oomBreadcrumbRecorder = com.antivocale.app.transcription.OomBreadcrumbRecorder(
                fakePrefs,
                backendManager = mockk(relaxed = true),
                llmManager = mockk(relaxed = true),
                backendRegistry = staticRegistry(),
            ),
            activeModelRepository = ActiveModelRepository(
                fakePrefs,
                mockk<Context>(relaxed = true),
                staticRegistry(), FakeExternalRecordsProvider(),
            ),
            // TASK-670 simplify F2: the enrollment pipeline seam; these
            // tests never enroll.
            speakerEnroller = mockk(),
            speakerIdentityStore = com.antivocale.app.transcription.diarization.SpeakerIdentityStore(
                java.nio.file.Files.createTempDirectory("speaker-ids").toFile()),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tour completion seeds the interface language as the initial favorite`() = runTest {
        assertFalse(fakePrefs.onboardingCompleted.value)

        viewModel.setOnboardingCompleted(interfaceLanguage = "it")
        runCurrent()

        assertEquals("it", fakePrefs._modelFilterLanguage.value)
        assertTrue(fakePrefs.onboardingCompleted.value)
    }

    @Test
    fun `an interface language the filter does not offer writes the clear`() = runTest {
        viewModel.setOnboardingCompleted(interfaceLanguage = "tl")
        runCurrent()

        // Review R2: no favorite, but the FIRST completion still writes an
        // OUTCOME (""): null now means strictly "tour never completed", so a
        // replayed tour after a later locale switch cannot silently seed.
        assertEquals("", fakePrefs._modelFilterLanguage.value)
        assertTrue(fakePrefs.onboardingCompleted.value)
    }

    @Test
    fun `a non-empty favorite is never overwritten by tour completion`() = runTest {
        fakePrefs._modelFilterLanguage.value = "de"

        viewModel.setOnboardingCompleted(interfaceLanguage = "it")
        runCurrent()

        assertEquals("de", fakePrefs._modelFilterLanguage.value)
    }

    @Test
    fun `a replayed tour after an explicit clear does not re-seed`() = runTest {
        fakePrefs._modelFilterLanguage.value = ""

        viewModel.setOnboardingCompleted(interfaceLanguage = "it")
        runCurrent()

        assertEquals("", fakePrefs._modelFilterLanguage.value)
    }

    @Test
    fun `the seed never touches the decode-language preference`() = runTest {
        val decodeBefore = fakePrefs._transcriptionLanguage.value

        viewModel.setOnboardingCompleted(interfaceLanguage = "it")
        runCurrent()

        // TASK-457 no-pin: the seeded favorite fills the Models filter only;
        // the decode path keeps model-side detection.
        assertEquals(decodeBefore, fakePrefs._transcriptionLanguage.value)
        assertFalse(decodeBefore == "it")
    }

    @Test
    fun `a completion with an unoffered interface language writes the clear`() = runTest {
        // Review R2: the FIRST completion always writes an outcome, so a
        // replayed tour after a later locale switch cannot silently seed.
        viewModel.setOnboardingCompleted(interfaceLanguage = "tl")
        runCurrent()
        assertEquals("", fakePrefs._modelFilterLanguage.value)
        viewModel.setOnboardingCompleted(interfaceLanguage = "it")
        runCurrent()
        assertEquals("", fakePrefs._modelFilterLanguage.value)
    }


    @Test
    fun `the persisted tri-state maps to the UI boundary`() = runTest {
        // Review R5: the UI sees null ("All languages") for the explicit
        // clear and the code for a favorite; the map in ModelViewModel is
        // `it?.takeIf(String::isNotEmpty)` and this pins its contract at
        // the persistence boundary the seed writes.
        fakePrefs.saveModelFilterLanguage("")
        assertEquals(null, fakePrefs._modelFilterLanguage.first()?.takeIf { it.isNotEmpty() })
        fakePrefs.saveModelFilterLanguage("it")
        assertEquals("it", fakePrefs._modelFilterLanguage.first()?.takeIf { it.isNotEmpty() })
    }

}
