package com.antivocale.app.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import com.antivocale.app.R
import com.antivocale.app.data.BackupArchiveCodec
import com.antivocale.app.data.BackupHistoryCodec
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-847 (code-review F8 of the backup delta): the SettingsViewModel
 * backup arms. Construction goes through [SettingsViewModelTestHelper]
 * (TASK-848); the arms' seams are the resolver-stubbed Application and the
 * hoisted DAO. The arms run on Dispatchers.IO by design (SAF I/O), so each
 * test polls the terminal state on real time instead of advancing the test
 * scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelBackupTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var harness: SettingsViewModelTestHelper
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var logDao: LogDao
    private lateinit var viewModel: SettingsViewModel

    private val resolver = mockk<ContentResolver>(relaxed = true)

    /** android.net.Uri is a null stub off Robolectric; a relaxed mock is
     *  the only instantiable Uri, and it survives the $uri interpolation
     *  in the arms' log lines (the resolver stubs match any Uri). */
    private val testUri: Uri = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // The shared construction harness (TASK-848); the backup arms'
        // seams: a resolver-stubbed Application and the hoisted DAO.
        harness = SettingsViewModelTestHelper(
            application = mockk(relaxed = true) {
                every { contentResolver } returns resolver
            },
        )
        fakePrefs = harness.fakePrefs
        logDao = harness.logDao
        viewModel = harness.viewModel
    }

    @After
    fun tearDown() {
        harness.cleanup()
        Dispatchers.resetMain()
    }

    private suspend fun awaitTerminal(): SettingsViewModel.BackupState =
        withTimeout(5_000) {
            while (viewModel.backupState.value.let {
                    it is SettingsViewModel.BackupState.Idle ||
                        it is SettingsViewModel.BackupState.Exporting ||
                        it is SettingsViewModel.BackupState.Importing
                }) {
                kotlinx.coroutines.delay(20)
            }
            viewModel.backupState.value
        }

    /**
     * The one archive fixture (settings map + raw history payload): every
     * test that needs an archive builds it here, so a future shape change
     * is one edit, not N divergent zip blocks.
     */
    private fun archive(settings: Map<String, Any>, historyLines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            BackupArchiveCodec.writeSettingsEntries(zip, settings, "test", 1L)
            zip.putNextEntry(java.util.zip.ZipEntry(BackupHistoryCodec.HISTORY_ENTRY))
            zip.write(historyLines.joinToString("\n").toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** One valid archive: the portable setting plus two history rows. */
    private fun validArchive(): ByteArray = archive(
        settings = mapOf("auto_copy_enabled" to true),
        historyLines = listOf(
            BackupHistoryCodec.rowToJson(row("r1", 100L)).toString(),
            BackupHistoryCodec.rowToJson(row("r2", 200L)).toString()))

    private fun row(id: String, timestamp: Long) = LogEntity(
        id = id, timestamp = timestamp, taskId = "t-$id", type = "AUDIO",
        status = "SUCCESS", result = "text $id")

    /** A stream factory: every openInputStream(uri) gets a FRESH stream. */
    private fun stubArchiveBytes(bytes: ByteArray) {
        every { resolver.openInputStream(any<Uri>()) } answers {
            ByteArrayInputStream(bytes)
        }
    }

    @Test
    fun `an unreadable archive applies nothing and fails honestly`() = runBlocking {
        stubArchiveBytes("this is not a zip".toByteArray())

        viewModel.importBackup(testUri)
        val state = awaitTerminal()

        assertEquals(
            SettingsViewModel.BackupState.Failed(false, R.string.backup_import_failed),
            state)
        assertTrue("nothing applied for an unreadable archive",
            fakePrefs._portableSettings.value.isEmpty())
    }

    @Test
    fun `a history write failure keeps the settings and reports its own outcome`() = runBlocking {
        stubArchiveBytes(validArchive())
        io.mockk.coEvery { logDao.insertAll(any()) } throws RuntimeException("disk full")

        viewModel.importBackup(testUri)
        val state = awaitTerminal()

        assertEquals(
            SettingsViewModel.BackupState.Failed(false, R.string.backup_import_history_failed),
            state)
        assertEquals(
            "the settings ARE applied when only the history write fails",
            mapOf<String, Any>("auto_copy_enabled" to true),
            fakePrefs._portableSettings.value)
    }

    @Test
    fun `the happy path imports settings and merges rows with counts`() = runBlocking {
        // One good page of two rows, then a malformed line: dropped = 1.
        stubArchiveBytes(archive(
            settings = mapOf("auto_copy_enabled" to true),
            historyLines = listOf(
                BackupHistoryCodec.rowToJson(row("r1", 100L)).toString(),
                BackupHistoryCodec.rowToJson(row("r2", 200L)).toString(),
                "{ not json")))
        val chunks = slot<List<LogEntity>>()
        io.mockk.coEvery { logDao.insertAll(capture(chunks)) } returns Unit

        viewModel.importBackup(testUri)
        val state = awaitTerminal()

        assertEquals(
            SettingsViewModel.BackupState.Done(
                export = false,
                messageRes = R.string.backup_import_done,
                args = listOf(2, 1)),
            state)
        assertEquals(listOf("r1", "r2"), chunks.captured.map { it.id })
        io.mockk.coVerify(exactly = 1) { logDao.insertAll(any()) }
        assertEquals(mapOf<String, Any>("auto_copy_enabled" to true),
            fakePrefs._portableSettings.value)
    }

    @Test
    fun `export writes a readable archive and reports under its own card`() = runBlocking {
        val written = ByteArrayOutputStream()
        every { resolver.openOutputStream(any<Uri>()) } answers { written }
        // ONE page of two rows: a short page ends the keyset walk (the
        // codec breaks before re-querying), so there is no second call to
        // answer.
        io.mockk.coEvery { logDao.pageBefore(any(), any()) } returns
            listOf(row("e1", 10L), row("e2", 20L))
        fakePrefs._portableSettings.value = mapOf<String, Any>("auto_copy_enabled" to true)

        viewModel.exportBackup(testUri)
        val state = awaitTerminal()

        assertEquals(
            SettingsViewModel.BackupState.Done(true, R.string.backup_export_done),
            state)
        val bytes = written.toByteArray()
        // The USER's current settings ride the archive (a regression that
        // exports an empty settings map fails here, not in the field).
        assertEquals(mapOf<String, Any>("auto_copy_enabled" to true),
            BackupArchiveCodec.parse(bytes))
        val rows = BackupArchiveCodec.openHistoryReader(bytes.inputStream())?.let { reader ->
            val collected = mutableListOf<String>()
            val dropped = BackupHistoryCodec.readHistoryEntryChunked(reader) { chunk ->
                collected.addAll(chunk.map { it.id })
            }
            collected to dropped
        }
        assertEquals(listOf("e1", "e2") to 0, rows)
    }
}
