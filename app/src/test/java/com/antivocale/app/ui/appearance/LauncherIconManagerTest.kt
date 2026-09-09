package com.antivocale.app.ui.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.antivocale.app.util.ComponentAliasSync

/**
 * Launcher-icon alias switching (TASK-392) against Robolectric's real
 * PackageManager, mirroring ShareTargetManagerExternalTest: component state is
 * written with setComponentEnabledSetting and read back via
 * getComponentEnabledSetting, no mocking of the package layer. Write ordering
 * and no-op skipping are pinned through the [com.antivocale.app.util.ComponentAliasSync]
 * seam (spy + callOriginal), which records every component write.
 *
 * The alias component names are a pinned contract with the manifest
 * activity-alias literals (cross-checked structurally in
 * LauncherIconManifestTest).
 */

/** Simulates a hard process death mid-sequence: an Error escapes ComponentAliasSync's catch(Exception). */
private class SimulatedProcessDeath : Error()

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LauncherIconManagerTest {

    private lateinit var context: Context
    private lateinit var manager: LauncherIconManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = LauncherIconManager(context)
    }

    private fun aliasState(variant: LauncherIconVariant): Int =
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context, variant.aliasComponentName)
        )

    private fun aliasEnabled(variant: LauncherIconVariant): Boolean =
        aliasState(variant) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Effective enabledness, mirroring the manager's own read: manifest-default counts as enabled for Default only. */
    private fun effectivelyEnabled(variant: LauncherIconVariant): Boolean =
        when (aliasState(variant)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> variant == LauncherIconVariant.DEFAULT
            else -> false
        }

    private fun forceAlias(variant: LauncherIconVariant, state: Int) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, variant.aliasComponentName),
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    /** Spies the shared write seam, recording "className=enabled" for every component write. */
    private fun recordWrites(block: (MutableList<String>) -> Unit): List<String> {
        val writes = mutableListOf<String>()
        mockkObject(ComponentAliasSync) {
            every {
                ComponentAliasSync.setEnabled(any(), any(), any(), any())
            } answers {
                writes.add("${args[1]}=${args[2]}")
                callOriginal()
            }
            block(writes)
        }
        return writes
    }

    // ---- pinned contract: variant ids and alias component names ----

    @Test
    fun `variant ids and alias component names follow the pinned pattern`() {
        assertEquals(
            mapOf(
                "default" to "com.antivocale.app.LauncherDefault",
                "wavecut" to "com.antivocale.app.LauncherWavecut",
                "crossed" to "com.antivocale.app.LauncherCrossed",
                "textblock" to "com.antivocale.app.LauncherTextblock",
                "monogram" to "com.antivocale.app.LauncherMonogram",
                "capsule" to "com.antivocale.app.LauncherCapsule",
                "mutebar" to "com.antivocale.app.LauncherMutebar",
            ),
            LauncherIconVariant.entries.associate { it.name.lowercase() to it.aliasComponentName },
        )
    }

    @Test
    fun `default is the canonical first variant and every variant carries a localized name`() {
        assertEquals(LauncherIconVariant.DEFAULT, LauncherIconVariant.entries.first())
        LauncherIconVariant.entries.forEach { variant ->

            assert(variant.nameRes != 0) { "${variant.name} needs a localized name" }
        }
    }

    // ---- switch logic against Robolectric's PackageManager ----

    @Test
    fun `fresh install state resolves to Default`() {
        // All aliases sit at COMPONENT_ENABLED_STATE_DEFAULT: the manifest
        // declares Default enabled and the concepts disabled, and the
        // default-state resolution itself must count Default as enabled.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, aliasState(LauncherIconVariant.DEFAULT))
        assertEquals(LauncherIconVariant.DEFAULT, manager.current())
    }

    @Test
    fun `select enables exactly the chosen alias`() {
        manager.select(LauncherIconVariant.WAVECUT)

        assertEquals(LauncherIconVariant.WAVECUT, manager.current())
        LauncherIconVariant.entries.forEach { variant ->
            assertEquals(
                "alias of ${variant.name}",
                variant == LauncherIconVariant.WAVECUT,
                aliasEnabled(variant),
            )
        }
        // Default was explicitly DISABLED by the switch (not left at its
        // manifest-default state): one pinned write per non-target alias.
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            aliasState(LauncherIconVariant.DEFAULT),
        )
    }

    @Test
    fun `switching back and forth flips the enabled alias`() {
        manager.select(LauncherIconVariant.MUTEBAR)
        assertEquals(LauncherIconVariant.MUTEBAR, manager.current())

        manager.select(LauncherIconVariant.DEFAULT)
        assertEquals(LauncherIconVariant.DEFAULT, manager.current())
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            aliasState(LauncherIconVariant.MUTEBAR),
        )
    }

    @Test
    fun `re-selecting the current variant keeps exactly one enabled`() {
        manager.select(LauncherIconVariant.CROSSED)
        manager.select(LauncherIconVariant.CROSSED)

        assertEquals(LauncherIconVariant.CROSSED, manager.current())
        assertEquals(1, LauncherIconVariant.entries.count(::aliasEnabled))
    }

    @Test
    fun `heal re-enables Default when every alias was left disabled`() {
        // The retired-variant migration (TASK-473): a user with a removed
        // variant selected also carries an explicit DISABLED write for
        // Default, so after the update nothing is enabled and the app would
        // vanish from the launcher until something writes Default back.
        LauncherIconVariant.entries.forEach {
            forceAlias(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }

        manager.healIfNoAliasEnabled()

        assertEquals(LauncherIconVariant.DEFAULT, manager.current())
        assertEquals(1, LauncherIconVariant.entries.count(::aliasEnabled))
    }

    @Test
    fun `heal is free when an alias is already enabled`() {
        val writes = recordWrites { manager.healIfNoAliasEnabled() }
        assertTrue("converged heal must not write", writes.isEmpty())
    }

    @Test
    fun `no alias enabled falls back to Default`() {
        // Unknown state (e.g. everything disabled by hand): Default wins.
        LauncherIconVariant.entries.forEach {
            forceAlias(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }

        assertEquals(LauncherIconVariant.DEFAULT, manager.current())
    }

    @Test
    fun `drift with two enabled aliases resolves to the first enabled in canonical order and select heals it`() {
        forceAlias(LauncherIconVariant.DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        forceAlias(LauncherIconVariant.WAVECUT, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        forceAlias(LauncherIconVariant.CROSSED, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        assertEquals(LauncherIconVariant.WAVECUT, manager.current())

        manager.select(LauncherIconVariant.MUTEBAR)
        assertEquals(LauncherIconVariant.MUTEBAR, manager.current())
        assertEquals(1, LauncherIconVariant.entries.count(::aliasEnabled))
    }

    // ---- write ordering and no-op skipping (ComponentAliasSync seam) ----

    @Test
    fun `select enables the target before disabling any other alias`() {
        val writes = recordWrites { manager.select(LauncherIconVariant.WAVECUT) }

        assertTrue("select must write at least the enable", writes.isNotEmpty())
        assertEquals("com.antivocale.app.LauncherWavecut=true", writes.first())
        assertTrue(
            "every write after the first must be a disable: $writes",
            writes.drop(1).isNotEmpty() && writes.drop(1).all { it.endsWith("=false") },
        )
    }

    @Test
    fun `a kill mid-sequence can never leave zero aliases enabled`() {
        // Enable-first ordering guarantee: whatever the abort point, the
        // sequence never starts with a disable, so at least one alias stays
        // enabled. killAt = the write index the "process death" preempts
        // (indices past the actual write count simply complete the switch).
        var diedAtLeastOnce = false
        for (killAt in 0 until LauncherIconVariant.entries.size) {
            LauncherIconVariant.entries.forEach {
                forceAlias(it, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            }
            var writes = 0
            mockkObject(ComponentAliasSync) {
                every {
                    ComponentAliasSync.setEnabled(any(), any(), any(), any())
                } answers {
                    if (writes == killAt) throw SimulatedProcessDeath()
                    writes++
                    callOriginal()
                }
                // An Error escapes the seam's catch(Exception), like a real kill.
                try {
                    manager.select(LauncherIconVariant.WAVECUT)
                } catch (e: SimulatedProcessDeath) {
                    // aborted mid-sequence: assert the invariant below
                    diedAtLeastOnce = true
                }
            }
            assertTrue(
                "abort before write ${killAt + 1} left zero enabled aliases",
                LauncherIconVariant.entries.any(::effectivelyEnabled),
            )
        }
        // Vacuity guard: if a refactor ever bypasses the seam, no death fires
        // and every iteration above passes trivially.
        assertTrue("kill simulation never fired: the test exercised nothing", diedAtLeastOnce)
    }

    @Test
    fun `re-selecting a converged state performs no component writes`() {
        manager.select(LauncherIconVariant.WAVECUT)

        val writes = recordWrites { manager.select(LauncherIconVariant.WAVECUT) }
        assertTrue("expected zero writes, got $writes", writes.isEmpty())
    }

    @Test
    fun `selecting Default on a fresh install writes nothing`() {
        // Default is effectively enabled at its manifest-default state and the
        // recolors are effectively disabled: nothing differs from the goal.
        val writes = recordWrites { manager.select(LauncherIconVariant.DEFAULT) }
        assertTrue("expected zero writes, got $writes", writes.isEmpty())
    }
}
