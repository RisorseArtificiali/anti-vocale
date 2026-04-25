package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.transcription.Gemma4GgufModelManager.GgufVariant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class Gemma4GgufModelManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `gguf variants have correct file names`() {
        assertEquals("gemma-4-E4B-it-OBLITERATED-Q4_K_M.gguf", GgufVariant.Q4_K_M.fileName)
        assertEquals("gemma-4-E4B-it-OBLITERATED-Q5_K_M.gguf", GgufVariant.Q5_K_M.fileName)
        assertEquals("gemma-4-E4B-it-OBLITERATED-Q8_0.gguf", GgufVariant.Q8_0.fileName)
    }

    @Test
    fun `gguf variants have correct estimated sizes`() {
        assertEquals(4900L, GgufVariant.Q4_K_M.estimatedSizeMB)
        assertEquals(5300L, GgufVariant.Q5_K_M.estimatedSizeMB)
        assertEquals(7400L, GgufVariant.Q8_0.estimatedSizeMB)
    }

    @Test
    fun `gguf variants implement ModelVariant interface`() {
        for (variant in GgufVariant.entries) {
            assertTrue(variant is ModelVariant)
            assertNotNull(variant.dirName)
            assertTrue(variant.estimatedSizeMB > 0)
            assertNotEquals(0, variant.titleResId)
            assertNotEquals(0, variant.descriptionResId)
        }
    }

    @Test
    fun `storage dir is under models gguf`() {
        val dir = Gemma4GgufModelManager.getModelStorageDir(context)
        assertTrue(dir.absolutePath.endsWith("models/gguf"))
    }

    @Test
    fun `isDownloaded returns false when no model exists`() {
        assertFalse(Gemma4GgufModelManager.isDownloaded(context, GgufVariant.Q4_K_M))
    }

    @Test
    fun `getLocalPath returns null when not downloaded`() {
        assertNull(Gemma4GgufModelManager.getLocalPath(context, GgufVariant.Q4_K_M))
    }

    @Test
    fun `isDownloaded returns true when gguf file exists`() {
        val dir = Gemma4GgufModelManager.getModelStorageDir(context)
        dir.mkdirs()
        val file = File(dir, GgufVariant.Q4_K_M.fileName)
        file.writeText("fake model data")

        try {
            assertTrue(Gemma4GgufModelManager.isDownloaded(context, GgufVariant.Q4_K_M))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `getLocalPath returns path when model exists`() {
        val dir = Gemma4GgufModelManager.getModelStorageDir(context)
        dir.mkdirs()
        val file = File(dir, GgufVariant.Q4_K_M.fileName)
        file.writeText("fake model data")

        try {
            val path = Gemma4GgufModelManager.getLocalPath(context, GgufVariant.Q4_K_M)
            assertNotNull(path)
            assertTrue(path!!.endsWith(".gguf"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `delete removes gguf file`() {
        val dir = Gemma4GgufModelManager.getModelStorageDir(context)
        dir.mkdirs()
        val file = File(dir, GgufVariant.Q4_K_M.fileName)
        file.writeText("fake model data")

        assertTrue(Gemma4GgufModelManager.delete(context, GgufVariant.Q4_K_M))
        assertFalse(file.exists())
        assertFalse(Gemma4GgufModelManager.isDownloaded(context, GgufVariant.Q4_K_M))
    }

    @Test
    fun `delete returns false when nothing to delete`() {
        assertFalse(Gemma4GgufModelManager.delete(context, GgufVariant.Q4_K_M))
    }

    @Test
    fun `detectPartialDownload returns null when no tmp file`() {
        assertNull(Gemma4GgufModelManager.detectPartialDownload(context, GgufVariant.Q4_K_M))
    }

    @Test
    fun `detectPartialDownload returns state when tmp file exists`() {
        val dir = Gemma4GgufModelManager.getModelStorageDir(context)
        dir.mkdirs()
        val tmpFile = File(dir, "${GgufVariant.Q4_K_M.fileName}.tmp")
        // Write enough bytes to have meaningful progress
        tmpFile.writeBytes(ByteArray(1024))

        try {
            val state = Gemma4GgufModelManager.detectPartialDownload(context, GgufVariant.Q4_K_M)
            assertNotNull(state)
            assertTrue(state!!.progressPercent in 0..99)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `clearPartialDownload removes tmp file`() {
        val dir = Gemma4GgufModelManager.getModelStorageDir(context)
        dir.mkdirs()
        val tmpFile = File(dir, "${GgufVariant.Q4_K_M.fileName}.tmp")
        tmpFile.writeText("partial data")

        assertTrue(Gemma4GgufModelManager.clearPartialDownload(context, GgufVariant.Q4_K_M))
        assertFalse(tmpFile.exists())
    }

    @Test
    fun `cancel sets flag for variant`() {
        Gemma4GgufModelManager.cancel(GgufVariant.Q4_K_M)
        // No crash — cancel is fire-and-forget
    }

    @Test
    fun `cancel all sets flags for all variants`() {
        Gemma4GgufModelManager.cancel()
        // No crash
    }
}
