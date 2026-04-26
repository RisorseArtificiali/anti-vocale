package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.manager.LlmManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TranscriptionBackendManagerTest {

    private lateinit var llmManager: LlmManager
    private lateinit var backendA: TranscriptionBackend
    private lateinit var backendB: TranscriptionBackend
    private lateinit var context: Context
    private lateinit var config: BackendConfig

    @Before
    fun setUp() {
        llmManager = mockk(relaxed = true)
        backendA = mockk(relaxed = true) {
            every { id } returns "backend_a"
            every { displayName } returns "Backend A"
            every { supportsAudio } returns true
            every { supportsText } returns false
            every { maxChunkDurationSeconds } returns 30
        }
        backendB = mockk(relaxed = true) {
            every { id } returns "backend_b"
            every { displayName } returns "Backend B"
            every { supportsAudio } returns false
            every { supportsText } returns true
            every { maxChunkDurationSeconds } returns null
        }
        context = mockk(relaxed = true)
        config = mockk(relaxed = true)
    }

    private fun createManager(vararg backends: TranscriptionBackend): TranscriptionBackendManager =
        TranscriptionBackendManager(llmManager, backends.toSet())

    // --- Initialization ---

    @Test
    fun `empty backends set creates manager with no available backends`() {
        val manager = createManager()
        assertTrue(manager.getAvailableBackends().isEmpty())
    }

    @Test
    fun `backends are indexed by id`() {
        val manager = createManager(backendA, backendB)

        assertEquals(2, manager.getAvailableBackends().size)
        assertSame(backendA, manager.getBackend("backend_a"))
        assertSame(backendB, manager.getBackend("backend_b"))
    }

    @Test
    fun `getBackend returns null for unknown id`() {
        val manager = createManager(backendA)
        assertNull(manager.getBackend("nonexistent"))
    }

    @Test
    fun `duplicate backend ids keep last entry`() {
        val duplicate = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "backend_a"
            every { displayName } returns "Backend A Duplicate"
        }
        val manager = createManager(backendA, duplicate)

        assertEquals(1, manager.getAvailableBackends().size)
        assertEquals("Backend A Duplicate", manager.getBackend("backend_a")?.displayName)
    }

    // --- Active backend state ---

    @Test
    fun `initially no backend is active`() {
        val manager = createManager(backendA)
        assertNull(manager.getActiveBackend())
        assertFalse(manager.hasActiveBackend())
        assertNull(manager.activeBackendId.value)
    }

    @Test
    fun `setActiveBackend initializes and activates backend`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.success(Unit)
        val manager = createManager(backendA)

        val result = manager.setActiveBackend("backend_a", context, config)

        assertTrue(result.isSuccess)
        assertTrue(manager.hasActiveBackend())
        assertSame(backendA, manager.getActiveBackend())
        assertEquals("backend_a", manager.activeBackendId.value)
    }

    @Test
    fun `setActiveBackend returns failure for unknown backend`() = runTest {
        val manager = createManager(backendA)

        val result = manager.setActiveBackend("nonexistent", context, config)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(manager.hasActiveBackend())
    }

    @Test
    fun `setActiveBackend returns failure when initialization fails`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.failure(
            RuntimeException("Init failed")
        )
        val manager = createManager(backendA)

        val result = manager.setActiveBackend("backend_a", context, config)

        assertTrue(result.isFailure)
        assertEquals("Init failed", result.exceptionOrNull()?.message)
        assertFalse(manager.hasActiveBackend())
        assertNull(manager.activeBackendId.value)
    }

    @Test
    fun `setActiveBackend unloads previous backend when switching`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.success(Unit)
        coEvery { backendB.initialize(any(), any()) } returns Result.success(Unit)
        val manager = createManager(backendA, backendB)

        manager.setActiveBackend("backend_a", context, config)
        manager.setActiveBackend("backend_b", context, config)

        verify { backendA.unload() }
        assertSame(backendB, manager.getActiveBackend())
        assertEquals("backend_b", manager.activeBackendId.value)
    }

    @Test
    fun `setActiveBackend does not unload when re-activating same backend`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.success(Unit)
        val manager = createManager(backendA)

        manager.setActiveBackend("backend_a", context, config)
        manager.setActiveBackend("backend_a", context, config)

        verify(exactly = 0) { backendA.unload() }
    }

    // --- Unload ---

    @Test
    fun `unloadActiveBackend clears active state`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.success(Unit)
        val manager = createManager(backendA)

        manager.setActiveBackend("backend_a", context, config)
        manager.unloadActiveBackend()

        assertFalse(manager.hasActiveBackend())
        assertNull(manager.getActiveBackend())
        assertNull(manager.activeBackendId.value)
        verify { backendA.unload() }
    }

    @Test
    fun `unloadActiveBackend is safe when no backend is active`() {
        val manager = createManager(backendA)
        manager.unloadActiveBackend() // should not throw
        assertFalse(manager.hasActiveBackend())
    }

    // --- Keep-alive ---

    @Test
    fun `setKeepAliveTimeout delegates to active backend`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.success(Unit)
        val manager = createManager(backendA)

        manager.setActiveBackend("backend_a", context, config)
        manager.setKeepAliveTimeout(10)

        verify { backendA.setKeepAliveTimeout(10) }
    }

    @Test
    fun `setKeepAliveTimeout is safe when no backend is active`() {
        val manager = createManager(backendA)
        manager.setKeepAliveTimeout(10) // should not throw
    }

    // --- getAvailableBackends ---

    @Test
    fun `getAvailableBackends returns all injected backends`() {
        val manager = createManager(backendA, backendB)
        val backends = manager.getAvailableBackends()

        assertEquals(2, backends.size)
        assertTrue(backends.contains(backendA))
        assertTrue(backends.contains(backendB))
    }

    // --- unloadAll ---

    @Test
    fun `unloadAll unloads active transcription backend even without LlmManager`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.success(Unit)
        val manager = createManager(backendA)

        manager.setActiveBackend("backend_a", context, config)
        manager.unloadAll()

        verify { backendA.unload() }
        assertFalse(manager.hasActiveBackend())
    }

    @Test
    fun `unloadAll is safe when nothing is loaded`() {
        val manager = createManager()
        manager.unloadAll() // should not throw
        assertFalse(manager.hasActiveBackend())
    }

    // --- Backend initialization failure ---

    @Test
    fun `backend initialization failure does not change active backend`() = runTest {
        coEvery { backendA.initialize(any(), any()) } returns Result.success(Unit)
        coEvery { backendB.initialize(any(), any()) } returns Result.failure(RuntimeException("fail"))
        val manager = createManager(backendA, backendB)

        manager.setActiveBackend("backend_a", context, config)
        val result = manager.setActiveBackend("backend_b", context, config)

        assertTrue(result.isFailure)
        assertSame(backendA, manager.getActiveBackend())
        assertEquals("backend_a", manager.activeBackendId.value)
    }
}
