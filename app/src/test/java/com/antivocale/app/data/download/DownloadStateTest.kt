package com.antivocale.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTest {

    @Test
    fun `CopyingFiles holds file index, total, and file name`() {
        val state = DownloadState.CopyingFiles(
            fileIndex = 2,
            totalFiles = 5,
            fileName = "encoder.int8.onnx"
        )
        assertEquals(2, state.fileIndex)
        assertEquals(5, state.totalFiles)
        assertEquals("encoder.int8.onnx", state.fileName)
    }

    @Test
    fun `CopyingFiles defaults fileName to empty string`() {
        val state = DownloadState.CopyingFiles(fileIndex = 1, totalFiles = 3)
        assertEquals("", state.fileName)
    }

    @Test
    fun `CopyingFiles and Extracting are distinct states`() {
        val copying = DownloadState.CopyingFiles(fileIndex = 1, totalFiles = 3, fileName = "a.onnx")
        val extracting = DownloadState.Extracting(fileIndex = 1, totalFiles = 3, fileName = "a.onnx")

        assertFalse(copying.equals(extracting))
        assertFalse(extracting.equals(copying))
    }

    @Test
    fun `CopyingFiles equality works correctly`() {
        val a = DownloadState.CopyingFiles(fileIndex = 2, totalFiles = 5, fileName = "model.onnx")
        val b = DownloadState.CopyingFiles(fileIndex = 2, totalFiles = 5, fileName = "model.onnx")
        val c = DownloadState.CopyingFiles(fileIndex = 1, totalFiles = 5, fileName = "model.onnx")

        assertEquals(a, b)
        assertFalse(a.equals(c))
    }

    @Test
    fun `CopyingFiles is distinct from all other DownloadState variants`() {
        val copying = DownloadState.CopyingFiles(fileIndex = 1, totalFiles = 2)

        assertTrue(copying !is DownloadState.Idle)
        assertTrue(copying !is DownloadState.CheckingAccess)
        assertTrue(copying !is DownloadState.Connecting)
        assertTrue(copying !is DownloadState.Downloading)
        assertTrue(copying !is DownloadState.Retrying)
        assertTrue(copying !is DownloadState.Extracting)
        assertTrue(copying !is DownloadState.PartiallyDownloaded)
        assertTrue(copying !is DownloadState.Complete)
        assertTrue(copying !is DownloadState.Error)
        assertTrue(copying !is DownloadState.Cancelled)
    }

    @Test
    fun `Extracting retains bytesExtracted and currentFileSize fields`() {
        val state = DownloadState.Extracting(
            fileIndex = 1,
            totalFiles = 2,
            fileName = "archive.tar.bz2",
            bytesExtracted = 1024L,
            currentFileSize = 2048L
        )
        assertEquals(1024L, state.bytesExtracted)
        assertEquals(2048L, state.currentFileSize)
    }

    @Test
    fun `when expression distinguishes CopyingFiles from Extracting`() {
        val states = listOf(
            DownloadState.CopyingFiles(1, 3, "model.onnx"),
            DownloadState.Extracting(1, 3, "archive.tar.bz2"),
            DownloadState.Downloading(100L, 200L, 50f),
            DownloadState.Idle
        )

        val copyingCount = states.count { it is DownloadState.CopyingFiles }
        val extractingCount = states.count { it is DownloadState.Extracting }

        assertEquals(1, copyingCount)
        assertEquals(1, extractingCount)
    }
}
