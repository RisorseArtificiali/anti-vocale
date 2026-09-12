package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-427: the tier boundaries of the pre-download fit hint. The budget is
 * the selection gate's own (2.5x headroom, 1.2GB floor), so the tiers and
 * the gate can never disagree about the DoesNotFit line.
 */
class DeviceCompatibilityFitTest {

    @Test
    fun `budget keeps the headroom factor and the model floor`() {
        assertEquals((640L * 5) / 2, DeviceCompatibility.requiredBudgetMb(640))
        // 100MB model still demands the 1.2GB floor.
        assertEquals(1_200L, DeviceCompatibility.requiredBudgetMb(100))
    }

    @Test
    fun `tiers split at the budget line and a quarter above it`() {
        // 640MB model -> budget 1600MB. Boundaries as literals so a factor
        // change in production cannot silently keep this green.
        // 1599MB: DoesNotFit / 1600MB: Tight / 1999MB: Tight / 2000MB: Fits.
        assertEquals(
            DeviceCompatibility.ModelFit.DoesNotFit,
            DeviceCompatibility.modelFitForRam(1599L * 1024 * 1024, 640))
        assertEquals(
            DeviceCompatibility.ModelFit.Tight,
            DeviceCompatibility.modelFitForRam(1600L * 1024 * 1024, 640))
        assertEquals(
            DeviceCompatibility.ModelFit.Tight,
            DeviceCompatibility.modelFitForRam(1999L * 1024 * 1024, 640))
        assertEquals(
            DeviceCompatibility.ModelFit.Fits,
            DeviceCompatibility.modelFitForRam(2000L * 1024 * 1024, 640))
    }

    @Test
    fun `both unreadable sentinels fail open as null`() {
        assertEquals(null, DeviceCompatibility.modelFitForRam(-1, 640))
        assertEquals(null, DeviceCompatibility.modelFitForRam(Long.MAX_VALUE, 640))
    }
}
