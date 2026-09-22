package com.antivocale.app.manager

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The 2026-09-22 email bug report (LiteRT-LM "model is null" from
 * `LiteRTResourceCalculator`): a bare exists() check let a truncated or
 * empty model file descend into the native stack, which fails with an
 * opaque "model is null" instead of naming the real cause. The manager
 * must reject empty and truncated files BEFORE touching LiteRT-LM.
 */
class LlmManagerEmptyModelFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `zero-byte model file is rejected before the native stack`() = runBlocking {
        val manager = LlmManager(scope)
        val f = File(tmp.newFolder(), "gemma.litertlm").apply { createNewFile() }

        val result = manager.initialize(context, f.absolutePath)

        assertTrue(result.isFailure)
        assertTrue(
            "expected the empty-file cause, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("empty or truncated (0 bytes") == true)
    }

    @Test
    fun `truncated model file is named as a truncated download`() = runBlocking {
        val manager = LlmManager(scope)
        // Below the 1 MB sanity floor: every real model is gigabyte-scale.
        val f = File(tmp.newFolder(), "gemma.litertlm").apply { writeBytes(ByteArray(500_000)) }

        val result = manager.initialize(context, f.absolutePath)

        assertTrue(result.isFailure)
        assertTrue(
            "expected the truncated-download cause, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("truncated") == true)
    }

    @Test
    fun `missing file keeps the historical message`() = runBlocking {
        val manager = LlmManager(scope)

        val result = manager.initialize(context, File(tmp.newFolder(), "nope.litertlm").absolutePath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("file not found") == true)
    }
}
