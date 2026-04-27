package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDiscoveryTest {

    // --- findCurrentModel ---

    @Test
    fun `findCurrentModel returns null when currentPath is null`() {
        assertNull(ModelDiscovery.findCurrentModel(null, emptyList()))
    }

    @Test
    fun `findCurrentModel returns null when currentPath is empty`() {
        assertNull(ModelDiscovery.findCurrentModel("", emptyList()))
    }

    @Test
    fun `findCurrentModel returns null when no model matches`() {
        val models = listOf(
            DiscoveredModel("Model A", "/path/a", ModelSource.DOWNLOADED, 100)
        )
        assertNull(ModelDiscovery.findCurrentModel("/path/other", models))
    }

    @Test
    fun `findCurrentModel returns matching model`() {
        val model = DiscoveredModel("Model A", "/path/a", ModelSource.DOWNLOADED, 100)
        val models = listOf(model)

        val result = ModelDiscovery.findCurrentModel("/path/a", models)

        assertEquals(model, result)
    }

    @Test
    fun `findCurrentModel returns correct model from multiple`() {
        val modelA = DiscoveredModel("Model A", "/path/a", ModelSource.DOWNLOADED, 100)
        val modelB = DiscoveredModel("Model B", "/path/b", ModelSource.DOWNLOADED, 200)
        val models = listOf(modelA, modelB)

        val result = ModelDiscovery.findCurrentModel("/path/b", models)

        assertEquals(modelB, result)
    }

    @Test
    fun `findCurrentModel returns first match when paths are unique`() {
        val modelA = DiscoveredModel("Model A", "/path/a", ModelSource.DOWNLOADED, 100)
        val modelB = DiscoveredModel("Model B", "/path/b", ModelSource.DOWNLOADED, 200)
        val models = listOf(modelA, modelB)

        val result = ModelDiscovery.findCurrentModel("/path/a", models)

        assertEquals(modelA, result)
    }

    @Test
    fun `findCurrentModel returns null when list is empty`() {
        assertNull(ModelDiscovery.findCurrentModel("/some/path", emptyList()))
    }

    // --- DiscoveredModel data class ---

    @Test
    fun `DiscoveredModel default variant is null`() {
        val model = DiscoveredModel("Test", "/test", ModelSource.DOWNLOADED, 50)

        assertNull(model.variant)
    }

    @Test
    fun `DiscoveredModel equality works`() {
        val a = DiscoveredModel("Test", "/test", ModelSource.DOWNLOADED, 50)
        val b = DiscoveredModel("Test", "/test", ModelSource.DOWNLOADED, 50)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `DiscoveredModel copy preserves values`() {
        val original = DiscoveredModel("Test", "/test", ModelSource.DOWNLOADED, 50)
        val copied = original.copy(name = "Renamed")

        assertEquals("Renamed", copied.name)
        assertEquals(original.path, copied.path)
        assertEquals(original.source, copied.source)
        assertEquals(original.sizeMB, copied.sizeMB)
        assertEquals(original.variant, copied.variant)
    }

    // --- ModelSource enum ---

    @Test
    fun `ModelSource DOWNLOADED exists`() {
        assertNotNull(ModelSource.valueOf("DOWNLOADED"))
        assertEquals("DOWNLOADED", ModelSource.DOWNLOADED.name)
    }
}
