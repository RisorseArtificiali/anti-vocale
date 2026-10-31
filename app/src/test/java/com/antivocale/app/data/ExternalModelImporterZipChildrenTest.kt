package com.antivocale.app.data

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TASK-925 (GH #124 reopen 2): the bundle scan behind the multi-zip
 * chooser, for 0 / 1 / 2+ zip children. The SAF tree is mocked because the
 * whole behavior IS the filter (isFile only, case-insensitive .zip,
 * provider order); SAF itself stays device-verified, so no Robolectric.
 */
class ExternalModelImporterZipChildrenTest {

    private lateinit var importer: ExternalModelImporter

    @Before
    fun setUp() {
        importer = ExternalModelImporter(
            store = ExternalModelStore(FakePreferencesManager()),
            filesRoot = { File("unused") },
            uuid = { "fedcba9876543210" },
        )
    }

    /** A tree child mock; the uri mock is identity-only (never dereferenced). */
    private fun fakeChild(name: String?, isFile: Boolean = true): DocumentFile {
        val child = mockk<DocumentFile>()
        every { child.name } returns name
        every { child.isFile } returns isFile
        every { child.uri } returns mockk<Uri>()
        return child
    }

    /** The scan through a mocked fromTreeUri; static mocking is reverted so
     *  it cannot leak into other suite members. */
    private fun scannedZipUris(children: Array<DocumentFile>): List<Uri> {
        mockkStatic(DocumentFile::class)
        try {
            val tree = mockk<DocumentFile>()
            every { tree.listFiles() } returns children
            every {
                DocumentFile.fromTreeUri(any<android.content.Context>(), any<Uri>())
            } returns tree
            return runBlocking { importer.bundleZipChildren(mockk(), mockk()) }
        } finally {
            unmockkStatic(DocumentFile::class)
        }
    }

    @Test
    fun `a folder without zip children scans empty`() {
        assertEquals(
            emptyList<Uri>(),
            scannedZipUris(arrayOf(
                fakeChild("encoder.onnx"),
                fakeChild("weights", isFile = false),
            )))
    }

    @Test
    fun `the single zip child is the whole scan, extension case-insensitive`() {
        val zip = fakeChild("MODEL.ZIP")
        assertEquals(
            listOf(zip.uri),
            scannedZipUris(arrayOf(fakeChild("tokens.txt"), zip)))
    }

    @Test
    fun `several zips keep provider order, a zip-named directory does not count`() {
        val first = fakeChild("parakeet-it.zip")
        val second = fakeChild("whisper-large.ZIP")
        assertEquals(
            listOf(first.uri, second.uri),
            scannedZipUris(arrayOf(
                fakeChild("readme.md"),
                first,
                fakeChild("bundle.zip", isFile = false),
                second,
            )))
    }
}
